import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "harness"))

import feature_pipeline as fp  # noqa: E402


def valid_result():
    return {
        "status": "done",
        "changed": [
            {"kind": "notion", "url": "https://app.notion.com/p/abc", "summary": "기능 요구사항 SEARCH-R12 추가"},
            {"kind": "figma", "url": "https://www.figma.com/design/x/knot?node-id=1-2", "summary": "탐색 답변 화면, 변경 없음, 참고 링크"},
        ],
        "next": [
            {"agent": "QA 시트", "message": "시나리오 뽑아 업데이트해라"},
            {"agent": "이슈", "message": "이슈 생성해"},
        ],
        "question": "",
        "side_effects": [],
    }


def proposal_result():
    return {
        "status": "needs_user_input",
        "changed": [],
        "next": [],
        "question": "제안 페이지 https://app.notion.com/p/proposal 에서 1차 컨펌을 받아야 한다.",
        "side_effects": [],
    }


def pending_state():
    return {
        "request": "탐색 답변에 복사 버튼 만들어 줘",
        "existing_issue_url": None,
        "area": "frontend",
        "done_condition": "development",
        "remote_write_authorized": True,
        "implementer": "vlmbuyd",
        "plan_confirmation": {"required": True, "status": "pending", "proposal_url": "https://app.notion.com/p/proposal", "proposer": "poketopa", "reviewers": ["vlmbuyd"]},
        "answers": [],
        "steps": [
            {"agent": "기능 요구사항", "message": "기존과 대조해 제안해라", "result": proposal_result()},
        ],
        "issues": [],
        "prs": [],
    }


def confirmed_plan(confirmed_by=None):
    return {
        "required": True,
        "status": "confirmed",
        "proposal_url": "https://app.notion.com/p/proposal",
        "proposer": "poketopa",
        "reviewers": ["vlmbuyd"],
        "confirmed_by": confirmed_by or ["vlmbuyd"],
        "confirmed_at": "2026-10-26",
        "evidence": "확정 버튼, 컨펌 기록: 범위를 탐색 답변으로 좁힘",
    }


def valid_state():
    return {
        "request": "탐색 답변에 복사 버튼 만들어 줘",
        "existing_issue_url": None,
        "area": "frontend",
        "done_condition": "development",
        "remote_write_authorized": False,
        "implementer": "vlmbuyd",
        "plan_confirmation": confirmed_plan(),
        "answers": [{"to": "이슈", "question": "게시할까요?", "answer": "허용"}],
        "steps": [
            {"agent": "기능 요구사항", "message": "기존과 대조해 제안해라", "result": proposal_result()},
            {"agent": "기능 요구사항", "message": "기존과 대조해 업데이트해라", "result": valid_result()},
        ],
        "issues": [{"area": "frontend", "number": 12, "url": "https://github.com/o/r/issues/12"}],
        "prs": [{"area": "frontend", "url": "https://github.com/o/r/pull/13"}],
    }


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

    def test_next_must_name_known_agent_and_message(self):
        result = valid_result()
        result["next"] = [{"agent": "디자인 팀", "message": "고쳐라"}]
        errors = fp.validate_result(result)
        self.assertTrue(any("next[0].agent" in e for e in errors))
        self.assertTrue(any("next[0].message" in e for e in errors))

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

    def test_area_and_done_condition_are_enumerated(self):
        state = valid_state()
        state["area"] = "fullstack"
        state["done_condition"] = "pr"
        errors = fp.validate_state(state)
        self.assertTrue(any("area" in e for e in errors))
        self.assertTrue(any("done_condition" in e for e in errors))

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

    def test_implementer_is_required_once_issue_exists(self):
        # #979: 이슈 담당자가 실제 구현한 사람과 달랐다. 이슈를 만들기 전에 구현할 사람을 적는다.
        state = valid_state()
        del state["implementer"]
        self.assertIn("implementer is required before issues or prs are created", fp.validate_state(state))

    def test_collect_changed_keeps_step_order(self):
        state = valid_state()
        second = valid_result()
        second["changed"] = [{"kind": "issue", "url": "https://github.com/o/r/issues/12", "summary": "#12 [FE] 복사 버튼"}]
        state["steps"].append({"agent": "이슈", "message": "이슈 생성해", "result": second})
        kinds = [item["kind"] for item in fp.collect_changed(state)]
        self.assertEqual(kinds, ["notion", "figma", "issue"])


class PlanConfirmationTest(unittest.TestCase):
    def test_pending_proposal_passes(self):
        self.assertEqual(fp.validate_state(pending_state()), [])

    def test_pending_blocks_notion_update(self):
        # #982: 1차 컨펌 전에 노션 정본과 이슈를 다 만든 뒤 컨펌을 요청했다.
        state = pending_state()
        state["steps"].append({"agent": "기능 요구사항", "message": "기존과 대조해 업데이트해라", "result": valid_result()})
        errors = fp.validate_state(state)
        self.assertTrue(any("노션과 Figma를 바꾸지 않는다" in e for e in errors))
        self.assertTrue(any("steps[1] runs before 1차 컨펌" in e for e in errors))

    def test_pending_blocks_issue(self):
        state = pending_state()
        state["issues"] = [{"area": "frontend", "number": 12, "url": "https://github.com/o/r/issues/12"}]
        self.assertTrue(any("이슈와 PR을 만들지 않는다" in e for e in fp.validate_state(state)))

    def test_rejected_proposal_leaves_nothing_to_roll_back(self):
        state = pending_state()
        state["plan_confirmation"]["status"] = "rejected"
        self.assertEqual(fp.validate_state(state), [])
        state["issues"] = [{"area": "frontend", "number": 12, "url": "https://github.com/o/r/issues/12"}]
        self.assertTrue(any("rejected" in e and "이슈와 PR" in e for e in fp.validate_state(state)))

    def test_required_confirmation_needs_proposal_page(self):
        state = pending_state()
        del state["plan_confirmation"]["proposal_url"]
        self.assertTrue(any("proposal_url" in e for e in fp.validate_state(state)))

    def test_confirmed_needs_who_when_and_evidence(self):
        state = valid_state()
        state["plan_confirmation"] = {
            "required": True,
            "status": "confirmed",
            "proposal_url": "https://app.notion.com/p/proposal",
            "confirmed_by": [],
            "evidence": " ",
        }
        errors = fp.validate_state(state)
        self.assertTrue(any("confirmed_by" in e for e in errors))
        self.assertTrue(any("confirmed_at" in e for e in errors))
        self.assertTrue(any("evidence" in e for e in errors))

    def test_confirmed_by_button_record_passes(self):
        state = valid_state()
        state["plan_confirmation"] = confirmed_plan()
        self.assertEqual(fp.validate_state(state, ["vlmbuyd"]), [])

    def test_confirmed_by_non_confirmer_fails(self):
        # 기획 컨펌 담당이 아닌 팀원이 상태를 확정으로 바꾼 경우
        state = valid_state()
        state["plan_confirmation"] = confirmed_plan(confirmed_by=["Eastar-DS"])
        errors = fp.validate_state(state, ["vlmbuyd"])
        self.assertTrue(any("must include a plan confirmer" in e for e in errors))

    def test_state_cli_checks_repository_confirmers(self):
        state = valid_state()
        state["plan_confirmation"] = confirmed_plan(confirmed_by=["jyt6640"])
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "state.json"
            path.write_text(json.dumps(state, ensure_ascii=False), encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(ROOT / "harness" / "feature_pipeline.py"), "state", str(path)],
                capture_output=True,
                text=True,
            )
        self.assertEqual(result.returncode, 1)
        self.assertIn("must include a plan confirmer", result.stdout)

    def test_required_proposal_needs_proposer_and_reviewer(self):
        # 2026-10-26 첫 제안: 제안자가 비고 기획 컨펌 담당에게 알림이 가지 않았다.
        state = pending_state()
        del state["plan_confirmation"]["proposer"]
        state["plan_confirmation"]["reviewers"] = []
        errors = fp.validate_state(state)
        self.assertTrue(any("proposer is required" in e for e in errors))
        self.assertTrue(any("reviewers is required" in e for e in errors))

    def test_reviewers_must_include_plan_confirmer(self):
        state = pending_state()
        state["plan_confirmation"]["reviewers"] = ["Eastar-DS"]
        errors = fp.validate_state(state, ["vlmbuyd"])
        self.assertTrue(any("reviewers must include a plan confirmer" in e for e in errors))

    def test_not_needed_matches_required_false(self):
        state = valid_state()
        state["plan_confirmation"] = {"required": False, "status": "not_needed"}
        self.assertEqual(fp.validate_state(state), [])
        state["plan_confirmation"] = {"required": True, "status": "not_needed", "proposal_url": "https://app.notion.com/p/proposal"}
        self.assertTrue(any("cannot be not_needed" in e for e in fp.validate_state(state)))
        state["plan_confirmation"] = {"required": False, "status": "pending"}
        self.assertTrue(any("must be not_needed" in e for e in fp.validate_state(state)))

    def test_propose_message_is_known(self):
        result = valid_result()
        result["next"] = [{"agent": "기능 요구사항", "message": "기존과 대조해 제안해라"}]
        self.assertEqual(fp.validate_result(result), [])


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

    def test_result_and_state_commands_report_status(self):
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

            rendered = self.run_cli("render", str(state_path), "--issue")
            self.assertEqual(rendered.returncode, 0)
            self.assertTrue(rendered.stdout.startswith("**노션 변경 사항**"))


if __name__ == "__main__":
    unittest.main()
