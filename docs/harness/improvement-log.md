# 하네스 개선 로그

하네스는 계속 고친다. 무엇을 왜 바꿨고 무엇을 미뤘는지 날짜순으로 쌓는다. 한 항목은 출처(피드백,
실패, 회의), 판단, 바꾼 것(파일), 미룬 것과 그 이유, 그리고 규칙이 어디서 강제되는지로 이루어진다.
팀이 읽는 문서라 이유와 결과만 적고, 이야기와 과정은 개인 글감 페이지에 따로 쌓는다.

## 규칙이 강제되는 곳

AI는 지침 문장을 무시할 수 있다. 지켜져야 하는 규칙은 아래 셋 중 하나에 있어야 하고, 아직 문장뿐인
규칙은 이 표에 남겨 두고 하나씩 옮긴다.

| 규칙 | 강제 수단 | 상태 (2026-10-23) |
| --- | --- | --- |
| 서브 에이전트 반환값 형식, `next` 금지, `flags` 키와 형 | `harness/feature_pipeline.py result` | 있음 |
| 다음 단계 분기 | `harness/feature_pipeline.py next` (`next_stage`) | 있음 |
| 상태 파일 형식(`kind`, `assumptions`, `implementation_order`) | `harness/feature_pipeline.py state` | 있음 |
| 노션 문서 서브 에이전트는 Figma와 저장소 파일을 못 고친다 | `.claude/agents/knot-feature-*.md`의 `disallowedTools` | 있음 (Claude Code만. Codex는 문장뿐) |
| 디자인 서브 에이전트는 노션을 못 고친다 | 같은 `disallowedTools` | 있음 (Claude Code만) |
| 이슈 서브 에이전트는 코드를 못 고친다 | 같은 `disallowedTools` | 있음 (Claude Code만) |
| Issue 계약(위험도, 인터뷰, Grill, ADR, 게시 권한) | `harness/issue_planning.py` | 있음 |
| Claude Code 진입점과 Codex 정본의 일치 | `harness/tests/test_agent_harness_parity.py` | 있음 |
| 기획서 수정 금지 | 문장뿐 | 옮길 곳: 노션 REST 스크립트가 허용 DB 목록만 받기 |
| FE 길에서 `frontend/` 밖 수정 금지 | 문장뿐 | 옮길 곳: PreToolUse 훅이 상태 파일의 `area`를 보고 경로 밖 Edit, Write 거부 |
| PR 첫 섹션 `## 노션 변경 사항` | 문장뿐 | 옮길 곳: Governance 검증기. 단 knot-feature 흐름의 PR에만 적용해야 해서 표식이 먼저 필요 |
| E2E 테스트 이름에 QA 행 ID | 문장뿐 | 옮길 곳: CI 스크립트가 QA 시트 캐시의 ID와 대조. 캐시 결정 뒤 |
| 커밋 타입 5개 | 문장뿐 | 옮길 곳: Governance 또는 commit-msg 훅 |
| 끝났다고 보고하기 전 상태 파일이 `finished` | 문장뿐 | 옮길 곳: Stop 훅이 `feature_pipeline.py next`를 실행 |

## 2026-10-23 팀 피드백 반영 (#932)

출처: knot-feature 하네스와 워크플로우에 대한 한 팀원의 피드백 8개. 전문은 개인 글감 페이지에 있다.

| 피드백 | 판단 | 이번에 한 것 | 미룬 것 |
| --- | --- | --- | --- |
| 기획서 없이는 오타 하나도 못 고친다 | 절반은 오해(버그, 오타는 원래 흐름 밖), 절반은 맞음(어디에도 적혀 있지 않았다) | 요청 종류 `feature`, `fix`, `chore`를 두고 `fix`, `chore`는 문서 단계를 건너뛴다. 「이 흐름 밖의 작업」 표를 SKILL.md와 AGENTS.md에 적었다 | 없음 |
| 하네스를 고칠 때 우회로가 없다 | 맞음. #909 자체가 Issue 계약과 ADR을 거쳤다 | 하네스 경로를 정하고 knot-feature와 Issue 계약을 면제했다. 게이트는 테스트다 (`harness-verification.md`) | Governance CI가 하네스 라벨 PR을 어떻게 볼지 확인 |
| QA 시트를 저장소에 두면 노션 MCP 토큰을 아낀다 | 맞음. 금지의 진짜 대상은 손으로 베끼기였고 백엔드는 이미 `sync_notion_docs.py`로 캐시를 쓴다 | ADR #909 「다시 논의해야 할 조건」에 올렸다. AGENTS.md 금지 문장을 「손으로 옮기기」로 좁혔다 | 생성 캐시 허용 결정(유월). 결정 뒤 `harness/notion_cache.py`(QA 시트, 기능 요구사항 읽기 캐시와 REST 쓰기) |
| 사람 개입은 시작과 끝에만 | 맞음. 중간 질문 지점이 넷이었다 | 시작 질문을 종류, 영역, 완료 조건, 게시 허용 한 번으로 묶었다. 중간 미결을 두 등급(멈춤, 기본값)으로 나누고 `assumptions`를 상태 파일과 PR에 둔다. `feature_pipeline.py status`로 「방금 뭐 하고 있었지」에 답한다 | 읽기만으로 미리 알아내는 preflight 자동화는 캐시가 생긴 뒤 |
| 분기를 AI가 한다 | 맞음. 서브 에이전트가 `next`로 다음 단계를 골랐다 | 서브 에이전트는 사실(`flags`)만 돌려주고 `feature_pipeline.py next_stage`가 분기한다. 검증기가 `next`를 거부한다. 분기 테스트 11개 | 스킬 분리(`knot-feature-docs`, `knot-feature-build`)는 분기 스크립트가 자리 잡은 뒤 |
| AI가 컨벤션을 지킬 거라 기대하지 말라 | 맞음. 기획서 수정 금지조차 일부러 문장으로 뒀었다 | 위 「규칙이 강제되는 곳」 표를 만들고 서브 에이전트 진입점에 `disallowedTools`를 넣었다 | 표의 「문장뿐」 항목을 하나씩 훅, CI로 |
| 사전 문서를 사람이 만드는 게 병목 | 맞음. 한 문장 약속은 기존 화면 안의 변경에만 성립한다 | 약속의 범위를 스킬 설명에 적었다 | 도메인 준비 체크리스트(`preflight`)와 문서가 없을 때 최소 초안 허용. 캐시 결정 뒤 |
| 개인 하네스를 꺼야 팀 하네스 적중률이 보인다 | 가장 세게 맞음. 유월 환경에 개인 CLAUDE.md, 스킬 4개, 훅 9개, 자동 메모리 60개가 있고 팀 정본이 `~/.local/share/agent-skills`를 가리킨다 | 빈 프로필 검증 모드(`CLAUDE_CONFIG_DIR`)를 정하고 하네스 PR에 검증 여부를 적게 했다. 절대 개인 경로 금지 테스트 | `knot-ux-journal`, `knot-marker-cleanup`을 저장소로 옮길지 결정(유월, Codex 개인 설정도 바뀜) |

같이 받은 고민: Figma 시트가 없는 팀원은 MCP를 못 쓴다. 방향은 「수치 md와 캡처 png 한 쌍」을
스크립트로 생성해 저장소에 두는 것이고, 위 셋째 줄의 생성 캐시 결정과 한 묶음이다. 결정 뒤
`harness/figma_export.py`(REST API, 보기 권한 토큰)와 `frontend/design/screens/<화면>/spec.md`, `frame.png`.

바꾼 파일: `harness/feature_pipeline.py`, `harness/tests/test_feature_pipeline.py`,
`harness/tests/test_agent_harness_parity.py`, `.agents/skills/knot-feature/SKILL.md`,
`references/return-contract.md`, `subagents/*.md`, `.claude/agents/knot-feature-*.md`, `AGENTS.md`,
`docs/adr/909-feature-pipeline-entry-and-subagents.md`, `docs/harness/harness-verification.md`.
