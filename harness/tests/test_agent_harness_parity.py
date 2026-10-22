import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SKILL_NAMES = (
    "knot-issue-planning",
    "knot-deep-interview",
    "knot-grill-me",
    "knot-qa",
)


class AgentHarnessParityTest(unittest.TestCase):
    def test_claude_imports_shared_project_instructions(self):
        claude_md = (ROOT / "CLAUDE.md").read_text(encoding="utf-8")

        self.assertIn("@AGENTS.md", claude_md)
        self.assertIn("harness/issue_planning.py", claude_md)
        self.assertIn("harness/materialize_adr.py", claude_md)
        self.assertIn("--issue-number", claude_md)

    def test_claude_skills_point_to_canonical_agent_skills(self):
        for name in SKILL_NAMES:
            with self.subTest(skill=name):
                canonical = ROOT / ".agents" / "skills" / name / "SKILL.md"
                adapter = ROOT / ".claude" / "skills" / name / "SKILL.md"

                self.assertTrue(canonical.is_file())
                self.assertTrue(adapter.is_file())
                adapter_text = adapter.read_text(encoding="utf-8")
                self.assertIn(f"../../../.agents/skills/{name}/SKILL.md", adapter_text)

    def test_repo_wide_scope_and_frontend_issue_ownership_are_explicit(self):
        agents_md = (ROOT / "AGENTS.md").read_text(encoding="utf-8")

        self.assertIn("저장소 전역과 BE·FE 작업에 공통", agents_md)
        self.assertIn("Issue #167", agents_md)
        self.assertIn("Issue #165", agents_md)

    def test_issue_implementation_runs_shared_qa_skill(self):
        agents_md = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
        skill_dir = ROOT / ".agents" / "skills" / "knot-qa"
        canonical = (skill_dir / "SKILL.md").read_text(encoding="utf-8")

        self.assertIn("$knot-qa", agents_md)
        self.assertIn("references/qa-sheet-record.md", canonical)
        self.assertTrue((skill_dir / "references" / "qa-sheet-record.md").is_file())

    def test_backend_instructions_preserve_common_adr_decision(self):
        agents_md = (ROOT / "AGENTS.md").read_text(encoding="utf-8")

        self.assertIn("백엔드 구현 지침", agents_md)
        self.assertIn("ADR 필요 여부는 이 공통 계약의 판정", agents_md)
        self.assertIn("adr.required=true", agents_md)
        self.assertIn("ADR 자산화 절차", agents_md)

    def test_default_mode_requires_explicit_publish_for_remote_write(self):
        agents_md = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
        canonical = (
            ROOT / ".agents" / "skills" / "knot-issue-planning" / "SKILL.md"
        ).read_text(encoding="utf-8")
        contract = (
            ROOT
            / ".agents"
            / "skills"
            / "knot-issue-planning"
            / "references"
            / "issue-contract.md"
        ).read_text(encoding="utf-8")

        for text in (agents_md, canonical, contract):
            with self.subTest(document=text[:40]):
                self.assertIn("remote_write_authorized", text)
                self.assertIn("false", text)
                self.assertIn("--publish", text)
                self.assertIn("재사용", text)
                self.assertIn("publish_issue", text)
        self.assertIn("gh issue edit", agents_md)
        self.assertIn("Project 변경", agents_md)
        self.assertIn("requested_action=publish_issue", canonical)

    def test_temporary_snapshot_is_restricted_and_removed(self):
        agents_md = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
        canonical = (
            ROOT / ".agents" / "skills" / "knot-issue-planning" / "SKILL.md"
        ).read_text(encoding="utf-8")

        for text in (agents_md, canonical):
            with self.subTest(document=text[:40]):
                self.assertIn("OS 임시", text)
                self.assertIn("삭제", text)

    def test_codex_and_claude_share_conditional_interview_contract(self):
        canonical = (
            ROOT / ".agents" / "skills" / "knot-issue-planning" / "SKILL.md"
        ).read_text(encoding="utf-8")
        claude_adapter = (
            ROOT / ".claude" / "skills" / "knot-issue-planning" / "SKILL.md"
        ).read_text(encoding="utf-8")

        self.assertIn("자료 충분으로 인터뷰 생략", canonical)
        self.assertIn("interview_status", canonical)
        self.assertIn(
            "../../../.agents/skills/knot-issue-planning/SKILL.md", claude_adapter
        )
        self.assertIn("interview_notice", claude_adapter)


if __name__ == "__main__":
    unittest.main()
