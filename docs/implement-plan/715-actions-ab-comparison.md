# Backend CI Actions A/B 비교 실행 계획

## 1. 범위와 브랜치

- 상태: **계획 확정, Actions 실행 전**
- 확인일: 2026-10-17
- 이슈: [#715 — Backend CI 실행 시간 개선](https://github.com/woowacourse-teams/2026-knot/issues/715)
- 담당자: 흑곰(`jyt6640`), 라벨 `BE`·`Refactor`·`흑곰`
- 현재 브랜치: `be/refactor/#715`
- 목표: 개선안 1·2·4를 함께 적용했을 때 실제 Backend CI 피드백 시간이 줄어드는지 동일 제품 코드에서 확인한다.

이번 작업은 최종 변경의 커밋·push와 실행 계획 작성까지다. 비교용 브랜치·PR 생성, workflow 재실행, develop 추가 병합은 하지 않는다. 후속 Actions 실행이 승인되면 아래 순서대로 진행한다.

### 확정 기준선

| 항목 | A — 개선 전 | B — 개선 후 |
| --- | --- | --- |
| 공통 제품 코드 D | `0c0c4e64e9bcbf3c68a26c62bb42aa0aea4694dd` | 동일 |
| 스케줄러 수정 S | 적용 | 동일 |
| 리포트 보존 W | 적용 | 동일 |
| 개선안 1·2·4 | 미적용 | 적용 |
| 개선안 3·5 | 미적용 | 미적용 |
| 캐시 크기 / 테스트 JVM 최대 힙 | 8 / 1GB | 동일 |
| 고정 소스 tree | `82f44a9047f0ece2c92720432d7cba4a1a93ba2f` | `683b6d90249b60d3d60362af507b767971badfc7` |

B의 실행 관련 코드는 커밋 `29bf52bad846be410ef3e3d0e83613ef7e4583fc`에 반영했다. 개선안 5 제외는 `f1c653d75`, W는 `29bf52bad`다. 이후 문서 커밋은 실행 코드에 영향을 주지 않는다. 고정 tree와 현재 커밋 전체 tree는 실험 문서 포함 여부가 달라 다를 수 있으므로 실행 관련 경로의 blob을 대조한다.

S와 W는 전후 공통이다. 따라서 이번 비교는 **스케줄러 수정 효과가 아닌 개선안 1·2·4의 합산 효과**를 측정한다. 과거 CI 평균7분13초를 A 측정값 대신 사용하지 않는다.

### 확인한 근거

| 근거 | 관측과 적용 범위 |
| --- | --- |
| [최종 기준선 결과](../experiments/715-ci-final-baseline.md) | A/B의 실행 관련 차이는 개선안 1·2·4의 Java10파일. 운영 코드·스키마·빌드·공통 테스트 지원·workflow 동일 |
| [대상·격리 검증](../experiments/715-ci-final-isolation.md) | B의 정상20회171건·예상 부정1회 완료. 이번에 격리 실험을 추가 반복하지 않음 |
| [일반 전체 회귀](../experiments/715-ci-final-regression.md) | B에서 Spotless·단위1543·통합676·인수567·bootJar 통과. 총2786건, 명령308.778초. CI 성능 근거와 구분 |
| `.github/workflows/backend-ci.yml` | PR 또는 main/develop push에서 실행. workflow_dispatch 없음. ubuntu-latest·Java25·기존5개 검증 단계 |
| 공통 workflow SHA-256 | `b45e3e9c20499c1c8c6a4253052eb4d71d1decd13bba7e51e2f980ba0a03bf64`. 고정 A/B와 현재 코드 동일 |
| GitHub workflow API | 현재 대상 workflow ID `332475666`, 경로 `.github/workflows/backend-ci.yml`. 같은 이름의 과거 workflow와 혼동하지 않음 |
| 현재 원격 상태 | 작업 브랜치에 열린 PR 없음. `git pull --ff-only` Already up to date. fetch된 새 develop `292bee4a9`는 고정 D에 혼합하지 않음 |

제품 API·DB 계약을 변경하는 계획이 아니므로 새 제품 요구사항이나 migration을 정하지 않는다. 저장소·Issue·기존 실행 증거와 공식 Actions 문서를 근거로 한다.

## 2. 실행 흐름

```text
고정 D의 비교용 base 준비
→ A/B head의 실행 소스·공통 workflow 대조
→ A PR 생성·A1 종료 및 증거 보존
→ B PR 생성·B1 종료 및 증거 보존
→ B2 → A2 → A3 → B3 순서로 기존 run 재실행
→ 6개 attempt의 환경·캐시·실행 목록 확인
→ 통과 여부와 시간 집계를 별도로 판정
```

### 비교용 ref와 PR 준비 — 후속 작업

| 역할 | 제안 브랜치 | 내용 |
| --- | --- | --- |
| 공통 PR base | `be/chore/#715` | 고정 D 자체. 비교 중 이동하지 않음 |
| A head | `be/bugfix/#715` | D + 스케줄러 수정 S + 리포트 보존 W |
| B head | 기존 `be/refactor/#715` | 최종 B. 문서 차이는 허용하되 실행 관련 소스는 고정 B와 일치 |

세 이름은 저장소 `<area>/<type>/#<issue>` 형식을 따른다. 후속 준비 때 같은 이름의 다른 작업이 생겼는지 다시 확인하고, 기존 ref를 덮어쓰거나 force-push하지 않는다. A는 D에서 S와 W만 적용해 만들고 고정 A의 실행 경로와 대조한다. S 커밋은 `e456c44ff74f5625a2902730afded0726d1756cc`, W 커밋은 `29bf52bad`다.

두 PR은 동일한 고정 base를 사용한다. 최신 develop을 PR base로 사용하면 측정 도중 제품 코드가 바뀔 수 있으므로 이번 고정 D 비교에는 사용하지 않는다. 비교 PR은 develop 반영 PR과 구분하며 자동 병합·Issue 자동 종료를 설정하지 않는다. 후속 PR에도 `BE`·`Refactor`·`흑곰` 라벨, 담당자 `jyt6640`, 관련 이슈 #715와 비교 목적을 명시한다.

현재 checkout은 ref 옵션이 없어 `pull_request`의 merge ref를 실행한다. 따라서 head SHA만 기록해서는 부족하다. **base SHA·head SHA·merge/실제 checkout SHA·실행 관련 파일 hash**를 모두 기록하고, 그 merge 소스가 기대한 A 또는 B와 같은지 확인한다. [GitHub PR 이벤트 문서](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#pull_request)

PR 생성은 Actions를 즉시 시작할 수 있으므로 후속 실행 단계에서만 한다. A1이 종료된 뒤 B PR을 생성해 두 Backend CI가 겹치지 않게 한다. PR 생성으로 Governance·알림 등 기존 자동화도 동작할 수 있으며 기존 자동화는 유지한다.

### 실행 순서와 횟수

| 순서 | 표본 | 실행 방식 |
| --- | --- | --- |
| 1 | A1 | A PR 생성으로 시작한 Backend CI |
| 2 | B1 | A1 완료 후 B PR 생성으로 시작한 Backend CI |
| 3 | B2 | B1 run의 전체 재실행 |
| 4 | A2 | A1 run의 전체 재실행 |
| 5 | A3 | A1 run의 전체 재실행 |
| 6 | B3 | B1 run의 전체 재실행 |

총 **A3회+B3회=6회**다. 기존 run의 모든 job을 재실행하고 `--failed`·debug 모드는 사용하지 않는다. GitHub 재실행은 원래 이벤트의 GITHUB_SHA/GITHUB_REF를 유지하므로 run ID와 attempt 번호를 함께 식별자로 사용한다. 그동안 두 head와 base를 변경하지 않는다. [GitHub 재실행 문서](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/re-run-workflows-and-jobs)

## 3. 재사용할 코드와 수집 방법

새 운영 클래스·메서드·진단 agent는 추가하지 않는다. 양쪽에서 같은 Backend CI를 그대로 실행한다.

| 기존 위치 | 이번 역할 |
| --- | --- |
| `.github/workflows/backend-ci.yml` | 동일한 runner·Java/Gradle 설정과 검증5단계, 항상 리포트 업로드 |
| `backend/build.gradle` | unit/integration/acceptance 태그 분리·캐시8·힙1GB 유지 |
| `actions/checkout@v6` | 실제 checkout SHA를 로그에서 확인 |
| `gradle/actions/setup-gradle@v4` | 기존 캐시 정책 유지, 복원 로그·job summary 보존 |
| GitHub run/job API·CLI | run/attempt·SHA·큐 대기·job/step 시작 종료·성공/실패 수집 |
| XML·HTML artifact | 테스트 실행 수·실패·느린 클래스·캡처된 테스트 로그 대조 |

검증 명령은 양쪽 모두 기존 순서 그대로다.

```text
./gradlew spotlessCheck
./gradlew test
./gradlew integrationTest
./gradlew acceptanceTest
./gradlew bootJar
```

로컬 회귀에서 쓴 `--rerun-tasks --no-build-cache`를 한쪽 CI에만 추가하지 않는다. 테스트 태스크가 FROM-CACHE/UP-TO-DATE로 생략됐는지 실제 로그로 확인한다.

후속 수집은 `gh run view RUN_ID --attempt N --log`와 run/job API를 사용하고, `backend-test-reports-RUN_ID-ATTEMPT` 이름으로 해당 artifact를 내려받는다. 메타데이터 JSON·원본 로그·artifact 원본·추출 파일 hash를 `backend/output/ci-715/actions-ab/`의 표본별 폴더에 저장한다. 이 디렉터리와 새 수집기는 아직 만들거나 실행하지 않았다.

## 4. 보존 조건과 측정 범위

DB 스키마·마이그레이션·timeout·retry·SSL·스케줄러 주기·assertion·트랜잭션·롤백·정리 방식은 바꾸지 않는다. 실제 PostgreSQL·Flyway와 각 테스트의 기존 검증을 유지한다.

### 환경과 캐시

매 표본에서 다음 항목을 기록한다.

- run ID·attempt·PR 번호·이벤트·시각·base/head/checkout SHA
- workflow hash와 각 action의 실제 resolved SHA
- runner OS/image 버전·노출된 runner 정보, 실제 Java/Gradle 버전
- Gradle 캐시 복원 hit/miss·복원 key·read-only 여부·다운로드 상황
- 세 테스트 태스크의 실행/생략 상태, 새 XML·테스트 수·실패·오류·스킵
- DB 이미지 태그와 로그에 노출된 식별자. digest가 없으면 미확인으로 기록

같은 ubuntu-latest 라벨이 같은 물리 하드웨어를 뜻하지는 않는다. 캐시 hit라는 단어만으로 같은 상태라고 판단하지 않는다. setup-gradle은 PR에서 일반적으로 기존 캐시를 읽고 기본 브랜치에서 저장하며, job 간 의존성·빌드 상태 등을 복원할 수 있다. 실제 key와 로그로 확인한다. [setup-gradle v4 캐시 문서](https://github.com/gradle/actions/blob/v4/docs/setup-gradle.md#caching-build-state-between-jobs)

기본 브랜치의 새 CI로 복원 캐시가 달라지거나 이미지/Java/action 버전 차이가 생기면 그 결과도 보존한다. 비교 가능하지 않은 표본을 임의로 제거하거나 캐시를 삭제해 맞추지 않는다. 6회가 끝나면 비교 가능 여부부터 판정하며 조건이 맞지 않으면 개선율은 미확정으로 남긴다. 추가 실행이나 공통 계측 변경이 필요하면 별도 후속 범위로 정한다.

### 시간 지표

| 지표 | 정의와 용도 |
| --- | --- |
| 주 지표1 | `verify` job 시작~종료 경과 시간. setup·5개 검증·업로드·post 단계 포함 |
| 주 지표2 | 기존 검증5단계의 각 경과 시간. 포맷/단위/통합/인수/JAR로 분해 |
| 보조 | 검증5단계 시간의 합, workflow 경과 시간, 리포트 업로드와 setup/post 시간 |
| 별도 | 큐 대기. run 생성~job 시작의 간격으로 기록하며 호스트 대기 외 준비 시간도 포함할 수 있음 |
| 참고 | HTML/XML 클래스 시간·느린 클래스 순위. step/job 경과 시간과 합치지 않음 |

각 버전의 원시값3개·중앙값·최솟값~최댓값·성공/실패 수를 함께 제시한다. 비교 조건과 검증 gate를 모두 충족했을 때만 다음 식을 사용한다.

```text
감소 시간 = A 중앙값 - B 중앙값
감소율(%) = (A 중앙값 - B 중앙값) / A 중앙값 × 100
```

세 번씩의 작은 표본이므로 값이 줄어도 관찰한 실행 범위의 결과로 적는다. 범위 겹침·환경 차이·표본 간 방향 불일치가 크면 안정적인 개선이라고 확대 해석하지 않는다. 남은 인수/SSE/준비·정리 비용은 느린 클래스와 단계 수준으로 분류하고 새 최적화는 추가하지 않는다.

## 5. 검증 순서와 실패 처리

이번은 운영 기능 구현이 끝난 뒤 실제 CI를 비교하는 계획이다. 새 기능의 TDD나 로컬 전체3회씩을 추가하지 않는다. 후속 집계기가 필요하면 누락·실패·캐시 생략을 잘못 성공 처리하지 않는 작은 fixture를 먼저 확인한다.

| 검증 | 통과 기준 | 미충족 시 |
| --- | --- | --- |
| 소스 | 같은 D·S·W, 실행 경로 A/B 차이는 개선안1·2·4만 | 실행 전 중단·차이 기록 |
| workflow | 같은 경로/ID·workflow hash·5개 명령·권한·관측 조건 | 다른 workflow 결과와 혼합하지 않음 |
| 테스트 | 각 정상 실행에서 unit1543·integration676·acceptance567, 실패/오류/스킵0 | 원본 보존, 전체 비교 gate 미충족 |
| 목록 | 각 태스크 XML testcase 행의 이름과 중복 횟수까지 대조 | 표시 이름만 deduplicate하지 않음. 차이는 조사 후 기록 |
| 실제 실행 | 세 테스트 태스크 수행, 과거 리포트/캐시 결과로 대체되지 않음 | 실제 수행 시간 비교 미성립 |
| 패키징·포맷 | Spotless·bootJar 모두 성공 | 최종 검증 gate 미충족 |
| 증거 | 6개 attempt의 원본 로그·XML/HTML·메타데이터·업로드 상태 보존 | 증거 부족으로 표시 |
| 비교 조건 | 복원 캐시·runner/image/Java/Gradle/action 조건이 비교 가능 | 원시값만 보고, 개선율 미확정 |

테스트 실패가 발생하면 해당 run/attempt의 job/step metadata·전체 로그·실패 XML과 system-out/system-err를 먼저 보존한다. DB timeout·deadlock·assertion·외부 환경 오류를 구분해 기록하되 원인을 추측해 확정하지 않는다.

실패를 성공 표본으로 교체하거나 `--failed` 재실행으로 숨기지 않는다. 실패한 표본의 시간은 성공 표본처럼 성능 중앙값에 섞지 않는다. 계획한6회의 성공·실패 수와 검증 gate를 그대로 보고하고, 실패가 남으면 CI 개선 검증 완료로 표시하지 않는다. 수정이 필요해도 이 계획의 측정 도중 코드를 바꾸지 않는다.

공통 artifact 업로드가 실패하면 테스트 성공과 증거 보존 실패를 별도로 기록한다. 업로드된 자료에 없는 서버 상태나 DB digest를 확인했다고 쓰지 않는다.

## 6. 완료 기준과 다음 작업

이번 요청의 완료 기준은 **최종 변경 커밋·일반 push, 이슈 라벨/담당자 확인, 실행 계획 저장**이다. Actions·PR·비교용 ref는 현재 만들지 않는다.

후속 실행의 완료 기준은 다음과 같다.

1. 고정 base와 A/B의 실행 소스·workflow를 대조하고 실제 checkout SHA를 확보한다.
2. 예정 순서대로 Backend CI A3회/B3회를 직렬 실행하고 전 표본을 보존한다.
3. 검증 통과 여부와 비교 가능 여부를 먼저 판정한다.
4. 비교 가능한 경우 중앙값·범위·단계별 변화를 계산하고, 불가능하면 그 이유를 기록한다.
5. 실제 CI 성과와 로컬 대상 실험 효과를 구분해 별도 결과 문서를 작성한다.

결과표는 다음 형태로 채운다. **현재 측정값은 없다.**

| 표본 | run/attempt | checkout SHA | 캐시 조건 | verify 시간 | 통합/인수 시간 | 검증 결과 |
| --- | --- | --- | --- | --- | --- | --- |
| A1 | 예정 | 예정 | 예정 | 미측정 | 미측정 | 미실행 |
| B1 | 예정 | 예정 | 예정 | 미측정 | 미측정 | 미실행 |
| B2 | 예정 | 예정 | 예정 | 미측정 | 미측정 | 미실행 |
| A2 | 예정 | 예정 | 예정 | 미측정 | 미측정 | 미실행 |
| A3 | 예정 | 예정 | 예정 | 미측정 | 미측정 | 미실행 |
| B3 | 예정 | 예정 | 예정 | 미측정 | 미측정 | 미실행 |

이 비교가 끝나면 최종 성과 정리가 남는다. 개선안3·5 재실험이나 연결·시계 관측 확대를 마무리의 필수 작업으로 다시 추가하지 않는다.
