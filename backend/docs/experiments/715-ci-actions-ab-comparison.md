# Backend CI 개선안 1·2·4의 GitHub Actions A/B 비교 결과

- 측정일: 2026-10-17
- 이슈: [#715 — Backend CI 실행 시간 개선](https://github.com/woowacourse-teams/2026-knot/issues/715)
- 실행 계획: [Actions A/B 비교 실행 계획](../implement-plan/715-actions-ab-comparison.md)
- 현재 상태: **전후 각 3회와 원본 보존 대조 완료. 전체 job 중앙값은 423→418초로 관찰됐으나 안정적인 전체 CI 단축은 입증하지 못했다. 반복 초기화는 각 7건 감소했고 여섯 실행 모두 성공했다.**

## 1. 비교 목적과 코드 범위

호환 가능한 테스트 설정을 정렬한 개선안 1·2·4가 실제 GitHub Actions 전체 CI 시간에도 영향을 주는지 확인했다. 스케줄러 수정은 양쪽에 적용했으므로 이번 비교에서 스케줄러 수정 자체의 효과는 측정하지 않는다.

| 항목 | A — 개선 전 | B — 개선 후 |
| --- | --- | --- |
| 비교용 Draft PR | [#754](https://github.com/woowacourse-teams/2026-knot/pull/754) | [#756](https://github.com/woowacourse-teams/2026-knot/pull/756) |
| 공통 base | `be/chore/#715` | 동일 |
| 공통 제품 코드 D | `0c0c4e64e9bcbf3c68a26c62bb42aa0aea4694dd` | 동일 |
| head 브랜치 | `be/bugfix/#715` | `be/refactor/#715` |
| head SHA | `2b9f4c0e3f47308fec191aad0a6a30566eaa0dad` | `517546487c05f9289ea750d6b8abd578d59ae554` |
| 실제 checkout SHA | `00707328b976e19ad53ba04aa45a01e41226e077` | `f61a9e10e12416f232b3776827a7d2e658325d97` |
| 스케줄러 수정·리포트 보존 | 적용 | 동일 |
| 개선안 1·2·4 | 미적용 | 적용 |
| 개선안 3·5 | 미적용 | 미적용 |
| 컨텍스트 캐시 / 테스트 JVM 힙 | 8 / 1GB | 동일 |

두 PR과 이슈의 라벨은 `BE`·`Refactor`·`흑곰`, 담당자는 흑곰(`jyt6640`)으로 확인했다. 두 PR은 고정 base를 사용하는 비교용 Draft이며 develop 반영 PR과 구분한다.

A/B 실행 코드의 차이는 인증 저장소·문서 조회 JPA·snapshot 조회 테스트의 설정을 정렬한 Java 테스트 파일 10개다. 운영 코드·DB 스키마·빌드 설정·workflow·기존 assertion과 격리 방식은 같다. B의 문서 차이는 실행 코드 차이에서 분리해 대조했다.

PR head만 비교하지 않고 merge commit의 tree와 Checkout 단계의 실제 SHA를 함께 확인했다. A는 고정 A tree와 일치하고, B의 실행 경로는 고정 B tree와 일치한다. 비교 중 base/head를 이동하거나 새 develop을 섞지 않았다.

## 2. 실행 조건과 측정 방법

대상은 이름이 같은 과거 workflow가 아닌 `.github/workflows/backend-ci.yml`, workflow ID `332475666`이다. 양쪽 workflow SHA-256은 `b45e3e9c20499c1c8c6a4253052eb4d71d1decd13bba7e51e2f980ba0a03bf64`다.

실행 순서는 **A1 → B1 → B2 → A2 → A3 → B3**다. 첫 실행은 각 PR 생성으로 시작했고, 이후 동일 run의 전체 workflow를 재실행했다. run ID 두 개의 attempt 1·2·3을 사용하므로 각 표본은 **run ID + attempt 번호**로 구분한다. 실패한 표본을 다른 성공 실행으로 교체하지 않는다.

| 조건 | 실제 관측 |
| --- | --- |
| runner 라벨 / 이미지 | `ubuntu-latest` / `ubuntu-24.04`, 이미지 버전 `20261012.334.1` |
| runner 소프트웨어 | `2.337.0` |
| Java / Gradle | Temurin `25.0.4+1` / Wrapper `9.5.1` |
| Java / Gradle setup 단계 시간 | Java 0~1초·Gradle 2~6초. 이 단계 외 컴파일·의존성 해석 비용까지 포함한 수치는 아님 |
| Gradle 캐시 | 읽기 전용, 같은 restore key와 5개 보조 캐시 key 복원 |
| 캐시 출처 | `gradle-home-v1\|Linux-X64\|verify[d50a68fdff518f324334bc568263f980]-292bee4a9d54047194750b41f38ef4258f6f2a79` |
| 검증 명령 | `spotlessCheck` → `test` → `integrationTest` → `acceptanceTest` → `bootJar` |
| DB | 기존 실제 PostgreSQL `pgvector/pgvector:pg18`·Flyway 유지 |
| 관측 변경 | CI에 별도 agent·연결 관측·TRACE 로깅·재실행 옵션 추가 없음 |

캐시 key의 `292bee4a9…`는 복원 캐시의 출처이며 테스트한 제품 코드 D를 변경했다는 뜻이 아니다. 로그의 `Restored cache entry with key`에 표시된 요청 key와 실제 `Cache hit for restore-key`를 구분했다.

주 지표는 `verify` job의 시작~종료 시간이다. setup·검증 5단계·리포트 업로드·post 단계를 포함하며 큐 대기는 제외한다. 단계별 시간은 GitHub job API의 시작·종료 시각으로 계산해 초 단위로 기록했다. XML 클래스 시간이나 로컬 Gradle 명령 시간과 합치지 않는다.

## 3. 실행 시간 원시값

아래 값의 단위는 초다. 모든 표본을 예정 순서대로 남겼다.

| 표본·실행 링크 | 포맷 | 단위 | 통합 | 인수 | JAR | verify 전체 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| [A1 · attempt 1](https://github.com/woowacourse-teams/2026-knot/actions/runs/39024700614/attempts/1) | 14 | 39 | 238 | 118 | 2 | 423 |
| [B1 · attempt 1](https://github.com/woowacourse-teams/2026-knot/actions/runs/39025180206/attempts/1) | 16 | 47 | 209 | 120 | 2 | 406 |
| [B2 · attempt 2](https://github.com/woowacourse-teams/2026-knot/actions/runs/39025180206/attempts/2) | 15 | 47 | 221 | 121 | 2 | 418 |
| [A2 · attempt 2](https://github.com/woowacourse-teams/2026-knot/actions/runs/39024700614/attempts/2) | 11 | 32 | 196 | 96 | 2 | 351 |
| [A3 · attempt 3](https://github.com/woowacourse-teams/2026-knot/actions/runs/39024700614/attempts/3) | 16 | 44 | 235 | 123 | 2 | 438 |
| [B3 · attempt 3](https://github.com/woowacourse-teams/2026-knot/actions/runs/39025180206/attempts/3) | 14 | 44 | 220 | 129 | 2 | 420 |

### 중앙값과 범위

| 시간 지표 | A — 개선 전 | B — 개선 후 | 관측 차이 |
| --- | --- | --- | --- |
| verify 원시값 | 423·351·438초 | 406·418·420초 | 각 3회 모두 포함 |
| verify 중앙값 | **423초 — 7분 3초** | **418초 — 6분 58초** | **5초 감소, 약 1.2%** |
| verify 범위 | 351~438초 — 5분 51초~7분 18초 | 406~420초 — 6분 46초~7분 | 범위 겹침 |
| 검증 5단계 합의 중앙값 | 411초 | 406초 | 5초 감소 |
| workflow 경과 시간 중앙값 | 427초 | 423초 | API run_started_at~updated_at 기준 |

| 단계 | A 중앙값 | B 중앙값 | B−A |
| --- | ---: | ---: | ---: |
| 포맷 검사 | 14초 | 15초 | +1초 |
| 단위 테스트·컴파일 | 39초 | 47초 | +8초 |
| 통합 테스트 | 235초 | 220초 | −15초, 약 6.4% |
| 인수 테스트 | 118초 | 121초 | +3초 |
| JAR 생성 | 2초 | 2초 | 0초 |

각 단계 중앙값을 더한 값과 실행별 단계 합의 중앙값은 서로 다른 통계량이다. 큐 대기는 3~5초였으며 verify 시간에 포함하지 않았다. workflow 경과 시간에는 큐 대기 외에도 실행 종료 후 상태 반영 등이 포함될 수 있으므로 verify와의 차이를 전부 큐 시간으로 해석하지 않는다.

전체 중앙값 감소율 약 1.2%와 통합 단계 약 6.4%는 이번 표본의 기술 통계다. 가장 빠른 A2도 제외하지 않았다. A2는 B 세 실행 모두보다 빨랐고 범위가 크게 겹치므로 코드 변경이 전체 CI를 안정적으로 단축했다고 확정하지 않는다.

## 4. 기존 테스트와 실제 실행 확인

**여섯 실행 모두 Spotless·단위 1,543개·통합 676개·인수 567개·bootJar가 통과했다.** 매 실행 2,786개, 누적 testcase 행 16,716개이며 실패·오류·스킵은 0개다. HTML 요약 18개와 XML 1,866개를 보존했다.

캐시 적중만으로 테스트 수행을 판단하지 않았다. 실제 Gradle 로그에서 세 테스트 태스크가 `FROM-CACHE`·`UP-TO-DATE`·`SKIPPED`로 생략되지 않고 수행된 것을 확인하고 새 XML의 testcase 행 수를 대조했다. 단위 단계에는 운영·테스트 코드 컴파일도 포함된다.

### 표시 이름에 포함된 JVM 주소 처리

원본 XML 이름을 그대로 대조하면 `SearchInvariantExceptionTest`의 파라미터 테스트 10건이 서로 다르다. 이름에 `$$Lambda/0x…@…` 형태의 JVM별 주소가 포함되기 때문이다. 해당 클래스는 A/B에서 소스 SHA-256이 `1dfc76c8f8033ff529acb707f3e62c5e80974eb5dfccf30cfe5c8c2892e3b4c0`으로 같다.

원본 이름과 hash를 보존하면서 이 클래스의 정확한 10개 이름 형식에 한해서 주소 부분만 정규화했다. 클래스·호출 번호·기대 오류 코드·중복 횟수는 유지했다. 다른 이름이나 예상하지 않은 형식은 그대로 비교한다. 주소 정규화 후에도 호출 번호나 오류 코드가 바뀌면 불일치하도록 fixture로 확인했다. 원본 문자열이 완전히 같았다고 보고하지 않는다.

집계기 사전 검증 4개가 통과했다. 중복 표시 이름의 개수 유지, 정확한 태스크 이름 구분, 실패·누락·스킵·캐시 생략의 성공 오판 방지, 제한된 JVM 주소 정규화를 확인했다.

기존 대상·격리 검증은 [최종 B 집중 검증 결과](715-ci-final-isolation.md)를 근거로 한다. 정상 20회·171건과 스케줄러 오염 부정 사례 1회를 이미 확인했다. 이번 CI에서는 오염을 다시 주입하지 않고 일반 전체 회귀를 비교한다. 일반 회귀 성공을 모든 실행 순서·공유 조합에 대한 격리 증명으로 확대하지 않는다.

## 5. 반복 초기화 감소

전후 각 3회 모두 통합 단계의 Spring 시작 완료·PostgreSQL 시작 완료·Flyway 적용 완료 로그가 아래와 같이 각각 7건 줄었다.

| 통합 단계 로그 | A | B | 차이 |
| --- | ---: | ---: | ---: |
| Spring 시작 완료 | 51 | 44 | 7건 감소 |
| PostgreSQL 시작 완료 | 59 | 52 | 7건 감소 |
| Flyway 적용 완료 | 119 | 112 | 7건 감소 |

인수 단계는 각 로그가 15건으로 같았다. 위 수치는 로그 발생 횟수이며 서로 다른 컨텍스트 설정 수나 동시에 떠 있는 컨테이너 수로 바꾸어 해석하지 않는다. Flyway의 의도적인 migration 검증도 그대로 포함한다.

Spring 시작 시간에는 DB·Flyway 준비가 포함될 수 있다. 따라서 세 종류의 시간을 단순 합산해 절약한 시간을 계산하지 않는다.

## 6. 시간 결과의 해석과 남은 비용

같은 A 소스에서 A1 423초·A2 351초·A3 438초가 관찰됐다. 코드가 바뀌지 않은 인수 단계도 118·96·123초로 변했다. 이 변동은 한 번의 전후 차이만으로 코드 효과를 확정하기 어렵다는 근거다.

A2의 runner 지역은 `centralus`, A3는 `westus3`, A1·B1·B2·B3는 `eastus`였다. 이미지·Java·캐시가 같아도 같은 물리 호스트가 아니며 CPU·I/O·네트워크 자원은 이번 workflow에서 직접 계측하지 않았다. 지역 차이가 시간 차이의 원인이라고 확정하거나 A2를 사후 제외하지 않는다. 표본은 전후 각 3회다.

### 초기화와 테스트 수행 비용의 구분

| 관측 근거 | 확인한 내용 | 해석 범위 |
| --- | --- | --- |
| A1 `AuthNicknameCompletionIntegrationTest` | 클래스 22.822초, Spring 시작 21.729초, testcase 시간 합 약 0.390초 | 첫 클래스의 준비 비용이 큼. 세 시간을 더하지 않음 |
| A1 `SearchFollowupQuestionAcceptanceTest` | 클래스 20.106초, 일부 testcase 약 3.8~4.0초 | 기존 SSE 작업 종료 대기·실제 HTTP 검증 포함. 각 작업의 비용 분리는 미계측 |
| A1 `SearchAnswerEventsAcceptanceTest` | heartbeat·timeout testcase 8.994초 | 코드에서 연결을 세 번 열어 정상 종료·상태 보존을 검증. 반복 자체를 불필요한 비용으로 판단하지 않음 |

R4의 남은 비용 분류는 위 단계·클래스 수준까지 수행한다. 새로운 SSE 최적화나 DB 연결 진단을 이번 비교에 추가하지 않는다. 클래스 Duration을 테스트 본문 시간으로 간주하지 않으며 HTML Duration 합을 CI job 시간 대신 사용하지 않는다.

과거 CI 평균 7분 13초나 로컬 대상 그룹의 33.8%·32.0%·50.7%를 이번 A 측정값 대신 사용하지 않는다. 그룹별 감소율을 합산하지 않는다. 이번 비교는 스케줄러 수정 공통 적용 후 개선안 1·2·4를 함께 적용한 결과다.

## 7. 로그·리포트 보존

원본 경로는 `backend/output/ci-715/actions-ab/`다. 각 `A1`·`B1`·`B2`·`A2`·`A3`·`B3` 폴더에서 다음을 구분해 보존한다.

- `run.json`·`jobs.json`·`pr.json`: attempt·상태·단계 시각·runner·head/base 메타데이터
- `merge-commit.json`: 실제 checkout과 대조한 merge commit/tree
- `logs.zip`·`logs/`: 전체 workflow 로그 원본과 추출본
- `artifacts.json`·`reports.zip`·`reports/`: 업로드 artifact 메타데이터·XML/HTML 원본
- `sha256.json`: 수집 직후 원본 파일 hash
- `analysis.json`: testcase 목록·중복 횟수·초기화 로그·환경·시간 집계

상위 `manifest.json`·`state.json`에 소스·실행 순서·run/attempt를 기록하고, 상위 `analysis.json`·`audit.json`에서 집계와 보존 대조 결과를 확인한다. 보존 대조는 **41/41 통과**했다. 표본당 원본 파일 1,898개, 총 11,388개의 hash가 같고 로그·리포트 ZIP 총 12개의 CRC가 통과했다. 리포트 artifact 6개를 모두 내려받았다. 기존 사용자 변경 4개와 고정 원격 ref 3개도 대조했다.

이번 여섯 실행에는 테스트 실패가 없었다. 정상 실행에서도 stderr·XML·전체 로그를 보존했으며, 과거 연결 timeout의 해결이나 미재현 원인을 이번 성공으로 확정하지 않는다. 실패 표본을 버리고 성공 실행으로 교체한 경우는 없다.

DB 이미지 tag는 확인했지만 immutable digest는 이번 로그에서 확인하지 못했다. 서버 내부 상태나 호스트 패킷을 수집했다고 주장하지 않는다.

## 8. 최종 판정

**개선안 1·2·4와 스케줄러 수정의 채택 범위를 유지한다. 개선안 3·5는 보류한다.** 호환 가능한 설정을 정렬한 결과, 기존 검증을 유지하면서 통합 단계의 초기화 로그를 각각 7건 줄인 효과가 실제 Actions에서도 전후 각 3회 반복됐다. 스케줄러 수정은 양쪽 공통이므로 이번 비교에서 그 성능 효과를 분리하지 않았다.

전체 job 중앙값은 7분 3초에서 6분 58초로 약 1.2% 감소했지만, 전후 범위가 겹치고 동일 소스에서도 실행 변동이 컸다. 따라서 **반복 초기화 감소와 6/6회 회귀 성공은 확인했지만 안정적인 전체 CI 시간 개선은 미입증**으로 기록한다. 통합 단계 중앙값 감소만으로 전체 CI의 개선율을 바꾸어 표시하지 않는다.

이번 비교와 결과 문서 작업은 완료했다. 두 PR은 비교용 Draft로 유지하며 develop 병합이나 이슈 종료는 수행하지 않았다. 새로운 전체 실행·SSE 최적화·연결 진단 반복은 이번 마무리에 추가하지 않는다. 결과 문서는 비교 중 고정한 원격 head를 이동시키지 않도록 로컬에 작성했다.

절차상 한계: 실제 Actions·원본 대조와 별개로 `npx ph workflow finish implement`는 기존 환경의 `source-read-runtime-unavailable`로 exit 1이었다. Persona 공식 종료 인증은 미완료다. 기존 환경·이력을 재생성하거나 무관한 검증을 반복하지 않았다.
