---
name: knot-feature
description: 「○○ 기능 구현해 줘」 한 문장을 받아 Knot의 기능 요구사항 갱신, 도메인 규칙과 API 명세(백엔드 길), 디자인 링크, QA 시트, GitHub Issue, 구현, 테스트, PR까지 한 흐름으로 끝내는 입구 스킬이다. "○○ 기능 구현해 줘", "○○ 기능 만들어 줘", "○○ 이슈까지만 만들어 줘", "이 이슈(URL) 구현해 줘" 요청에 사용한다. 기획서만 고치는 요청, 스프린트 시트 태스크 관리, 디자인이 먼저 바뀌는 역방향 흐름, 단순 GitHub 조회에는 사용하지 않는다.
---

<!-- 만든 사람: 유월 / 모델: Claude Fable 5.1 (Claude Code) / 2026-10-22 -->

# Knot Feature for Claude Code

작업 전에 [공통 입구 스킬](../../../.agents/skills/knot-feature/SKILL.md)과 연결된
[반환 규약](../../../.agents/skills/knot-feature/references/return-contract.md),
[정본 위치](../../../.agents/skills/knot-feature/references/sources.md)를 완전히 읽고 정본으로 따른다.

- 문서 단계의 여섯 단계는 `.claude/agents/knot-feature-*.md`를 Agent 도구로 한 번에 하나씩
  실행한다. 프롬프트에는 상태 파일 경로, 메시지 문장, 사용자 답변만 넣고 문서 내용을 넣지 않는다.
- 구현 단계는 이 세션이 직접 한다. 정본의 `references/fe-implementation.md`,
  `references/be-implementation.md`를 읽고 기존 `frontend`, `backend` 하네스(규칙, 스킬, 에이전트)로
  수행한다.
- 두 질문(영역, 완료 조건)과 `needs_user_input`의 질문은 AskUserQuestion으로 묻는다. 요청으로 알 수
  있으면 묻지 않는다.
- 정본의 `$knot-*` 표기는 같은 이름의 `/knot-*` 스킬 호출로 해석한다.
- 이 파일에 공통 규칙을 복사하거나 Claude 전용 기준을 추가하지 않는다.
