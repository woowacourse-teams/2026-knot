---
paths:
  - "src/**/test.ts"
  - "src/**/test.tsx"
  - "src/**/*.test.ts"
  - "src/__test__/**"
description: 단위·통합·E2E 테스트의 위치와 대상을 정의하는 테스트 전략 가이드라인. 테스트 코드 작성·배치 전 필독.
---

# 테스트 전략 가이드라인

자동화 테스트는 **단위·통합·E2E 세 가지만** 가져감. 스토리북은 테스트가 아니라 컴포넌트를 직접 조작해 보는 도구로 씀. (`.claude/rules/segment-pattern.md`의 「스토리 위치」)

AI로 기능 개발 시 검증 루프가 필요한데, 검증 루프를 테스트 코드로 만들어 두면 개발 효율이 좋아짐. 기능 구현 시 테스트 코드를 검증 루프로 활용할 것.

## 테스트 종류별 위치와 대상

| 종류        | 위치                                                                                                                                                                                 | 대상                                    |
| ----------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------- |
| 단위 테스트 | 유틸 구현 파일 옆에 나란히 코로케이션. 세그먼트 `utils`는 `utils/formatDate.ts` + `utils/formatDate.test.ts`, `shared/utils`는 기존대로 `formatDate/index.ts` + `formatDate/test.ts` | 유틸 함수                               |
| 통합 테스트 | 최종 책임 컴포넌트(대부분 섹션 단위의 widgets) 폴더 안의 `test.tsx`                                                                                                                  | 패칭부터 UI까지 하나의 유저 플로우 전체 |
| E2E 테스트  | 전역 `src/__test__/`에 페이지 이름을 붙인 파일 (예: `dashboardPage.test.ts`)                                                                                                         | 페이지 단위 수행 권장                   |

## 배치 규칙

- E2E 테스트를 페이지 폴더에 두기는 애매하므로 전역 `__test__/` 폴더로 분리. 페이지 폴더에 두지 않음.
- 테스트 파일은 "인덱스 제외 나머지는 폴더로 둔다"는 네이밍 규칙의 예외. 세그먼트 `utils`의 단위 테스트는 `{구현체 이름}.test.ts`, `shared/utils`의 단위 테스트는 폴더 안 `test.ts`, JSX를 렌더링하는 컴포넌트 통합 테스트는 컴포넌트 폴더 바로 아래 `test.tsx`로 둠.
- API 응답은 `vitest.setup.ts`에서 전역으로 켠 msw 서버(`shared/api/mock/server`)가 대신함. 실제 서버로 테스트하면 DB가 바뀌고 특정 플로우에 도달하기 어려우므로 **실제 API 연동 후에도 자동화 테스트는 계속 mock으로 돌리고**, 실제 API 검증은 QA에서 수행. 테스트가 요청 함수나 쿼리 훅을 직접 모킹하지 않고, 기대값은 문자열로 박지 않고 `shared/api/mock/responses`의 mock 응답을 **응답 DTO 클래스로 변환한 값**에서 가져옴(아래 「기대값」). 특정 케이스(빈 목록·에러)는 해당 테스트에서 `mockServer.use(...)`로 덮음. (`.claude/rules/api-guide.md`의 「API mock」)
- **훅은 테스트하지 않음.** 단위 테스트 대상은 유틸 함수이며, 훅의 동작은 해당 컴포넌트의 통합 테스트로 검증.
- 스토리(`index.stories.tsx`)는 테스트 파일이 아니므로 이 문서의 테스트 규칙을 적용하지 않음. 위치는 `.claude/rules/segment-pattern.md`의 「스토리 위치」, 작성은 `create-story` 스킬을 따름.

## QA 시트와 E2E 잇기

QA 시트(노션 [QA 체크리스트](https://app.notion.com/p/40291cc013ea43a0854771144e94d014)) 행 중 mock으로 매번 같은 결과가 나오는 화면 동작은 E2E로 옮겨 PR마다 지킴. QA 시트의 정본은 노션 하나이고, 우선순위와 판정도 노션에만 둠. 저장소에는 사본을 두지 않음.

- **knot-qa**: 확정 Issue 구현을 시작할 때 공용 `knot-qa` 스킬(`../.agents/skills/knot-qa/SKILL.md`) 준비 단계가 QA 시트에서 관련 행을 찾아 확인 방법을 나누고, E2E 행은 구현 전에 `qa-e2e` 절차로 먼저 써서 실패를 확인함.
- **관련 QA**: QA 시트 행을 고치거나 구현한 PR은 본문 「## 관련 QA」에 행 ID를 한 줄에 하나씩 적음(`- STT-R25`). PR 본문을 만드는 `create-pr-content` 스킬이 이슈와 대화에서 ID를 찾아 채우고, 그 ID가 노션에 있는지는 에이전트가 노션에서 직접 확인함.
- **이름 규칙**: E2E 이름 앞에 `[QA ID]`를 붙임(`[STT-R25] 독의 마이크로 녹음을 시작하고...`). 여러 ID는 쉼표로 나눔(`[CONF-R2, CONF-R3]`).
- **태그**: 핵심 여정이면 `@core`만 닮. 우선순위 태그는 달지 않음.
- **E2E 제외**: 실제 서버 응답, 실기기, 사람의 판단이 필요해 E2E로 볼 수 없는 행은 이유와 함께 적음(`- STT-R28 (E2E 제외: 실제 서버 응답 필요)`).
- **PR 관문**: `.github/workflows/frontend-e2e.yml`의 qa-link가 관련 QA의 ID마다 그 ID를 붙인 E2E가 있는지, 제외에 이유가 있는지 봄. 어긋나면 실패하고 고칠 것을 알림. 그다음 `@core`와 관련 QA의 E2E를 돌림.
- **결과 보기**: 관문은 PR 댓글 하나(관련 QA별 결과, 핵심 여정 결과, 보고서 artifact 링크)를 달고 다시 돌면 같은 댓글을 고침. 보고서는 통과해도 7일 동안 남고, CI에서 실패한 테스트는 스크린샷과 영상이 함께 들어 있음.
- **E2E 결과 기록**: 관문의 E2E 결과는 QA 시트 「판정」이 아니라 「자동 확인」, 「E2E 테스트」, 「자동 확인 일시」 칸에 감. `knot-qa` 실행 단계가 관문 댓글을 근거로 적고, 「판정」은 실제 서버에서 확인한 결과만 받음.
- E2E 작성 절차는 `qa-e2e` 스킬을 따름. 로컬에서 검사만 돌릴 때는 `PR_BODY="$(cat 본문.md)" pnpm qa:link`.

## 기대값

mock 응답(`shared/api/mock/responses`)은 네트워크로 나가는 **서버 JSON(`Raw`) 모양**이고, 컴포넌트가 받는 건 `fetch/`에서 `new XxxResponseDto(response.data)`를 거친 **앱 모양**. 생성자가 값을 바꾸는 필드(`null → ""`, 이름 변경, `trim`)는 둘이 다르므로 mock 값을 그대로 기대값으로 쓰면 어긋남. (`.claude/rules/dto-guide.md`)

- 기대값은 mock 응답을 **해당 응답 DTO 클래스로 변환한 값**에서 가져옴. 변환이 없는 필드도 같은 경로를 씀 — 나중에 그 필드에 변환이 생겨도 테스트를 고치지 않아도 됨.
- 이를 위해 **테스트 파일(`test.tsx` · `*.test.ts` · `src/__test__/**`)만 `dto/`의 응답 DTO 클래스를 값 import할 수 있음.** 프로덕션 코드의 `shared/api` 밖 `dto/` import 금지는 그대로. (`.claude/rules/dto-guide.md` 「의존 규칙」)
- 변환 로직을 테스트에 되풀이하지 않음(`mockResponse.name.trim()` ❌). 변환 규칙의 정본은 DTO 생성자 하나.
- 요청 DTO는 테스트에서 `new`하지 않음. 컴포넌트가 `mutate`에 plain object(`Input` 모양)를 넘기므로 테스트도 화면 입력만 재현하면 됨.

```tsx
// src/modules/widgets/workspace/WorkspaceList/test.tsx
import { GetWorkspacesResponseDto } from "@api/dto/workspace";
import { workspacesResponse } from "@api/mock/responses/workspace";

const expected = new GetWorkspacesResponseDto(workspacesResponse);

it("워크스페이스 목록을 보여준다", async () => {
  render(<WorkspaceList />);

  expect(
    await screen.findByText(expected.workspaces[0].name),
  ).toBeInTheDocument();
});
```
