#!/usr/bin/env python3
"""Validate first, then install a user-owned Mac Alloy LaunchAgent."""

import argparse
import os
from pathlib import Path
import plistlib
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
from urllib.parse import urlsplit

LABEL = 'kr.knot.mac-metrics'
SOURCE = Path(__file__).resolve().parent


def checked(command, step, **kwargs):
    result = subprocess.run(command, capture_output=True, text=True, **kwargs)
    if result.returncode:
        raise ValueError(step + ' 실패: 종료 코드 ' + str(result.returncode))
    return result.stdout


def validate_endpoint(value):
    parsed = urlsplit(value)
    if (parsed.scheme != 'https' or not parsed.hostname or parsed.username
            or parsed.password or parsed.query or parsed.fragment
            or parsed.path not in ('', '/')):
        raise ValueError('수신 주소는 사용자 정보·경로·쿼리가 없는 HTTPS 주소여야 합니다')
    _ = parsed.port
    return value.rstrip('/')


def validate_tls(directory):
    for name in ('ca.crt', 'client.crt', 'client.key'):
        if not (directory / name).is_file():
            raise ValueError('TLS 파일이 없습니다: ' + name)
    context = ssl.create_default_context(cafile=str(directory / 'ca.crt'))
    context.load_cert_chain(str(directory / 'client.crt'), str(directory / 'client.key'))
    checked(['openssl', 'verify', '-CAfile', str(directory / 'ca.crt'),
             '-purpose', 'sslclient', str(directory / 'client.crt')], '클라이언트 인증서 검증')
    subject = checked(['openssl', 'x509', '-in', str(directory / 'client.crt'),
                       '-noout', '-subject', '-nameopt', 'RFC2253'], '인증서 주체 확인')
    prefix, separator, name = subject.strip().partition('=')
    if prefix != 'subject' or not separator or name.strip() != 'CN=knot-mac-alloy':
        raise ValueError('Mac 전용 CN=knot-mac-alloy 인증서가 필요합니다')


def launch_agent(root, binary, ingest):
    return {
        'Label': LABEL,
        'ProgramArguments': [sys.executable, str(root / 'run.py'), str(binary)],
        'EnvironmentVariables': {
            'KNOT_MAC_CONFIG_DIR': str(root), 'KNOT_INGEST_BASE_URL': ingest,
        },
        'RunAtLoad': True, 'KeepAlive': True, 'ThrottleInterval': 30,
        'ExitTimeOut': 20,
        'StandardOutPath': '/dev/null', 'StandardErrorPath': '/dev/null',
    }


def copy_private(source, destination):
    shutil.copyfile(source, destination)
    destination.chmod(0o600)


def install(binary, ingest, tls, root, agents, apply):
    if sys.platform != 'darwin' or os.getuid() == 0:
        raise ValueError('macOS 로그인 사용자로 실행해야 합니다. sudo로 설치하지 않습니다')
    ingest = validate_endpoint(ingest)
    binary = binary.expanduser().resolve(strict=True)
    tls = tls.expanduser().resolve(strict=True)
    if not binary.is_file() or not os.access(binary, os.X_OK):
        raise ValueError('실행 가능한 Alloy 바이너리가 필요합니다')
    validate_tls(tls)
    plist = agents / (LABEL + '.plist')
    if root.exists() or plist.exists():
        raise ValueError('기존 설치가 있습니다. 기존 파일을 보존한 뒤 업데이트 절차를 확인하세요')
    with tempfile.TemporaryDirectory(prefix='knot-alloy-check-') as temporary:
        candidate = Path(temporary)
        (candidate / 'tls').mkdir(mode=0o700)
        for name in ('ca.crt', 'client.crt', 'client.key'):
            copy_private(tls / name, candidate / 'tls' / name)
        environment = dict(os.environ, KNOT_MAC_CONFIG_DIR=str(candidate),
                           KNOT_INGEST_BASE_URL=ingest)
        checked([str(binary), 'validate', str(SOURCE / 'config.alloy')],
                'Alloy 설정 검증', env=environment)
    agent = launch_agent(root, binary, ingest)
    plistlib.loads(plistlib.dumps(agent))
    if not apply:
        print('검증 통과. --apply를 추가해야 파일 설치와 수집을 시작합니다.')
        return
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 12346))
    root.mkdir(parents=True, mode=0o700)
    root.chmod(0o700)
    for directory in ('tls', 'logs', 'wal'):
        (root / directory).mkdir(mode=0o700)
    copy_private(SOURCE / 'config.alloy', root / 'config.alloy')
    copy_private(SOURCE / 'run.py', root / 'run.py')
    for name in ('ca.crt', 'client.crt', 'client.key'):
        copy_private(tls / name, root / 'tls' / name)
    agents.mkdir(parents=True, exist_ok=True)
    plist.write_bytes(plistlib.dumps(agent))
    plist.chmod(0o600)
    domain = 'gui/' + str(os.getuid())
    try:
        checked(['launchctl', 'enable', domain + '/' + LABEL], '자동 시작 활성화')
        checked(['launchctl', 'bootstrap', domain, str(plist)], '수집기 시작')
    except ValueError:
        subprocess.run(['launchctl', 'bootout', domain + '/' + LABEL],
                       capture_output=True)
        plist.unlink(missing_ok=True)
        print('시작 실패. 새 설치 파일은 보존했습니다. 수정 후 다시 검증하세요.', file=sys.stderr)
        raise
    print('사용자 LaunchAgent를 시작했습니다. 중앙 수신과 마지막 표본 시각을 확인하세요.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--alloy', required=True, type=Path)
    parser.add_argument('--ingest-base-url', required=True)
    parser.add_argument('--tls-directory', required=True, type=Path)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    root = Path.home() / 'Library/Application Support/Knot/observability'
    agents = Path.home() / 'Library/LaunchAgents'
    try:
        install(args.alloy, args.ingest_base_url, args.tls_directory, root, agents, args.apply)
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 1
    except (OSError, ssl.SSLError):
        print('설치 또는 검증에 실패했습니다. 입력·인증서·로그인 세션·기존 설치를 확인하세요.',
              file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
