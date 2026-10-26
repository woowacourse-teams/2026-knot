import json
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "harness"))

import team_roles as tr  # noqa: E402


def team():
    return {
        "version": 1,
        "members": [
            {"github": "vlmbuyd", "name": "유월", "area": "frontend"},
            {"github": "Eastar-DS", "name": "이스타", "area": "frontend"},
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

    def test_confirmers_command(self):
        code, payload = self.run_cli("confirmers")
        self.assertEqual(code, 0)
        self.assertIn("유월", [item["name"] for item in payload["plan_confirmers"]])


if __name__ == "__main__":
    unittest.main()
