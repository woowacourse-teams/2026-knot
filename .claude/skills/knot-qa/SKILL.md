---
name: knot-qa
description: Knot의 QA를 팀원 누구의 에이전트든 같은 기준으로 돌린다. 확정 Issue 구현을 시작할 때 노션 QA 체크리스트에서 관련 행을 찾아 확인 방법(E2E, 에이전트 QA, 사람)을 나눈 QA 계획을 만들고, 구현이 develop에 머지돼 dev에 배포된 뒤 그 변경의 행만, 배포 전 QA 회차에는 전체를 실행해 판정과 증거를 QA 체크리스트에 새 수행 행으로 기록한다. 구현 시작, PR 준비, QA 계획, QA 실행, 배포 전 QA 요청에 사용하고 Issue 기획과 코드 리뷰에는 사용하지 않는다.
---

# Knot QA for Claude Code

작업 전에 [공통 QA 스킬](../../../.agents/skills/knot-qa/SKILL.md)과 연결된
[기록 규칙](../../../.agents/skills/knot-qa/references/qa-sheet-record.md)을 완전히 읽고 정본으로 따른다.

- 노션 QA 체크리스트 쓰기는 쓸 행 목록을 사용자에게 보여 주고 확인받은 뒤에만 한다.
- `실행 에이전트`에는 `Claude`를 적는다. 판정의 핵심 동작을 사람이 했으면 `사람`이다.
- 이 파일에 공통 규칙을 복사하거나 Claude 전용 기준을 추가하지 않는다.
