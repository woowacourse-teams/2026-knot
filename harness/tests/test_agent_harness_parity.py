import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SKILL_NAMES = (
    "knot-issue-planning",
    "knot-deep-interview",
    "knot-grill-me",
    "knot-feature",
)


class AgentHarnessParityTest(unittest.TestCase):
    def test_feature_pipeline_validator_is_referenced_by_canonical_skill(self):
        canonical = (ROOT / ".agents" / "skills" / "knot-feature" / "SKILL.md").read_text(encoding="utf-8")
        contract = (
            ROOT / ".agents" / "skills" / "knot-feature" / "references" / "return-contract.md"
        ).read_text(encoding="utf-8")

        self.assertTrue((ROOT / "harness" / "feature_pipeline.py").is_file())
        self.assertIn("harness/feature_pipeline.py", canonical)
        self.assertIn("harness/feature_pipeline.py", contract)
        self.assertIn("노션 변경 사항", contract)

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

    def test_routing_lives_in_the_script_not_in_subagent_next(self):
        feature = ROOT / ".agents" / "skills" / "knot-feature"
        canonical = (feature / "SKILL.md").read_text(encoding="utf-8")
        contract = (feature / "references" / "return-contract.md").read_text(encoding="utf-8")

        self.assertIn("feature_pipeline.py next", canonical)
        self.assertIn("feature_pipeline.py status", canonical)
        self.assertIn("flags", contract)
        for path in sorted((feature / "subagents").glob("*.md")):
            with self.subTest(subagent=path.name):
                self.assertNotIn("보내는 메시지 (`next`)", path.read_text(encoding="utf-8"))

    def test_claude_subagent_entries_restrict_tools(self):
        entries = sorted((ROOT / ".claude" / "agents").glob("knot-feature-*.md"))
        self.assertEqual(len(entries), 6)
        for path in entries:
            with self.subTest(entry=path.name):
                frontmatter = path.read_text(encoding="utf-8").split("\n---\n", 1)[0]
                self.assertIn("disallowedTools:", frontmatter)

    def test_team_harness_has_no_personal_absolute_paths(self):
        # macOS 홈 디렉터리로 시작하는 절대 경로. 이 파일 자신은 검사에서 뺀다.
        needle = "/" + "Users" + "/"
        roots = (ROOT / ".agents", ROOT / ".claude", ROOT / "harness", ROOT / "docs" / "harness", ROOT / "AGENTS.md", ROOT / "CLAUDE.md")
        for root in roots:
            files = [root] if root.is_file() else [p for p in root.rglob("*") if p.is_file() and not p.is_symlink()]
            for path in files:
                if path.suffix not in (".md", ".py", ".json", ".yaml", ".yml", ".toml") or path == Path(__file__).resolve():
                    continue
                with self.subTest(file=str(path.relative_to(ROOT))):
                    self.assertNotIn(needle, path.read_text(encoding="utf-8", errors="ignore"))

    def test_harness_edits_have_their_own_path(self):
        agents_md = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
        self.assertIn("docs/harness/harness-verification.md", agents_md)
        verification = ROOT / "docs" / "harness" / "harness-verification.md"
        self.assertTrue(verification.is_file())
        self.assertIn("규칙이 강제되는 곳", verification.read_text(encoding="utf-8"))

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
