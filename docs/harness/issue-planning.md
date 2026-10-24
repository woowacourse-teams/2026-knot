# Knot Issue·ADR 하네스

- 적용 범위: 저장소 전역, BE·FE 공통
- 상태: dry-run 기본, 명시적 publish opt-in
- 원격 변경: `--publish --repo OWNER/REPO`에서 계약 표식 기반 Issue 생성·재사용 활성화

이 하네스는 깊이 있는 내부 계약을 검증한 뒤 팀의 기존 세 섹션으로 짧은 GitHub Issue
후보를 만든다. 기본 실행은 Issue 본문과 판정 결과만 생성한다. 사용자가 현재 요청에서
실제 GitHub Issue 생성을 명시적으로 허용했고 계약이 통과한 경우에만 CLI `--publish`
옵션으로 GitHub Issue를 게시한다.

## 요청 방법

실제 생성을 의도한 표현은 다음과 같다.

```text
로그인·회원가입 GitHub Issue 만들어줘
이 기능을 Issue로 등록해줘
```

초안만 필요한 표현은 다음과 같다.

```text
로그인 Issue 초안 잡아줘
이 요구사항을 Issue 형식으로 검토해줘
```

기본 실행에서는 두 요청 모두 원격 변경 없이 `action=render_draft`로 결과만 보여준다.
생성 요청은 `requested_action=publish_issue`, `publish_ready=true`로 의도를 구분한다.
`remote_write_authorized=false`인 결과의 다른 필드는 쓰기 권한이 아니다. `publish_ready`는
Issue 후보 계약만 통과했다는 뜻이며 ADR 실제 경로 확정을 뜻하지 않는다.

실제 게시를 허용받은 경우에만 다음처럼 실행한다.

```bash
python3 harness/issue_planning.py <snapshot.json> \
  --publish --repo OWNER/REPO --pretty
```

`--publish`는 `operation=create`, `status=pass`, `publish_ready=true`일 때만 동작한다. 먼저
`contract_id` 표식으로 모든 상태의 기존 Issue를 찾고, 하나면 재사용하며 둘 이상이면
`hold`한다. 없으면 `gh issue create`를 실행한다. ADR이 필요하면 실제 Issue 번호로 예정
경로를 확정하기 위해 같은 Issue 본문만 갱신한다. 성공 결과는 `action=publish_issue` 또는
`reuse_existing_issue`, `remote_write_authorized=true`, `issue_url`, `issue_number`를 포함한다.
생성 뒤 본문 갱신이 실패하면 번호와 URL을 보존한 `partial_publish_issue`로 보고한다.
Project 변경, branch, commit, push, PR merge 또는 ADR 파일 생성은 함께 하지 않는다.

## Issue 라벨 선택과 게시 후 확인

실제 Issue 게시가 승인된 작업에서는 담당자·개발 영역·작업 성격에 맞는 기존 라벨 적용까지
완료한다. 라벨은 대화 상대나 이슈 작성자가 아니라 해당 이슈의 실제 작업을 기준으로 고른다.

1. 저장소의 기존 라벨 이름과 설명을 조회하고 이슈 제목·본문·작업 범위를 대조한다.
   개발 영역은 실제 범위에 따라 `BE` 또는 `FE`, 작업 유형은 기능 추가·버그 수정·리팩토링·
   운영/문서 작업 등 이슈 성격에 맞는 기존 라벨을 선택한다. 모든 이슈에 `Feature`를 붙이지 않는다.
2. 담당자 라벨은 현재 요청에서 지정한 실제 개발자를 우선 확인하고, 지정이 없으면 해당
   이슈의 assignee와 명시된 담당 근거를 확인한다. 대화 사용자·이슈 작성자·과거 담당자를
   자동으로 작업자로 간주하지 않는다. 흑곰 담당 작업에는 확인된 `흑곰` 라벨을 쓰되,
   루덴스 등 다른 개발자 작업에는 그 담당자와 대응하는 기존 라벨을 확인해 사용한다.
3. 담당자나 GitHub 계정과 라벨의 대응 관계가 불명확하면 그 항목만 질문한다. 확인 가능한
   영역·작업 유형 선택과 승인된 독립 작업은 계속하며, 미확인 담당자를 임의 지정하지 않는다.
   담당자 라벨 요청만으로 GitHub assignee도 변경하지 않는다.
4. 초안에서는 선택할 라벨과 담당 근거를 본문 밖에 제시하고 원격 변경은 하지 않는다.
   게시 시에는 이번 요청으로 생성하거나 계약 표식으로 재사용한 정확한 Issue 번호에만
   선택한 기존 라벨을 추가한다. 예: `gh issue edit <번호> --repo OWNER/REPO --add-label '<라벨 목록>'`.
   새 라벨 생성, 기존 라벨 제거와 다른 Issue 변경은 별도 요청 없이 수행하지 않는다.
5. 게시 후 해당 Issue의 실제 라벨을 다시 조회해 선택한 값이 붙었는지 확인한다. Issue 생성은
   성공했지만 라벨 적용이 실패했다면 번호·URL과 미반영 라벨을 보고하고, 이미 생성한 Issue의
   라벨만 복구한다. 라벨 실패 때문에 Issue를 다시 만들지 않는다.

`harness/issue_planning.py --publish`는 현재 라벨을 자동 적용하지 않는다. 게시기의 성공 결과를
확인한 다음 위 절차로 적용·검증하며, 생성만 성공한 것을 라벨 적용 완료로 보고하지 않는다.

## 동작

```text
요청 권한 판정
→ 저장소·문서·코드 조사
→ 위험 분류
→ 저위험: 최소 계약
→ 고위험: 여섯 결정 근거 판정
  → 근거 충분: 인터뷰 생략
  → 근거 누락·충돌·현재 유효성 불명확: Deep Interview
→ Grill Me → Pass/Hold → ADR 판단
→ 결정적 검증기
→ 세 섹션 dry-run Issue 본문
→ 명시적으로 허용된 경우에만 GitHub Issue 게시
```

`Hold`이면 Issue 후보를 만들지 않고 누락 항목과 재개 조건을 보여준다. `Pass`이면
계약 식별자와 Issue 본문을 보여준다.

고위험 작업은 현재 맥락, 구체적인 문제 상황, 선택 필요성, 실제 대안, 최종 선택과 선택
이유마다 내용과 출처를 확인한다. 여섯 항목이 모두 명시돼 있고 자료가 서로 충돌하지
않으며 현재도 유효하면 인터뷰를 생략한다. 결과에는 `interview_status=skipped`와
`자료 충분으로 인터뷰 생략`을 표시한다. 하나라도 부족하면 해당 판단을 사용자에게 한
질문씩 확인하고 `interview_status=completed`가 된 뒤에만 진행한다.

Issue 본문은 다음 형태만 사용한다.

```markdown
## 구현 기능 설명

## TODO

## 메모
```

내부 snapshot의 범위, 실패·복구 흐름, 완료 조건과 검증 근거는 판정에 사용하되 본문에
그대로 펼치지 않는다. ADR이 필요하면 `구현 기능 설명`에 구체적인 문제 상황과 목표를
1~2문장으로 적고, `메모`에는 결정 한 줄과 예정 경로만 적는다. ADR이 필요하지 않으면
평소의 간단한 Issue로 남긴다.

snapshot은 저장소 밖의 OS 임시 파일에만 만들고 현재 사용자만 읽을 수 있게 제한한다.
판정 또는 ADR 생성이 끝나면 성공·실패와 관계없이 삭제한다. 인터뷰 원문과 비밀값은
snapshot이나 저장소 문서에 남기지 않는다.

## ADR 판단과 자산화

다음 세 조건을 모두 만족할 때만 ADR이 필요하다.

1. 팀이 실제로 검토한 현실적인 대안이 둘 이상이다.
2. 제품 정책, 아키텍처, 보안, 데이터 또는 공용 워크플로우에 장기간 영향을 준다.
3. 후속 Issue와 구현자가 같은 결정을 반복해서 참조한다.

AI는 대안이 없었다면 없다고 답하도록 안내한다. 한 번 더 현상 유지나 반대 방향의 검토
여부를 확인한 뒤에도 대안이 없으면 ADR을 만들지 않으며, 새 대안을 지어내지 않는다.

Issue 단계에서는 ADR 파일을 만들지 않는다. 실제 구현을 시작한 작업 브랜치에서 다음
명령으로 실제 Issue 번호를 확정해 `Proposed` 파일을 만든다.

```bash
python3 harness/materialize_adr.py <snapshot.json> \
  --issue-number <actual-issue-number> --implementation --pretty
```

`--implementation`은 Issue 기획 단계의 실수로 파일이 생기는 것을 막는 실행 단계
표시다. 새 Issue 번호가 정해지기 전에는 본문에
`backend/docs/adr/{ISSUE_NUMBER}-<slug>.md`만 표시하고 실제 파일을 만들지 않는다. 번호가 정해진
뒤 materializer가 `backend/docs/adr/<Issue 번호>-<slug>.md`를 만든다. 파일은 코드와 같은 PR에
포함하고 팀 리뷰 후에만 `Accepted`로 바꾼다. materializer는 commit, push, Issue 게시와
PR merge를 실행하지 않는다.

## 팀 공통 적용과 프론트엔드 하네스

Issue #167의 이 하네스는 저장소 공통 Issue 기획, 위험 분류와 ADR 생명주기를 담당한다.
Issue #165의 프론트엔드 공통 하네스는 `frontend/` 코드 구현·테스트·리뷰 규칙을 담당한다.

프론트엔드 작업에서는 다음 순서로 적용한다.

1. Issue 기획에는 루트 `AGENTS.md`, `CLAUDE.md`와 이 공통 하네스를 적용한다.
2. 구현에는 `frontend/` 아래의 더 구체적인 지침을 추가로 적용한다.
3. 하위 지침이 공통 Issue 안전 계약을 약화하면 자동 선택하지 않고 충돌을 보고한다.

## 적용 사례

- [회원가입 Issue·ADR 하네스 적용 사례](signup-issue-adr-walkthrough.md): 실제 요구사항
  대화와 ADR을 설명하기 위한 가상 추가 대화를 구분해 보여준다.

## 현재 범위

- Issue 생성 전 계약 검증
- 저위험·고위험 routing
- 근거 기반 Deep Interview 생략·수행 판정과 Grill Me
- ADR 필요 여부와 채택안
- 원격 쓰기 권한 판정
- 명시적 GitHub Issue 게시
- 안정적인 계약 식별자 기반 중복 검색·재사용·중복 감지
- 게시 직후 실제 Issue 번호로 ADR 예정 경로 확정
- 구현 브랜치의 안전하고 멱등적인 `Proposed` ADR 파일 생성
- GitHub Actions의 결정적 하네스 테스트

Codex는 `.agents/skills`, Claude Code는 `.claude/skills`에서 이 공통 계약과 스크립트를
참조한다. 도구별 지침은 실행 진입점만 제공하며 판단 규칙을 복사해 별도 정본으로 만들지
않는다.

Commit hook, AI PR gate와 추가 Merge gate는 포함하지 않는다. 하네스 전용 CI를 추가하되
기존 Governance와 Backend CI는 변경하지 않는다.
