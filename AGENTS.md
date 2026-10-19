# Knot 저장소 공통 작업 지침

## 현재 제품 기획

제품 요구사항을 사용하는 Issue 기획·구현·리뷰 전에
[현재 V2 MVP 기획 기준](docs/product/current-v2-mvp.md)을 읽고, 작업 항목에 해당하는
원문과 적용 범위를 확인한다. 출처 충돌·미확정·접근 불가 시 처리도 이 문서를 따른다.
Notion 후보와 저장소 문서·GitHub Issue·실제 구현을 대조하고 queue 상태를 처리할 때는
[Notion 정합성 워크플로](docs/harness/notion-alignment.md)를 따른다.

## 적용 범위와 우선순위

이 지침의 Issue 기획·검증·ADR 생명주기는 저장소 전역과 BE·FE 작업에 공통으로 적용한다.
하위 디렉터리의 지침은 해당 코드의 구현 규칙을 추가할 수 있지만, 공통 Issue 템플릿,
원격 쓰기 opt-in, 실제 대안 확인과 ADR 생성 조건을 약화할 수 없다. 충돌하면 임의로 한쪽을
선택하지 말고 충돌과 영향을 보고한다.

백엔드 구현 지침은 코드와 검증 규칙을 추가하되 ADR 필요 여부는 이 공통 계약의 판정을
그대로 사용한다. `adr.required=true`인 확정 Issue는 구현 단계에서 아래 ADR 자산화 절차를
수행한다.

- Issue #167은 저장소 공통 Issue·ADR 기획 계약을 담당한다.
- Issue #165의 프론트엔드 하네스는 `frontend/` 구현·리뷰 규칙을 담당한다.
- 프론트엔드 Issue 기획에는 이 공통 계약을 먼저 적용하고, 실제 구현에는 #165의
  `frontend/` 지침을 함께 적용한다.

## Issue 기획

Knot의 GitHub Issue 생성·초안·검토 요청에는 `$knot-issue-planning`을 사용한다. 운영
규칙은 `docs/harness/issue-planning.md`를 읽는다.

기본 하네스 실행은 dry-run이다. `Issue 만들어줘` 요청도 기본값은 `action=render_draft`와
`remote_write_authorized=false`이며, `requested_action=publish_issue`는 사용자의 의도일 뿐
쓰기 권한이 아니다. 사용자가 현재 요청에서 실제 GitHub Issue 생성을 명시적으로 허용했고
판정기가 `pass`와 `publish_ready=true`를 반환한 경우에만 저장소 루트에서
`python3 harness/issue_planning.py <snapshot.json> --publish --repo OWNER/REPO --pretty`를
실행할 수 있다. 게시기는 계약 표식으로 기존 Issue를 먼저 찾아 하나면 재사용하고, 둘 이상이면
중복 해소 전까지 멈춘다. `gh issue edit`는 ADR 예정 경로를 실제 Issue 번호로 확정하기 위해
방금 생성하거나 재사용한 동일 Issue의 본문을 갱신하거나, 승인된 같은 게시 요청에서 해당
Issue에 확인된 기존 라벨을 추가할 때만 허용한다. 라벨은 [공통 라벨 선택 규칙](docs/harness/issue-planning.md#issue-라벨-선택과-게시-후-확인)을
따라 실제 담당자·영역·작업 성격으로 선택하며 대화 사용자를 담당자로 고정하지 않는다. Project 변경, branch,
commit, push, PR merge와 ADR 파일 생성은 함께 실행하지 않는다. `draft` 또는 `hold` 계약은
`--publish`가 있어도 GitHub에 쓰지 않는다.

## Issue 구현

확정 Issue 구현을 시작할 때 Issue 본문을 먼저 읽는다. `메모`에 ADR 결정과 예정 경로가
있고 파일이 없다면 실제 Issue 번호를 확인한 뒤 검증된 결정 snapshot으로
`python3 harness/materialize_adr.py <snapshot.json> --issue-number <번호> --implementation --pretty`를
실행한다. Issue 기획 결과의 `{ISSUE_NUMBER}`는 번호 확정 전 표시일 뿐 실제 파일명이
아니다. ADR은 구현 브랜치에 `Proposed`로 만들고 코드와 같은 PR에 포함한다. 원래
snapshot을 복구할 수 없으면 현재 상황, 실제로 논의한 대안과 선택 이유만 다시 확인하며
대안을 추측하지 않는다. 팀 리뷰가 승인한 뒤에만 `Accepted`로 바꾸며 commit, push와
merge는 사용자가 명시적으로 요청한 범위에서만 수행한다.

확정 Issue 구현을 시작할 때 개발자가 부르지 않아도 `$knot-qa` 준비 단계로 노션 QA 시트에서
관련 행을 찾아 QA 계획을 먼저 만들고, PR이 develop에 머지돼 dev에 배포된 뒤 같은 스킬의 실행
단계로 그 변경의 행을 실제 서버에서 확인한다. PR 단계는 자동 E2E 관문이 지킨다.
QA 시트 기록은 쓸 행 목록을 사용자에게 보여 주고 확인받은 뒤에만 한다.

Issue에는 기존 템플릿의 `구현 기능 설명`, `TODO`, `메모`만 사용한다. 전체 내부 계약,
ADR 전문과 인터뷰 원문은 Issue에 넣지 않는다.

## 백엔드 Codex 스킬

저장소 공통 Codex 스킬은 루트 `.agents/skills`에, 백엔드 전용 Codex 스킬은
`backend/.agents/skills`에 둔다. Codex는 현재 작업 디렉터리부터 저장소 루트까지 각
디렉터리의 `.agents/skills`를 찾으므로, 백엔드 작업은 `backend/`에서 시작해야 공통 스킬과
백엔드 전용 스킬을 함께 발견한다. 저장소 루트에서 시작한 Codex는 하위 디렉터리인
`backend/.agents/skills`를 자동으로 찾지 않는다.

대표 자연어 요청은 다음 스킬로 연결한다.

- `커밋해줘`, `작업 단위로 커밋해줘`, `커밋 메시지 작성해줘`: `$knot-commit`
- `PR 본문 작성해줘`, `PR 준비 상태 확인해줘`, `PR 생성해줘`, `PR 올려줘`: `$knot-pr`

명시적 `$knot-commit`, `$knot-pr` 호출도 fallback으로 유지한다. 메시지나 본문 초안 요청은
실제 commit 또는 PR 게시 권한이 아니다. 실제 commit과 PR 게시는 각각 해당 쓰기 동작을
현재 요청에서 명시한 경우에만 수행하며, push와 merge 권한은 별도로 판정한다.

## 임시 snapshot 안전 규칙

Issue 계약 snapshot은 저장소 밖의 OS 임시 파일에만 쓰고 권한을 현재 사용자로 제한한다.
판정이나 ADR 생성이 끝나면 성공·실패와 관계없이 즉시 삭제한다. 토큰, 비밀번호,
개인정보와 비공개 대화 원문은 snapshot이나 문서에 넣지 않는다.

## 전달 규칙

Issue와 PR 컨벤션은 `CONTRIBUTING.md`와 `.github/knot-conventions.yml`을 따른다. 기존
Governance, Backend CI와 Project 자동화를 유지한다.

사용자의 기존 변경을 보존한다. 원격 쓰기, branch, commit, push, PR과 merge 권한은
요청마다 별도로 판정한다.
