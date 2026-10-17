# Backend CI 최종 변경 범위와 비교 기준선 확정 계획

> 후속 전달: 개선안5 제외 `f1c653d75`·W `29bf52bad` 커밋/push 완료. 고정B와 실행 경로 일치. 다음 [Actions A/B 실행 계획](715-actions-ab-comparison.md)을 별도로 작성했으며 Actions는 시작하지 않았다. 아래의 미커밋·미실행 표시는 해당 단계 당시 기록이다.

> 2026-10-17 실행 기록: 1단계의 개선안 5 제외·develop D 병합·공통 W와 A/B 소스 고정을 완료했다. 별도 [결과 문서](../experiments/715-ci-final-baseline.md)에 실제 SHA·정적 검증·미실행 범위를 기록했다. 아래는 승인 당시 계획이며 대상·전체 테스트와 Actions는 아직 실행하지 않았다.

> 후속 2단계 완료: 같은 B에서 [대상 테스트와 격리 검증](../experiments/715-ci-final-isolation.md)을 실행했다. 정상 20회·171건 통과, 스케줄러 정상3·오염1 확인. 이어 [일반 전체 회귀](../experiments/715-ci-final-regression.md) 1회도 Spotless·2786건·bootJar 모두 통과했다. 아래 미실행 표시는 승인 당시 기록이며 Actions는 미실행이다.

## 1. 범위와 브랜치

- 상태: **개선안 5의 이번 반영 보류 확정. 최종 코드 정리·develop 병합·검증은 실행 전**
- 확인일: 2026-10-17
- 이슈: [#715 — Backend CI 실행 시간 개선](https://github.com/woowacourse-teams/2026-knot/issues/715)
- 작업 브랜치·HEAD: `be/refactor/#715`, `1b7d001ecb29d9021659e270655d3bd364da8803`
- 확인한 develop: `0c0c4e64e9bcbf3c68a26c62bb42aa0aea4694dd`

**최종 반영 대상은 개선안 1·2·4와 스케줄러 테스트 수정이다. 개선안 3·5는 보류하고, 연결·시계 진단은 기존 결과와 한계를 기록한 상태로 종료한다.**

사용자가 확정한 것은 이번 변경의 채택 범위다. 현재 코드에는 개선안 5가 아직 포함돼 있으며, 이번 계획 작성에서 코드를 되돌리거나 develop을 병합하지 않았다.

### 확인한 저장소 근거

| 근거 | 확인한 사실 | 구분 |
| --- | --- | --- |
| 사용자 결정 | 1·2·4와 스케줄러 채택, 5 이번 보류 | 확정 요구 |
| Issue #715 | 검증 범위·격리 유지, 같은 조건의 전후 각 3회 이상 비교 | 이슈 요구 |
| 현재 브랜치 이력 | 채택 코드 4개 커밋과 개선안 5 커밋이 분리돼 있음 | 코드·이력 관측 |
| `git pull --ff-only` | 현재 브랜치 upstream 기준 Already up to date. develop 최신 ref fetch | 실제 실행 |
| HEAD와 develop 비교 | 브랜치 전용 10커밋, develop 전용 146커밋 | 이력 관측 |
| 공통 조상 이후 develop의 BE·CI 경로 | 관련 91커밋·변경 경로 180개, 이번 개선 대상 15개 Java 파일과 직접 겹치는 경로 0개 | 정적 비교. 병합 성공·동작 호환 보장은 아님 |
| workflow·Gradle·테스트 공통 설정 | 공통 조상과 develop에서 Backend CI, build.gradle, wrapper 설정, TestcontainersConfiguration, TestApplicationProperties의 blob 동일 | 코드 관측 |
| [시계 판정 검증 결과](../experiments/715-db-connection-clock.md) | fixture 15개·C1~C3 재생 완료, 원본 보존. A3 timeout 원인 미확정 | 기존 실험 근거 |

제품 API·도메인·스키마 계약을 새로 정하는 작업이 아니므로 운영 Notion 제품 원문을 새 계약으로 사용하지 않는다. 관련 이슈·backend/test 지침·실제 코드와 기존 실험 문서를 근거로 한다. 계획은 `implementation-plan-coach`의 공통 형식에 따라 작성했다.

### develop 반영 범위

**실제 정리 시점에 고정한 develop 전체를 공통 제품 기준선 D로 사용한다.** 일부 제품 커밋만 골라 전후 버전에 다르게 반영하지 않는다. 현재 확인 SHA는 위와 같고, 실행 전에 다시 fetch해 바뀌었다면 새 D를 기록한다. 비교를 시작한 뒤에는 D를 고정한다.

- develop에 이미 병합된 BE·FE·migration 변경을 같은 기준으로 반영한다. 최종 CI 개선 diff는 그 D 위에서 분리한다.
- develop에는 테스트 추가·변경과 `V52__remove_audio_deletion_confirmation_wait.sql`도 있다. 전후는 같은 migration·테스트 소스를 사용한다. 해당 migration이 실제 운영 DB에 적용됐는지는 이번 코드 조사로 확인하지 않았다.
- 현재 열린 PR 코드는 D에 미리 넣지 않는다. #733은 DocumentDetailQueryIntegrationTest, #737은 TranscriptSegmentRepositoryIntegrationTest와 겹치며, #744는 전역 모델·저장소·인프라 패키지 정리다. 병합되면 import·파일 위치·테스트 수를 다시 대조한다.
- 비교 도중 관련 PR이 병합돼 D를 갱신해야 하면 양쪽 버전을 함께 다시 구성한다. 다른 D의 결과를 같은 비교 묶음으로 섞지 않는다.

관련 열린 PR: [#733](https://github.com/woowacourse-teams/2026-knot/pull/733), [#737](https://github.com/woowacourse-teams/2026-knot/pull/737), [#744](https://github.com/woowacourse-teams/2026-knot/pull/744). 확인 당시 미병합 상태이며 팀 승인·향후 병합 순서로 해석하지 않는다.

## 2. 최종 정리 흐름과 비교 기준선

```text
기존 변경·실험 자료 보존
→ 개선안 5 커밋의 변경만 되돌림
→ 고정 develop D를 현재 브랜치에 병합하고 관련 설정 대조
→ 채택 1·2·4와 스케줄러 수정만 남았는지 확인
→ 같은 D에서 비교 전 A / 비교 후 B 구성
→ 대상 격리·일반 회귀 확인
→ 같은 CI 조건에서 A/B 각 3회 측정
→ 실제 전체 CI 효과와 한계 기록
```

### 성능 비교의 주 기준

| 구성 | 비교 전 A | 비교 후 B |
| --- | --- | --- |
| 제품 기준선 | 같은 develop D | 같은 develop D |
| 스케줄러 수정 S | 적용 | 동일하게 적용 |
| 공통 리포트 보존 W | 적용 | 동일하게 적용 |
| 개선안 1·2·4 | 미적용 | **적용** |
| 개선안 3·5 | 미적용 | 미적용 |
| 캐시·힙·DB·테스트 목록 | 같은 조건 | 같은 조건 |

즉 **A = D + S + W**, **B = D + S + W + 개선안 1·2·4**로 구성한다.

스케줄러 수정은 안정성 보완으로 양쪽에 공통 적용한다. A/B에서 구하는 개선율은 **1·2·4의 합친 효과**이며 스케줄러 수정 자체의 시간 효과를 포함했다고 주장하지 않는다. 스케줄러의 채택 근거는 기존 반복·오염 부정 사례와 최종 회귀로 따로 설명한다.

과거 평균 7분 13초는 문제 배경이다. 과거 커밋·runner·캐시·제품 범위가 다른 실행을 새 B의 직접 대조군으로 사용하지 않는다.

### 공통 리포트 보존 W — 구현 제안

현재 Backend CI에는 XML·HTML 업로드 단계가 없다. 실제 CI의 테스트 목록과 실패 자료를 보존하기 위해 전후 양쪽에 동일한 최소 업로드 단계를 제안한다.

- 기존 포맷·단위·통합·인수·JAR 명령, 실행 순서, runner, Gradle 캐시, 실패 알림을 유지한다.
- 실패 시에도 생성된 `backend/build/test-results/`와 `backend/build/reports/tests/`를 수집한다. 실행 ID·attempt로 구분해 보존한다.
- 공통 리포트 단계의 workflow 내용과 SHA를 양쪽에서 동일하게 맞춘다. 이는 성능 개선안이 아닌 비교 자료 보존 장치다.
- 기존 5단계 시간과 리포트 업로드 시간을 분리해 기록한다. workflow 전체 시간에는 공통 업로드 비용도 포함됨을 명시한다.

W는 아직 추가하지 않았다. 비교용 ref·PR 생성·push·원격 CI 실행도 후속 실행 범위이며 이번에는 수행하지 않는다.

## 3. 최종 코드 범위와 정리 방법

### 채택할 변경

| 대상 | 기존 커밋 | 최종 유지 내용 | Java 변경 파일 수 |
| --- | --- | --- | ---: |
| 개선안 1 | `0fb869689` | AuthSessionRepositoryTest에 ConsumedRefreshTokenRepositoryImpl을 함께 import | 1 |
| 개선안 2 | `4ff206f6a` | 문서 조회 4클래스에서 같은 조회 어댑터 4개와 Testcontainers 설정 import | 4 |
| 개선안 4 | `16ac9dfe0` | snapshot 5클래스의 동일한 type-level spy 구성·주입 유지 | 5 |
| 스케줄러 S | `e456c44ff` | 최초 TRUNCATE를 assertFreshDatabase()로 바꾸고 8개 테이블의 빈 상태 검증 | 1 |
| **합계** | | 현재 파일 구조 기준 채택 Java diff | **11** |

파일 이동이 develop에 새로 반영되면 개수·경로를 다시 확인한다. 문서와 W는 위 Java 11파일과 별도다.

### 실제 클래스와 책임

| 그룹 | 클래스·경로 | 유지할 검증·책임 |
| --- | --- | --- |
| 인증 | `auth/infrastructure/AuthSessionRepositoryTest`, `ConsumedRefreshTokenRepositoryTest` | 저장·조회·중복·FK·만료 제약, 실제 PG·트랜잭션 롤백 |
| 문서 조회 | `document/infrastructure/DocumentConfirmationQueryIntegrationTest`, `DocumentDetailQueryIntegrationTest`, `DocumentGenerationJobListQueryIntegrationTest`, `DocumentListQueryIntegrationTest` | 확인·상세·작업 목록·문서 목록의 조회 검증 |
| 문서 snapshot | `document/application/DocumentConfirmationSnapshotIntegrationTest`, `DocumentListSnapshotIntegrationTest`, `DocumentTranscriptSnapshotIntegrationTest` | 실제 트랜잭션·동시 변경에도 응답의 snapshot 일관성 |
| 탐색 snapshot | `search/application/SearchConversationListSnapshotIntegrationTest`, `SearchMessageListSnapshotIntegrationTest` | 대화·메시지 조회의 snapshot 일관성 |
| 스케줄러 | `document/infrastructure/DocumentGenerationSchedulerIntegrationTest` | 실제 100ms 스케줄링, 완료 10초 한도, 트랜잭션 밖 외부 처리, 기존 결과 assertion·종료 격리 |

공통 경로는 `backend/src/test/java/com/knot/backend/`다. 새 API·운영 메서드는 추가하지 않는다. 스케줄러의 기존 private `assertFreshDatabase()`와 공유 설정만 유지한다.

### 개선안 5 제외

보류 대상은 `f9fa5058f4bb02b9a2acd6bc0e1c9ad0567ebea0`의 import 변경이다. **이 커밋의 역변경만 적용**한다. 파일 전체를 오래된 버전으로 복원하지 않는다.

- 문서 조회 4개는 개선안 2의 조회 어댑터 4개 공유 구성을 유지한다.
- DocumentCommandRepositoryIntegrationTest, DocumentGenerationJobRetryRepositoryIntegrationTest, DocumentTranscriptQueryIntegrationTest, TranscriptSegmentRepositoryIntegrationTest는 개선안 5 이전의 개별 import 구성으로 돌린다.
- 스케줄러 수정은 별도 커밋이므로 유지한다. 실제 정리 시 파일별 diff로 확인한다.
- 개선안 5와 timeout·시계 실험의 문서·로그는 보류 판단의 근거로 보존한다. 코드 제외를 위해 과거 결과를 삭제하거나 성공 결과로 바꾸지 않는다.

현재 경로에서 develop과 직접 겹치는 변경은 없지만, 병합 결과를 확인하기 전 무충돌·동작 호환을 확정하지 않는다. 원격에 공유된 이력을 reset·재작성하는 대신 기존 브랜치에서 범위를 되돌리고 develop 병합을 기록하는 방식을 제안한다.

## 4. 공통 설정과 보존 조건

이번 CI 개선으로 추가할 DB migration은 없다. D에 포함된 migration을 양쪽에서 동일하게 사용한다.

| 조건 | 최종 기준 |
| --- | --- |
| Spring 컨텍스트 캐시 | 최대 8 |
| 테스트 JVM 최대 힙 | 1GB |
| PostgreSQL | 기존 `pgvector/pgvector:pg18`, 실제 사용 digest 기록 |
| 실제 DB·Flyway | 양쪽 유지 |
| JPA 트랜잭션·롤백·flush·SQL/제약 검증 | 유지 |
| snapshot 준비·정리·동시 쓰기·spy reset | 기존 검증 보존 및 종료 확인 |
| 스케줄러 생명주기 | 100ms 주기·기존 timeout·DirtiesContext·전용 DB 폐기 유지 |
| 테스트 목록 | D 반영 후 A/B의 클래스·메서드 목록을 XML로 비교 |
| 진단 장치 | 과거 Java agent·TCP·추가 서버 로그 관측을 최종 성능 실행에 넣지 않음 |

과거 649개 통합·1,425개 단위·561개 인수 테스트와 39/47/117 초기화 횟수는 당시 기준의 결과다. 최신 제품 변경과 개선안 5 제외 후의 성공 기준 숫자로 재사용하지 않는다.

관측을 사용하는 집중 격리 검증은 별도 실행으로 남긴다. 관측 JAR·init script·TRACE 로깅이 없는 일반 회귀·CI 성능 실행과 시간을 섞지 않는다. 새 비교 자료는 별도 경로에 저장하고 기존 C1~C3·A3 실패·개선안 5 원본은 보존한다.

## 5. 정리와 최종 검증 순서

설정 정리 작업이므로 형식적인 새 테스트를 만들지 않고, 기존 실패·제약·동시성 검증을 유지해 검증한다. 아직 아래 실행은 수행하지 않았다.

### 1단계 — 범위 정리와 정적 대조

1. 사용자 변경·후보·실험 원본을 구분한다. 작업 브랜치와 D의 SHA를 기록한다.
2. 개선안 5의 import 변경만 되돌리고 develop D를 병합한다.
3. D 대비 Java diff가 채택 11파일의 설정 정렬·스케줄러 초기 상태 검증에 한정되는지 확인한다.
4. 테스트 본문·assertion·태그·조건부 스킵·트랜잭션·캐시·힙·DB 설정을 대조한다. snapshot의 type-level spy 전환 외 설정 차이를 확인한다.
5. A/B를 같은 D·S·W에서 구성하고, 두 버전의 차이가 1·2·4뿐인지 확인한다.

### 2단계 — 대상 검증과 격리

| 대상·방식 | 확인할 동작 | 기대 결과 |
| --- | --- | --- |
| 인증 2클래스 | 단독·결합·정순/역순, 기존 성공·제약 실패 | 검증 성공·롤백 유지, 결합에서 컨텍스트/DB 공유 |
| 문서 조회 4클래스 | 단독·결합·정순/역순, 기존 scope·조회·cursor·빈 결과 | 개선안 2의 공유 유지, 클래스 간 오염 없음 |
| snapshot 5클래스 | 단독·결합·정순/역순, 동시 쓰기·기존 응답 assertion | spy 동작·상태 reset, 쓰기/비동기 종료, 다음 클래스 영향 없음 |
| 스케줄러 | A/B 수정 소스 동일성 및 최종 B 독립 실행 | 실제 주기·완료·기존 데이터 결과·종료 격리 확인 |
| 스케줄러 오염 부정 사례 | 별도 전용 실행에서 member 1건 주입 | fixture 전 빈 DB assertion이 의도대로 실패. 오염 자동 삭제 없음 |
| 대상 DB 관측 | 별도 집중 실행에서 트랜잭션 경계·사용 테이블·스레드 종료 확인 | JPA 롤백과 snapshot/스케줄러의 실제 커밋 후 정리를 구분해 확인 |

과거 대상 수는 인증 10개·문서 조회 38개·snapshot 8개다. 재실행에서는 실제 목록으로 확인한다. 스케줄러의 과거 독립 20회를 무조건 반복하지 않고, 최종 B에서 독립 정상 3회와 오염 부정 사례 1회를 계획한다. 실패나 관련 코드 변경이 있으면 필요한 범위만 보강한다.

실제 클래스 순서를 시작 로그로 대조한다. `--tests` 인자 나열 순서를 실행 순서의 증거로 사용하지 않는다.

### 3단계 — 최종 B 일반 전체 회귀 1회

backend 디렉터리에서 다음 기존 작업을 사용한다.

```sh
./gradlew spotlessCheck test integrationTest acceptanceTest bootJar --rerun-tasks --no-build-cache
```

이는 제품 검증용 로컬 명령이다. 한 명령으로 실행한 로컬 시간을 Actions 5단계의 시간과 직접 비교하지 않는다.

- 새 XML에서 최종 테스트 목록·실패·오류·스킵과 태스크 실제 실행 여부를 확인한다.
- Spotless·단위·통합·인수·JAR 생성이 모두 성공해야 한다.
- 공통 D에 기능 변경이 들어왔으므로 이 최종 전체 회귀는 필요하다. 동일 결과를 확인하려고 전후 로컬 전체 3회씩을 추가하지 않는다.

### 4단계 — 실제 GitHub Actions 전후 각 3회

| 항목 | 계획 |
| --- | --- |
| 비교 버전 | 고정 A와 B, 같은 D·S·W |
| 실행 수 | A 3회 + B 3회, 총 6회 |
| 순서 | A1 → B1 → B2 → A2 → A3 → B3, 한 번에 하나씩 실행 |
| trigger | 같은 pull_request 유형 사용. 현재 workflow에는 workflow_dispatch 없음 |
| ref 구성 | 비교용 ref/PR을 준비해 head/base·실제 checkout SHA를 고정. 아직 생성하지 않음 |
| 캐시 | 같은 정책의 동등한 복원 상태끼리 비교. 명령에 임의의 rerun/no-cache 옵션을 한쪽만 넣지 않음 |
| 환경 | 실제 runner image·Java/Gradle 버전·DB digest·실행 시각 기록. 같은 ubuntu-latest 라벨만으로 동일 하드웨어를 보장하지 않음 |
| 증거 | run ID·attempt·head/base/checkout SHA·workflow hash·캐시 복원 로그·각 단계 시간·XML·실패 로그 |

기존 명령은 `spotlessCheck → test → integrationTest → acceptanceTest → bootJar`다. feature 브랜치 push만으로 Backend CI가 실행된다고 가정하지 않는다. 실제 Actions 호출·PR·push는 별도 실행 단계이며 이번 계획에서는 수행하지 않는다.

캐시 적중 여부와 태스크 실행 여부를 별도로 본다. 테스트가 UP-TO-DATE/FROM-CACHE로 생략되면 실제 테스트 수행 시간 비교가 성립하지 않으므로 그 결과를 명시한다. 양쪽 조건이 맞지 않으면 결과를 보존하고 성능 결론을 미확정으로 남긴다. 비교 수치를 맞추기 위한 임의의 캐시 삭제나 실패 표본 교체는 하지 않는다.

### 결과 집계 기준

- 주 지표: verify job의 실행 시간과 기존 5개 검증 단계별 시간. 큐 대기는 별도 기록한다.
- 보조 지표: workflow 전체 시간·공통 리포트 업로드 시간·느린 테스트 클래스. XML 시간과 step 시간을 더해 전체 시간으로 만들지 않는다.
- 전후 각 3회의 원시값·중앙값·범위·성공/실패 수를 함께 기록한다.
- 감소율은 `(A 중앙값 − B 중앙값) / A 중앙값 × 100`으로 계산한다. 실제 실행·환경·목록이 비교 가능한 경우만 적용한다.
- 인수·SSE의 남은 비용은 단계와 느린 클래스 수준에서 분류한다. 이번 범위에 새 최적화 실험을 추가하지 않는다.
- 실패하면 XML·로그를 보존하고 최종 검증 gate를 미충족으로 남긴다. 실패는 성능 중앙값에 성공 표본처럼 섞지 않으며 성공한 실행만 골라 완료하지도 않는다.

### 변경 단위

후속 구현 시 개선안 5 제외, develop 병합, 공통 리포트 보존 W, 최종 결과 문서를 분리해 검토한다. commit·push·PR은 해당 쓰기 요청 범위에서만 수행한다. 이번 계획 작성은 원격 게시나 Git 이력 변경 권한으로 해석하지 않는다.

## 6. 완료 기준과 다음 작업

| 묶음 | 완료 기준 | 현재 상태 |
| --- | --- | --- |
| 최종 코드·기준선 | 5 제외, D 반영, 1·2·4·S 범위 대조, A/B 공통 D·S·W 확정 | **범위·계획 확정, 코드 정리 전** |
| 최종 검증·CI 비교 | 대상 격리·최종 일반 회귀와 비교 가능한 Actions 각 3회 결과 보존 | 실행 전 |
| 성과 문서 | 실제 CI 효과·채택/보류·실패·한계 분리 | 최종 수치 전 |

개선안 5의 이번 보류 결정은 완료했다. 후보 코드 제외는 후속 코드 정리 작업으로 남아 있다. 3·5를 다시 채택할 때까지 반복 측정하지 않는다.

전체 CI 개선율이 확인되지 않더라도 그 결과를 그대로 기록한다. 실제 근거 없이 `7분 13초 → N분` 성과를 만들지 않는다. 이번에 확정한 계획 다음 작업은 **개선안 5 import 제외와 develop 공통 기준선 반영**이다.

이번에는 저장소·이슈·열린 PR 조사, 현재 브랜치 pull, 계획·진행표 갱신만 수행했다. 코드 원복·develop 병합·새 테스트·DB/패킷 수집·commit/push·PR/Issue/Notion 원격 쓰기는 하지 않았다.
