# 개선안 5 실험 계획: 문서·원문 JPA 테스트의 컨텍스트 공유

## 1. 범위와 브랜치

- 상태: 2026-10-16 승인 실험 완료·최종 채택 보류. 아래 본문은 실행 전 계획을 보존한다.
- 결과: [개선안 5 결과 문서](../experiments/715-ci-document-persistence-context.md). 대상 74개·전후 각3회·격리·최종 회귀는 통과했지만, 전체 관측 실행에서 스케줄러 deadlock 1회가 있어 전체 시간 개선과 최종 채택은 보류했다.
- 후속 재측정: [스케줄러 수정 통제 후 전체 비교 결과](../experiments/715-ci-document-persistence-context-recheck.md). 양쪽 동일 스케줄러로 전후 각3회 모두 649개 통과. 초기화 각각4회 감소를 재확인했으나 전체 중앙값은 159.825→160.671초였고 변경 후 지연 표본도 남아, 최종 채택 보류를 유지했다.
- 확인일: 2026-10-16.
- 관련 Issue: [#715 — Backend CI 실행 시간 개선](https://github.com/woowacourse-teams/2026-knot/issues/715).
- 브랜치: `be/refactor/#715`.
- 코드 기준: 개선안 4를 반영한 `16ac9dfe0`. 기존 결과 문서까지 반영한 HEAD는 `d26aefb18`.
- 대상: 문서·원문 JPA 테스트 8개, 기존 테스트 74개의 import 설정 정렬.

개선안 2에서 문서 조회 테스트 4개가 하나의 컨텍스트를 공유하도록 정렬했다. 개선안 4에서는 스냅샷 조회 테스트 5개의 spy 구성을 맞췄다. 이번에는 최신 성공 리포트의 서로 다른 설정 36개 중, 기존 문서 조회 그룹과 함께 묶을 수 있는 JPA 테스트를 찾았다.

이번 작업은 테스트 설정을 대상으로 한다. HTTP/API, 인증·권한, 제품 정책을 바꾸지 않는다. `backend/docs/notion-context.md`의 탐색 원칙을 확인했으며, 제품 Notion 원문은 새로 수집하지 않았다. 별도 PR 코드에 의존하지 않고 현재 브랜치의 코드와 기존 리포트를 기준으로 계획한다.

| 근거 | 적용하는 사실 | 확인 상태 |
| --- | --- | --- |
| Issue #715 | 검증 범위·격리 유지, 반복 측정과 실패 원본 보존 | 작업 계약 |
| 개선안 2 결과: `docs/experiments/715-ci-document-query-context.md` | 문서 조회 4클래스·38개 테스트는 이미 같은 import를 사용 | 기존 채택 결과 |
| 개선안 4 결과: `docs/experiments/715-ci-snapshot-context.md` | 최신 전체 성공 실행에서 서로 다른 설정 36개·실제 컨텍스트 43개 | 로컬 관측 |
| `backend/output/ci-715/snapshot-context-20261016/analysis.json` 및 실행별 XML | 아래 대상·횟수·로딩 구간 집계 | 기존 자료 직접 재집계 |
| 대상 8개 테스트, 어댑터 10개, TestcontainersConfiguration | 기본 JPA slice·실제 DB·트랜잭션 조건 및 의존성 비교 | 현재 코드 관측 |

### 후보 비교와 선정 이유

개선안 4의 `verified-full-after-1/2/3`만 사용한다. 세 실행 모두 전체 통합 테스트 649개가 성공했다. 실패 실행과 준비 실행은 섞지 않는다.

실제 캐시 키의 **첫 MISS→STORE 구간**을 설정별 최초 로딩 시간으로 집계했다. 같은 실행에서 후보 그룹의 시간을 합산한 뒤, 3회의 중앙값을 비교한다. 이 구간에는 컨텍스트 준비와 DB 초기화 외에 캐시 등록 시 다른 컨텍스트를 닫는 비용 등이 포함될 수 있다. Spring·PostgreSQL·Flyway 시간을 별도로 더하지 않는다.

| 후보 | 클래스·테스트 수 | 현재 설정 수 | 로딩 구간 합계 중앙값 | 판단 |
| --- | --- | ---: | ---: | --- |
| **문서·원문 JPA 그룹** | **8개·74개** | **5개** | **9.838초** | **선정. 공통 JPA 설정에서 import 차이를 정렬할 후보가 가장 많음** |
| 워크스페이스 저장소 그룹 | 3개·29개 | 2개 | 4.092초 | 이미 두 클래스가 공유 중이며, 줄일 수 있는 설정은 상대적으로 적음 |
| 검색 목록 JPA 조회 그룹 | 2개·25개 | 2개 | 3.889초 | SQL 개수 검증용 Hibernate statistics 설정을 유지하는 별도 그룹으로 검토 |

선정 그룹의 실행별 합계는 **9.838 / 10.316 / 9.806초**다. 이는 현재 발생한 비용이다. 공유 후에도 한 번의 초기화가 남고 추가 어댑터의 준비 비용이 생기므로, **9.838초를 예상 절감 시간으로 사용하지 않는다.**

## 2. 실험 가설과 검증 흐름

> 기본 JPA 테스트 설정이 같고 import만 다른 문서·원문 테스트 8개에 동일한 어댑터 구성을 적용하면, 기존 SQL·제약·flush·rollback 검증을 유지하면서 5개 컨텍스트 설정을 하나로 줄일 수 있다.

| 현재 그룹 | 대상 클래스 | 테스트 수 | 해당 설정의 최초 로딩 중앙값 |
| --- | --- | ---: | ---: |
| A | DocumentCommandRepositoryIntegrationTest | 6 | 1.696초 |
| B | DocumentConfirmationQueryIntegrationTest | 10 | 아래 4클래스 합쳐 2.054초 |
| B | DocumentDetailQueryIntegrationTest | 9 | 같은 설정 공유 |
| B | DocumentGenerationJobListQueryIntegrationTest | 12 | 같은 설정 공유 |
| B | DocumentListQueryIntegrationTest | 7 | 같은 설정 공유 |
| C | DocumentGenerationJobRetryRepositoryIntegrationTest | 10 | 2.189초 |
| D | DocumentTranscriptQueryIntegrationTest | 3 | 1.700초 |
| E | TranscriptSegmentRepositoryIntegrationTest | 17 | 2.524초 |
| **합계** | **8클래스·5설정** | **74** | **그룹 합계는 실행별로 계산** |

개별 중앙값을 더한 값과 실행별 합계의 중앙값은 다르다. 위 5개 설정은 기존 성공 실행 3건 모두에서 각각 한 번 생성됐다. 대상 클래스들의 XML에서 Spring 시작 완료·PostgreSQL 시작 완료·Flyway 적용 완료가 각각 총 5건임을 확인했다.

공통 조건은 `@Tag("integration")`, 기본 `@DataJpaTest`, `TestcontainersConfiguration`이다. 대상 클래스에는 별도 ActiveProfiles·TestPropertySource·DynamicPropertySource·Mockito override·DirtiesContext·명시적 트랜잭션 전파 변경이 없다. 기본 테스트 트랜잭션과 rollback을 사용한다.

기존 테스트 흐름은 다음과 같다.

```text
실제 PostgreSQL과 Flyway로 JPA 테스트 환경 준비
→ 테스트별 트랜잭션 시작·fixture 저장
→ 실제 저장소/조회 어댑터 호출
→ 필요한 flush·clear 또는 SQL 조회·제약 위반 확인
→ 응답·DB 상태 검증
→ rollback 후 다음 테스트 진행
```

현재 어댑터 10개는 저장소 의존성에 처리를 위임하며 자체 워커나 스케줄러를 시작하지 않는다. 문서 원문 조회 어댑터는 TranscriptSegmentRepository에 의존하므로 원문 세그먼트 어댑터도 같은 import에 포함해야 한다.

## 3. 변경 후보와 기존 책임

**아래 내용은 제안이며 아직 적용하지 않았다.**

| 위치·클래스 | 현재 책임 | 변경 후보 |
| --- | --- | --- |
| `backend/src/test/java/com/knot/backend/document/infrastructure/`의 위 7개 테스트 | 문서 명령·조회·생성 작업 재시도·원문 조회 검증 | 7개 클래스의 `@Import`를 같은 구성으로 정렬 |
| `backend/src/test/java/com/knot/backend/recording/infrastructure/TranscriptSegmentRepositoryIntegrationTest.java` | 세그먼트 저장·정렬·DB 제약 검증 | 같은 `@Import` 구성 적용 |
| 기존 문서 어댑터 9개·세그먼트 어댑터 1개 | 실제 Spring Data JPA 등에 위임 | 기존 구현 재사용 |
| TestcontainersConfiguration·Gradle 테스트 설정 | DB 연결·태그·힙·캐시 조건 | 기존 조건 유지 |

모든 대상의 import를 다음 **10개 어댑터와 TestcontainersConfiguration**으로 맞춘다.

```text
DocumentRepositoryAdapter
DocumentConfirmationRepositoryAdapter
DocumentConfirmationQueryAdapter
DocumentDetailQueryAdapter
DocumentGenerationJobListQueryAdapter
DocumentListQueryAdapter
DocumentGenerationJobRepositoryAdapter
DocumentGenerationInputQueryAdapter
DocumentTranscriptQueryAdapter
TranscriptSegmentRepositoryAdapter
TestcontainersConfiguration
```

기존 `findByWorkspaceIdAndIdForUpdate`, `findByWorkspaceIdAndDocumentId`, `saveAll`, `flush` 등 공개 동작과 반환값은 바꾸지 않는다. 테스트 본문·fixture·assertion도 유지한다. 새 서비스·메서드·공통 부모 클래스·합성 애노테이션은 만들지 않는다.

검색 목록의 `hibernate.generate_statistics=true`와 SQL 실행 횟수 assertion은 이번 그룹에 포함하지 않는다. 마이그레이션 검증, 스냅샷 동시성, 워커·SSE 생명주기도 이번 범위에서 제외한다.

## 4. 저장 기반과 주의점

스키마·migration·제품 트랜잭션 변경은 없다. 실제 `pgvector/pgvector:pg18`과 Flyway, 컨텍스트 캐시 8, 테스트 JVM 최대 힙 1GB를 유지한다.

| 유지할 검증 | 공유 시 확인할 위험 |
| --- | --- |
| 워크스페이스 범위와 존재하지 않는 문서·작업 조회 | 이전 클래스의 fixture가 다음 조회에 섞이지 않는지 |
| 문서 확인·상태 변경과 SQL 조회 전 flush | flush를 제거해 이전 변경이 보이지 않는 문제를 숨기지 않는지 |
| 재시도 횟수·작업 상태, `flush` 후 `clear`와 재조회 | 같은 영속성 컨텍스트의 메모리 값만 확인하지 않는지 |
| 세그먼트 순서·nullable 필드·실제 원문 연결 | 문서가 연결한 원문을 조회하며 다른 원문과 섞이지 않는지 |
| UNIQUE·FK·CHECK와 삭제 제한 | 제약 위반 후 실패한 트랜잭션이 rollback되고 다음 테스트가 정상 동작하는지 |
| 기존 4개 문서 조회 테스트의 페이지·정렬·상태 검증 | import 확장이 기존 38개 검증에 영향을 주지 않는지 |

PostgreSQL sequence는 트랜잭션 rollback으로 되돌아가지 않는다. 격리 검증에서 ID가 항상 1부터 시작한다고 가정하지 않고, 실제 fixture가 반환한 ID와 테이블의 업무 데이터를 비교한다. 인위적인 TRUNCATE나 RESTART IDENTITY를 추가해 순서 의존성을 가리지 않는다.

전체 통합 실행에서는 문서와 recording 패키지 사이의 실행 간격 때문에 공통 컨텍스트가 캐시에서 퇴출될 수도 있다. **서로 다른 설정 수 감소와 실제 생성 횟수 감소를 따로 확인한다.**

## 5. 측정과 검증 순서

설정 변경이므로 기존 74개의 성공·실패·경계 테스트를 회귀 기준으로 사용한다. 설정 차이를 반복하는 인위적인 실패 테스트를 새로 만들지 않는다. 격리 문제가 발견되면 먼저 그 실패를 재현하고 원인을 해결한 뒤 다음 단계로 진행한다.

### 1단계: 변경 전 기준선 확보

- 대상 8클래스를 동일 테스트 JVM에서 함께 실행해 변경 전 3회 측정한다.
- 기존 리포트는 대상 선정 근거로 사용하고, 이번 실험의 변경 후 측정과 직접 비교할 변경 전 실행은 새로 확보한다.
- 실행 전 컴파일을 끝내고 소스·클래스·runtime classpath가 해당 버전과 일치하는지 기록한다.
- 변경 전후 같은 관측 설정으로 컨텍스트 키·ID·생성/퇴출 이유, PostgreSQL·Flyway 횟수, 테스트·rollback 횟수를 수집한다.

### 2단계: 최소 설정 변경과 전후 비교

- 위 8개 클래스의 import만 정렬한다.
- 변경 전후 각각 최소 3회씩, 가능하면 `전1→후1→전2→후2→전3→후3` 순서로 실행한다. 각 버전의 소스와 컴파일 산출물 대응을 보장한다.
- 이전 실험의 `integrationTest --rerun --no-build-cache`와 대상별 `--tests` 필터를 기준으로 동일 실행 조건을 사용한다. 의존성·Docker 이미지·Gradle daemon 캐시 조건도 기록한다.
- 다른 작업과 같은 build 출력에 동시에 실행하지 않는다. 각 버전·실행의 XML·HTML·binary·관측 로그 경로를 분리한다.
- Gradle 프로세스 시작부터 종료까지의 시간, 테스트 리포트 Duration, 최초 컨텍스트 로딩 구간을 분리해 기록한다. 중앙값·범위·모든 반복값을 남긴다.

좁은 실행의 목표는 **기존 74개 모두 성공, 서로 다른 설정 5→1, 실제 컨텍스트·PostgreSQL·Flyway 초기화 각각 5→1**이다. 실제 키와 동일 JVM 안의 컨텍스트 ID로 공유 여부를 확인한다.

### 3단계: 단독·순서·DB 격리 확인

| 검증 방식 | 기대 결과 |
| --- | --- |
| 8클래스 각각 단독 실행 | 기존 테스트 수와 assertion 유지, 모두 성공 |
| 기본 순서로 8클래스 실행 | 74개 성공, 동일 컨텍스트 재사용 |
| 기본 순서의 역순 실행 | 74개 성공, 실제 클래스 시작 순서가 역순인지 로그 확인 |
| 제약 위반이 많은 세그먼트·재시도 테스트를 먼저 실행 | 이후 조회·명령 테스트까지 성공, 이전 실패 트랜잭션 영향 없음 |
| 테스트별 트랜잭션·업무 데이터 관측 | 각 테스트 rollback과 다음 fixture 준비 전 업무 데이터 정리 확인 |

필터 인자 순서를 실제 실행 순서로 간주하지 않는다. 관측한 클래스 시작 순서로 순서 검증의 유효성을 판정한다. rollback 74건 여부도 실제 로그로 확인하며 테스트 개수만으로 추정하지 않는다.

### 4단계: 전체 회귀와 영향 확인

- 전체 통합 테스트에서 기존 649개 성공과 inventory 일치를 확인한다.
- 전체 설정 수가 36→32로 줄어드는지 검증한다. 이는 네 설정을 합쳤을 때의 구조적 가설이다.
- 실제 생성·캐시 퇴출·재생성·PostgreSQL·Flyway 횟수를 재집계한다. 기존 43개 컨텍스트가 반드시 39개가 된다고 가정하지 않는다.
- 전체 시간 개선을 주장하려면 전체 통합 실행도 같은 조건의 변경 전후 각 3회 이상으로 비교한다. 좁은 실행의 개선율을 전체 CI 개선율로 환산하지 않는다.
- 최종 채택 후보에서 Spotless, 단위·통합·인수 테스트와 JAR 빌드를 확인한다. 현재 inventory는 단위 1,425개·통합 649개·인수 561개다.
- 실패·타임아웃은 원본과 환경을 보존한다. 원인을 확인하지 않은 자동 재시도나 가장 빠른 실행만 골라 개선율을 계산하지 않는다.

검증 후 커밋은 테스트 설정의 `refactor`와 결과 문서의 `docs`를 분리한다. 이 계획 자체는 개선안 5 구현·커밋을 실행한 결과가 아니다.

## 6. 완료 기준과 다음 작업

1. 대상 8클래스·74개 테스트가 같은 설정과 실제 컨텍스트를 재사용한다.
2. 기존 SQL·제약·flush·rollback 검증이 유지되고 단독·실제 순서 변경에서도 통과한다.
3. 변경 전후 각 3회 이상의 시간·초기화·격리 근거를 확보한다.
4. 전체 통합 및 최종 회귀가 통과하고, 캐시 퇴출·메모리·추가 Bean 비용에 의한 악화 여부를 확인한다.
5. 결과를 `docs/experiments/715-ci-document-persistence-context.md`에 별도 문서로 작성하고 채택 여부를 판단한다.

컨텍스트 수가 줄어도 총비용이나 격리가 악화되면 그대로 채택하지 않는다. 개선 폭과 변동 범위가 겹치면 구조적 효과와 시간 효과를 구분해 기록한다.

GitHub Actions의 개선 여부는 이후 같은 workflow·runner 조건의 실제 실행으로 확인한다. 현재 문서는 로컬의 기존 리포트와 코드로 작성한 다음 실험 계획이며, 개선안 5의 성능 결과는 아직 없다.
