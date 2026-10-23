import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "harness"))

import feature_pipeline as fp  # noqa: E402


def valid_result(**flags):
    return {
        "status": "done",
        "changed": [
            {"kind": "notion", "url": "https://app.notion.com/p/abc", "summary": "기능 요구사항 SEARCH-R12 추가"},
            {"kind": "figma", "url": "https://www.figma.com/design/x/knot?node-id=1-2", "summary": "탐색 답변 화면, 변경 없음, 참고 링크"},
        ],
        "flags": {"requirements_changed": True, "screen_changed": True, "figma_url": "https://www.figma.com/design/x/knot?node-id=1-2", **flags},
        "question": "",
        "side_effects": [],
    }


def step(agent, message, result):
    return {"agent": agent, "message": message, "result": result}


def valid_state(**overrides):
    state = {
        "request": "탐색 답변에 복사 버튼 만들어 줘",
        "existing_issue_url": None,
        "kind": "feature",
        "area": "frontend",
        "done_condition": "development",
        "remote_write_authorized": False,
        "answers": [{"to": "이슈", "question": "게시할까요?", "answer": "허용"}],
        "assumptions": [{"stage": "기능 요구사항", "assumed": "탐색 도메인 아래에 추가", "reason": "요청이 탐색 화면을 가리킨다"}],
        "steps": [step("기능 요구사항", "기존과 대조해 업데이트해라", valid_result())],
        "issues": [{"area": "frontend", "number": 12, "url": "https://github.com/o/r/issues/12"}],
        "prs": [{"area": "frontend", "url": "https://github.com/o/r/pull/13"}],
    }
    state.update(overrides)
    return state


def done_result(**flags):
    return {"status": "done", "changed": [], "flags": flags, "question": "", "side_effects": []}


class ResultContractTest(unittest.TestCase):
    def test_valid_result_passes(self):
        self.assertEqual(fp.validate_result(valid_result()), [])

    def test_status_must_be_known(self):
        result = valid_result()
        result["status"] = "ok"
        self.assertTrue(any("status" in error for error in fp.validate_result(result)))

    def test_question_required_when_waiting_or_blocked(self):
        for status in ("needs_user_input", "blocked"):
            with self.subTest(status=status):
                result = valid_result()
                result["status"] = status
                result["question"] = ""
                self.assertTrue(any("question is required" in e for e in fp.validate_result(result)))

    def test_next_is_rejected_so_routing_stays_in_the_script(self):
        result = valid_result()
        result["next"] = [{"agent": "QA 시트", "message": "시나리오 뽑아 업데이트해라"}]
        self.assertTrue(any("next is no longer accepted" in e for e in fp.validate_result(result)))

    def test_flags_must_be_known_and_typed(self):
        result = valid_result()
        result["flags"] = {"requirements_changed": "yes", "figma_url": "notion://x", "mood": True}
        errors = fp.validate_result(result)
        self.assertTrue(any("requirements_changed must be a boolean" in e for e in errors))
        self.assertTrue(any("figma_url must be null or an http(s) URL" in e for e in errors))
        self.assertTrue(any("mood is not a known flag" in e for e in errors))

    def test_flags_are_optional(self):
        result = valid_result()
        del result["flags"]
        self.assertEqual(fp.validate_result(result), [])

    def test_changed_requires_kind_url_summary(self):
        result = valid_result()
        result["changed"] = [{"kind": "slack", "url": "notion://x", "summary": " "}]
        errors = fp.validate_result(result)
        self.assertEqual(len(errors), 3)

    def test_result_must_be_object(self):
        self.assertEqual(fp.validate_result(["done"]), ["result must be an object"])


class StateFileTest(unittest.TestCase):
    def test_valid_state_passes(self):
        self.assertEqual(fp.validate_state(valid_state()), [])

    def test_kind_area_and_done_condition_are_enumerated(self):
        state = valid_state(kind="bug", area="fullstack", done_condition="pr")
        errors = fp.validate_state(state)
        self.assertTrue(any(e.startswith("kind") for e in errors))
        self.assertTrue(any(e.startswith("area") for e in errors))
        self.assertTrue(any(e.startswith("done_condition") for e in errors))

    def test_assumptions_need_stage_assumed_reason(self):
        state = valid_state(assumptions=[{"stage": "어딘가", "assumed": "", "reason": "x"}])
        errors = fp.validate_state(state)
        self.assertTrue(any("assumptions[0].stage" in e for e in errors))
        self.assertTrue(any("assumptions[0].assumed" in e for e in errors))

    def test_step_results_are_validated(self):
        state = valid_state()
        state["steps"][0]["result"]["status"] = "needs_user_input"
        state["steps"][0]["result"]["question"] = ""
        errors = fp.validate_state(state)
        self.assertTrue(any(e.startswith("steps[0].result.question") for e in errors))

    def test_issue_number_must_be_positive_integer(self):
        state = valid_state()
        state["issues"][0]["number"] = "12"
        self.assertTrue(any("issues[0].number" in e for e in fp.validate_state(state)))

    def test_collect_changed_keeps_step_order(self):
        state = valid_state()
        second = done_result()
        second["changed"] = [{"kind": "issue", "url": "https://github.com/o/r/issues/12", "summary": "#12 [FE] 복사 버튼"}]
        state["steps"].append(step("이슈", "이슈 생성해", second))
        kinds = [item["kind"] for item in fp.collect_changed(state)]
        self.assertEqual(kinds, ["notion", "figma", "issue"])


class NextStageTest(unittest.TestCase):
    """분기 규칙이 스크립트에 있고 서브 에이전트의 판단에 기대지 않는다."""

    def test_feature_always_starts_with_requirements(self):
        decision = fp.next_stage(valid_state(steps=[], issues=[], prs=[]))
        self.assertEqual((decision["status"], decision["agent"]), ("next", "기능 요구사항"))

    def test_frontend_feature_with_figma_url_goes_requirements_qa_issue_fe(self):
        state = valid_state(steps=[], issues=[], prs=[])
        visited = []
        for _ in range(6):
            decision = fp.next_stage(state)
            if decision["status"] != "next":
                break
            visited.append(decision["agent"])
            state["steps"].append(step(decision["agent"], decision["message"], valid_result() if decision["agent"] == "기능 요구사항" else done_result()))
        self.assertEqual(visited, ["기능 요구사항", "QA 시트", "이슈", "FE 구현 하네스"])
        self.assertEqual(fp.next_stage(state)["status"], "finished")

    def test_frontend_screen_without_figma_url_calls_design(self):
        state = valid_state(steps=[step("기능 요구사항", "기존과 대조해 업데이트해라", valid_result(figma_url=None))])
        self.assertEqual(fp.next_stage(state)["agent"], "디자인")

    def test_backend_rules_route_through_domain_rules_and_api_spec(self):
        state = valid_state(area="backend", steps=[step("기능 요구사항", "기존과 대조해 업데이트해라", valid_result(backend_rules_affected=True))])
        self.assertEqual(fp.next_stage(state)["agent"], "도메인 규칙")
        state["steps"].append(step("도메인 규칙", "업데이트해라", done_result(domain_rules_changed=True)))
        self.assertEqual(fp.next_stage(state)["agent"], "API 명세")
        state["steps"].append(step("API 명세", "업데이트해라", done_result()))
        self.assertEqual(fp.next_stage(state)["agent"], "QA 시트")

    def test_domain_rules_unchanged_skips_api_spec(self):
        state = valid_state(area="backend", steps=[
            step("기능 요구사항", "기존과 대조해 업데이트해라", valid_result(backend_rules_affected=True)),
            step("도메인 규칙", "업데이트해라", done_result(domain_rules_changed=False)),
        ])
        self.assertEqual(fp.next_stage(state)["agent"], "QA 시트")

    def test_unchanged_requirements_skip_qa_sheet(self):
        state = valid_state(steps=[step("기능 요구사항", "기존과 대조해 업데이트해라", done_result(requirements_changed=False))], issues=[])
        self.assertEqual(fp.next_stage(state)["agent"], "이슈")

    def test_fix_and_chore_skip_the_document_stage(self):
        for kind in ("fix", "chore"):
            with self.subTest(kind=kind):
                decision = fp.next_stage(valid_state(kind=kind, steps=[], issues=[], prs=[]))
                self.assertEqual(decision["agent"], "이슈")
                self.assertIn(kind, decision["reason"])

    def test_existing_issue_url_starts_at_implementation(self):
        state = valid_state(existing_issue_url="https://github.com/o/r/issues/12", steps=[], prs=[])
        self.assertEqual(fp.next_stage(state)["agent"], "FE 구현 하네스")

    def test_issue_only_finishes_after_issue(self):
        state = valid_state(done_condition="issue", steps=[
            step("기능 요구사항", "기존과 대조해 업데이트해라", valid_result()),
            step("QA 시트", "시나리오 뽑아 업데이트해라", done_result()),
            step("이슈", "이슈 생성해", done_result()),
        ])
        self.assertEqual(fp.next_stage(state)["status"], "finished")

    def test_both_areas_follow_implementation_order(self):
        base = [
            step("기능 요구사항", "기존과 대조해 업데이트해라", valid_result()),
            step("QA 시트", "시나리오 뽑아 업데이트해라", done_result()),
            step("이슈", "이슈 생성해", done_result()),
        ]
        state = valid_state(area="both", steps=list(base))
        self.assertEqual(fp.next_stage(state)["agent"], "BE 구현 하네스")
        state["steps"].append(step("BE 구현 하네스", "구현해라", done_result()))
        self.assertEqual(fp.next_stage(state)["agent"], "FE 구현 하네스")
        reordered = valid_state(area="both", steps=list(base), implementation_order=["frontend", "backend"])
        self.assertEqual(fp.next_stage(reordered)["agent"], "FE 구현 하네스")

    def test_waiting_until_the_answer_is_recorded_then_recalls_same_agent(self):
        asking = {"status": "needs_user_input", "changed": [], "question": "충돌: R3와 다릅니다. 어떻게 할까요?", "side_effects": ["R3 구현이 바뀜"]}
        state = valid_state(answers=[], steps=[step("기능 요구사항", "기존과 대조해 업데이트해라", asking)])
        decision = fp.next_stage(state)
        self.assertEqual((decision["status"], decision["agent"]), ("waiting", "기능 요구사항"))
        state["answers"].append({"to": "기능 요구사항", "question": asking["question"], "answer": "R3를 바꾼다"})
        decision = fp.next_stage(state)
        self.assertEqual((decision["status"], decision["agent"], decision["message"]), ("next", "기능 요구사항", "기존과 대조해 업데이트해라"))

    def test_blocked_stops(self):
        blocked = {"status": "blocked", "changed": [], "question": "노션 토큰이 없다", "side_effects": []}
        state = valid_state(steps=[step("기능 요구사항", "기존과 대조해 업데이트해라", blocked)])
        self.assertEqual(fp.next_stage(state)["status"], "blocked")


class StatusTest(unittest.TestCase):
    def test_summary_names_done_steps_next_and_assumptions(self):
        text = fp.summarize_status(valid_state())
        self.assertIn("1. 기능 요구사항 「기존과 대조해 업데이트해라」 — done (변경 2건)", text)
        self.assertIn("다음: QA 시트 「시나리오 뽑아 업데이트해라」", text)
        self.assertIn("기본값으로 처리한 미결:", text)
        self.assertIn("탐색 도메인 아래에 추가", text)


class RenderTest(unittest.TestCase):
    def test_pr_section_uses_h2_and_links_only(self):
        text = fp.render_notion_changes(valid_result()["changed"])
        self.assertTrue(text.startswith("## 노션 변경 사항\n\n"))
        self.assertIn("- 노션: [기능 요구사항 SEARCH-R12 추가](https://app.notion.com/p/abc)", text)
        self.assertIn("- Figma: [탐색 답변 화면, 변경 없음, 참고 링크](", text)

    def test_issue_variant_has_no_h2(self):
        text = fp.render_notion_changes(valid_result()["changed"], for_issue=True)
        self.assertTrue(text.startswith("**노션 변경 사항**\n\n"))
        self.assertNotIn("## ", text)

    def test_empty_changed_renders_none(self):
        self.assertEqual(fp.render_notion_changes([]), "## 노션 변경 사항\n\n- 없음\n")

    def test_invalid_changed_raises(self):
        with self.assertRaises(ValueError):
            fp.render_notion_changes([{"kind": "notion", "url": "x", "summary": "y"}])


class CliTest(unittest.TestCase):
    def run_cli(self, *args):
        return subprocess.run(
            [sys.executable, str(ROOT / "harness" / "feature_pipeline.py"), *args],
            capture_output=True,
            text=True,
            cwd=ROOT,
        )

    def test_commands_report_status(self):
        with tempfile.TemporaryDirectory() as tmp:
            result_path = Path(tmp) / "result.json"
            result_path.write_text(json.dumps(valid_result(), ensure_ascii=False), encoding="utf-8")
            state_path = Path(tmp) / "state.json"
            state_path.write_text(json.dumps(valid_state(), ensure_ascii=False), encoding="utf-8")
            bad_path = Path(tmp) / "bad.json"
            bad_path.write_text(json.dumps({"status": "blocked"}), encoding="utf-8")

            ok = self.run_cli("result", str(result_path))
            self.assertEqual(ok.returncode, 0)
            self.assertEqual(json.loads(ok.stdout)["status"], "pass")

            state = self.run_cli("state", str(state_path), "--pretty")
            self.assertEqual(state.returncode, 0)

            bad = self.run_cli("result", str(bad_path))
            self.assertEqual(bad.returncode, 1)
            self.assertEqual(json.loads(bad.stdout)["status"], "fail")

            nxt = self.run_cli("next", str(state_path))
            self.assertEqual(nxt.returncode, 0)
            self.assertEqual(json.loads(nxt.stdout)["agent"], "QA 시트")

            status = self.run_cli("status", str(state_path))
            self.assertEqual(status.returncode, 0)
            self.assertTrue(status.stdout.startswith("요청: "))

            rendered = self.run_cli("render", str(state_path), "--issue")
            self.assertEqual(rendered.returncode, 0)
            self.assertTrue(rendered.stdout.startswith("**노션 변경 사항**"))


if __name__ == "__main__":
    unittest.main()
