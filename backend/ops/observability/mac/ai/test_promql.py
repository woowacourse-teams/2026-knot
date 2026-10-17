"""Run dashboard expressions against synthetic series with the pinned Prometheus tool.

Requires Docker; no ports are opened and the container has no network access.
"""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


DASHBOARD = Path(__file__).resolve().parents[2] / "provisioning/mac-dashboards/knot-mac-ai.json"
IMAGE = "prom/prometheus@sha256:efd719c99d83b060d9daefdcf00360461adf279f45ef5391f8d111892118753e"
LABELS = 'project="knot",env="shared",job="mac-ai",instance="knot-ai-macmini",ai_scope="validation"'


def series(name, values, extra=""):
    return {"series": name + "{" + LABELS + extra + "}", "values": values}


def expect(expr, value=None, labels=None):
    samples = [] if labels is None else [{"labels": labels, "value": value}]
    return {"expr": expr.replace("$ai_scope", "validation"), "eval_time": "0s", "exp_samples": samples}


@unittest.skipUnless(shutil.which("docker"), "Docker is required for the real PromQL evaluator")
class DashboardPromqlTest(unittest.TestCase):
    def test_current_states_and_absence_with_the_real_evaluator(self):
        # given
        dashboard = json.loads(DASHBOARD.read_text())
        panels = {p["title"]: p for p in dashboard["panels"]}
        query = lambda title, index=0: panels[title]["targets"][index]["expr"]
        ready = query("앱 준비 상태")
        active = query("전사 작업 진행")
        elapsed = query("현재 작업 경과")
        average = query("완료 단계 평균 시간")
        healthy = [series("up", "1"), series("knot_ai_ready", "1"),
                   series("knot_ai_active_jobs", "1"), series("knot_ai_job_elapsed_seconds", "12"),
                   {"series": 'knot_ai_ready{' + LABELS.replace('"validation"', '"service"') + '}', "values": "0"}]
        tests = [
            {"name": "ready and busy are independent; service scope is excluded", "input_series": healthy,
             "promql_expr_test": [expect(ready, 1, "knot_ai_ready{" + LABELS + "}"),
                                  expect(active, 1, "knot_ai_active_jobs{" + LABELS + "}")]},
            {"name": "failed scrape hides previous app state", "input_series": [series("up", "0"), series("knot_ai_ready", "1")],
             "promql_expr_test": [expect(ready), expect(query("지표 수집 성공"), 0, "up{" + LABELS + "}")]},
            {"name": "old samples are not current app state", "input_series": healthy,
             "promql_expr_test": [dict(expect(ready), eval_time="20s"),
                                  dict(expect(query("지표 수집 성공")), eval_time="20s")]},
            {"name": "real idle zero is preserved", "input_series": [series("up", "1"), series("knot_ai_job_elapsed_seconds", "0")],
             "promql_expr_test": [expect(elapsed, 0, "knot_ai_job_elapsed_seconds{" + LABELS + "}")]},
            {"name": "no completed stages have no average", "input_series": [series("up", "1"),
                series("knot_ai_stage_duration_seconds_sum", "0", ',stage="download",outcome="completed"'),
                series("knot_ai_stage_duration_seconds_count", "0", ',stage="download",outcome="completed"')],
             "promql_expr_test": [expect(average)]},
            {"name": "completed stage average has the expected value", "input_series": [series("up", "1"),
                series("knot_ai_stage_duration_seconds_sum", "6", ',stage="download",outcome="completed"'),
                series("knot_ai_stage_duration_seconds_count", "2", ',stage="download",outcome="completed"')],
             "promql_expr_test": [expect(average, 3, "{" + LABELS + ',stage="download"}')]},
            {"name": "stage aggregates retain the scope matching labels", "input_series": [series("up", "1"),
                series("knot_ai_stage_duration_seconds_count", "2", ',stage="download",outcome="completed"'),
                series("knot_ai_stage_duration_seconds_count", "1", ',stage="download",outcome="failed"')],
             "promql_expr_test": [expect(query("완료 단계 수"), 3, "{" + LABELS + ',stage="download"}')]},
        ]
        with tempfile.TemporaryDirectory(prefix="knot-ai-promql-") as temporary:
            directory = Path(temporary)
            rules = {"groups": [{"name": "dashboard_syntax", "rules": [
                {"record": "dashboard_expression_" + str(index), "expr": target["expr"].replace("$ai_scope", "validation")}
                for index, target in enumerate(t for p in dashboard["panels"] for t in p["targets"])
            ]}]}
            (directory / "rules.json").write_text(json.dumps(rules))
            (directory / "tests.json").write_text(json.dumps({"rule_files": [], "evaluation_interval": "5s", "tests": tests}))
            command = ["docker", "run", "--rm", "--network", "none", "--cap-drop", "ALL",
                       "--security-opt", "no-new-privileges:true", "--entrypoint", "/bin/promtool",
                       "-v", temporary + ":/checks:ro", IMAGE]
            # when
            syntax = subprocess.run(command + ["check", "rules", "/checks/rules.json"], capture_output=True, text=True)
            behavior = subprocess.run(command + ["test", "rules", "/checks/tests.json"], capture_output=True, text=True)
        # then
        self.assertEqual(syntax.returncode, 0, syntax.stdout + syntax.stderr)
        self.assertEqual(behavior.returncode, 0, behavior.stdout + behavior.stderr)


if __name__ == "__main__":
    unittest.main()
