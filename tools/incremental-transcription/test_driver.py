import contextlib
import io
import json
from pathlib import Path
import sys
import subprocess
import time
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch
import wave

import run_benchmark as driver


class PortableBenchmarkDriverTest(unittest.TestCase):
    def wav(self, path):
        with wave.open(str(path), 'wb') as output:
            output.setparams((1, 2, 16000, 16000, 'NONE', 'not compressed'))
            output.writeframes(b'\x01\x00' * 16000)

    def test_short_source_requires_explicit_repeat(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / 'source.wav'
            self.wav(source)
            with self.assertRaises(driver.BenchmarkFailure):
                driver.validate_audio(source, 2, 0, False)

    def test_explicit_repeat_records_repetition_and_exact_pcm_length(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / 'source.wav'
            self.wav(source)
            output = root / 'output'
            output.mkdir()
            value = driver.fixture(source, output, 2, 0, True)
            self.assertTrue(value['fixtureRepeated'])
            self.assertFalse(value['humanQualityValidated'])
            self.assertEqual((output / 'input.pcm').stat().st_size, 64000)
            self.assertEqual((output / 'input.wav').stat().st_size, 64044)

    def test_sanitization_drops_transcripts_vectors_urls_and_secrets(self):
        raw = {'execution': 'passed', 'workersStopped': True, 'cleanupRemainingVersions': 0,
               'rawWhisperJson': 'secret text', 'token': 'secret token', 'downloadUrl': 'https://secret',
               'runs': [{'round': 1, 'bothWithin30Seconds': True, 'embedding': [1.0], 'privateText': 'secret'}]}
        result = json.dumps(driver.sanitize_report(raw, 'fixed-prefix/'))
        self.assertNotIn('secret', result)
        self.assertNotIn('embedding', result)
        self.assertIn('bothWithin30Seconds', result)

    def test_allowed_hosts_are_limited_to_explicit_regional_endpoints(self):
        self.assertEqual(driver.allowed_s3_hosts('test.bucket', 'ap-northeast-2'),
                         ['test.bucket.s3.ap-northeast-2.amazonaws.com', 's3.ap-northeast-2.amazonaws.com'])
        self.assertEqual(driver.allowed_s3_hosts('test-bucket', 'cn-north-1'),
                         ['test-bucket.s3.cn-north-1.amazonaws.com.cn', 's3.cn-north-1.amazonaws.com.cn'])
        self.assertEqual(driver.allowed_s3_hosts('test-bucket', 'us-east-1'),
                         ['test-bucket.s3.us-east-1.amazonaws.com', 's3.us-east-1.amazonaws.com',
                          'test-bucket.s3.amazonaws.com', 's3.amazonaws.com'])

    def test_without_run_starts_no_process_and_creates_no_report(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'backend').mkdir()
            (root / 'backend/gradlew').touch()
            (root / 'ai/runner').mkdir(parents=True)
            source = root / 'source.wav'
            self.wav(source)
            report = root / 'report.json'
            args = ['run_benchmark.py', '--repo-root', str(root), '--ai-source-root', str(root / 'ai'),
                    '--audio-file', str(source), '--profile', str(root / 'profile.json'), '--bucket', 'test-bucket',
                    '--seconds', '1', '--report', str(report)]
            with patch.object(sys, 'argv', args), patch.object(driver.subprocess, 'run') as run, \
                    patch.object(driver.subprocess, 'Popen') as process, contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(driver.main(), 0)
                run.assert_not_called()
                process.assert_not_called()
            self.assertFalse(report.exists())


class OwnedProcessTest(unittest.TestCase):
    def launch_family(self):
        process = driver.start_process(
            [sys.executable, '-c', "import subprocess,sys,time; subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)']); time.sleep(60)"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            members = driver.observe_group(process)
            if members and len(members) >= 2:
                return process
            time.sleep(0.02)
        driver.stop_process(process, 1, 1)
        self.fail('owned child family did not start')

    def assert_stopped(self, process):
        self.assertTrue(driver.stop_process(process, 1, 1))
        members = driver.group_members(process.pid)
        self.assertIsNotNone(members)
        self.assertFalse(any(not identity[1].startswith('Z') for identity in members.values()))

    def test_owned_child_and_grandchild_stop_together(self):
        process = self.launch_family()
        try:
            self.assert_stopped(process)
        finally:
            driver.stop_process(process, 1, 1)

    def test_overall_timeout_stops_owned_family(self):
        process = self.launch_family()
        try:
            with self.assertRaisesRegex(driver.BenchmarkFailure, 'OVERALL_EXECUTION_TIMEOUT'):
                driver.wait_backend(process, time.monotonic() - 1, time.monotonic() + 60, Path('/nonexistent-report'), None)
        finally:
            self.assert_stopped(process)

    def test_bootstrap_timeout_stops_owned_family(self):
        process = self.launch_family()
        try:
            with self.assertRaisesRegex(driver.BenchmarkFailure, 'BACKEND_BOOTSTRAP_TIMEOUT'):
                driver.wait_backend(process, time.monotonic() + 60, time.monotonic() - 1, Path('/nonexistent-report'), None)
        finally:
            self.assert_stopped(process)

    def test_keyboard_interrupt_still_stops_owned_family(self):
        process = self.launch_family()
        try:
            with patch.object(driver.time, 'sleep', side_effect=KeyboardInterrupt), self.assertRaises(KeyboardInterrupt):
                driver.wait_backend(process, time.monotonic() + 60, time.monotonic() + 60, Path('/nonexistent-report'), None)
        finally:
            self.assert_stopped(process)

    def test_unregistered_foreign_process_is_not_signaled(self):
        foreign = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            self.assertFalse(driver.stop_process(foreign, 1, 1))
            self.assertIsNone(foreign.poll())
        finally:
            foreign.terminate()
            foreign.wait(timeout=3)

    def test_reused_pid_with_changed_start_identity_is_not_signaled(self):
        foreign = SimpleNamespace(pid=99999999, _benchmark_group=99999999,
                                  _benchmark_known_starts={99999999: 'original-start'})
        with patch.object(driver, 'group_members', return_value={99999999: ('other-start', 'S')}), \
                patch.object(driver.os, 'killpg') as kill:
            self.assertFalse(driver.stop_process(foreign, 1, 1))
            kill.assert_not_called()

    def test_budget_accounts_for_rounds_feed_wait_and_shutdown(self):
        self.assertEqual(driver.execution_budget(70, 3, True, False, 600, 1200, 300), 2400)
        self.assertEqual(driver.execution_budget(70, 3, False, True, 600, 1200, 300), 12990)


class NativeOwnershipTest(unittest.TestCase):
    def record(self, root, value):
        directory = root / 'ai-work' / 'job-uuid'
        directory.mkdir(parents=True)
        path = directory / 'process-owner.json'
        path.write_text(json.dumps(value))
        return path

    def test_no_record_does_not_invent_a_native_process(self):
        with tempfile.TemporaryDirectory() as folder:
            self.assertTrue(driver.native_ownership_clean(Path(folder) / 'ai-work'))

    def test_cleaned_schema_one_record_confirms_detached_cleanup(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            self.record(root, {'schemaVersion': 1, 'state': 'CLEANED', 'pgid': 123,
                               'parentPid': 99, 'parentStarted': 'start', 'members': {}})
            self.assertTrue(driver.native_ownership_clean(root / 'ai-work'))

    def test_running_failed_unknown_and_invalid_records_block_private_deletion(self):
        for value in [{'schemaVersion': 1, 'state': 'RUNNING'},
                      {'schemaVersion': 1, 'state': 'CLEANUP_FAILED'},
                      {'schemaVersion': 2, 'state': 'CLEANED'}, []]:
            with self.subTest(value=value), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                self.record(root, value)
                report = {}
                with patch.object(driver, 'stop_process', return_value=True):
                    self.assertFalse(driver.stop_native(object(), root, report))
                self.assertFalse(report['nativeOwnershipClean'])

    def test_unparseable_or_symlink_record_is_not_cleanup_confirmation(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            record = self.record(root, {})
            record.write_text('{invalid')
            self.assertFalse(driver.native_ownership_clean(root / 'ai-work'))
            record.unlink()
            target = root / 'external.json'
            target.write_text(json.dumps({'schemaVersion': 1, 'state': 'CLEANED'}))
            record.symlink_to(target)
            self.assertFalse(driver.native_ownership_clean(root / 'ai-work'))


if __name__ == '__main__':
    unittest.main()
