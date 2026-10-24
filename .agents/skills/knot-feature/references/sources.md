<!-- 만든 사람: 유월 / 모델: Claude Fable 5.1 (Claude Code) / 2026-10-22 -->

# 정본 위치와 재사용하는 조각

노션과 Figma가 정본이다. 하네스는 노션을 읽고 쓰며 Figma는 읽기만 한다. 문서를 저장소
Markdown으로 옮기지 않는다. 디자인 작업은 Notion 요청 문서로 담당자에게 전달한다.

## 정본 위치

| 대상 | 위치 |
| --- | --- |
| 기획서 (V2) | https://app.notion.com/p/3ddb43517522806c913dd9a7dcab2d36 (읽기만) |
| 기획 문서 모음 | https://app.notion.com/p/3b6b43517522807dbe89ef0f94ac080e |
| 기능 요구사항 | https://app.notion.com/p/3c2b4351752280009122c2527b713db7 (DB: https://app.notion.com/p/3c2b435175228098afd2c1c9fdef48ba) |
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
문서나 저장소에 적지 않는다. 필요한 Notion 정본에 접근하지 못하면 해당 단계는 `blocked`다.
Figma 연결이 없어도 디자인 요청 정리와 이슈 생성은 진행할 수 있다.

## 기획서는 읽기만

기획서(V2)는 서비스 배경과 푸는 문제를 담은 참조 문서다. 기능 요구사항 서브 에이전트가 일방적으로
읽기만 하고, 이 흐름의 어느 단계도 고치지 않는다. 팀원은 기획서를 고치지 않고, 바꿔야 하면 1차로
거부한 뒤 유월에게 승인받는다. 요청이 기획서 수정을 전제로 해야만 성립하면 입구 스킬이 요청을
거부하고 유월에게 컨펌받으라고 안내한다. 흐름 도중에 발견하면 그 서브 에이전트가 `blocked`로
돌아오고 `question`에 유월 승인이 필요하다고 적는다. 훅이나 루트 Markdown으로 막지 않고 지침으로
거부한다.

## 재사용하는 조각 (새로 만들지 않는다)

| 단계 | 저장소 위치 | 쓰는 서브 에이전트 |
| --- | --- | --- |
| 이슈 생성(위험도, 인터뷰, Grill, ADR 판정, 게시) | `.agents/skills/knot-issue-planning`, `knot-deep-interview`, `knot-grill-me`, `harness/issue_planning.py`, `docs/harness/issue-planning.md` | 이슈 |
| 제품 기획 기준(진입점, 정본은 노션) | `backend/docs/product/current-v2-mvp.md` | 기능 요구사항, 이슈, 구현 하네스 |
| 백엔드 노션 근거 탐색 | `backend/docs/notion-context.md`, `docs/harness/mcp-access.md` | 도메인 규칙, API 명세, BE 구현 하네스 |
| 백엔드 API 명세, 커밋, PR | `backend/.agents/skills/knot-api-spec`, `knot-commit`, `knot-pr` (진입점 `backend/.claude/skills`) | API 명세, BE 구현 하네스 |
| 프론트 작업 절차와 규칙 | `frontend/CLAUDE.md`, `frontend/.claude/rules/*.md` | FE 구현 하네스 |
| 프론트 스토리북, PR 본문, 리뷰, 폴더 구조 | `frontend/.claude/skills/create-story`, `create-pr-content`, `review`, `project-structure` | FE 구현 하네스 |
| 디자인 요청과 기존 Figma 참조 | `.agents/skills/knot-feature/subagents/design.md` (Notion에 요청 정리, 유월 또는 편집 가능한 담당자가 디자인) | 디자인 |
| QA 시트 읽기와 E2E, PR CI 게이트 | `knot-qa` 스킬, `frontend/.claude/skills/qa-e2e`, `.github/workflows/frontend-e2e.yml`, PR 템플릿 「관련 QA」 (PR #855, 머지 대기) | QA (E2E) |
| ADR 자산화 | `harness/materialize_adr.py` | BE, FE 구현 하네스 |
| CI | `frontend-ci.yml`(필수 검사), `backend-ci.yml`, `storybook-preview.yml`, `governance.yml`, `issue-harness.yml` | 구현 하네스 |
| Issue, PR 컨벤션 | `.github/ISSUE_TEMPLATE/knot-issue-template.md`, `.github/PULL_REQUEST_TEMPLATE.md`, `CONTRIBUTING.md`, `.github/knot-conventions.yml` | 이슈, 구현 하네스 |
