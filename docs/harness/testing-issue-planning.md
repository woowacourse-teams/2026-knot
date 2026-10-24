# Issue·ADR 하네스 테스트 방법

이 문서는 기본 dry-run으로 하네스의 routing과 계약 판정을 확인하고, 명시적 publish
경로를 별도로 검증하는 절차다.

## 1. 자동 테스트

저장소 루트에서 실행한다.

```bash
python3 -m unittest discover harness/tests -p 'test_*.py' -v
```

성공하면 모든 테스트가 `OK`로 끝난다. 같은 명령은 `Issue Harness` GitHub Actions에서도
실행한다. 테스트는 다음을 확인한다.

- 저위험 작업은 Interview·Grill·ADR 없이 Pass한다.
- 고위험 작업은 완료된 인터뷰 또는 여섯 항목의 생략 근거가 있어야 Pass한다.
- 생략 근거가 누락·충돌했거나 현재 유효성이 불명확하면 Hold한다.
- 고위험 작업의 필수 계약이 비면 Hold한다.
- 완성된 고위험 계약은 ADR 예정 경로와 함께 Pass한다.
- Issue 본문은 기존 템플릿의 세 섹션만 포함한다.
- 실제 대안이 둘 미만이거나 확인되지 않으면 ADR을 요구할 수 없다.
- 생성·초안 요청 모두 기본 dry-run에서는 원격 쓰기를 허용하지 않는다.
- `--publish --repo OWNER/REPO`를 명시한 생성 요청만 GitHub Issue 생성을 허용한다.
- 같은 계약 표식의 Issue는 재사용하고, 둘 이상이면 추가 생성 없이 Hold한다.
- ADR 필요 Issue는 생성 직후 실제 번호로 본문 경로를 확정하고 부분 실패를 보존한다.
- 잘못된 문자열·목록 타입과 Markdown H2 삽입은 예외 없이 Hold한다.
- Hold 결과는 CLI 종료 코드 1을 반환한다.
- 같은 계약은 같은 `contract_id`를 만든다.
- 알 수 없는 위험 코드는 Hold한다.
- ADR materializer는 안전한 경로만 `Proposed`로 생성하고 같은 입력에 멱등적이다.

## 2. fixture dry-run

저위험 생성 요청을 확인한다.

```bash
python3 harness/issue_planning.py \
  harness/tests/fixtures/low-risk-create.json --pretty
```

예상 결과는 `status=pass`, `action=render_draft`,
`requested_action=publish_issue`, `publish_ready=true`와
`remote_write_authorized=false`다. 생성 의도와 계약 통과 여부를 보여줄 뿐 기본 실행은
GitHub 쓰기 권한을 부여하지 않는다.

미완성 고위험 요청을 확인한다.

```bash
python3 harness/issue_planning.py \
  harness/tests/fixtures/high-risk-hold.json --pretty
```

예상 결과는 `status=hold`, `action=report_hold`와 누락 필드 목록이다. CLI 종료 코드는 1이다.

완성된 고위험 요청을 확인한다.

```bash
python3 harness/issue_planning.py \
  harness/tests/fixtures/high-risk-create.json --pretty
```

예상 결과는 `status=pass`, `action=render_draft`,
`requested_action=publish_issue`다. 출력된 `issue_body`의 `메모`에는 ADR 결정 한 줄과 예정
경로 `backend/docs/adr/{ISSUE_NUMBER}-auth-account-linking.md`가 포함된다.
`adr_path_status=pending_issue_number`, `next_after_issue_created=finalize_adr_path`이며
`interview_status=skipped`, `interview_notice=자료 충분으로 인터뷰 생략`,
`remote_write_authorized=false`다. 저장소에 ADR 파일은 생성되지 않는다.

명시적으로 GitHub Issue 생성을 검증할 때는 owner/repo를 지정한다.

```bash
python3 harness/issue_planning.py \
  harness/tests/fixtures/low-risk-create.json \
  --publish --repo OWNER/REPO --pretty
```

성공 결과는 `status=pass`, `action=publish_issue` 또는 `reuse_existing_issue`,
`remote_write_authorized=true`, `issue_url`, `issue_number`를 포함한다.
이 명령은 실제 GitHub Issue를 생성하므로 테스트 저장소나 실제 생성이 허용된 상황에서만
실행한다. `draft`, `hold`, 누락된 `--repo`는 원격 호출 없이 멈춘다. 승인된 게시 단계의
검색·생성 실패는 권한과 성공을 구분해 보고하고, 생성 뒤 ADR 본문 갱신 실패는
`partial_publish_issue`와 실제 번호·URL을 보존한다.

## 3. ADR materializer 격리 테스트

실제 작업 트리를 건드리지 않으려면 임시 디렉터리를 사용한다.

```bash
work_dir="$(mktemp -d)"
python3 harness/materialize_adr.py \
  harness/tests/fixtures/high-risk-create.json \
  --repo-root "$work_dir" --issue-number 123 --implementation --pretty
```

`$work_dir/backend/docs/adr/123-auth-account-linking.md`가 `Proposed`로 생성돼야 한다. 실제 Issue
번호 없이 `--implementation`만 실행하면 `require_final_adr_path`로 Hold해야 한다. 같은
명령을 다시 실행하면 파일을 덮어쓰지 않고 `unchanged`를 반환해야 한다. 작업이 끝나면
생성한 임시 디렉터리만 삭제한다.

실제 Issue 계약을 시험할 때도 snapshot은 저장소가 아닌 OS 임시 파일에 쓰고 권한을
현재 사용자로 제한한다. 명령의 성공 여부와 관계없이 snapshot을 삭제하며 대화 원문과
비밀값은 넣지 않는다.

## 4. Codex 자연어 테스트

Codex에서 `2026-Knot` 폴더를 프로젝트로 연 뒤 다음 순서로 확인한다.

1. `오탈자 수정 Issue 초안 잡아줘`라고 요청한다.
2. `$knot-issue-planning`이 선택되고 질문 없이 Lightweight 초안을 만드는지 확인한다.
3. `로그인·회원가입 GitHub Issue 만들어줘. 실제 GitHub에는 올리지 마`라고 요청한다.
4. 여섯 결정 정보의 출처를 먼저 확인하고, 부족한 항목만 한 번에 하나씩 질문하는지
   확인한다.
5. 여섯 항목의 근거가 모두 있으면 질문 없이 생략 사실과 근거를 보여주는지 확인한다.
6. 중요한 정책이 비어 있을 때 `Hold`하는지 확인한다.
7. 대안이 없다고 답했을 때 한 번만 확인하고 ADR을 만들지 않는지 확인한다.
8. 실제 대안이 둘 이상이면 Grill 결과, ADR 판단과 세 섹션 dry-run 본문을 보여주는지
   확인한다.
9. 기본 dry-run 결과에서 `remote_write_authorized=false`인지 확인한다.
10. 사용자가 실제 생성을 허용한 경우에만 `--publish --repo OWNER/REPO`를 사용하고,
    성공 결과의 `issue_url`을 확인한다.

## 5. Claude Code 자연어 테스트

Claude Code에서 저장소를 연 뒤 `/skills`에 `knot-issue-planning`,
`knot-deep-interview`, `knot-grill-me`가 보이는지 확인한다. 실행 중인 세션에
`.claude/skills`를 처음 추가했다면 Claude Code를 한 번 다시 시작한다.

Codex 테스트와 같은 두 요청을 실행해 같은 위험 경로, Pass/Hold 기준, 세 섹션 본문과
ADR 생명주기를 따르는지 확인한다. 두 도구 모두 `harness/issue_planning.py`와
`harness/materialize_adr.py`를 사용해야 한다.

Claude Code 로그인 또는 실행 가능한 플랜이 없는 환경에서는 공통 실행기 테스트와
`.claude/skills` 어댑터 정합성까지만 검증하고, 실제 모델 E2E는 `미실행`으로 기록한다.
Claude Code를 사용할 수 있는 팀원이 같은 요청을 실행해 결과를 PR 리뷰에 남긴다. 실행할
수 없는 환경에서 모델 E2E를 통과했다고 기록하지 않는다.

프로젝트 스킬 위치와 탐색 방식은
[Claude Code Skills 공식 문서](https://code.claude.com/docs/en/skills), `CLAUDE.md`의
`AGENTS.md` import는
[Claude Code Memory 공식 문서](https://code.claude.com/docs/en/memory)를 기준으로 한다.

## 6. 안전 확인

테스트 전후에 다음 명령을 실행한다.

```bash
git status --short
git log -1 --oneline
```

기존 작업 파일 외에 예상한 하네스 파일만 변경됐는지 확인한다. 기본 dry-run 스킬과
검증기는 `gh issue create`, `gh issue edit`, Project 변경, `git commit`, `git push`,
PR merge를 실행하지 않는다. 명시적 publish 검증은 계약 표식 검색, 필요한 생성, ADR 경로
확정을 위한 동일 Issue 본문 갱신만 실행하며 Project나 git 작업을 함께 수행하지 않는다.

## 7. 새 Codex 세션에서 routing 확인

다음 명령은 별도의 일회성 Codex 세션을 read-only sandbox로 실행한다. 모델 호출이므로
토큰을 사용한다.

```bash
codex exec --ephemeral --sandbox read-only -C . \
  '오탈자 수정 Issue 초안 잡아줘. 실제 파일과 GitHub를 변경하지 마. 사용 스킬, 위험 등급, status, action만 보고해.'
```

`knot-issue-planning`이 선택되고 위험 등급이 `Low`로 나와야 한다. 결과의
`remote_write_authorized`는 기본 dry-run에서 `false`여야 한다. 필수 정보가 부족하면
`Hold`가 정상이다.

고위험 routing은 다음 명령으로 확인한다.

```bash
codex exec --ephemeral --sandbox read-only -C . \
  '로그인과 OAuth 기능을 GitHub Issue로 만들어줘. 실제 파일과 GitHub는 변경하지 마. 고위험이면 첫 질문 하나만 하고 멈춰.'
```

`knot-issue-planning` 다음에 `knot-deep-interview`가 선택되고, `security`, `external`,
`core-flow` 중 관련 위험 신호와 질문 하나가 표시돼야 한다.

## 수동 검증 또는 후속 검증 항목

- 실제 GitHub Issue 생성·재사용과 별도 권한의 Project 연결
- GitHub 인증 실패 뒤 재개
- 팀원별 Codex 버전과 자연어 implicit invocation 편차
- 팀원별 Claude Code 버전과 skill discovery 편차
- Claude Code를 사용할 수 없는 로컬 환경의 실제 모델 E2E

이 항목은 dry-run 결과를 팀이 검토한 뒤 가능한 환경에서 테스트하고, 실행 여부와 결과를
구분해 기록한다.
