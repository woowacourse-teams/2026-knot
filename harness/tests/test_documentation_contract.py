import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class DocumentationContractTest(unittest.TestCase):
    def test_repository_issue_template_keeps_exactly_three_sections(self):
        template = (
            ROOT / ".github" / "ISSUE_TEMPLATE" / "knot-issue-template.md"
        ).read_text(encoding="utf-8")
        headings = [line for line in template.splitlines() if line.startswith("## ")]

        self.assertEqual(["## 구현 기능 설명", "## TODO", "## 메모"], headings)

    def test_test_guide_matches_remote_write_contract(self):
        guide = (ROOT / "docs" / "harness" / "testing-issue-planning.md").read_text(
            encoding="utf-8"
        )

        self.assertIn("action=render_draft", guide)
        self.assertIn("requested_action=publish_issue", guide)
        self.assertIn("remote_write_authorized=false", guide)
        self.assertIn("--publish --repo OWNER/REPO", guide)
        self.assertIn("action=publish_issue", guide)
        self.assertIn("reuse_existing_issue", guide)
        self.assertIn("partial_publish_issue", guide)
        self.assertIn("remote_write_authorized=true", guide)
        self.assertIn("--issue-number 123", guide)
        self.assertIn("adr_path_status=pending_issue_number", guide)

    def test_signup_walkthrough_separates_actual_result_from_hypothetical_adr(self):
        walkthrough = (
            ROOT / "docs" / "harness" / "signup-issue-adr-walkthrough.md"
        ).read_text(encoding="utf-8")
        issue_example = (
            ROOT / "docs" / "harness" / "examples" / "10-oauth-signup-issue-draft.md"
        ).read_text(encoding="utf-8")
        adr_example = (
            ROOT
            / "docs"
            / "harness"
            / "examples"
            / "10-github-oauth-member-delayed-creation.md"
        ).read_text(encoding="utf-8")

        self.assertIn("실제 대화 결과: ADR 없는 짧은 Issue 초안", walkthrough)
        self.assertIn("가상 추가 대화 결과: ADR 조건과 문서 형식 시연", walkthrough)
        self.assertIn("## 메모\n\n- 없음", issue_example)
        self.assertNotIn("예정 경로", issue_example)
        self.assertIn("ADR 형식을 보여주는 가상 예시", adr_example)

    def test_proposed_lifecycle_adr_uses_actual_issue_number(self):
        path = ROOT / "backend" / "docs" / "adr" / "167-short-issue-and-proposed-adr-lifecycle.md"
        adr = path.read_text(encoding="utf-8")

        self.assertTrue(path.is_file())
        self.assertIn("## 상태\n\nProposed", adr)
        self.assertIn("- #167 [BE] 팀 공통 Issue / ADR 기획 하네스 도입", adr)

    def test_high_risk_interview_can_only_skip_with_complete_evidence(self):
        guide = (ROOT / "docs" / "harness" / "issue-planning.md").read_text(
            encoding="utf-8"
        )
        contract = (
            ROOT
            / ".agents"
            / "skills"
            / "knot-issue-planning"
            / "references"
            / "issue-contract.md"
        ).read_text(encoding="utf-8")

        self.assertIn("현재 맥락", guide)
        self.assertIn("자료 충분으로 인터뷰 생략", guide)
        self.assertIn('"status": "skipped"', contract)
        self.assertIn('"current_validity": "confirmed"', contract)


if __name__ == "__main__":
    unittest.main()
