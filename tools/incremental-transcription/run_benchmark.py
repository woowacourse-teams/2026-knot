#!/usr/bin/env python3
"""Opt-in real S3 + isolated PostgreSQL + native AI service benchmark.

No network, processes, or scratch files are created without --run. AWS credentials
are resolved by the Java SDK's standard provider chain; this driver never reads,
fetches, prints, or serializes credential values.
"""
import argparse
import contextlib
import hashlib
import json
import math
import os
from pathlib import Path
import re
import secrets
import signal
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.request
import uuid
import wave
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
PREFIX_ROOT = 'knot/experiments/recording-release/'
SAFE_CODE = re.compile(r'[A-Z][A-Z0-9_]{0,99}')


class BenchmarkFailure(Exception):
    pass


def private_json(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n')
    path.chmod(0o600)


def validate_audio(path, seconds, start_seconds, repeat):
    with wave.open(str(path), 'rb') as audio:
        if (audio.getframerate(), audio.getnchannels(), audio.getsampwidth(), audio.getcomptype()) != (16000, 1, 2, 'NONE'):
            raise BenchmarkFailure('PCM16_16KHZ_MONO_REQUIRED')
        start = round(start_seconds * 16000)
        available = audio.getnframes() - start
        if available <= 0:
            raise BenchmarkFailure('SOURCE_START_OUT_OF_RANGE')
        if seconds * 16000 > available and not repeat:
            raise BenchmarkFailure('SOURCE_TOO_SHORT_EXPLICIT_REPEAT_REQUIRED')
        return start, available


def fixture(source, directory, seconds, start_seconds, repeat):
    start, available = validate_audio(source, seconds, start_seconds, repeat)
    target_frames = seconds * 16000
    remaining = target_frames
    with wave.open(str(source), 'rb') as audio, (directory / 'input.pcm').open('wb') as pcm, wave.open(str(directory / 'input.wav'), 'wb') as wav:
        wav.setparams((1, 2, 16000, target_frames, 'NONE', 'not compressed'))
        audio.setpos(start)
        while remaining:
            block = audio.readframes(min(remaining, 16384))
            if not block:
                if not repeat:
                    raise BenchmarkFailure('SOURCE_TRUNCATED')
                audio.setpos(start)
                block = audio.readframes(min(remaining, 16384))
                if not block:
                    raise BenchmarkFailure('EMPTY_REPEAT_SOURCE')
            if len(block) % 2:
                raise BenchmarkFailure('INVALID_PCM_LENGTH')
            pcm.write(block)
            wav.writeframesraw(block)
            remaining -= len(block) // 2
    return {'sourceKind': 'explicit supplied PCM WAV excerpt', 'sourceStartFrame': start,
            'sourceAvailableFrames': available, 'durationSeconds': seconds, 'totalFrames': target_frames,
            'fixtureRepeated': target_frames > available, 'repeatRequested': repeat,
            'humanQualityValidated': False, 'naturalLongMeeting': False,
            'wavByteLength': (directory / 'input.wav').stat().st_size}


def allowed_s3_hosts(bucket, region):
    suffix = 'amazonaws.com.cn' if region.startswith('cn-') else 'amazonaws.com'
    regional = 's3.' + region + '.' + suffix
    hosts = [bucket + '.' + regional, regional]
    if region == 'us-east-1':
        hosts.extend([bucket + '.s3.amazonaws.com', 's3.amazonaws.com'])
    return hosts


def source_identity(root):
    head = subprocess.run(['git', '-C', str(root), 'rev-parse', 'HEAD'], capture_output=True, text=True, timeout=5)
    status = subprocess.run(['git', '-C', str(root), 'status', '--porcelain', '--untracked-files=normal'],
                            capture_output=True, text=True, timeout=5)
    if head.returncode or status.returncode or not re.fullmatch(r'[a-f0-9]{40,64}', head.stdout.strip()):
        return {'gitHead': None, 'dirty': None, 'available': False}
    return {'gitHead': head.stdout.strip(), 'dirty': bool(status.stdout), 'available': True}


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open('rb') as content:
        while block := content.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def sanitize_report(value, prefix):
    result = {'experimentPrefix': prefix}
    for key in ['execution', 'cleanupStatus', 'stage', 'feedMode']:
        item = value.get(key)
        if isinstance(item, str) and re.fullmatch(r'[a-zA-Z0-9 _;:.+-]{1,180}', item):
            result[key] = item
    for key in ['sourceFrames', 'sourceDurationSeconds', 'currentRound', 'cleanupStableEmptyScans', 'cleanupRemainingVersions', 'workersStopped', 'includePreparedLegacyContention']:
        item = value.get(key)
        if isinstance(item, (int, float, bool)) and math.isfinite(item):
            result[key] = item
    run_keys = {'round', 'firstEndToFinalMillis', 'secondEndToFinalMillis', 'bothWithin30Seconds',
                'preEndCatchUp', 'feedElapsedMillis', 'feedPauseMillis', 'maxUploadLagMillis', 'finalUploadLagMillis'}
    result['runs'] = []
    for row in value.get('runs', []):
        if not isinstance(row, dict):
            continue
        cleaned = {key: item for key, item in row.items() if key in run_keys and isinstance(item, (int, float, bool)) and math.isfinite(item)}
        if row.get('legacyStatusAtPcmCompletion') in {'PENDING', 'QUEUED', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'NOT_INCLUDED'}:
            cleaned['legacyStatusAtPcmCompletion'] = row['legacyStatusAtPcmCompletion']
        result['runs'].append(cleaned)
    for key in ['windowStatuses', 'transcriptionStatuses', 'windowFailureCodes']:
        result[key] = []
        for row in value.get(key, []):
            label_key = 'failure_code' if key == 'windowFailureCodes' else 'status'
            label, total = row.get(label_key), row.get('total')
            if isinstance(label, str) and SAFE_CODE.fullmatch(label) and isinstance(total, int):
                result[key].append({label_key: label, 'total': total})
    return result


@contextlib.contextmanager
def private_workspace(report):
    root = Path(tempfile.mkdtemp(prefix='knot-portable-benchmark-'))
    root.chmod(0o700)
    try:
        yield root
    finally:
        safe = report.get('nativeProcessStopped') is True and (
            not report.get('backendLaunched') or (report.get('workersStopped') is True and report.get('backendProcessStopped') is True))
        if safe:
            try:
                shutil.rmtree(root)
            except OSError:
                report['privateCleanupDeferred'] = True
        else:
            report['privateCleanupDeferred'] = True
        if report.get('privateCleanupDeferred'):
            print(json.dumps({'status': 'private_cleanup_deferred', 'privateRecoveryDirectory': str(root)}))


def group_members(group):
    """Read identity metadata only. Unknown state must never be mistaken for empty."""
    try:
        result = subprocess.run(['ps', '-axo', 'pid=,pgid=,lstart=,stat='], capture_output=True,
                                text=True, timeout=3)
        if result.returncode:
            return None
        members = {}
        for row in result.stdout.splitlines():
            fields = row.split()
            if len(fields) >= 8 and fields[0].isdigit() and fields[1].isdigit() and int(fields[1]) == group:
                members[int(fields[0])] = (' '.join(fields[2:7]), fields[7])
        return members
    except (OSError, subprocess.TimeoutExpired):
        return None


def start_process(command, **arguments):
    process = subprocess.Popen(command, start_new_session=True, **arguments)
    process._benchmark_group = process.pid
    process._benchmark_known_starts = {}
    members = group_members(process.pid)
    if members and process.pid in members:
        process._benchmark_known_starts = {pid: identity[0] for pid, identity in members.items()}
    return process


def observe_group(process):
    group = getattr(process, '_benchmark_group', None)
    known = getattr(process, '_benchmark_known_starts', {})
    if group != process.pid or group == os.getpgrp() or not known:
        return None
    members = group_members(group)
    if members is None or not members:
        return members
    if not any(known.get(pid) == identity[0] for pid, identity in members.items()):
        return None
    known.update({pid: identity[0] for pid, identity in members.items()})
    return members


def stop_process(process, grace_seconds=30, kill_seconds=10):
    if process is None:
        return True
    members = observe_group(process)
    if members is None:
        return False
    for requested_signal, seconds in [(signal.SIGTERM, grace_seconds), (signal.SIGKILL, kill_seconds)]:
        live = {pid: identity for pid, identity in members.items() if not identity[1].startswith('Z')}
        if not live:
            process.poll()  # Reap our direct child; zombies cannot write audio or S3 objects.
            return True
        try:
            os.killpg(process._benchmark_group, requested_signal)
        except ProcessLookupError:
            pass
        except OSError:
            return False
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            members = observe_group(process)
            if members is None:
                return False
            if not any(not identity[1].startswith('Z') for identity in members.values()):
                process.poll()
                return True
            process.poll()
            time.sleep(0.1)
    return False


def native_ownership_clean(work_directory):
    """Supervisor native groups detach from the AI JVM group; never infer their exit from JVM exit."""
    try:
        if not work_directory.exists():
            return not work_directory.is_symlink()
        if work_directory.is_symlink():
            return False
        for record_path in work_directory.rglob('process-owner.json'):
            if record_path.is_symlink() or not record_path.resolve().is_relative_to(work_directory.resolve()):
                return False
            record = json.loads(record_path.read_text())
            if not isinstance(record, dict) or record.get('schemaVersion') != 1 or record.get('state') != 'CLEANED':
                return False
        return True
    except (OSError, ValueError, RuntimeError):
        return False


def stop_native(process, private, report):
    group_stopped = stop_process(process)
    ownership_clean = native_ownership_clean(private / 'ai-work')
    report['nativeOwnershipClean'] = ownership_clean
    return group_stopped and ownership_clean


def execution_budget(seconds, rounds, paced, include_legacy, final_timeout, catchup_timeout, bootstrap_timeout):
    feed = seconds if paced else 0
    catchup = (0 if paced else 2 * catchup_timeout) + (catchup_timeout if include_legacy else 0)
    return bootstrap_timeout + rounds * (feed + final_timeout + catchup) + 90


def wait_backend(process, deadline, bootstrap_deadline, private_report, ai):
    while True:
        observe_group(process)
        if ai is not None:
            observe_group(ai)
        code = process.poll()
        if code is not None:
            return code
        now = time.monotonic()
        if now >= deadline:
            raise BenchmarkFailure('OVERALL_EXECUTION_TIMEOUT')
        if not private_report.exists() and now >= bootstrap_deadline:
            raise BenchmarkFailure('BACKEND_BOOTSTRAP_TIMEOUT')
        time.sleep(0.5)


def diagnostics(backend):
    kinds = set()
    for xml in (backend / 'build/test-results/integrationTest').glob('TEST-*IncrementalNativeS3BenchmarkTest.xml'):
        try:
            for failure in ET.parse(xml).getroot().iter('failure'):
                values = [failure.get('type', '')] + re.findall(r'Caused by: ([A-Za-z_$][\w.$]+)', failure.text or '')
                kinds.update(kind for kind in values if re.fullmatch(r'[A-Za-z_$][\w.$]+', kind))
        except ET.ParseError:
            kinds.add('INVALID_TEST_REPORT')
    return sorted(kinds)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo-root', type=Path, required=True, help='Knot checkout containing backend/')
    parser.add_argument('--ai-source-root', type=Path, required=True, help='AI module containing runner/ and the verified bootJar')
    parser.add_argument('--audio-file', type=Path, required=True, help='Authorized local PCM16 16kHz mono WAV')
    parser.add_argument('--profile', type=Path, required=True, help='Complete local native-model profile JSON')
    parser.add_argument('--bucket', required=True, help='Explicit versioned S3 bucket for isolated experiment objects')
    parser.add_argument('--region', default='ap-northeast-2')
    parser.add_argument('--seconds', type=int, default=70)
    parser.add_argument('--start-seconds', type=float, default=0)
    parser.add_argument('--rounds', type=int, default=3)
    parser.add_argument('--without-legacy', action='store_true', help='Official two-recording condition: omit third legacy workload')
    parser.add_argument('--repeat', action='store_true', help='Explicitly allow repeating the supplied excerpt; never human quality evidence')
    parser.add_argument('--feed-pause-ms', type=int, choices=[0, 5000], default=5000, help='5000: paced source clock, 0: accelerated catch-up experiment')
    parser.add_argument('--timeout-seconds', type=int, default=600)
    parser.add_argument('--catchup-timeout-seconds', type=int, default=1200)
    parser.add_argument('--bootstrap-timeout-seconds', type=int, default=300)
    parser.add_argument('--report', type=Path, help='Sanitized JSON destination; never audio or transcript')
    parser.add_argument('--run', action='store_true', help='Explicitly allow local native processes and isolated S3 writes')
    args = parser.parse_args()
    if not 1 <= args.seconds <= 7200 or not 1 <= args.rounds <= 3 or not math.isfinite(args.start_seconds) or args.start_seconds < 0:
        parser.error('seconds 1..7200, rounds 1..3, and finite nonnegative source start required')
    if not 1 <= args.timeout_seconds <= 86400 or not 1 <= args.catchup_timeout_seconds <= 86400:
        parser.error('bounded timeouts 1..86400 required')
    if not 1 <= args.bootstrap_timeout_seconds <= 3600:
        parser.error('bootstrap-timeout-seconds must be 1..3600')
    if not re.fullmatch(r'[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]', args.bucket) or '..' in args.bucket:
        parser.error('explicit DNS S3 bucket required')
    if not re.fullmatch(r'[a-z]{2}(?:-gov)?-[a-z]+-[0-9]+', args.region):
        parser.error('explicit AWS region required')
    repo, ai_root = args.repo_root.resolve(), args.ai_source_root.resolve()
    if not (repo / 'backend/gradlew').is_file() or not (ai_root / 'runner').is_dir():
        raise BenchmarkFailure('REPO_OR_AI_ROOT_INVALID')
    validate_audio(args.audio_file, args.seconds, args.start_seconds, args.repeat)
    if not args.run:
        print(json.dumps({'status': 'prepared_not_executed', 'seconds': args.seconds, 'rounds': args.rounds,
                          'fixtureRepeatAllowed': args.repeat, 'feedPauseMillis': args.feed_pause_ms,
                          'includePreparedLegacyContention': not args.without_legacy,
                          'realS3WritesIfRun': True, 'productBrowserE2e': False, 'humanQualityValidated': False}))
        return 0
    os.umask(0o077)
    run_id = str(uuid.uuid4())
    prefix = PREFIX_ROOT + 'incremental-e2e-' + run_id + '/'
    report_path = args.report.resolve() if args.report else Path.cwd() / ('incremental-benchmark-' + run_id + '.json')
    if report_path.exists():
        raise BenchmarkFailure('REPORT_DESTINATION_ALREADY_EXISTS')
    if not report_path.parent.is_dir():
        raise BenchmarkFailure('REPORT_PARENT_DIRECTORY_REQUIRED')
    value = {'execution': 'failed_before_report', 'cleanupStatus': 'not_started', 'runs': []}
    report = {'schemaVersion': 1, 'experimentPrefix': prefix, 'productBrowserE2e': False,
              'humanQualityValidated': False, 'releaseQualityGate': 'HOLD', 'nativeProcessStopped': True,
              'scope': 'real S3 + BE services + isolated PostgreSQL + localhost native AI'}
    ai = None
    backend_process = None
    budget = execution_budget(args.seconds, args.rounds, args.feed_pause_ms > 0, not args.without_legacy,
                              args.timeout_seconds, args.catchup_timeout_seconds, args.bootstrap_timeout_seconds)
    overall_deadline = time.monotonic() + budget
    execution_deadline = overall_deadline - 90  # Reserve bounded BE/AI shutdown time.
    report['overallDeadlineSeconds'] = budget
    try:
        jars = [jar for jar in (ai_root / 'build/libs').glob('*.jar') if not jar.name.endswith('-plain.jar')]
        if len(jars) != 1 or not (HERE / 'java/com/knot/backend/recording/infrastructure/storage/IncrementalNativeS3BenchmarkTest.java').is_file():
            raise BenchmarkFailure('BUILD_VERIFIED_AI_JAR_AND_BENCHMARK_SOURCE_FIRST')
        report['repoSource'] = source_identity(repo)
        report['aiSource'] = source_identity(ai_root)
        report['aiJarSha256'] = sha256_file(jars[0])
        profile = json.loads(args.profile.read_text())
        profile = profile.get('profile', profile)
        required = ['whisperBinary', 'diarizeBinary', 'whisperModel', 'vadModel', 'segmentationModel', 'embeddingModel', 'sherpaLibrary', 'pythonBinary']
        if any(not isinstance(profile.get(key), str) or not Path(profile[key]).is_absolute() or not Path(profile[key]).is_file() for key in required):
            raise BenchmarkFailure('COMPLETE_ABSOLUTE_MODEL_PROFILE_REQUIRED')
        with private_workspace(report) as private:
            report['fixture'] = fixture(args.audio_file, private, args.seconds, args.start_seconds, args.repeat)
            private_json(private / 'profile.json', profile)
            with (private / 'ffmpeg.log').open('w') as conversion_log:
                conversion = subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-y', '-i', str(private / 'input.wav'),
                                              '-c:a', 'libopus', str(private / 'input.webm')], stdout=conversion_log, stderr=subprocess.STDOUT,
                                              timeout=min(120, max(0.001, execution_deadline - time.monotonic())))
            if conversion.returncode:
                raise BenchmarkFailure('WEBM_FIXTURE_CONVERSION_FAILED')
            with socket.socket() as reservation:
                reservation.bind(('127.0.0.1', 0))
                port = reservation.getsockname()[1]
            token = secrets.token_urlsafe(32)
            env = os.environ.copy()
            native_env = env.copy()
            for key in ['AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'AWS_SESSION_TOKEN']:
                native_env.pop(key, None)
            native_env.update(AI_PORT=str(port), AI_APP_TOKEN=token, AI_WORK_DIRECTORY=str(private / 'ai-work'),
                              AI_PROFILE_FILE=str(private / 'profile.json'), AI_RUNNER_DIRECTORY=str(ai_root / 'runner'),
                              AI_ALLOWED_S3_HOSTS=','.join(allowed_s3_hosts(args.bucket, args.region)))
            with (private / 'native.log').open('w') as native_log:
                ai = start_process(['java', '-jar', str(jars[0]), '--logging.level.root=WARN'], cwd=ai_root,
                                      env=native_env, stdout=native_log, stderr=subprocess.STDOUT)
                report['nativeProcessStopped'] = False
                try:
                    origin = 'http://127.0.0.1:' + str(port)
                    deadline = time.monotonic() + 30
                    while True:
                        if time.monotonic() >= min(deadline, execution_deadline):
                            raise BenchmarkFailure('ISOLATED_AI_READY_TIMEOUT')
                        observe_group(ai)
                        if ai.poll() is not None:
                            raise BenchmarkFailure('ISOLATED_AI_START_FAILED')
                        try:
                            request = urllib.request.Request(origin + '/health', headers={'Authorization': 'Bearer ' + token})
                            with urllib.request.urlopen(request, timeout=1) as response:
                                if response.status == 200:
                                    break
                        except Exception:
                            pass
                        if time.monotonic() >= min(deadline, execution_deadline):
                            raise BenchmarkFailure('ISOLATED_AI_READY_TIMEOUT')
                        time.sleep(0.1)
                    env.update(KNOT_E2E_RUN='true', KNOT_E2E_BUCKET=args.bucket, KNOT_E2E_REGION=args.region,
                               KNOT_E2E_PREFIX=prefix, KNOT_E2E_AI_URL=origin, KNOT_E2E_AI_TOKEN=token,
                               KNOT_E2E_PCM=str(private / 'input.pcm'), KNOT_E2E_WEBM=str(private / 'input.webm'),
                               KNOT_E2E_ROUNDS=str(args.rounds), KNOT_E2E_INCLUDE_LEGACY=str(not args.without_legacy).lower(),
                               KNOT_E2E_FEED_PAUSE_MS=str(args.feed_pause_ms),
                               KNOT_E2E_TIMEOUT_SECONDS=str(args.timeout_seconds), KNOT_E2E_CATCHUP_TIMEOUT_SECONDS=str(args.catchup_timeout_seconds),
                               KNOT_E2E_SOURCE_DIR=str(HERE / 'java'), KNOT_E2E_REPORT=str(private / 'report.json'))
                    with (private / 'backend.log').open('w') as backend_log:
                        report['backendLaunched'] = True
                        backend_process = start_process(['./gradlew', '--no-daemon', '-I', str(HERE / 'init.gradle'), 'integrationTest',
                                                          '--tests', '*IncrementalNativeS3BenchmarkTest', '--console=plain'],
                                                         cwd=repo / 'backend', env=env, stdout=backend_log, stderr=subprocess.STDOUT)
                        exit_code = wait_backend(backend_process, execution_deadline,
                                                 min(execution_deadline, time.monotonic() + args.bootstrap_timeout_seconds),
                                                 private / 'report.json', ai)
                        report['backendProcessStopped'] = stop_process(backend_process)
                    if (private / 'report.json').is_file():
                        value = json.loads((private / 'report.json').read_text())
                    report.update(sanitize_report(value, prefix))
                    report['gradleExitCode'] = exit_code
                    report['sanitizedFailureTypes'] = diagnostics(repo / 'backend')
                    passed = exit_code == 0 and report.get('backendProcessStopped') is True and report.get('execution') == 'passed' and report.get('workersStopped') is True \
                        and report.get('cleanupRemainingVersions') == 0 and report.get('cleanupStableEmptyScans', 0) >= 2
                    if not passed:
                        report['execution'] = 'failed'
                finally:
                    if backend_process is not None:
                        report['backendProcessStopped'] = stop_process(backend_process)
                    if 'gradleExitCode' not in report and (private / 'report.json').is_file():
                        try:
                            value = json.loads((private / 'report.json').read_text())
                            report.update(sanitize_report(value, prefix))
                        except (OSError, ValueError):
                            report['failureCode'] = 'INVALID_PRIVATE_REPORT'
                    report['nativeProcessStopped'] = stop_native(ai, private, report)
                    ai = None
    except BaseException as failure:
        report.update(sanitize_report(value, prefix))
        report['execution'] = 'failed'
        report['failureType'] = type(failure).__name__
        if isinstance(failure, BenchmarkFailure) and SAFE_CODE.fullmatch(str(failure)):
            report['failureCode'] = str(failure)
    finally:
        if ai is not None:
            report['nativeProcessStopped'] = stop_native(ai, private, report)
        if (not report.get('nativeProcessStopped') or report.get('privateCleanupDeferred')
                or (report.get('backendLaunched') and report.get('backendProcessStopped') is not True)):
            report['execution'] = 'failed'
            report['failureCode'] = 'PROCESS_OR_PRIVATE_CLEANUP_UNCONFIRMED'
        private_json(report_path, report)
    print(json.dumps({'status': report.get('execution'), 'report': str(report_path),
                      'cleanupStatus': report.get('cleanupStatus', 'not_started')}))
    return 0 if report.get('execution') == 'passed' and report.get('gradleExitCode') == 0 \
        and report.get('nativeProcessStopped') and report.get('backendProcessStopped') and report.get('cleanupStableEmptyScans', 0) >= 2 else 1


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except BenchmarkFailure as failure:
        print(json.dumps({'status': 'failed_before_run', 'failureCode': str(failure)}))
        raise SystemExit(1)
    except Exception as failure:
        print(json.dumps({'status': 'failed_before_run', 'failureType': type(failure).__name__}))
        raise SystemExit(1)
