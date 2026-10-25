import importlib.util
from pathlib import Path
import unittest

root = Path(__file__).parent
spec = importlib.util.spec_from_file_location("render_agent", root / "render.py")
renderer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(renderer)


class RenderTest(unittest.TestCase):
    def setUp(self):
        self.source = (root / "config.alloy").read_text()

    def test_prod_preserves_cursors_and_real_collection_intervals(self):
        result = renderer.render(self.source, "prod", "https://ingest.example.test")
        self.assertIn('loki.source.journal "spring_blue_legacy"', result)
        self.assertEqual(result.count('scrape_interval = "5s"'), 2)
        self.assertEqual(result.count('scrape_timeout = "3s"'), 2)
        self.assertEqual(result.count('period = "1m"'), 9)
        self.assertIn('regex = "dev|prod"', result)
        self.assertIn('DBInstanceIdentifier = "knot-database"', result)  # DB mapping is materialized too.

    def test_dev_keeps_existing_interval_and_cursor(self):
        result = renderer.render(self.source, "dev", "https://ingest.example.test/")
        self.assertIn('loki.source.journal "spring"', result)
        self.assertNotIn('spring_blue_legacy', result)
        self.assertEqual(result.count('period = "5m"'), 9)
        self.assertNotIn('example.test//', result)

    def test_typo_or_missing_slot_is_rejected_even_if_alloy_syntax_is_valid(self):
        for bad in [self.source.replace('_SYSTEMD_UNIT=knot-backend-green', '_SYSTMD_UNIT=knot-backend-green'),
                    self.source.replace('knot-backend-green.service', 'knot-backend-blue.service')]:
            with self.assertRaises(ValueError):
                renderer.render(bad, "prod", "https://ingest.example.test")

    def test_endpoint_does_not_accept_credentials_or_plain_http(self):
        for url in ['http://ingest.example.test', 'https://user:secret@ingest.example.test',
                    'https://ingest.example.test?token=secret']:
            with self.assertRaises(ValueError):
                renderer.render(self.source, "prod", url)

    def test_legacy_cannot_masquerade_as_a_blue_slot(self):
        for bad in [self.source.replace('slot = "legacy"', 'slot = "blue"'),
                    self.source.replace('unit = "knot-backend-green.service"', 'unit = "knot-backend-blue.service"')]:
            with self.assertRaisesRegex(ValueError, 'exact slot'):
                renderer.render(bad, 'prod', 'https://ingest.example.test')

    def test_scrape_job_defaults_match_the_exporter_target_labels(self):
        for job in ['integrations/unix', 'integrations/cloudwatch', 'integrations/postgres']:
            self.assertIn('job_name = "' + job + '"', self.source)


if __name__ == "__main__":
    unittest.main()
