# 개선안 4 실험 계획: 스냅샷 조회 테스트의 컨텍스트 공유

## 1. 범위와 근거

- 상태: 2026-10-16 사용자 승인으로 실험 완료·채택. 아래 본문은 실행 전 계획을 보존한다.
- 결과: [개선안 4 결과 문서](../experiments/715-ci-snapshot-context.md). 전후 측정·단독/순서/spy/DB 격리·전체 회귀 완료. 전체 CI 개선율은 미검증.
- 확인일: 2026-10-16.
- 관련 Issue: [#715 — Backend CI 실행 시간 개선](https://github.com/woowacourse-teams/2026-knot/issues/715).
- 현재 브랜치: be/refactor/#715.
- 기준 HEAD: 7fd075436df67921edc2fd0666eb2534fb302507.
- 대상: 문서·검색 스냅샷 조회 통합 테스트 5개의 spy 설정 정렬.

개선안 3에서는 캐시 8의 용량 퇴출로 동일 설정이 3회 재초기화되는 것을 확인했다. 그러나 서로 다른 설정 40개의 최초 초기화는 캐시를 늘려도 남았으며, 캐시 16은 전체 테스트 실패와 자원 비용 때문에 적용하지 않았다. 이번에는 설정 차이로 분리되는 최초 컨텍스트 자체를 줄일 후보를 선정한다.

이번 계획은 테스트 실행 환경을 대상으로 한다. HTTP/API 계약이나 제품 정책을 바꾸지 않으므로 운영 Notion의 제품 요구사항은 새로 수집하지 않았다. backend/docs/notion-context.md의 탐색 원칙을 확인하고 Issue·기존 결과·실제 코드를 근거로 삼았다.

| 근거 | 확인 시점·출처 | 적용하는 사실 | 판정 |
| --- | --- | --- | --- |
| Issue #715 | 2026-10-16 원격 본문 확인 | 검증 범위·격리 유지, 전후 각 3회 이상 측정, 실패 원본 보존 | 현재 작업 계약 |
| 개선안 3 성공 리포트 | 2026-10-15 실행, 2026-10-16 재집계 | cache-8-3/4/5는 각 649개 성공, 동일 HEAD·순서·관측 조건 | 로컬 실행 관측 |
| backend/output/ci-715/cache-context-20261015/analysis.json 및 실행별 events.tsv·XML | 기존 파일 직접 확인 | 실제 캐시 키별 클래스, MISS→STORE 시간, 테스트 inventory | 로컬 실행 관측 |
| docs/experiments/715-ci-document-query-context.md | 기존 결과 확인 | JPA 조회 4클래스 공유는 이미 채택됨. 이번 대상과 다름 | 기존 채택 결과 |
| 대상 테스트 5개·공통 설정·조회 서비스·어댑터 | 위 HEAD의 현재 코드 확인 | 공통 Boot/dev 설정, 개별 spy 차이, 실제 PostgreSQL 동시성 검증 | 코드 관측 |
| 설치된 Spring Test 7.0.8 sources JAR | 로컬 소스 확인 | 클래스 수준 MockitoSpyBean의 types, 기본 AFTER reset, 필드명도 키 비교에 참여 | 설치 패키지 근거 |

GitHub Actions의 변경 후 실행 시간은 이 자료로 검증하지 않았다. 로컬 수치를 Actions 전체 개선율로 환산하지 않는다.

### 비용 집계 기준

리포트 경로가 분리된 캐시 8 성공 실행 cache-8-3, cache-8-4, cache-8-5만 사용한다. 실패 실행·캐시 16·경로 분리 전 실행은 이번 우선순위 수치에 섞지 않는다.

실제 캐시 키별 **첫 MISS에서 첫 STORE까지의 시간**을 최초 컨텍스트 로딩 구간으로 집계하고 3회의 중앙값을 사용한다. 테스트 클래스 전체 시간이나 Spring의 Started 로그 시간과는 다르다. 컨텍스트·DB 준비뿐 아니라 캐시 등록 시 다른 컨텍스트를 닫는 비용 등이 포함될 수 있어 순수 Bean 초기화 시간으로 단정하지 않는다. Spring·PostgreSQL·Flyway 시간을 여기에 더하지 않는다.

### 최초 로딩 비용 상위 설정

40개 설정을 위 기준으로 정렬한 상위 8개다. 아래 설정은 각각 한 클래스가 사용했다.

| 순위 | 대표 클래스 | 최초 로딩 중앙값 | 판단 |
| --- | --- | ---: | --- |
| 1 | AuthNicknameCompletionIntegrationTest | 6.239초 | 세 실행 모두 JVM의 첫 컨텍스트. 최초 준비 비용과 설정 고유 비용 분리가 먼저 필요 |
| 2 | DocumentRetentionIntegrationTest | 2.633초 | Clock·Storage mock, repository spy, AFTER_CLASS 생명주기가 있어 이번 공유 대상에서 제외 |
| 3 | SearchConversationListSnapshotIntegrationTest | 2.203초 | 공통 조회 환경에서 개별 spy 차이. 선정 그룹에 포함 |
| 4 | AuthServiceIntegrationTest | 2.151초 | JPA slice·다수 mock·NOT_SUPPORTED 트랜잭션을 별도로 검토할 대상 |
| 5 | SearchAnswerExecutionIntegrationTest | 2.150초 | 답변 실행 기능의 설정과 생명주기를 별도로 검토할 대상 |
| 6 | DocumentGenerationResultIntegrationTest | 2.029초 | Clock mock과 repository/query spy 4개. 이번 단순 조회 그룹과 분리 |
| 7 | SearchMessageListSnapshotIntegrationTest | 2.000초 | 공통 조회 환경에서 개별 spy 차이. 선정 그룹에 포함 |
| 8 | DocumentListSnapshotIntegrationTest | 1.954초 | 공통 조회 환경에서 개별 spy 차이. 선정 그룹에 포함 |

개별 최댓값만으로 대상을 고르지 않는다. **함께 줄일 수 있는 초기화의 합계, 설정 호환성, 검증을 훼손할 위험**을 함께 본다. AuthNicknameCompletion의 6.239초가 전부 제거 가능한 낭비라는 근거는 없다.

## 2. 실험 가설과 유지할 검증 흐름

> 공통 실행 설정이 같고 spy 대상만 다른 스냅샷 조회 테스트 5개에 동일한 spy 구성을 적용하면, 기존 PostgreSQL 스냅샷 일관성 검증을 유지하면서 서로 다른 컨텍스트 5개를 하나의 설정으로 줄일 수 있다.

| 대상 클래스 | 테스트 수 | 현재 spy | 최초 로딩 중앙값 |
| --- | ---: | --- | ---: |
| SearchConversationListSnapshotIntegrationTest | 1 | SearchConversationListQueryAdapter | 2.203초 |
| SearchMessageListSnapshotIntegrationTest | 2 | SearchMessageListQueryAdapter | 2.000초 |
| DocumentListSnapshotIntegrationTest | 2 | DocumentListQueryAdapter | 1.954초 |
| DocumentConfirmationSnapshotIntegrationTest | 2 | DocumentConfirmationQueryAdapter | 1.675초 |
| DocumentTranscriptSnapshotIntegrationTest | 1 | TranscriptSegmentRepositoryAdapter | 1.745초 |
| **합계** | **8개** | **서로 다른 5개 설정** | **아래 실행별 합계로 비교** |

5개 로딩 구간을 같은 실행 안에서 합산한 값은 9.652 / 9.691 / 9.351초이며, 중앙값은 **9.652초**였다. 개별 중앙값의 합과는 계산 순서가 다르다. 3회 모두 이 5개 클래스에서 Spring·PostgreSQL·Flyway 완료 로그가 각각 5회였다.

**이는 현재 비용이며 예상 절감 시간이 아니다.** 공유 후에도 최소 한 번의 초기화와 추가 spy 생성·reset 비용이 남는다.

다섯 클래스의 공통 조건은 다음과 같다.

- integration 태그, dev 프로필, 기본 SpringBootTest.
- TestcontainersConfiguration import와 TestApplicationProperties.
- 별도 Mockito mock, DirtiesContext, 동적 속성, 테스트 트랜잭션 지정 없음.
- 각 BeforeEach에서 members/workspaces를 CASCADE로 TRUNCATE하고 fixture 재생성.

기존 검증 흐름은 다음과 같다.

    fixture 준비·spy에 시점 제어 설정
    → 실제 서비스의 REPEATABLE_READ 읽기 트랜잭션 시작
    → 조회 중 별도 스레드·별도 트랜잭션으로 변경 commit
    → 최초 응답이 같은 DB 스냅샷을 유지하는지 확인
    → 실제 DB 변경과, 해당 사례의 다음 응답 반영을 확인

spy는 기존 Bean을 감싸며 기본적으로 실제 메서드를 호출한다. 실제 SQL·별도 트랜잭션·commit을 mock으로 바꾸지 않는다. 시점 제어, 10초 대기 상한, executor 종료, 기존 assertion을 유지한다.

SearchAnswerSnapshotIntegrationTest는 추가로 SearchGenerationAvailability를 mock으로 바꾸므로 이번에는 제외한다. 그룹을 키우기 위해 이 mock을 다른 클래스에 확산하지 않는다. 전 실험의 MCP 실패 클래스, 마이그레이션, 워커의 DirtiesContext도 이번 변경 대상이 아니다.

## 3. 변경 후보와 책임

**아래는 제안이며 아직 적용하지 않았다.**

| 위치 | 현재 책임 | 제안하는 변경 |
| --- | --- | --- |
| 위 5개 테스트 클래스 | 각 spy로 동시 변경 시점을 제어하고 DB 스냅샷 검증 | 클래스 수준에서 같은 5개 타입의 spy 선언 |
| 각 테스트의 query 또는 segments 필드 | stub 설정·실제 메서드 복귀에 사용 | 필드의 MockitoSpyBean을 Autowired로 바꾸어 공통 spy Bean 주입 |
| 기존 5종 어댑터 | Spring Data JPA 등에 실제 처리를 위임 | production 변경 없음 |
| 공통 Testcontainers·속성, build.gradle | DB 연결과 테스트 실행 조건 | 변경 없음 |

클래스 수준 MockitoSpyBean의 types에 아래 타입을 같은 구성으로 지정하는 방안을 사용한다.

- DocumentListQueryAdapter
- DocumentConfirmationQueryAdapter
- TranscriptSegmentRepositoryAdapter
- SearchConversationListQueryAdapter
- SearchMessageListQueryAdapter

필드 수준 spy 선언은 남기지 않아 중복 정의를 피한다. 각 클래스는 필요한 필드만 주입받고 기존 stub 본문을 유지한다. 이번 실험에서는 공통 부모 클래스나 합성 애노테이션을 새로 도입하지 않는다.

설치된 Spring Test에서 필드명·qualifier도 override 비교에 참여하는 것을 확인했다. 타입 목록만 같으면 공유한다고 단정하지 않고 **실제 캐시 키와 컨텍스트 ID**로 확인한다. 클래스 수준 선언의 기본 reset은 AFTER지만, 선언의 기본값과 실행 시 상태 격리는 별도로 확인한다.

위 어댑터는 저장소 의존성에 처리를 위임하며 자체 스레드나 스케줄러를 만드는 코드는 확인되지 않았다. 추가 spy와 AOP/트랜잭션 proxy의 상호작용, 공유 컨텍스트의 stub 유출은 실험으로 검증해야 한다.

## 4. 저장 기반과 주의점

스키마·migration 변경은 필요 없다. 실제 pgvector/pgvector:pg18과 Flyway, 캐시 8, 테스트 JVM 힙 1GB, 서비스의 REPEATABLE_READ를 유지한다.

이 8개 테스트는 클래스 전체를 테스트 트랜잭션으로 감싸 자동 rollback하는 구조가 아니다. **rollback 횟수를 격리 근거로 사용하지 않는다.** TRUNCATE와 fixture 재생성, 실제 commit, spy reset, 별도 스레드 종료를 확인한다. 테스트에 Transactional을 새로 붙여 본래 스냅샷 검증을 바꾸지 않는다.

RESTART IDENTITY로 ID가 재사용되므로 이전 테스트의 인자별 stub이 남으면 위험하다. 각 테스트의 stub 설정 전에 5개 spy에 이전 stub이 없는지 관측한다. 이전 callback/executor가 남지 않았는지도 확인한다.

전 실험의 리포트 EOF와 같은 폴더의 동시 Gradle 빌드 기록은 직접 인과관계가 확정되지 않았다. 이번에도 같은 build 출력에 병렬 실행하지 않고 실행별 binary/XML/HTML 경로를 분리한다. 종료 코드와 XML 갱신 시각을 확인하여 이전 XML을 성공 결과로 사용하지 않는다.

## 5. 실험·검증 순서

### 5.1 변경 전 기준 확보

기존 3회 수치는 대상 선정 근거다. 실제 채택을 위한 전후 비교에서는 변경 전도 같은 조건으로 새로 측정한다.

1. 기준 HEAD, 대상 8개 테스트 inventory, 기존 리포트를 보존한다.
2. 5개 클래스를 같은 JVM에서 실행하여 5개 키·5개 컨텍스트·각 5회 초기화를 재확인한다. 다르면 변경 전제를 다시 검토한다.
3. 변경 전과 후보의 컴파일을 계측 밖에서 수행하고, 측정 시 컴파일·리소스 처리가 UP-TO-DATE인지 확인한다.

새 public 기능을 추가하는 작업이 아니므로 형식적인 실패 테스트를 늘리지 않고, 기존 8개 테스트를 회귀 기준으로 사용한다. 먼저 분리된 5개 설정을 관측하고 최소 설정 변경 후 실제 공유를 검증한다.

### 5.2 최소 변경 후 공유·동작 확인

5개 클래스의 spy 선언만 정렬한다. 대상 결합 실행에서 실제 키 1개·컨텍스트 1개·8개 테스트 성공을 확인한다.

| 검증 | 유지할 동작과 탐지할 오류 | 기대 결과 |
| --- | --- | --- |
| 문서 목록 2개 | 생성·확인 commit으로 집계와 카드가 서로 다른 시점을 읽는 오류 | 최초 응답의 이전 상태와 다음 응답의 변경 반영 유지 |
| 확인 목록 2개 | 확인·탈퇴 commit으로 집계와 대상 상태가 어긋나는 오류 | 기존 PENDING 등 assertion 유지 |
| 원문 1개 | 원문과 구간의 이전·새 데이터가 섞이는 오류 | 최초 응답은 이전 원문·구간, 다음은 새 데이터 |
| 대화 목록 1개 | 메시지 commit으로 preview·갱신 시각이 섞이는 오류 | 이전 preview·시각과 실제 DB commit 확인 |
| 메시지 목록 2개 | 본문·상태·근거의 시점이 달라지는 오류 | 최초 응답은 이전 상태, 다음 조회는 완료 본문·근거 |
| spy 상태 | 이전 callback/stub의 테스트 간 유출 | 각 테스트의 stub 설정 전 이전 stub 없음 |
| 단독·순서 변경 | fixture ID 재사용과 공유 상태의 실행 순서 의존 | 5개 클래스 단독 및 서로 다른 결합 순서 3가지 이상에서 성공 |

seed만 보고 실행 순서를 추정하지 않고 실제 클래스 진입 로그를 확인한다. 필요한 spy 상태 관측은 앞뒤 같은 init script 조건으로 수행한다. 새로운 전역 reset이나 대기 변경으로 실패를 숨기지 않는다.

### 5.3 전후 각 3회 이상 측정

캐시 8·힙 1GB, 같은 호스트·Java/Gradle/Docker, 같은 관측 설정·클래스 순서를 유지한다. daemon·의존성·Docker 이미지 캐시는 유지한다. 변경 전후를 교차 측정해 순서 편향을 줄이되, 각 상태의 컴파일과 생성물을 분리하고 같은 build 출력에 동시에 실행하지 않는다.

기본 대상 필터는 다음과 같다. 이번 작업에서 실행하지 않은 예정 명령이다.

    ./gradlew integrationTest --rerun --no-build-cache --console=plain \
      --tests 'com.knot.backend.document.application.DocumentListSnapshotIntegrationTest' \
      --tests 'com.knot.backend.document.application.DocumentConfirmationSnapshotIntegrationTest' \
      --tests 'com.knot.backend.document.application.DocumentTranscriptSnapshotIntegrationTest' \
      --tests 'com.knot.backend.search.application.SearchConversationListSnapshotIntegrationTest' \
      --tests 'com.knot.backend.search.application.SearchMessageListSnapshotIntegrationTest'

실제 계측에서는 개선안 3의 init script/probe에 캐시 8·이벤트 경로·probe 경로·실행별 report 경로를 모두 지정한다. 위 기본 명령만으로 컨텍스트 ID를 측정할 수 있는 것은 아니다.

Gradle 명령 시작부터 종료까지의 시간, 키별 MISS→STORE, 컨텍스트·PG·Flyway 횟수, 동시 PG 수·메모리를 구분해 기록한다. 앞뒤 각 3회 이상 성공 결과의 중앙값·범위를 비교한다. 실패 시간은 성능 개선율에 넣지 않고 별도 보존한다. 실패하면 성공까지 자동 반복하지 않고 원인을 분리한다. 프로토콜을 바꾸면 앞뒤 측정을 같은 새 조건으로 맞춘다.

### 5.4 전체 영향과 회귀 확인

대상 검증 이후 변경 전후 전체 통합 테스트를 측정해 설정 수·생성 수·용량 재생성·전체 시간·자원을 비교한다. 전체 시간 개선을 주장하려면 이 범위도 전후 각 3회 이상을 확보한다.

제안이 성립하면 고유 설정 수는 40→36이 목표다. 다만 전체 실행에서는 대상 클래스 사이의 다른 테스트 때문에 공유 설정이 퇴출되어 재생성될 수 있다. **Spring 시작 46회가 반드시 42회가 된다고 약속하지 않는다.** 최초 생성 감소와 LRU 재생성 변화를 분리해서 설명한다.

마지막에 Issue의 공개 전 기준인 spotlessCheck·단위 테스트·통합 테스트·acceptanceTest·bootJar를 확인한다. 회귀 검사의 시간을 집중 실험 수치에 혼합하지 않는다.

전 실험의 MCP 클래스 7개 실패는 미해결로 기록한다. 다시 실패하면 이번 변경의 원인 또는 무관함을 단정하지 않고 원래 설정과 비교한다. skip·timeout 연장·assertion 삭제로 통과시키지 않는다.

## 6. 완료·채택 기준과 다음 작업

| 조건 | 충족 근거 |
| --- | --- |
| 설정 공유 | 5개 클래스가 실제 키 1개·같은 컨텍스트 참조 |
| 초기화 감소 | 집중 실행에서 Spring·PG·Flyway가 각 5→1, 실제 식별자 대조 |
| 검증 유지 | 기존 8개 테스트·assertion·실제 DB·commit·대기 조건 유지, 실패·오류·skip 없음 |
| 격리 | 단독·다른 결합 순서, spy reset, DB 초기화, 스레드 종료 확인 |
| 시간·자원 | 전후 각 3회 이상을 비교해 중앙값 감소와 자원 비용 보고 |
| 전체 회귀 | 전체 통합 및 Issue의 공개 전 검사 충족, 알려진 실패 은폐 없음 |

컨텍스트를 공유해도 추가 spy 비용이나 재생성으로 실행 시간이 줄지 않거나 격리가 깨지면 채택하지 않는다. 필요하면 문서 3개/검색 2개 부분 공유를 별도 실험으로 설계한다. 측정 후 유리한 대상만 골라 같은 실험의 성공으로 바꾸지 않는다.

현재는 대상 선정과 계획 작성까지 완료했다. 실제 개선 결과는 실행 후 별도 문서로 기록한다. 이번에 제품·테스트 코드를 바꾸거나 테스트 재실행, Issue 수정, commit·push를 하지는 않았다.
