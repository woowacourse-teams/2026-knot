import importlib.util
import json
import os
from pathlib import Path
import plistlib
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parent


def load(name):
    spec = importlib.util.spec_from_file_location('mac_' + name, ROOT / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


installer = load('install')
runner = load('run')


class MacInstallTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.tls = self.root / 'tls'
        self.tls.mkdir()
        self.binary = self.root / 'alloy'
        self.binary.write_text('#!/bin/sh\nexit 0\n')
        self.binary.chmod(0o700)
        self.destination = self.root / 'installed'
        self.agents = self.root / 'agents'
        self.certificate('knot-mac-alloy')

    def certificate(self, cn):
        subprocess.run([
            'openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes',
            '-subj', '/CN=' + cn, '-days', '1',
            '-keyout', str(self.tls / 'client.key'),
            '-out', str(self.tls / 'client.crt'),
        ], check=True, capture_output=True)
        (self.tls / 'ca.crt').write_bytes((self.tls / 'client.crt').read_bytes())

    def install(self, apply=False):
        with patch.object(installer.sys, 'platform', 'darwin'), \
                patch.object(installer.os, 'getuid', return_value=501):
            installer.install(self.binary, 'https://fixture.invalid', self.tls,
                              self.destination, self.agents, apply)

    def test_endpoint_rejects_credentials_plaintext_and_arbitrary_paths(self):
        for address in ('http://fixture.invalid', 'https://user:password@fixture.invalid',
                        'https://fixture.invalid/path', 'https://fixture.invalid?token=x',
                        'https://fixture.invalid/#fragment', 'https://fixture.invalid:invalid'):
            with self.subTest(address=address), self.assertRaises(ValueError):
                installer.validate_endpoint(address)

    def test_tls_rejects_dev_certificate_even_when_key_matches(self):
        self.certificate('knot-dev-alloy')
        with self.assertRaisesRegex(ValueError, 'Mac 전용'):
            self.install()
        self.assertFalse(self.destination.exists())

    def test_tls_accepts_openssl_and_libressl_subject_prefix_spacing(self):
        original = installer.checked
        for subject in ('subject=CN=knot-mac-alloy\n', 'subject= CN=knot-mac-alloy\n'):
            def checked(command, step, **kwargs):
                if step == '인증서 주체 확인':
                    return subject
                return original(command, step, **kwargs)

            with self.subTest(subject=subject), \
                    patch.object(installer, 'checked', side_effect=checked):
                installer.validate_tls(self.tls)

    def test_dry_run_validates_without_installing_or_launching(self):
        with patch.object(installer, 'checked', wraps=installer.checked) as checked:
            self.install()
        self.assertFalse(self.destination.exists())
        self.assertFalse(self.agents.exists())
        self.assertFalse(any(call.args[0][0] == 'launchctl' for call in checked.call_args_list))

    def test_existing_installation_is_never_overwritten(self):
        self.destination.mkdir()
        marker = self.destination / 'existing'
        marker.write_text('preserve')
        with self.assertRaisesRegex(ValueError, '기존 설치'):
            self.install(apply=True)
        self.assertEqual(marker.read_text(), 'preserve')
        self.assertFalse(self.agents.exists())

    def test_install_writes_private_files_and_only_bootstraps_its_own_agent(self):
        original = installer.checked

        def checked(command, step, **kwargs):
            if command[0] == 'launchctl':
                return ''
            return original(command, step, **kwargs)

        with patch.object(installer, 'checked', side_effect=checked) as calls:
            self.install(apply=True)
        plist = self.agents / (installer.LABEL + '.plist')
        self.assertEqual(plist.stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.destination.stat().st_mode & 0o777, 0o700)
        self.assertEqual((self.destination / 'tls/client.key').stat().st_mode & 0o777, 0o600)
        launch_commands = [call.args[0] for call in calls.call_args_list
                           if call.args[0][0] == 'launchctl']
        self.assertEqual(launch_commands, [
            ['launchctl', 'enable', 'gui/501/kr.knot.mac-metrics'],
            ['launchctl', 'bootstrap', 'gui/501', str(plist)],
        ])

    def test_bootstrap_failure_removes_only_new_plist_and_preserves_diagnostics(self):
        original = installer.checked

        def checked(command, step, **kwargs):
            if command[:2] == ['launchctl', 'enable']:
                return ''
            if command[:2] == ['launchctl', 'bootstrap']:
                raise ValueError('synthetic bootstrap failure')
            return original(command, step, **kwargs)

        with patch.object(installer, 'checked', side_effect=checked), \
                patch.object(installer.subprocess, 'run', wraps=subprocess.run) as commands:
            with self.assertRaisesRegex(ValueError, 'bootstrap'):
                self.install(apply=True)
        self.assertFalse((self.agents / (installer.LABEL + '.plist')).exists())
        self.assertTrue((self.destination / 'config.alloy').exists())
        self.assertTrue(any(call.args[0] == [
            'launchctl', 'bootout', 'gui/501/kr.knot.mac-metrics',
        ] for call in commands.call_args_list))

    def test_launch_agent_has_no_public_listener_or_secret_contents(self):
        agent = installer.launch_agent(self.destination, self.binary, 'https://fixture.invalid')
        decoded = plistlib.loads(plistlib.dumps(agent))
        self.assertTrue(decoded['KeepAlive'])
        self.assertEqual(decoded['ThrottleInterval'], 30)
        self.assertNotIn('PRIVATE KEY', repr(decoded))
        self.assertEqual(decoded['EnvironmentVariables']['KNOT_MAC_CONFIG_DIR'], str(self.destination))
        self.assertNotIn('sudo', decoded['ProgramArguments'])

    def test_bounded_log_preserves_latest_records(self):
        path = self.root / 'alloy.log'
        runner.append_log(path, b'12345678', limit=10)
        runner.append_log(path, b'new', limit=10)
        self.assertEqual(path.read_bytes(), b'new')
        self.assertEqual(path.with_suffix('.log.1').read_bytes(), b'12345678')
        self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        runner.append_log(path, b'abcdefgh', limit=10)
        self.assertEqual(path.with_suffix('.log.1').read_bytes(), b'new')

    def test_stop_reaps_unresponsive_child_with_an_open_output_pipe(self):
        (self.root / 'logs').mkdir()
        ready = self.root / 'child.pid'
        self.binary.write_text(
            '#!' + sys.executable + '\n'
            'import os, signal, time\n'
            'signal.signal(signal.SIGTERM, signal.SIG_IGN)\n'
            'with open(' + repr(str(ready)) + ', "w") as output:\n'
            '    output.write(str(os.getpid()))\n'
            'while True: time.sleep(1)\n'
        )
        process = subprocess.Popen(
            [sys.executable, str(ROOT / 'run.py'), str(self.binary)],
            env=dict(os.environ, KNOT_MAC_CONFIG_DIR=str(self.root)),
            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
        )
        child_pid = None
        try:
            deadline = time.monotonic() + 10
            while not ready.exists() and time.monotonic() < deadline:
                if process.poll() is not None:
                    self.fail('수집기 wrapper가 준비 전에 종료되었습니다')
                time.sleep(0.05)
            self.assertTrue(ready.exists(), '합성 자식 프로세스가 준비되지 않았습니다')
            child_pid = int(ready.read_text())
            process.send_signal(signal.SIGTERM)
            process.wait(timeout=20)
            self.assertEqual(process.returncode, 128 + signal.SIGTERM)
            with self.assertRaises(ProcessLookupError):
                os.kill(child_pid, 0)
        finally:
            if process.poll() is None:
                process.kill()
            process.communicate(timeout=5)
            if child_pid is not None:
                try:
                    os.kill(child_pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass

    def test_dashboard_does_not_hide_missing_data_or_use_linux_memory(self):
        dashboard = json.loads((ROOT.parent / 'provisioning/mac-dashboards/knot-mac.json').read_text())
        queries = [target['expr'] for panel in dashboard['panels']
                   for target in panel.get('targets', [])]
        for query in queries:
            self.assertIn('instance="knot-ai-macmini"', query)
            self.assertNotIn('vector(0)', query)
            self.assertNotIn('MemAvailable', query)
        self.assertTrue(any('timestamp(node_memory_total_bytes' in query for query in queries))


if __name__ == '__main__':
    unittest.main()
