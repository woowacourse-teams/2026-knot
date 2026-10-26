<!-- 만든 사람: 유월 / 모델: Claude Fable 5.1 (Claude Code) / 2026-10-22 -->

# 정본 위치와 재사용하는 조각

노션과 Figma가 정본이다. 하네스는 읽고 쓰되 문서를 저장소 Markdown으로 옮기지 않는다.

## 정본 위치

| 대상 | 위치 |
| --- | --- |
| 기획서 (V2) | https://app.notion.com/p/3ddb43517522806c913dd9a7dcab2d36 (기능 요구사항 단계가 함께 고친다) |
| 기획 문서 모음 | https://app.notion.com/p/3b6b43517522807dbe89ef0f94ac080e |
| 기능 요구사항 | https://app.notion.com/p/3c2b4351752280009122c2527b713db7 (DB: https://app.notion.com/p/3c2b435175228098afd2c1c9fdef48ba) |
| 기능 요구사항 검토 (확정 전 제안, 정본 아님) | https://app.notion.com/p/405b4351752281149fd3f1adbf3b8e7c (DB: https://app.notion.com/p/9e390406e6e04b6dab5323662db1839e, `collection://403e65d6-30d8-4596-be70-40d05f3b1392`) |
| 문서 쓰기 규칙 | 문서 운영 가이드 https://app.notion.com/p/3ddb435175228119b0bdfcb795222ea8 |
| 도메인 규칙 | 도메인 규칙 및 UseCase https://app.notion.com/p/3e3b4351752280cd9f06edc2ccdff1d8 |
| API 명세 | (신)API 명세서 https://app.notion.com/p/3deb4351752280e2ad98f2b5c5670fb2 |
| QA 시트 | 제품 QA · v2 https://app.notion.com/p/3f2b4351752281758e61ea4a43d5da93 (DB: QA 체크리스트 https://app.notion.com/p/40291cc013ea43a0854771144e94d014) |
| 디자인 | Figma `knot` 파일 https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot 의 Design, Design System, UI/UX Policy Guidelines, User Flow 페이지 |
| 화면별 Figma URL | 노션 화면 정책의 링크, 구현 뒤에는 컴포넌트 옆 스토리북 파일 |
| 이슈, PR | GitHub `woowacourse-teams/2026-Knot`, 기본 브랜치 `develop`, `gh` CLI |
| 테스트 전략 | https://app.notion.com/p/3ebb4351752280bbad4fe349d0aea903, E2E와 QA 합치기 설계 v0.4 https://app.notion.com/p/3fdb4351752281b48ff3e996cb5b1589 |
| 이 하네스의 기준 | 하네스 구축 기준 문서 최종본 (2026-10-22) https://app.notion.com/p/401b43517522811ba9b2e2ecf13e1f1a |

노션과 Figma 접근: 설정 절차와 토큰 발급, 전달 규칙은 `docs/harness/mcp-access.md`에 있다. 노션은
유월이 만든 내부 통합 토큰을 각자 환경 변수로 두고, Figma는 각자 계정으로 로그인한다. 토큰 값은
문서나 저장소에 적지 않는다. 연결이 없는 세션의 서브 에이전트는 `blocked`로 돌아온다.

## 기능 요구사항 검토 (확정 전 제안)

기능 추가, 수정, 삭제는 기능 요구사항에 바로 쓰지 않고 이 DB에 제안 페이지로 먼저 쓴다. 정본은
확정된 것만 두고, 하네스와 팀원 에이전트는 정본만 구현 근거로 읽는다(2026-10-26 회의 제안). 코드로
치면 정본은 main, 제안은 PR이다.

- 제안 페이지는 기능 요구사항 서브 에이전트의 「제안해라」 단계만 만든다. 양식은 검토 페이지의
  「양식」 토글이다. 속성은 `상태`(작성 중, 검토 요청, 보류, 확정, 반영 완료, 반려), `종류`(추가, 수정,
  삭제), `영역`, `한 줄 요약`, `제안자`, `컨펌한 사람`, `컨펌 날짜`, `반영된 기능 요구사항`, `이슈`, `확정`
  버튼이다.
- 1차 컨펌은 기획 컨펌 담당이 `확정` 버튼을 눌러서만 한다. 버튼은 `상태`를 확정으로, `컨펌한 사람`을
  누른 사람으로, `컨펌 날짜`를 오늘로 채우고 `제안자`에게 노션 알림을 보낸다. 입구는 이 세 칸을 읽고
  `python3 harness/team_roles.py confirmation --notion <컨펌한 사람 ID>`로 기획 컨펌 담당인지 확인한다.
  디스코드나 구두 컨펌은 근거로 쓰지 않는다.
- 확정은 「컨펌됐고 아직 정본에 반영하지 않음」이다. 이슈 서브 에이전트가 이슈를 게시하면 반영 완료로
  바꾼다. 「확정된 제안 반영해 줘」는 `상태`가 확정인 제안만 찾는다.
- 요구사항 문장이 바뀌는 작업(기획 변경)은 크기와 시점에 관계없이 이 DB를 거친다. 문장이 바뀌지
  않는 작업(기획 그대로)만 거치지 않는다. 기준은 입구 스킬 「역할과 권한」에 있다.
- 다른 서브 에이전트와 FE, BE 구현 절차는 이 DB를 구현 근거로 읽지 않는다. BE 노션 동기화 범위
  (`harness/notion-sync-scope.json`)에서도 이 페이지를 뺐다.

## 기획서 고치기

기획서(V2)는 서비스 배경과 푸는 문제, 만드는 것과 만들지 않는 것, 검증 가설을 담은 참조 문서다.
도메인 팀이 기능을 기획하다 기획서도 바뀌어야 하면 기능 요구사항 서브 에이전트가 같은 흐름에서
고친다(2026-10-25 결정, ADR #958). 다만 기능 추가, 수정, 삭제에 딸린 기획서 변경은 기능 제안에
넣어 1차 컨펌을 받은 뒤에 고친다(2026-10-26 팀 회의). 다른 서브 에이전트는 기획서를 고치지 않는다.

- 고치기 직전 페이지를 다시 읽는다. 다른 팀이 먼저 고쳤으면 그 내용 위에 고친다.
- 충돌 검사는 기능 요구사항과 같다. 기존 문장을 바꾸거나 지우는 변경, 「만들지 않는 것」처럼 다른
  도메인을 막는 내용을 더하는 변경은 기존 요구사항, 규칙과 부딪히는지 본다. 부딪히면 고치지 않고
  `needs_user_input`으로 멈춰 영향받는 도메인과 요구사항을 `side_effects`에 적는다. 결정은 입구
  스킬의 「역할과 권한」 표대로 영향받는 도메인 팀이 한다.
- 고친 곳은 `changed`에 넣고 `summary`를 「기획서」로 시작한다. 이슈와 PR의 「노션 변경 사항」에
  링크로 나오고, 기획 컨펌 담당이 PR 2차 컨펌에서 함께 본다.
- 기획서만 고치는 요청은 이 흐름을 타지 않는다. 노션에서 바로 고친다.

## 재사용하는 조각 (새로 만들지 않는다)

| 단계 | 저장소 위치 | 쓰는 서브 에이전트 |
| --- | --- | --- |
| 이슈 생성(위험도, 인터뷰, Grill, ADR 판정, 게시) | `.agents/skills/knot-issue-planning`, `knot-deep-interview`, `knot-grill-me`, `harness/issue_planning.py`, `docs/harness/issue-planning.md` | 이슈 |
| 제품 기획 기준(진입점, 정본은 노션) | `backend/docs/product/current-v2-mvp.md` | 기능 요구사항, 이슈, 구현 하네스 |
| 백엔드 노션 근거 탐색 | `backend/docs/notion-context.md`, `harness/notion-sync-scope.json`, `harness/sync_notion_docs.py` | 도메인 규칙, API 명세, BE 구현 하네스 |
| 백엔드 API 명세, 커밋, PR | `backend/.agents/skills/knot-api-spec`, `knot-commit`, `knot-pr` (진입점 `backend/.claude/skills`) | API 명세, BE 구현 하네스 |
| 프론트 작업 절차와 규칙 | `frontend/CLAUDE.md`, `frontend/.claude/rules/*.md` | FE 구현 하네스 |
| 프론트 스토리북, PR 본문, 리뷰, 폴더 구조 | `frontend/.claude/skills/create-story`, `create-pr-content`, `review`, `project-structure` | FE 구현 하네스 |
| Figma 변경 표시 | 공유 스킬 `knot-ux-journal`, `knot-marker-cleanup` (`~/.local/share/agent-skills/`) | 디자인 |
| QA 시트 읽기와 E2E, PR CI 게이트 | `knot-qa` 스킬, `frontend/.claude/skills/qa-e2e`, `.github/workflows/frontend-e2e.yml`, PR 템플릿 「관련 QA」 (PR #855, 머지 대기) | QA (E2E) |
| ADR 자산화 | `harness/materialize_adr.py` | BE, FE 구현 하네스 |
| CI | `frontend-ci.yml`(필수 검사), `backend-ci.yml`, `storybook-preview.yml`, `governance.yml`, `issue-harness.yml` | 구현 하네스 |
| Issue, PR 컨벤션 | `.github/ISSUE_TEMPLATE/knot-issue-template.md`, `.github/PULL_REQUEST_TEMPLATE.md`, `CONTRIBUTING.md`, `.github/knot-conventions.yml` | 이슈, 구현 하네스 |
