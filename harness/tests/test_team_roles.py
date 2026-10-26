import json
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "harness"))

import team_roles as tr  # noqa: E402


YUWOL_NOTION = "336328ab-ea57-45f4-b351-80b35a95b12a"
EASTAR_NOTION = "7bdae97e-48c3-426c-a4cf-a651069aab81"


def team():
    return {
        "version": 1,
        "members": [
            {"github": "vlmbuyd", "name": "유월", "area": "frontend", "notion": YUWOL_NOTION},
            {"github": "Eastar-DS", "name": "이스타", "area": "frontend", "notion": EASTAR_NOTION},
            {"github": "geongyu09", "name": "도넛", "area": "frontend"},
            {"github": "jyt6640", "name": "흑곰", "area": "backend"},
            {"github": "poketopa", "name": "루덴스", "area": "backend"},
        ],
        "plan_confirmers": ["vlmbuyd"],
        "release_merger": "vlmbuyd",
    }


class TeamFileTest(unittest.TestCase):
    def test_repository_team_file_is_valid(self):
        self.assertEqual(tr.validate_team(tr.load_team()), [])

    def test_plan_confirmers_must_be_members(self):
        data = team()
        data["plan_confirmers"] = ["someone"]
        self.assertTrue(any("plan_confirmers" in error for error in tr.validate_team(data)))

    def test_github_must_be_unique(self):
        data = team()
        data["members"].append({"github": "vlmbuyd", "name": "유월", "area": "backend"})
        self.assertIn("members.github must be unique", tr.validate_team(data))


    def test_plan_confirmer_needs_notion_id(self):
        # 1차 컨펌은 제안 페이지 컨펌한 사람(노션 사용자)으로 확인한다.
        data = team()
        data["plan_confirmers"] = ["geongyu09"]
        self.assertIn("plan_confirmers needs a notion user ID: geongyu09", tr.validate_team(data))


class ConfirmationTest(unittest.TestCase):
    def test_confirmer_button_record_passes(self):
        result = tr.check_confirmation(team(), [f"user://{YUWOL_NOTION}"])
        self.assertEqual(result, {"confirmed_by": ["vlmbuyd"]})

    def test_notion_id_without_hyphens_matches(self):
        result = tr.check_confirmation(team(), [YUWOL_NOTION.replace("-", "").upper()])
        self.assertEqual(result["confirmed_by"], ["vlmbuyd"])

    def test_non_confirmer_fails(self):
        with self.assertRaisesRegex(ValueError, "기획 컨펌 담당"):
            tr.check_confirmation(team(), [EASTAR_NOTION])

    def test_empty_confirmer_fails(self):
        # 상태만 확정으로 바꾸고 버튼을 쓰지 않은 경우
        with self.assertRaisesRegex(ValueError, "비어 있습니다"):
            tr.check_confirmation(team(), [])

    def test_added_confirmer_counts(self):
        data = team()
        data["plan_confirmers"] = ["vlmbuyd", "Eastar-DS"]
        self.assertEqual(tr.check_confirmation(data, [EASTAR_NOTION])["confirmed_by"], ["Eastar-DS"])


class ProposalPeopleTest(unittest.TestCase):
    def test_reviewer_is_plan_confirmer_with_notion_id(self):
        people = tr.proposal_people(team(), "Eastar-DS")
        self.assertEqual(people["proposer"]["notion"], EASTAR_NOTION)
        self.assertEqual([r["github"] for r in people["reviewers"]], ["vlmbuyd"])
        self.assertEqual(people["reviewers"][0]["notion"], YUWOL_NOTION)

    def test_proposer_without_notion_id_is_none(self):
        # 명단에 노션 ID가 없으면 입구가 Notion MCP 로그인 계정으로 채운다.
        self.assertIsNone(tr.proposal_people(team(), "geongyu09")["proposer"]["notion"])

    def test_repository_members_used_so_far_have_notion_ids(self):
        data = tr.load_team()
        for login in ("vlmbuyd", "poketopa", "jyt6640", "geongyu09"):
            self.assertTrue(tr.find_member(data, login).get("notion"), login)

    def test_assignees_are_implementers_with_notion_ids(self):
        result = tr.proposal_assignees(team(), ["Eastar-DS", "vlmbuyd", "Eastar-DS"])
        self.assertEqual([a["notion"] for a in result["assignees"]], [EASTAR_NOTION, YUWOL_NOTION])
        self.assertEqual(result["missing"], [])

    def test_assignee_without_notion_id_goes_to_missing(self):
        # 노션 ID가 없으면 담당자 칸 대신 확정 댓글에 이름으로 적는다.
        result = tr.proposal_assignees(team(), ["geongyu09", "vlmbuyd"])
        self.assertEqual([a["github"] for a in result["assignees"]], ["vlmbuyd"])
        self.assertEqual([m["name"] for m in result["missing"]], ["도넛"])

    def test_assignees_need_implementer(self):
        with self.assertRaises(ValueError):
            tr.proposal_assignees(team(), [])


class MemberTest(unittest.TestCase):
    def test_member_label_is_name(self):
        member = tr.describe_member(team(), "poketopa")
        self.assertEqual(member["label"], "루덴스")
        self.assertEqual(member["area_label"], "BE")

    def test_login_is_case_insensitive(self):
        self.assertEqual(tr.describe_member(team(), "eastar-ds")["name"], "이스타")

    def test_unknown_member_fails(self):
        with self.assertRaises(ValueError):
            tr.describe_member(team(), "stranger")


class ReviewPlanTest(unittest.TestCase):
    def test_fullstack_fe_pr_by_backend_member_is_assigned_to_author(self):
        # #979: 루덴스가 FE 작업을 했는데 담당자가 도넛이었고, BE 승인만으로 머지됐다.
        plan = tr.review_plan(team(), "frontend", "poketopa")
        self.assertEqual(plan["assignee"], "poketopa")
        self.assertEqual(plan["labels"], ["FE", "루덴스"])
        self.assertEqual(plan["merge_by"], "poketopa")
        self.assertEqual(plan["approvals"][0]["any_of"], ["vlmbuyd", "Eastar-DS", "geongyu09"])
        self.assertNotIn("jyt6640", plan["approvals"][0]["any_of"])

    def test_author_is_not_own_reviewer(self):
        plan = tr.review_plan(team(), "frontend", "Eastar-DS")
        self.assertNotIn("Eastar-DS", plan["reviewers"])
        self.assertEqual(plan["approvals"][0]["count"], 1)

    def test_confirmed_plan_needs_no_plan_confirmer_approval(self):
        plan = tr.review_plan(team(), "backend", "jyt6640")
        self.assertEqual(len(plan["approvals"]), 1)
        self.assertEqual(plan["reviewers"], ["poketopa"])

    def test_plan_change_adds_second_confirmation(self):
        plan = tr.review_plan(team(), "backend", "jyt6640", plan_changed=True)
        self.assertEqual(plan["approvals"][1]["all_of"], ["vlmbuyd"])
        self.assertIn("vlmbuyd", plan["reviewers"])

    def test_design_change_adds_second_confirmation(self):
        plan = tr.review_plan(team(), "frontend", "geongyu09", design_changed=True)
        self.assertEqual(plan["approvals"][1]["all_of"], ["vlmbuyd"])
        self.assertEqual(plan["reviewers"].count("vlmbuyd"), 1)

    def test_plan_confirmer_as_author_skips_second_confirmation(self):
        plan = tr.review_plan(team(), "frontend", "vlmbuyd", plan_changed=True)
        self.assertEqual(len(plan["approvals"]), 1)
        self.assertTrue(any("1차 컨펌으로 갈음" in note for note in plan["notes"]))

    def test_unknown_area_fails(self):
        with self.assertRaises(ValueError):
            tr.review_plan(team(), "design", "vlmbuyd")


class CliTest(unittest.TestCase):
    def run_cli(self, *args):
        completed = subprocess.run(
            [sys.executable, str(ROOT / "harness" / "team_roles.py"), *args],
            check=False,
            capture_output=True,
            text=True,
        )
        return completed.returncode, json.loads(completed.stdout)

    def test_review_command(self):
        code, payload = self.run_cli("review", "--area", "frontend", "--author", "poketopa", "--plan-changed")
        self.assertEqual(code, 0)
        self.assertEqual(payload["status"], "pass")
        self.assertEqual(payload["assignee"], "poketopa")

    def test_unknown_author_fails(self):
        code, payload = self.run_cli("review", "--area", "frontend", "--author", "stranger")
        self.assertEqual(code, 1)
        self.assertEqual(payload["status"], "fail")

    def test_confirmation_command(self):
        code, payload = self.run_cli("confirmation", "--notion", YUWOL_NOTION)
        self.assertEqual(code, 0)
        self.assertEqual(payload["confirmed_by"], ["vlmbuyd"])
        code, payload = self.run_cli("confirmation", "--notion", EASTAR_NOTION)
        self.assertEqual(code, 1)

    def test_confirmers_command(self):
        code, payload = self.run_cli("confirmers")
        self.assertEqual(code, 0)
        self.assertIn("유월", [item["name"] for item in payload["plan_confirmers"]])
        # 제안 페이지 댓글에서 기획 컨펌 담당을 멘션할 노션 사용자 ID
        self.assertTrue(all(item.get("notion") for item in payload["plan_confirmers"]))


if __name__ == "__main__":
    unittest.main()
