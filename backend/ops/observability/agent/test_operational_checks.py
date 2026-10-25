import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("checks", Path(__file__).with_name("operational_checks.py"))
checks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checks)


class ChecksTest(unittest.TestCase):
    def test_inactive_standby_is_not_a_failure_but_no_active_backend_is(self):
        states = {unit: {"LoadState": "loaded", "ActiveState": "active"} for unit in checks.REQUIRED}
        states["knot-backend-blue.service"] = {"LoadState": "loaded", "ActiveState": "active"}
        states["knot-backend-green.service"] = {"LoadState": "loaded", "ActiveState": "inactive"}
        result = checks.service_metrics(states)
        self.assertIn('knot_systemd_service_failed{unit="knot-backend-green.service"} 0', result)
        self.assertIn("knot_backend_service_active 1", result)
        states["knot-backend-blue.service"]["ActiveState"] = "failed"
        result = checks.service_metrics(states)
        self.assertIn("knot_backend_service_active 0", result)
        self.assertIn('knot_systemd_service_failed{unit="knot-backend-blue.service"} 1', result)
        self.assertNotIn("prototype", result)

    def test_backup_query_failure_is_not_reported_as_success_and_keeps_last_timestamp(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            def successful(args):
                if args[0] == 'systemctl':
                    return 'LoadState=loaded\nActiveState=active\n'
                return json.dumps({'RetentionDays': 1, 'LatestRestorableTime': '2026-10-25T06:00:00Z'})
            checks.collect(root, 'prod', successful)
            previous = (root / 'rds-backup.prom').read_text()
            (root / 'rds-check.prom').unlink()
            def failing(args):
                if args[0] == 'aws':
                    raise RuntimeError('query denied')
                return successful(args)
            checks.collect(root, 'prod', failing)
            self.assertEqual((root / 'rds-backup.prom').read_text(), previous)
            self.assertIn('knot_rds_backup_check_success 0', (root / 'rds-check.prom').read_text())
            self.assertFalse(list(root.glob('.check-*')))

    def test_missing_backup_time_is_not_assumed_healthy(self):
        with self.assertRaises(AttributeError):
            checks.backup_metrics({'RetentionDays': 0, 'LatestRestorableTime': None})


if __name__ == '__main__':
    unittest.main()
