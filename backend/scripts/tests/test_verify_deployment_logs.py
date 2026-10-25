import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest

spec = importlib.util.spec_from_file_location('verify', Path(__file__).parents[1] / 'verify_deployment_logs.py')
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)
REQUEST_ID = '0123456789abcdef0123456789abcdef'


def vector(value=None):
    result = [] if value is None else [{'metric': {}, 'value': [123, str(value)]}]
    return {'status': 'success', 'data': {'resultType': 'vector', 'result': result}}


class VerifyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.config = root / 'check.json'
        self.config.write_text(json.dumps({'environment': 'prod', 'ingest_base_url': 'https://ingest.example.test'}))
        ca = root / 'origin.crt'
        ca.touch()
        self.args = SimpleNamespace(config=self.config, environment='prod', slot='green',
                                    preflight=False, timeout=3, tls_directory=root, origin_ca=ca)
        self.now = 0

    def sleep(self, seconds):
        self.now += seconds

    def run_check(self, fetch):
        return verify.verify(self.args, fetch=fetch, clock=lambda: self.now, sleep=self.sleep)

    def test_new_nginx_request_is_matched_only_against_the_candidate_slot(self):
        calls = []
        def fetch(url, options, timeout):
            calls.append((url, options))
            self.assertGreater(timeout, 0)
            self.assertNotIn('--insecure', options)
            if '/actuator/health' in url:
                self.assertIn('api.knoted.kr:443:127.0.0.1', options)
                self.assertIn('--cacert', options)
                return {'status': 'UP'}, 'HTTP/1.1 200 OK\r\nX-Request-ID: ' + REQUEST_ID
            self.assertEqual(url, 'https://ingest.example.test/prod/log-receipt?slot=green&request_id=' + REQUEST_ID)
            self.assertIn('--cert', options)
            self.assertIn('--key', options)
            return vector(None if len(calls) == 2 else 1), ''
        result = self.run_check(fetch)
        self.assertTrue(result['received'])
        self.assertEqual(result['request_id'], REQUEST_ID)
        self.assertEqual(len(calls), 3)

    def test_preflight_only_checks_authenticated_receipt_endpoint(self):
        self.args.preflight = True
        def fetch(url, options, timeout):
            self.assertIn('/prod/log-receipt?slot=green&request_id=', url)
            return vector(), ''
        self.assertEqual(self.run_check(fetch)['preflight'], 'ok')

    def test_missing_log_has_a_distinct_failure_from_query_outage(self):
        for unavailable, expected in [(False, 2), (True, 3)]:
            self.now = 0
            def fetch(url, options, timeout):
                if '/actuator/health' in url:
                    return {'status': 'UP'}, 'X-Request-ID: ' + REQUEST_ID
                if unavailable:
                    raise verify.CheckFailure('HTTP unavailable', 3)
                return vector(), ''
            with self.subTest(unavailable=unavailable), self.assertRaises(verify.CheckFailure) as caught:
                self.run_check(fetch)
            self.assertEqual(caught.exception.code, expected)
            self.assertEqual(self.now, 3)

    def test_malformed_or_nonfinite_counts_are_not_success(self):
        for data in [{}, {'status': 'error'}, vector('NaN'), vector('Inf'), vector(-1), vector(0.5)]:
            with self.subTest(data=data), self.assertRaises(verify.CheckFailure):
                verify.receipt_count(data)

    def test_missing_ambiguous_or_malformed_request_id_is_rejected(self):
        for headers in ['', 'X-Request-ID: short', 'X-Request-ID: ' + REQUEST_ID + '\nX-Request-ID: ' + 'f' * 32]:
            with self.subTest(headers=headers), self.assertRaises(verify.CheckFailure):
                verify.request_id({'status': 'UP'}, headers)
        with self.assertRaises(verify.CheckFailure):
            verify.request_id({'status': 'DOWN', 'components': {'status': 'UP'}}, 'X-Request-ID: ' + REQUEST_ID)

    def test_wrong_environment_or_insecure_origin_fails_before_http(self):
        for config in [{'environment': 'dev', 'ingest_base_url': 'https://ingest.example.test'},
                       {'environment': 'prod', 'ingest_base_url': 'http://ingest.example.test'},
                       {'environment': 'prod', 'ingest_base_url': 'https://user:password@ingest.example.test'}]:
            self.config.write_text(json.dumps(config))
            with self.subTest(config=config), self.assertRaises(verify.CheckFailure) as caught:
                self.run_check(lambda *args: self.fail('HTTP must not run'))
            self.assertEqual(caught.exception.code, 4)


if __name__ == '__main__':
    unittest.main()
