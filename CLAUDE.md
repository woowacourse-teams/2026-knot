@AGENTS.md

# Claude Code

Knot 저장소의 BE·FE Issue 기획에는 `/knot-issue-planning`을 사용한다. 자료가 부족한
고위험 작업의 인터뷰에는 `/knot-deep-interview`를 사용하고, 모든 고위험 계약의 압박
검증에는 `/knot-grill-me`를 사용한다.

`.claude/skills`는 Claude Code용 진입점이다. 백엔드 전용 스킬(`knot-commit`, `knot-pr`,
`knot-api-spec`)의 진입점은 `backend/.claude/skills`이고 정본은 `backend/.agents/skills`다.
백엔드 작업은 `backend/`에서 시작해야 두 진입점을 함께 발견한다. 판단 규칙의 정본은 `.agents/skills`와
`docs/harness/issue-planning.md`이며, 실행 결과는 Codex와 동일한
`harness/issue_planning.py`와 `harness/materialize_adr.py`로 검증한다. Claude 전용 규칙을
복사해 별도의 정책 정본을 만들지 않는다.

정본 문서의 `$knot-*` 표기는 Claude Code에서 같은 이름의 `/knot-*` 스킬 호출로 해석한다.
`requested_action=publish_issue`만으로는 원격 쓰기 권한이 아니다. 현재 사용자가 생성을
명시하고 계약이 통과한 경우에만 `--publish --repo OWNER/REPO`를 사용한다. ADR
materializer에는 `--issue-number`로 실제 GitHub Issue 번호를 전달한다.

기능 구현 요청(「○○ 기능 구현해 줘」, 「이 이슈 구현해 줘」)에는 `/knot-feature`를 사용한다.
흐름의 서브 에이전트 진입점은 `.claude/agents/knot-feature-*.md`이고 정본은
`.agents/skills/knot-feature/subagents/`다. 진입점에 공통 규칙을 복사하지 않는다.
