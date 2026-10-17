"""Contract checks for the opt-in AI collector and scope-isolated dashboard."""
import json
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
DASHBOARD = ROOT / "provisioning/mac-dashboards/knot-mac-ai.json"
MODULE = Path(__file__).with_name("config.alloy")
METRICS = {
    "knot_ai_ready", "knot_ai_active_jobs", "knot_ai_job_elapsed_seconds",
    "knot_ai_stage_active", "knot_ai_stage_elapsed_seconds",
    "knot_ai_jobs_completed_total", "knot_ai_stage_duration_seconds_sum",
    "knot_ai_stage_duration_seconds_count",
}


class AiMetricsContractTest(unittest.TestCase):
    def setUp(self):
        self.dashboard = json.loads(DASHBOARD.read_text())
        self.panels = self.dashboard["panels"]
        self.queries = [t["expr"] for p in self.panels for t in p["targets"]]

    def test_scope_selector_defaults_to_validation_without_all(self):
        # given / when
        selector, = self.dashboard["templating"]["list"]
        # then
        self.assertEqual(selector["name"], "ai_scope")
        self.assertEqual(selector["current"]["value"], "validation")
        self.assertEqual({o["value"] for o in selector["options"]}, {"validation", "service"})
        self.assertFalse(selector["multi"])
        self.assertFalse(selector["includeAll"])

    def test_every_metric_selector_is_isolated_to_the_requested_scope(self):
        # given / when
        selectors = [s for q in self.queries for s in re.findall(r"\{[^{}]*\}", q)]
        # then
        for selector in selectors:
            for label in ('project="knot"', 'env="shared"', 'job="mac-ai"',
                          'instance="knot-ai-macmini"', 'ai_scope="$ai_scope"'):
                self.assertIn(label, selector)
        self.assertEqual(set(re.findall(r"knot_ai_[a-z_]+", " ".join(self.queries))), METRICS)

    def test_current_state_never_reuses_a_historical_range_reduction(self):
        # given / when
        stats = [p for p in self.panels if p["type"] == "stat"]
        # then
        for panel in stats:
            for target in panel["targets"]:
                self.assertTrue(target["instant"])
                self.assertFalse(target["range"])
                if "knot_ai_" in target["expr"]:
                    self.assertIn("< 20", target["expr"])
                    self.assertIn("== 1", target["expr"])

    def test_missing_data_is_not_changed_to_idle_or_success(self):
        # given / when
        queries = " ".join(self.queries)
        # then
        self.assertNotRegex(queries, r"\bor\s+(?:vector\s*\()?0")
        for panel in self.panels:
            self.assertEqual(panel["fieldConfig"]["defaults"]["noValue"], "데이터 없음")
            if panel["type"] == "timeseries":
                self.assertFalse(panel["fieldConfig"]["defaults"]["custom"]["spanNulls"])

    def test_panels_explain_states_and_use_korean_names_without_error_thresholds(self):
        # given / when
        rendered = json.dumps(self.dashboard, ensure_ascii=False)
        # then
        self.assertIn("통합 runner", rendered)
        for panel in self.panels:
            self.assertRegex(panel["title"], r"[가-힣]")
            self.assertNotIn("thresholds", panel["fieldConfig"]["defaults"])
            for target in panel["targets"]:
                self.assertRegex(target["legendFormat"], r"[가-힣]")
                self.assertNotIn("{{", target["legendFormat"])

    def test_module_uses_local_authenticated_bounded_scrapes_and_existing_receiver(self):
        # given / when
        module = MODULE.read_text()
        # then
        for setting in ('default  = "127.0.0.1:18765"', 'default  = "service"',
                        'metrics_path      = "/internal/metrics"', 'scrape_interval   = "5s"',
                        'scrape_timeout    = "3s"', 'follow_redirects  = false',
                        'bearer_token_file = argument.token_file.value',
                        'forward_to       = argument.forward_to.value'):
            self.assertIn(setting, module)
        self.assertNotIn("prometheus.remote_write", module)
        self.assertNotRegex(module, r"(?:attempt_id|job_id|request_id|transcript)\s*=")


if __name__ == "__main__":
    unittest.main()
