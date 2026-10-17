# 스케줄러 테스트 deadlock 원인 분석과 해결 계획

## 1. 범위와 브랜치

- 상태: 구현·로컬 검증 완료. 독립20회·전체 통합3회·일반 전체 회귀 통과. 상대 SQL·정확한 잠금 순서는 미확정.
- 결과: [스케줄러 테스트 초기화 deadlock 해결 결과](../experiments/715-scheduler-test-deadlock.md)
- 확인일: 2026-10-16
- 관련 이슈: [#715 — Backend CI 실행 시간 개선](https://github.com/woowacourse-teams/2026-knot/issues/715)
- 브랜치: `be/refactor/#715`
- 기준 HEAD: `c5a01a087eb727fcbf2626bb4a75f46a87b40aa4`
- 대상: `DocumentGenerationSchedulerIntegrationTest`의 초기 데이터 정리와 실제 스케줄러 사이의 경합.
- 목적: 기존 자동 실행·DB·트랜잭션 검증을 유지하면서 간헐적인 초기화 실패를 제거한다.

현재 작업 트리에는 개선안 5의 JPA 테스트 import 정렬과 별도 사용자 변경이 있다. 이 계획에서는 해당 변경을 수정하지 않았다. 제품 요구사항, HTTP API, 운영 스케줄러 정책, 스키마 변경은 제안하지 않는다. 열린 PR이나 운영 Notion을 근거로 새로운 제품 결정을 도입하지 않았다.

### 근거

아래 경로는 저장소 루트 기준이다. 원시 자료 공통 경로를 `R = backend/output/ci-715/document-persistence-context-20261016`로 표시한다.

| 출처 | 확인한 내용 | 판정 |
| --- | --- | --- |
| Issue #715 본문, 2026-10-16 조회 | 검증 범위·격리·실제 PostgreSQL 검증을 유지하며 CI 개선 | 이번 작업의 요구사항 |
| `R/verified-full-after-3/xml/TEST-com.knot.backend.document.infrastructure.DocumentGenerationSchedulerIntegrationTest.xml` | 첫 `TRUNCATE`에서 두 DB 세션 사이 deadlock 발생 | 실패 실행의 직접 관측 |
| 같은 실행의 `summary.json` | 통합 테스트 649개 중 1개 실패, 오류·스킵 0개 | 직접 관측 |
| 스케줄러 테스트, `DocumentGenerationScheduler`, `DocumentGenerationJobJpaRepository` | 100ms 주기 실제 스케줄러, 시작 시 정리 SQL, 세 테이블 JOIN 조회 | 현재 코드 관측 |
| `TestcontainersConfiguration`, 실패 XML의 컨테이너·Flyway 로그 | 별도 컨테이너, 빈 스키마에서 44개 migration 적용 | 코드 및 실행 관측 |
| `V18`, `V27`, `V32` migration | workspace/member → recording → transcript/batch/job FK 연결 | 현재 스키마 정의 관측 |
| `R/diagnostic-scheduler-before`, `R/diagnostic-scheduler-after` | 기존 코드 단독 실행 각 1회 통과 | 재현 실패이며 해결 증거는 아님 |
| [개선안 5 결과](../experiments/715-ci-document-persistence-context.md) | 일반 전체 회귀는 통과했으나 deadlock 해결·최종 채택은 보류 | 앞선 실험의 판정 |

## 2. 현재 실패 흐름과 원인 가설

### 확인된 실패

실패 위치는 `DocumentGenerationSchedulerIntegrationTest.java:61`이다.

```sql
TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE;
```

이 SQL은 테스트 본문의 첫 작업이다. fixture 저장, mock 응답 설정, `intake.acceptCompletedTranscript(...)` 호출, 완료 대기는 실행되지 않았다. 따라서 이 실패를 문서 생성 중의 행 잠금 충돌이나 10초 완료 대기 초과로 설명할 수 없다.

실패 로그의 잠금 대기는 다음과 같다. 번호는 실패 당시 DB의 relation OID이며 테이블 이름은 확인되지 않았다.

| 세션 | 기다리는 잠금 | 상대 세션 |
| --- | --- | --- |
| Process 81 | relation 17412의 `AccessExclusiveLock` | Process 82가 차단 |
| Process 82 | relation 17186의 `AccessShareLock` | Process 81이 차단 |

두 세션이 서로 상대가 점유한 객체의 잠금을 기다리는 순환이 확인된다. 로그에는 각 세션이 이미 보유한 잠금 모드 전체와 상대 세션 SQL이 없다. OID를 임의로 `recording_sessions`나 `document_generation_jobs`에 대응시키지 않는다.

### 가장 유력한 원인

> 컨텍스트 시작과 함께 실행되는 스케줄러 조회와, 테스트 시작 시 수행하는 광범위한 `TRUNCATE … CASCADE`가 서로 다른 순서로 테이블 잠금을 얻으면서 충돌했을 가능성이 높다.

현재 코드의 호출 흐름은 다음과 같다.

```text
새 PostgreSQL 컨테이너 시작 → Flyway 적용 → Boot 컨텍스트 시작
  ├─ 실제 스케줄러: dispatchQueued / recoverExpired
  │    → JobRepositoryAdapter
  │    → findReadyCandidates / findExpiredCandidates
  │    → jobs JOIN batches JOIN recording_sessions 조회
  └─ 테스트 스레드: members, workspaces TRUNCATE … CASCADE
       → FK로 연결된 테이블까지 정리 대상 확장
       → 정리 완료 후 fixture 저장·접수 예정이었으나 정리에서 실패
```

두 스케줄러 메서드에는 `initialDelay` 설정이 없고, 이 테스트는 조회·복구 주기를 모두 100ms로 설정한다. `@Transactional(readOnly = true)` 조회도 테이블 잠금은 필요하다. PostgreSQL의 `SELECT`는 `AccessShareLock`, `TRUNCATE`는 `AccessExclusiveLock`을 획득하며 두 잠금은 충돌한다. `CASCADE`는 FK 참조 테이블까지 정리 대상을 확장한다. 이는 로그의 잠금 모드와 양립하는 설명이다. [PostgreSQL 18 잠금 문서](https://www.postgresql.org/docs/18/explicit-locking.html), [TRUNCATE 문서](https://www.postgresql.org/docs/18/sql-truncate.html).

다만 JOIN에 적힌 테이블 순서를 실제 잠금 획득 순서로 간주하지 않는다. 현재 자료만으로 Process 82가 `dispatchQueued`인지 `recoverExpired`인지, 다른 조회인지까지 확정할 수 없다. 실패 컨테이너는 현재 Docker 목록에 없고, 보존된 XML·콘솔에는 상대 SQL과 DB 카탈로그 매핑이 없다.

### 개선안 5와의 관계

| 항목 | 개선안 5의 JPA 공유 그룹 | 실패한 스케줄러 테스트 |
| --- | --- | --- |
| 같은 실패 실행의 컨텍스트 ID | `331064467` | `849294712` |
| PostgreSQL 컨테이너 ID 접두사 | `6e2fb441e910` | `a7554b2a99aa` |
| 설정 | JPA slice, 어댑터 import 정렬 | 실제 스케줄러를 활성화한 Boot 테스트 |

공유 그룹의 DB에서 직접 스케줄러와 충돌한 상황은 배제할 수 있다. 스케줄러 테스트 소스는 개선안 5 전·후 스냅샷 및 현재 파일의 SHA-256이 동일하다. 그러나 전체 실행 순서·CPU·메모리·타이밍 변화에 따른 간접 영향은 배제하지 않는다. 이번 실패를 개선안 5가 만든 버그 또는 개선안 5와 무관한 기존 버그로 단정하지 않는다.

`@DirtiesContext(AFTER_CLASS)`는 클래스 종료 후 컨텍스트를 폐기한다. 같은 클래스 안에서 이미 동작하는 스케줄러와 첫 정리 SQL의 동시 실행을 막지는 않는다.

## 3. 해결안과 변경 대상

### 우선안: 새 DB의 불필요한 시작 시점 TRUNCATE 제거

실패 실행에서는 새 컨테이너가 생성됐고 Flyway가 `<< Empty Schema >>`에서 시작했다. 테스트 클래스에는 테스트가 1개이며 컨테이너는 Spring Bean으로 생성된다. 재사용 옵션은 설정되어 있지 않다. 현재 migration 파일에서 초기 member/workspace/job fixture를 삽입하는 `INSERT INTO`·`COPY`도 발견되지 않았다.

따라서 **전용 DB가 비어 있다는 전제를 실행으로 확인한 뒤, 이 테스트의 첫 TRUNCATE를 제거하는 것**을 가장 작은 해결 후보로 선정한다. DB 초기 상태가 전제와 다르면 조용히 데이터를 삭제하지 않고 원인을 조사한다.

| 위치·클래스 | 기존 책임 | 제안 |
| --- | --- | --- |
| `DocumentGenerationSchedulerIntegrationTest.dispatchQueued_success_automaticallyCompletesIntake()` | fixture 저장 → 접수 → 실제 스케줄러 완료 확인 | 최초 TRUNCATE 제거, fixture 전 빈 DB 확인 |
| 같은 테스트의 `assertFreshDatabase()` | 현재 없음 | member/workspace/recording/transcript/batch/job/document/confirmation 데이터가 0건인지 읽기 조회로 확인하는 private helper 후보 |
| `DocumentGenerationScheduler` | 후보 조회, 실행 위임, 만료 복구 | 우선안에서는 변경 없음 |
| `DocumentGenerationJobJpaRepository` | 실제 JOIN 조회와 잠금 조회 | 우선안에서는 변경 없음 |
| `TestcontainersConfiguration` | 컨텍스트별 PostgreSQL 생성 | 전역 singleton·reuse로 바꾸지 않음 |

추가 helper는 `SELECT count(*)` 등 읽기 조회만 수행한다. 실제 코드에 도입할지와 조회 묶음은 구현 시 기존 테스트 스타일에 맞춰 최소화한다. 운영 클래스나 public 메서드는 추가하지 않는다.

### 검토한 대안

| 대안 | 판단 |
| --- | --- |
| 전용 새 DB 확인 후 최초 TRUNCATE 제거 | 우선 검증. 충돌을 일으킨 초기 정리 자체를 없애며 실제 스케줄링 검증 유지 |
| 정리·fixture 준비 후 스케줄러를 명시적으로 시작 | DB를 여러 테스트가 공유해야 하는 경우의 후속 대안. 현재는 생명주기 변경이 더 크고 자동 시작 검증에도 영향 |
| TRUNCATE를 DELETE로 대체 | 현재 새 DB라면 삭제 자체가 불필요함. FK 순서·정리 책임을 추가하므로 우선 적용하지 않음 |
| 초기 지연·sleep·timeout 증가·실패 재시도 | 잠금 획득의 동시성을 구조적으로 없애지 못하며 실패를 가릴 수 있어 채택하지 않음 |
| 스케줄러 비활성화·직접 worker 호출 | 테스트 목적이 실제 스케줄러 자동 처리이므로 채택하지 않음 |

## 4. 저장 기반과 주의할 점

스키마·migration·운영 잠금 정책 변경은 필요하지 않다.

관련 FK 연결은 `members/workspaces → recording_sessions → transcripts/document_generation_batches/document_generation_jobs`이며 문서·확인 데이터도 연결된다. `ON DELETE RESTRICT`인 FK도 `TRUNCATE … CASCADE`의 대상 확장을 막는 근거가 되지 않는다. DELETE와 TRUNCATE의 CASCADE 의미를 혼동하지 않는다.

우선안에서는 다음 검증을 유지한다.

- 실제 PostgreSQL 이미지 `pgvector/pgvector:pg18`과 Flyway 사용.
- 실제 생성·복구 스케줄러와 각각 100ms 주기.
- 접수 트랜잭션이 커밋된 뒤 실제 스케줄러가 작업을 자동 처리하는 흐름.
- 분류 1회와 두 주제 생성 완료, 문서 2건·확인 2건·시도 수 합계 3.
- classifier/generator 호출 시 실제 트랜잭션이 활성화되지 않았다는 기존 assertion.
- 기존 완료 대기 한도 10초와 polling 50ms.
- 클래스 종료 시 컨텍스트 폐기. 테스트 전체를 롤백 트랜잭션으로 감싸지 않음.

앞으로 같은 컨텍스트에 테스트를 추가하거나 DB reuse·seed를 도입하면 새 DB 전제를 다시 검토한다. 이번 제안을 다른 비동기 테스트의 정리 SQL 일괄 삭제로 확대하지 않는다.

## 5. 구현 이후의 검증 순서

아래는 실행 전에 확정한 검증 계획이다. 실제 수행 결과·준비 과정·미확정 범위는 별도 결과 문서에 기록했다.

### 1단계: 기존 실패 보존과 추가 관측

기존 실패 XML·콘솔·전후 소스를 기준 자료로 보존한다. 별도 진단 경로에서 변경 전 테스트를 최대 3회 실행하며, 성공 여부와 무관하게 PostgreSQL 서버 로그를 컨테이너 종료 전에 수집한다. 실패가 다시 발생하면 다음을 확보한다.

- 두 DB backend PID의 실제 SQL 및 query 시작 시각.
- `pg_locks`, `pg_stat_activity`, `pg_blocking_pids()`의 대기 중 스냅샷.
- 같은 DB의 `pg_class`·`pg_namespace`로 relation OID → 테이블 이름 매핑.
- 컨테이너 ID, 컨텍스트 ID, 실행 코드 버전, 전체 실행/단독 실행 여부.

deadlock 해소 후 조회한 잠금 목록으로 이미 사라진 대기를 복원했다고 주장하지 않는다. 3회 안에 재현되지 않으면 기존 실패와 코드 기반 가설을 유지하고, 상대 SQL 미확정 상태를 기록한다. 실패할 때까지 무제한 반복하지 않는다.

### 2단계: 실패 조건을 기준으로 최소 수정

기존 TRUNCATE 실패를 회귀 기준으로 삼고, 먼저 fixture 저장 전 초기 데이터 0건을 확인한다. 전용·빈 DB 전제가 맞으면 최초 TRUNCATE만 제거한다. 기존 본문과 assertion, scheduler 설정은 유지한다.

별도 진단 실행에서 잔여 member를 의도적으로 넣으면 초기 상태 검증이 fixture 생성 전에 실패하는지도 확인한다. 이 부정 사례는 새 JVM·컨테이너에서 격리해 확인하며, 같은 클래스에 두 번째 테스트를 추가해 새 DB 전제를 깨뜨리지 않는다. 예외를 잡아 무시하거나 자동 정리로 통과시키지 않는다.

### 3단계: 대상 테스트 안정성 확인

| 검증 | 조건 | 기대 결과 |
| --- | --- | --- |
| 실제 자동 처리 | 기존 스케줄러 테스트 | 기존 모든 assertion 통과, fixture 전 데이터 0건 |
| 반복 실행 | 새 JVM·컨테이너에서 순차 20회, 실제 테스트 수행 | 성공·실패 횟수 전체 기록, deadlock·잠금 timeout·완료 timeout 0건 |
| 초기 데이터 오염 | 별도 진단 실행에서 잔여 member 삽입 | 초기 상태 검사 실패, 자동 삭제나 재시도로 숨기지 않음 |
| 종료·격리 | 컨텍스트와 컨테이너 생명주기 확인 | 실행 종료 후 테스트 전용 자원·작업 잔존 없음 |

단독 실행 명령 후보는 기존 Gradle `integrationTest` 작업을 사용한다.

```bash
./gradlew integrationTest \
  --tests 'com.knot.backend.document.infrastructure.DocumentGenerationSchedulerIntegrationTest' \
  --rerun-tasks --no-build-cache --console=plain
```

각 반복의 XML·서버 로그는 다음 실행 전에 별도 경로로 보존한다. 단순 메서드 반복이 아닌 컨텍스트 시작 시점의 경합을 확인하기 위해 독립 실행을 사용한다. 20회 통과는 해당 조건의 관측 결과이며 간헐 실패가 영구적으로 없다는 증명은 아니다.

### 4단계: 전체 실행과 개선안 5 재판정

현재 개선안 5 후보를 유지한 상태에서 전체 통합 테스트를 3회 실행해 각각 기존 649개가 통과하는지 확인한다. 스케줄러뿐 아니라 전체 실패·오류·스킵과 background 예외도 기록한다. 실패 실행을 성공할 때까지 대체하지 않는다.

통합 검증 후 일반 설정에서 `spotlessCheck test integrationTest acceptanceTest bootJar`를 실제 실행한다. 진단 로그 수집 설정을 사용한 결과와 일반 회귀 결과를 구분한다.

이 작업의 성공 기준은 안정성이다. 개선안 5의 전체 성능을 다시 비교하려면 **스케줄러 수정은 양쪽에 동일하게 적용하고 JPA import 정렬만 달리한 새 전후 표본을 각각 3회 이상** 확보한다. 옛 실패 표본을 새 성공 표본으로 교체하거나 스케줄러 수정 효과와 컨텍스트 공유 효과를 섞어 계산하지 않는다.

커밋은 추후 요청 시 스케줄러 테스트 안정화와 개선안 5의 import 정렬을 분리한다. 이번 계획 작성은 commit·push 권한으로 해석하지 않는다.

## 6. 완료 기준과 다음 작업

1. 기존 실패가 최초 정리 SQL의 테이블 잠금 순환이었다는 사실과, 상대 SQL의 확정 여부를 구분해 기록한다.
2. 새 DB·초기 데이터 0건 전제가 실행으로 확인되고, 불필요한 TRUNCATE를 제거해도 기존 자동 처리·트랜잭션 검증이 유지된다.
3. 대상 독립 실행 20회와 전체 통합 3회의 모든 결과를 보존하고 deadlock·잠금 timeout·완료 timeout이 발생하지 않는다.
4. 일반 설정의 필수 회귀 검사가 통과하고 테스트 종료 후 전용 자원·작업이 남지 않는다.
5. 실제 실행 후 결과 문서를 계획과 별도로 작성한다. 미재현·미확정 사항과 실패 표본도 포함한다.

실행 결과, 전용 새 DB의 최초 TRUNCATE를 제거하고 초기 행 수를 읽기로 검증하는 변경을 채택했다. 독립20회·전체 통합3회 및 일반 전체 회귀에서 기존 검증·자원 종료를 확인했다. 상대 SQL과 정확한 테이블별 잠금 순서는 여전히 미확정이다. 개선안5의 전체 성능 재비교와 최종 채택은 이번 안정화 결과만으로 확정하지 않는다.
