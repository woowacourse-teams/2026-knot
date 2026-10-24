import copy
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "harness" / "issue_planning.py"
sys.path.insert(0, str(ROOT / "harness"))
SPEC = importlib.util.spec_from_file_location("issue_planning", MODULE_PATH)
issue_planning = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(issue_planning)

MATERIALIZER_PATH = ROOT / "harness" / "materialize_adr.py"
MATERIALIZER_SPEC = importlib.util.spec_from_file_location(
    "materialize_adr", MATERIALIZER_PATH
)
materialize_adr = importlib.util.module_from_spec(MATERIALIZER_SPEC)
assert MATERIALIZER_SPEC.loader is not None
MATERIALIZER_SPEC.loader.exec_module(materialize_adr)


def fixture(name: str) -> dict:
    path = ROOT / "harness" / "tests" / "fixtures" / name
    return json.loads(path.read_text(encoding="utf-8"))


def materializable_snapshot() -> dict:
    snapshot = fixture("high-risk-create.json")
    snapshot["issue_number"] = 123
    return snapshot


class FakeRunner:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    def __call__(self, argv, **kwargs):
        self.calls.append((argv, kwargs))
        if not self.responses:
            raise AssertionError(f"unexpected command: {argv}")
        response = self.responses.pop(0)
        if isinstance(response, Exception):
            raise response
        return response


def completed(returncode=0, stdout="", stderr=""):
    return subprocess.CompletedProcess([], returncode, stdout=stdout, stderr=stderr)


class IssuePlanningTest(unittest.TestCase):
    def test_low_risk_create_passes_without_heavy_contract(self):
        result = issue_planning.plan(fixture("low-risk-create.json"))

        self.assertEqual("pass", result["status"])
        self.assertEqual("low", result["risk_level"])
        self.assertEqual("render_draft", result["action"])
        self.assertEqual("publish_issue", result["requested_action"])
        self.assertFalse(result["remote_write_authorized"])
        self.assertTrue(result["publish_ready"])
        self.assertEqual("none", result["next_on_implementation"])
        self.assertEqual(
            ["## 구현 기능 설명", "## TODO", "## 메모"],
            h2_headings(result["issue_body"]),
        )
        self.assertNotIn("상황", result["issue_body"])

    def test_incomplete_high_risk_contract_is_held(self):
        result = issue_planning.plan(fixture("high-risk-hold.json"))

        self.assertEqual("hold", result["status"])
        self.assertEqual("high", result["risk_level"])
        self.assertEqual("report_hold", result["action"])
        self.assertEqual("publish_issue", result["requested_action"])
        self.assertFalse(result["remote_write_authorized"])
        self.assertFalse(result["publish_ready"])
        self.assertIn("missing: failure_flows", result["errors"])
        self.assertIn("missing: interview", result["errors"])
        self.assertIn("grill.status must be pass", result["errors"])

    def test_high_risk_contract_without_interview_is_held(self):
        snapshot = fixture("high-risk-create.json")
        del snapshot["interview"]

        result = issue_planning.plan(snapshot)

        self.assertEqual("hold", result["status"])
        self.assertIn("missing: interview", result["errors"])

    def test_skipped_interview_requires_all_evidence_with_sources(self):
        cases = (
            (
                "missing-evidence",
                lambda interview: interview["evidence"].pop("rationale"),
                "missing: interview.evidence.rationale",
            ),
            (
                "missing-source",
                lambda interview: interview["evidence"]["decision"].update(
                    {"sources": []}
                ),
                "missing: interview.evidence.decision.sources",
            ),
        )

        for name, mutate, expected_error in cases:
            with self.subTest(case=name):
                snapshot = fixture("high-risk-create.json")
                mutate(snapshot["interview"])

                result = issue_planning.plan(snapshot)

                self.assertEqual("hold", result["status"])
                self.assertIn(expected_error, result["errors"])

    def test_skipped_interview_holds_conflicts_or_unconfirmed_validity(self):
        cases = (
            (
                "conflict",
                lambda interview: interview["conflicts"].append(
                    "회의록과 현재 요청의 선택이 다르다."
                ),
                "interview.conflicts must be empty before pass",
            ),
            (
                "stale",
                lambda interview: interview.update({"current_validity": "unknown"}),
                "interview.current_validity must be confirmed",
            ),
        )

        for name, mutate, expected_error in cases:
            with self.subTest(case=name):
                snapshot = fixture("high-risk-create.json")
                mutate(snapshot["interview"])

                result = issue_planning.plan(snapshot)

                self.assertEqual("hold", result["status"])
                self.assertIn(expected_error, result["errors"])

    def test_completed_interview_requires_resolved_user_question(self):
        snapshot = fixture("high-risk-create.json")
        snapshot["interview"]["status"] = "completed"

        result = issue_planning.plan(snapshot)

        self.assertEqual("hold", result["status"])
        self.assertIn("missing: interview.resolved_questions", result["errors"])

    def test_completed_interview_passes_with_resolved_user_question(self):
        snapshot = fixture("high-risk-create.json")
        snapshot["interview"]["status"] = "completed"
        snapshot["interview"]["resolved_questions"] = [
            "사용자에게 자동 병합 여부를 확인해 명시적 확인 후 연결로 확정했다."
        ]

        result = issue_planning.plan(snapshot)

        self.assertEqual("pass", result["status"])
        self.assertEqual("completed", result["interview_status"])
        self.assertEqual("사용자 확인으로 인터뷰 완료", result["interview_notice"])

    def test_complete_high_risk_contract_keeps_issue_short_and_points_to_proposed_adr(
        self,
    ):
        result = issue_planning.plan(fixture("high-risk-create.json"))

        self.assertEqual("pass", result["status"])
        self.assertEqual("high", result["risk_level"])
        self.assertEqual("render_draft", result["action"])
        self.assertEqual("publish_issue", result["requested_action"])
        self.assertFalse(result["remote_write_authorized"])
        self.assertEqual("skipped", result["interview_status"])
        self.assertEqual("자료 충분으로 인터뷰 생략", result["interview_notice"])
        self.assertEqual("materialize_proposed_adr", result["next_on_implementation"])
        self.assertEqual("pending_issue_number", result["adr_path_status"])
        self.assertEqual("finalize_adr_path", result["next_after_issue_created"])
        self.assertEqual(
            ["## 구현 기능 설명", "## TODO", "## 메모"],
            h2_headings(result["issue_body"]),
        )
        self.assertIn("회의에서 동일 이메일 OAuth 사용자를", result["issue_body"])
        self.assertIn(
            "ADR: 동일 이메일 계정은 사용자 확인 후 연결한다. — 예정 경로:",
            result["issue_body"],
        )
        self.assertIn(
            "backend/docs/adr/{ISSUE_NUMBER}-auth-account-linking.md", result["issue_body"]
        )
        self.assertNotIn("## ADR 결정", result["issue_body"])
        self.assertNotIn("### 검토한 대안", result["issue_body"])
        self.assertNotIn("자동 병합: 편하지만", result["issue_body"])

    def test_draft_never_authorizes_remote_write(self):
        snapshot = fixture("high-risk-create.json")
        snapshot["operation"] = "draft"

        result = issue_planning.plan(snapshot)

        self.assertEqual("pass", result["status"])
        self.assertEqual("render_draft", result["action"])
        self.assertEqual("render_draft", result["requested_action"])
        self.assertFalse(result["remote_write_authorized"])
        self.assertFalse(result["publish_ready"])

    def test_issue_number_finalizes_adr_path(self):
        result = issue_planning.plan(materializable_snapshot())

        self.assertEqual("pass", result["status"])
        self.assertEqual("finalized", result["adr_path_status"])
        self.assertEqual("none", result["next_after_issue_created"])
        self.assertIn("backend/docs/adr/123-auth-account-linking.md", result["issue_body"])
        self.assertNotIn("{ISSUE_NUMBER}", result["issue_body"])

    def test_wrong_scalar_type_is_held_without_crashing(self):
        cases = (
            ("operation", ["create"], "operation must be draft or create"),
            ("purpose", ["목적"], "purpose must be a string"),
        )

        for field, value, expected_error in cases:
            with self.subTest(field=field):
                snapshot = fixture("low-risk-create.json")
                snapshot[field] = value

                result = issue_planning.plan(snapshot)

                self.assertEqual("hold", result["status"])
                self.assertIn(expected_error, result["errors"])
                self.assertFalse(result["remote_write_authorized"])

    def test_wrong_list_item_type_is_held_without_crashing(self):
        snapshot = fixture("low-risk-create.json")
        snapshot["scope"] = ["범위", 1]

        result = issue_planning.plan(snapshot)

        self.assertEqual("hold", result["status"])
        self.assertIn("scope must contain non-empty strings", result["errors"])

    def test_non_object_snapshot_is_held_without_crashing(self):
        result = issue_planning.plan(["not", "an", "object"])

        self.assertEqual("hold", result["status"])
        self.assertEqual("unknown", result["risk_level"])
        self.assertEqual("unknown", result["requested_action"])
        self.assertIn("snapshot must be an object", result["errors"])

    def test_markdown_h2_injection_is_held(self):
        cases = (
            ("purpose", "설명\n## 임의 섹션"),
            ("scope", ["정상 범위", "  ## 숨은 섹션"]),
        )

        for field, value in cases:
            with self.subTest(field=field):
                snapshot = fixture("low-risk-create.json")
                snapshot[field] = value

                result = issue_planning.plan(snapshot)

                self.assertEqual("hold", result["status"])
                self.assertTrue(
                    any(
                        "must not contain Markdown level-2 headings" in error
                        for error in result["errors"]
                    )
                )
                self.assertNotIn("issue_body", result)

    def test_hold_cli_exits_nonzero(self):
        completed = subprocess.run(
            [
                sys.executable,
                str(MODULE_PATH),
                str(ROOT / "harness/tests/fixtures/high-risk-hold.json"),
            ],
            cwd=ROOT,
            check=False,
            capture_output=True,
            text=True,
        )

        result = json.loads(completed.stdout)
        self.assertEqual(1, completed.returncode)
        self.assertEqual("hold", result["status"])
        self.assertFalse(result["remote_write_authorized"])

    def test_pass_cli_exits_zero_without_authorizing_remote_write(self):
        completed = subprocess.run(
            [
                sys.executable,
                str(MODULE_PATH),
                str(ROOT / "harness/tests/fixtures/low-risk-create.json"),
            ],
            cwd=ROOT,
            check=False,
            capture_output=True,
            text=True,
        )

        result = json.loads(completed.stdout)
        self.assertEqual(0, completed.returncode)
        self.assertEqual("pass", result["status"])
        self.assertEqual("publish_issue", result["requested_action"])
        self.assertEqual("render_draft", result["action"])
        self.assertFalse(result["remote_write_authorized"])

    def test_publish_requires_create_operation(self):
        snapshot = fixture("low-risk-create.json")
        snapshot["operation"] = "draft"
        runner = FakeRunner([])

        result = issue_planning.publish_issue(
            snapshot, "woowacourse-teams/2026-Knot", runner=runner
        )

        self.assertEqual("hold", result["status"])
        self.assertEqual("report_hold", result["action"])
        self.assertFalse(result["remote_write_authorized"])
        self.assertIn("publish requires operation=create", result["errors"][0])
        self.assertEqual([], runner.calls)

    def test_publish_requires_explicit_repo(self):
        runner = FakeRunner([])

        result = issue_planning.publish_issue(
            fixture("low-risk-create.json"), "invalid/repo/path", runner=runner
        )

        self.assertEqual("hold", result["status"])
        self.assertEqual("report_hold", result["action"])
        self.assertFalse(result["remote_write_authorized"])
        self.assertIn("--repo must be provided as OWNER/REPO", result["errors"][0])
        self.assertEqual([], runner.calls)

    def test_publish_searches_marker_then_creates_issue(self):
        runner = FakeRunner(
            [
                completed(stdout="[]"),
                completed(
                    stdout="https://github.com/woowacourse-teams/2026-Knot/issues/456\n"
                ),
            ]
        )

        result = issue_planning.publish_issue(
            fixture("low-risk-create.json"),
            "woowacourse-teams/2026-Knot",
            runner=runner,
        )

        self.assertEqual("pass", result["status"])
        self.assertEqual("publish_issue", result["action"])
        self.assertTrue(result["remote_write_authorized"])
        self.assertEqual(456, result["issue_number"])
        self.assertEqual(
            "https://github.com/woowacourse-teams/2026-Knot/issues/456",
            result["issue_url"],
        )
        self.assertEqual(2, len(runner.calls))
        search_command = runner.calls[0][0]
        self.assertEqual("list", search_command[2])
        self.assertIn("in:body", search_command[search_command.index("--search") + 1])
        command, kwargs = runner.calls[1]
        self.assertEqual(
            [
                "gh",
                "issue",
                "create",
                "--repo",
                "woowacourse-teams/2026-Knot",
                "--title",
                "[BE] 로그인 오류 메시지 오탈자 수정",
                "--body-file",
                "-",
            ],
            command,
        )
        self.assertIn("## 구현 기능 설명", kwargs["input"])
        self.assertIn(result["contract_id"], kwargs["input"])
        self.assertTrue(kwargs["capture_output"])
        self.assertFalse(kwargs["check"])

    def test_publish_reuses_exact_contract_marker(self):
        snapshot = fixture("low-risk-create.json")
        planned = issue_planning.plan(snapshot)
        runner = FakeRunner(
            [
                completed(
                    stdout=json.dumps(
                        [
                            {
                                "number": 456,
                                "url": "https://github.com/example/repo/issues/456",
                                "body": (
                                    "<!-- knot-issue-contract:"
                                    f"{planned['contract_id']} -->"
                                ),
                            }
                        ]
                    )
                )
            ]
        )

        result = issue_planning.publish_issue(snapshot, "example/repo", runner=runner)

        self.assertEqual("pass", result["status"])
        self.assertEqual("reuse_existing_issue", result["action"])
        self.assertTrue(result["remote_write_authorized"])
        self.assertEqual(456, result["issue_number"])
        self.assertEqual(1, len(runner.calls))

    def test_publish_holds_when_marker_is_duplicated(self):
        snapshot = fixture("low-risk-create.json")
        contract_id = issue_planning.plan(snapshot)["contract_id"]
        body = f"<!-- knot-issue-contract:{contract_id} -->"
        runner = FakeRunner(
            [
                completed(
                    stdout=json.dumps(
                        [
                            {"number": 1, "url": "https://x/issues/1", "body": body},
                            {"number": 2, "url": "https://x/issues/2", "body": body},
                        ]
                    )
                )
            ]
        )

        result = issue_planning.publish_issue(snapshot, "example/repo", runner=runner)

        self.assertEqual("hold", result["status"])
        self.assertTrue(result["remote_write_authorized"])
        self.assertIn("multiple existing issues", result["errors"][0])

    def test_publish_reports_authorized_search_failure(self):
        runner = FakeRunner([completed(returncode=1, stderr="authentication failed")])

        result = issue_planning.publish_issue(
            fixture("low-risk-create.json"), "example/repo", runner=runner
        )

        self.assertEqual("hold", result["status"])
        self.assertEqual("publish_issue_hold", result["action"])
        self.assertTrue(result["remote_write_authorized"])
        self.assertIn("authentication failed", result["errors"][0])

    def test_unparseable_create_output_is_partial_publish(self):
        runner = FakeRunner(
            [completed(stdout="[]"), completed(stdout="created without URL")]
        )

        result = issue_planning.publish_issue(
            fixture("low-risk-create.json"), "example/repo", runner=runner
        )

        self.assertEqual("hold", result["status"])
        self.assertEqual("partial_publish_issue", result["action"])
        self.assertTrue(result["remote_write_authorized"])
        self.assertEqual("created without URL", result["issue_url"])

    def test_publish_finalizes_adr_path_and_edits_created_issue(self):
        runner = FakeRunner(
            [
                completed(stdout="[]"),
                completed(stdout="https://github.com/example/repo/issues/459\n"),
                completed(),
            ]
        )

        result = issue_planning.publish_issue(
            fixture("high-risk-create.json"), "example/repo", runner=runner
        )

        self.assertEqual("pass", result["status"])
        self.assertEqual("finalized", result["adr_path_status"])
        self.assertEqual("none", result["next_after_issue_created"])
        self.assertIn("backend/docs/adr/459-auth-account-linking.md", result["issue_body"])
        edit_command, edit_kwargs = runner.calls[2]
        self.assertEqual(["gh", "issue", "edit", "459"], edit_command[:4])
        self.assertIn("backend/docs/adr/459-auth-account-linking.md", edit_kwargs["input"])

    def test_adr_edit_failure_preserves_created_issue_as_partial(self):
        runner = FakeRunner(
            [
                completed(stdout="[]"),
                completed(stdout="https://github.com/example/repo/issues/460\n"),
                completed(returncode=1, stderr="edit failed"),
            ]
        )

        result = issue_planning.publish_issue(
            fixture("high-risk-create.json"), "example/repo", runner=runner
        )

        self.assertEqual("hold", result["status"])
        self.assertEqual("partial_publish_issue", result["action"])
        self.assertEqual(460, result["issue_number"])
        self.assertEqual("finalized", result["adr_path_status"])
        self.assertIn("edit failed", result["errors"][0])

    def test_high_risk_without_adr_uses_plain_issue_template(self):
        snapshot = fixture("high-risk-create.json")
        snapshot["adr"] = {
            "required": False,
            "reason": "실제로 논의한 대안이 하나뿐이다.",
        }

        result = issue_planning.plan(snapshot)

        self.assertEqual("pass", result["status"])
        self.assertEqual(
            ["## 구현 기능 설명", "## TODO", "## 메모"],
            h2_headings(result["issue_body"]),
        )
        self.assertNotIn(snapshot["adr"]["reason"], result["issue_body"])
        self.assertNotIn("ADR", result["issue_body"])
        self.assertNotIn("회의에서 동일 이메일 OAuth 사용자를", result["issue_body"])
        self.assertIn("## 메모\n\n- 없음", result["issue_body"])
        self.assertNotIn("- - 없음", result["issue_body"])

    def test_low_risk_contract_cannot_require_adr(self):
        complete = fixture("high-risk-create.json")
        complete["risk_signals"] = []
        incomplete = fixture("low-risk-create.json")
        incomplete["adr"] = {"required": True}

        for name, snapshot in (("complete", complete), ("incomplete", incomplete)):
            with self.subTest(snapshot=name):
                with tempfile.TemporaryDirectory() as tmp:
                    result = issue_planning.plan(snapshot)
                    materialized = materialize_adr.materialize(
                        snapshot,
                        Path(tmp),
                        implementation=True,
                    )

                    self.assertEqual("hold", result["status"])
                    self.assertIn(
                        "adr.required=true requires a high-risk contract",
                        result["errors"],
                    )
                    self.assertEqual("hold", materialized["status"])
                    self.assertEqual([], list(Path(tmp).iterdir()))

    def test_contract_id_is_stable_for_same_contract(self):
        snapshot = fixture("high-risk-create.json")

        first = issue_planning.plan(snapshot)["contract_id"]
        second = issue_planning.plan(copy.deepcopy(snapshot))["contract_id"]

        self.assertEqual(first, second)

    def test_unknown_risk_signal_is_held(self):
        snapshot = fixture("low-risk-create.json")
        snapshot["risk_signals"] = ["mystery"]

        result = issue_planning.plan(snapshot)

        self.assertEqual("hold", result["status"])
        self.assertIn("unknown risk signal: mystery", result["errors"])

    def test_missing_risk_assessment_is_held(self):
        snapshot = fixture("low-risk-create.json")
        del snapshot["risk_signals"]

        result = issue_planning.plan(snapshot)

        self.assertEqual("hold", result["status"])
        self.assertIn("missing: risk_signals", result["errors"])

    def test_malformed_high_risk_sections_are_held(self):
        snapshot = fixture("high-risk-create.json")
        snapshot["interview"] = "skipped"
        snapshot["grill"] = "pass"
        snapshot["adr"] = "accepted"

        result = issue_planning.plan(snapshot)

        self.assertEqual("hold", result["status"])
        self.assertIn("interview must be an object", result["errors"])
        self.assertIn("grill must be an object", result["errors"])
        self.assertIn("adr must be an object", result["errors"])

    def test_unconfirmed_adr_alternatives_are_held(self):
        snapshot = fixture("high-risk-create.json")
        snapshot["adr"]["alternatives_confirmed"] = False

        result = issue_planning.plan(snapshot)

        self.assertEqual("hold", result["status"])
        self.assertIn("adr.alternatives_confirmed must be true", result["errors"])

    def test_insufficient_adr_alternatives_are_held(self):
        snapshot = fixture("high-risk-create.json")
        snapshot["adr"]["alternatives"] = ["사용자 확인 후 연결"]

        result = issue_planning.plan(snapshot)

        self.assertEqual("hold", result["status"])
        self.assertIn(
            "adr.alternatives must include at least 2 confirmed real alternatives",
            result["errors"],
        )

    def test_unsafe_adr_path_is_held(self):
        snapshot = fixture("high-risk-create.json")
        snapshot["adr"]["planned_path"] = "../adr/123-auth-account-linking.md"

        result = issue_planning.plan(snapshot)

        self.assertEqual("hold", result["status"])
        self.assertIn("adr.planned_path must stay under backend/docs/adr", result["errors"])

    def test_legacy_adr_path_is_held(self):
        snapshot = fixture("high-risk-create.json")
        snapshot["adr"]["planned_path"] = "docs/adr/123-auth-account-linking.md"

        result = issue_planning.plan(snapshot)

        self.assertEqual("hold", result["status"])
        self.assertIn(
            "adr.planned_path must match backend/docs/adr/<issue-number>-<slug>.md",
            result["errors"],
        )

    def test_adr_path_must_match_issue_number_and_slug(self):
        cases = (
            (
                "issue-number",
                "backend/docs/adr/124-auth-account-linking.md",
                "adr.planned_path issue number must match issue_number",
            ),
            (
                "slug",
                "backend/docs/adr/123-different-slug.md",
                "adr.planned_path slug must match adr.slug",
            ),
        )

        for name, planned_path, expected_error in cases:
            with self.subTest(case=name):
                snapshot = materializable_snapshot()
                snapshot["adr"]["planned_path"] = planned_path

                result = issue_planning.plan(snapshot)

                self.assertEqual("hold", result["status"])
                self.assertIn(expected_error, result["errors"])

    def test_create_contract_cannot_finalize_adr_path_without_issue_number(self):
        snapshot = fixture("high-risk-create.json")
        snapshot["adr"]["planned_path"] = "backend/docs/adr/123-auth-account-linking.md"

        result = issue_planning.plan(snapshot)

        self.assertEqual("hold", result["status"])
        self.assertIn(
            "issue_number is required to finalize an ADR path after Issue creation",
            result["errors"],
        )

    def test_invalid_title_prefix_is_held(self):
        snapshot = fixture("low-risk-create.json")
        snapshot["title"] = "로그인 오류 메시지 오탈자 수정"

        result = issue_planning.plan(snapshot)

        self.assertEqual("hold", result["status"])
        self.assertIn("title must start with [BE] or [FE]", result["errors"])

    def test_materializer_creates_proposed_adr_file(self):
        snapshot = materializable_snapshot()

        with tempfile.TemporaryDirectory() as tmp:
            result = materialize_adr.materialize(
                snapshot, Path(tmp), implementation=True
            )
            target = Path(result["path"])

            self.assertEqual("pass", result["status"])
            self.assertEqual("created", result["action"])
            self.assertTrue(target.exists())
            body = target.read_text(encoding="utf-8")
            self.assertIn("## 상태\n\nProposed", body)
            self.assertIn("## 트레이드 오프", body)
            self.assertIn("자동 병합: 편하지만 계정 탈취 위험이 있다.", body)
            self.assertIn("## 확인", body)
            self.assertIn("- 결정 주체: Knot 팀", body)

    def test_materializer_cli_finalizes_actual_issue_number(self):
        with tempfile.TemporaryDirectory() as tmp:
            completed = subprocess.run(
                [
                    sys.executable,
                    str(MATERIALIZER_PATH),
                    str(ROOT / "harness/tests/fixtures/high-risk-create.json"),
                    "--repo-root",
                    tmp,
                    "--issue-number",
                    "123",
                    "--implementation",
                ],
                cwd=ROOT,
                check=False,
                capture_output=True,
                text=True,
            )

            result = json.loads(completed.stdout)
            self.assertEqual(0, completed.returncode)
            self.assertEqual("created", result["action"])
            self.assertTrue(
                (Path(tmp) / "backend/docs/adr/123-auth-account-linking.md").is_file()
            )

    def test_materializer_rejects_conflicting_issue_number(self):
        snapshot = materializable_snapshot()

        with tempfile.TemporaryDirectory() as tmp:
            result = materialize_adr.materialize(
                snapshot,
                Path(tmp),
                implementation=True,
                issue_number=124,
            )

            self.assertEqual("hold", result["status"])
            self.assertIn(
                "CLI issue number must match snapshot issue_number", result["errors"]
            )
            self.assertEqual([], list(Path(tmp).iterdir()))

    def test_materializer_requires_implementation_context(self):
        snapshot = fixture("high-risk-create.json")

        with tempfile.TemporaryDirectory() as tmp:
            result = materialize_adr.materialize(snapshot, Path(tmp))

            self.assertEqual("hold", result["status"])
            self.assertEqual("require_implementation_context", result["action"])
            self.assertEqual([], list(Path(tmp).iterdir()))

    def test_materializer_requires_issue_number(self):
        snapshot = fixture("high-risk-create.json")

        with tempfile.TemporaryDirectory() as tmp:
            result = materialize_adr.materialize(
                snapshot, Path(tmp), implementation=True
            )

            self.assertEqual("hold", result["status"])
            self.assertEqual("require_final_adr_path", result["action"])
            self.assertIn(
                "missing: issue_number for ADR materialization", result["errors"]
            )
            self.assertEqual([], list(Path(tmp).iterdir()))

    def test_materializer_rejects_draft_planned_path_without_issue_number(self):
        snapshot = fixture("high-risk-create.json")
        snapshot["operation"] = "draft"
        snapshot["adr"]["planned_path"] = "backend/docs/adr/123-auth-account-linking.md"

        with tempfile.TemporaryDirectory() as tmp:
            result = materialize_adr.materialize(
                snapshot, Path(tmp), implementation=True
            )

            self.assertEqual("hold", result["status"])
            self.assertEqual("require_final_adr_path", result["action"])
            self.assertIn(
                "missing: issue_number for ADR materialization", result["errors"]
            )
            self.assertEqual([], list(Path(tmp).iterdir()))

    def test_materializer_holds_non_object_snapshot_without_crashing(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = materialize_adr.materialize(
                ["not", "an", "object"],
                Path(tmp),
                implementation=True,
            )

            self.assertEqual("hold", result["status"])
            self.assertIn("snapshot must be an object", result["errors"])
            self.assertEqual([], list(Path(tmp).iterdir()))

    def test_materializer_is_idempotent_for_identical_file(self):
        snapshot = materializable_snapshot()

        with tempfile.TemporaryDirectory() as tmp:
            first = materialize_adr.materialize(
                snapshot, Path(tmp), implementation=True
            )
            second = materialize_adr.materialize(
                snapshot, Path(tmp), implementation=True
            )

            self.assertEqual("created", first["action"])
            self.assertEqual("pass", second["status"])
            self.assertEqual("unchanged", second["action"])

    def test_materializer_refuses_divergent_overwrite(self):
        snapshot = materializable_snapshot()

        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "backend/docs/adr/123-auth-account-linking.md"
            target.parent.mkdir(parents=True)
            target.write_text("# existing\n", encoding="utf-8")

            result = materialize_adr.materialize(
                snapshot, Path(tmp), implementation=True
            )

            self.assertEqual("hold", result["status"])
            self.assertEqual("refuse_overwrite", result["action"])
            self.assertIn(
                "target ADR already exists with different content", result["errors"]
            )


def h2_headings(markdown: str) -> list[str]:
    return [line for line in markdown.splitlines() if line.startswith("## ")]


if __name__ == "__main__":
    unittest.main()
