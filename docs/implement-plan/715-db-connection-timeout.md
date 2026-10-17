# DB 연결 timeout 원인 분석과 다음 실험 계획

## 1. 범위와 브랜치

- 상태: 기존 증거 분석 완료, 다음 실험은 미실행
- 확인일: 2026-10-16
- 관련 이슈: [#715 — Backend CI 실행 시간 개선](https://github.com/woowacourse-teams/2026-knot/issues/715)
- 브랜치·HEAD: `be/refactor/#715`, `1b7d001ecb29d9021659e270655d3bd364da8803`
- 선행 결과: [개선안 5 — 절전 방지 후 전체 통합 테스트 3회 재측정 결과](../experiments/715-ci-awake-recheck.md)

이번 작업은 절전 방지 재측정에서 발생한 두 DB 연결 실패의 위치를 좁히고 다음 진단 실험을 설계하는 것이다. 제품 코드·테스트·관측 도구를 변경하거나 테스트를 재실행하지 않았다. 이번에 작성한 것은 이 계획 문서다.

**현재 확인된 문제는 컨테이너 시작 완료 이후, Flyway의 초기 JDBC 연결 수립 중 PostgreSQL 응답을 받지 못한 것이다.** 준비 완료 판정, 호스트 포트 경로, DB 서버 상태를 우선 분리해서 확인한다. SSL 설정 오류나 메모리 부족을 근본 원인으로 확정할 증거는 아직 없다.

### 분석 근거

아래 자료는 모두 2026-10-16에 확인했다. 설치 패키지 소스는 실제 실패 실행의 classpath 버전과 대조했다.

| 출처 | 이번 분석에 적용하는 사실 | 판정 |
| --- | --- | --- |
| `backend/output/ci-715/awake-recheck-20261016/awake-after-{1,2,3}/`의 XML·console·summary·자원 기록 | 실패 시각·예외 경로·컨테이너 상태 표본·실행 결과 | 실제 로컬 실행 관측 |
| `TestcontainersConfiguration`, 두 실패 테스트의 코드 | 컨텍스트별 PostgreSQL Bean, 테스트 생명주기와 DB 정리 위치 | 저장소 코드 관측 |
| 설치된 Testcontainers PostgreSQL/JDBC 2.0.5 소스 | PostgreSQL 준비 판정이 로그 대기 메서드로 재정의됨 | 설치 패키지 근거 |
| 설치된 PostgreSQL JDBC 42.7.11 소스 | SSL 응답 읽기 timeout 이후 비암호화 연결 fallback 경로 | 설치 패키지 근거 |
| 설치된 Spring Boot 4.1.0·Flyway 12.4.0 소스와 실제 예외 스택 | ServiceConnection의 연결 정보 전달, Flyway의 SimpleDriverDataSource 연결 | 설치 패키지·실패 실행 근거 |
| 실패 당시 PostgreSQL 서버 로그·inspect·실제 접속 대상 주소 | 기존 수집 자료에 없으며 해당 두 컨테이너도 이미 제거됨 | 미확인, 다음 실험 수집 대상 |

이 분석은 로컬 환경의 실패에 관한 것이다. GitHub Actions에서도 같은 원인이 발생한다고 판단하지 않는다. 제품 요구사항·API·스키마를 변경하는 계획은 아니다.

## 2. 실패 흐름과 원인 가설

### 관측된 실패

| 항목 | 재측정 1회 | 재측정 2회 |
| --- | --- | --- |
| 전체 명령 시간 | 208.204초 | 210.843초 |
| 실패 클래스 | `RecordingTranscriptionFlowIntegrationTest` | `DocumentGenerationResultIntegrationTest` |
| 클래스 Duration | 54.338초 | 52.541초 |
| PostgreSQL 시작 소요 로그 | 1.002초 | 1.117초 |
| 시작 완료·JDBC URL 로그 시각 | 14:12:23.240 | 14:15:19.703 |
| Spring 초기화 중단 로그 시각 | 14:13:13.447 | 14:16:09.830 |
| 위 두 로그 사이 간격 | **50.207초** | **50.127초** |
| 컨테이너가 출력한 접속 포트 | `localhost:50075` | `localhost:51040` |
| 최초 컨텍스트 로딩 실패 | 1건 | 1건 |
| failure threshold에 따른 후속 실패 | 2건 | 28건 |

시간은 같은 실행의 로그 시각을 뺀 값이다. 약 50초는 컨테이너 시작 완료부터 Spring 초기화 중단까지의 구간이며, 단일 JDBC 호출이나 단일 timeout 설정값으로 측정한 시간이 아니다. 전체 실행에서 50초만 빼서 정상 성능을 추정하지 않는다.

1회차의 녹음 흐름 테스트는 첫 컨텍스트에서 마이그레이션과 테스트 한 건을 완료했다. 그다음 새 컨텍스트·새 PostgreSQL 컨테이너를 준비하는 과정에서 실패했다. 이전 성공 DB 포트는 `50065`, 새 컨테이너가 출력한 포트는 `50075`였다.

2회차의 문서 생성 결과 테스트는 해당 컨텍스트의 초기화에서 실패했다. 3회차에서는 두 클래스 모두 성공했다. 따라서 특정 테스트의 assertion이 항상 실패하는 문제로 보기 어렵다.

```text
새 Spring 컨텍스트 초기화
→ PostgreSQL 서버 준비 로그를 기다림
→ 컨테이너 시작 완료·JDBC URL 출력
→ Flyway 초기화용 SimpleDriverDataSource가 연결 요청
→ PostgreSQL JDBC의 SSLRequest 응답 읽기 timeout
→ 비암호화 연결 fallback의 인증 응답 경로에서 EOF
→ Flyway 연결 실패, SQL State 08001
→ Spring 컨텍스트 초기화 실패
→ 같은 설정의 후속 테스트는 failure threshold에 따라 실패
```

두 실패 컨텍스트에서는 Flyway의 `Database: ...` 로그에 도달한 흔적이 없고, 예외도 연결을 여는 단계에 위치한다. 이번 최초 실패는 마이그레이션 SQL 실행이나 테스트 메서드의 TRUNCATE보다 앞선다.

### 설치 코드에서 확인한 해석

**컨테이너 시작 완료와 호스트 JDBC 사용 가능 상태를 구분해야 한다.** Testcontainers 2.0.5의 `PostgreSQLContainer.waitUntilContainerStarted()`는 PostgreSQL 준비 로그를 두 번 기다린다. 상위 `JdbcDatabaseContainer`에 존재하는 JDBC 연결·`SELECT 1` 대기 루프를 이 메서드에서는 실행하지 않는다. 현재 테스트 설정에는 이를 대체하는 준비 검사가 없다. 따라서 시작 완료 로그만으로 호스트의 매핑 포트를 통한 실제 JDBC 연결 성공을 증명할 수 없다.

**이번 오류는 Hikari 풀에서 연결을 기다린 스택이 아니다.** 실제 실패 스택은 `SimpleDriverDataSource → PostgreSQL Driver → enableSSL`이다. Hikari의 pool timeout을 늘리는 방안을 첫 대응으로 삼을 근거가 없다.

**SSL 경로에서 실패했다는 사실과 SSL 설정이 잘못됐다는 결론은 다르다.** JDBC 42.7.11 소스에서는 기본 조건에서 `sslmode=prefer`를 선택하고, SSL 응답 읽기 timeout 이후 비암호화 연결로 다시 시도할 수 있다. 실제 두 스택에도 이 fallback과 suppressed EOF가 있다. 기본 `sslResponseTimeout`은 5,000ms지만, 실제 적용 속성·각 연결 시도의 시작과 종료는 수집되지 않았다. 약 50초 전체를 SSL 대기 한 번이나 Flyway 재시도 횟수로 환산하지 않는다.

### 우선순위별 가설

| 우선순위 | 가설 | 근거와 남은 확인 |
| --- | --- | --- |
| 1 | 서버 준비 로그 이후에도 호스트 매핑 포트를 통한 연결 수립이 일시적으로 정상 동작하지 않음 | 로그 기반 준비 판정 이후 두 번 모두 연결 응답 읽기 실패. DB 내부 TCP 연결과 호스트 JDBC 연결을 같은 구간에 비교해야 함 |
| 2 | 호스트 또는 Docker VM의 자원 압박이 DB 응답·포트 전달을 지연시킴 | 실패 구간에서도 swap 사용과 swap in/out 증가 관측. 호스트 전체 지표이므로 DB·Docker VM의 지연 원인으로 확정할 수 없음 |
| 3 | 컨텍스트 교체·종료 과정에서 실제 JDBC 대상이나 컨테이너 생명주기가 어긋남 | 녹음 흐름 테스트는 메서드마다 컨텍스트 재생성. 반면 문서 결과 테스트에는 같은 DirtiesContext 설정이 없어 이 패턴만으로 두 실패를 설명할 수 없음. 실제 Flyway URL과 컨테이너 매핑을 대조해야 함 |

드라이버의 SSL fallback은 두 실패에서 확인된 경로다. 이것이 장애의 시작점인지, 이미 응답하지 않는 DB 연결에서 나타난 결과인지는 별도로 확인한다.

### 자원 지표가 말해 주는 범위

실패 컨테이너는 대기 구간의 Docker stats 표본 네 건씩에 계속 나타났다. 1회차 표본은 14:12:29~14:13:05, 2회차는 14:15:24~14:16:00 구간이다. 컨테이너 메모리는 약 26~30MiB였지만, 이 표본만으로 PostgreSQL 프로토콜 응답이나 과거 OOM·재시작 여부를 보장할 수 없다.

| 해당 네 표본 사이 관측 | 1회차 | 2회차 |
| --- | ---: | ---: |
| 호스트 swap 사용량 범위 | 18,063.44~18,467.75M | 18,831.69~18,919.81M |
| `vm_stat` Swapins 증가 | 66,402 | 39,171 |
| `vm_stat` Swapouts 증가 | 46,140 | 46,384 |

증가량은 약 36초 간격의 첫·마지막 표본 차이이며, 약 50초 실패 구간 전체의 합계가 아니다. `vm_stat` 카운터를 바이트 단위나 특정 프로세스의 사용량으로 해석하지 않는다. swap 사용량이 크다는 사실만으로 현재의 심각한 메모리 압박을 확정하지 않는다.

세 실행 모두 OS 절전 기록과 수십 초의 독립 JVM·외부 heartbeat 공백은 없었다. 따라서 과거 243초 실행에서 보인 공통 관측 중단과 같은 현상으로 묶을 근거는 약하다. 다만 heartbeat가 정상이어도 DB 서버·Docker VM·네트워크 경로 하나만 지연될 수 있다.

## 3. 기존 코드 책임과 제안하는 관측

### 기존 코드

| 위치·클래스 | 현재 책임 | 이번 실패와의 관계 |
| --- | --- | --- |
| `backend/src/test/java/com/knot/backend/testsupport/TestcontainersConfiguration.java` | `postgresContainer()`로 PostgreSQL Bean을 만들고 `@ServiceConnection` 제공 | 컨텍스트별 컨테이너·접속 정보의 기준. 별도 JDBC 준비 검사 없음 |
| `RecordingTranscriptionFlowIntegrationTest` | 실제 DB와 외부 HTTP 테스트 서버로 녹음 전사 흐름 검증 | `AFTER_EACH_TEST_METHOD`로 컨텍스트를 다시 만듦. DynamicPropertySource는 전사 설정이며 DB URL을 직접 바꾸지 않음 |
| `DocumentGenerationResultIntegrationTest` | 실제 트랜잭션·동시성·문서 생성 결과 검증 | dev 프로필과 Mockito Bean/Spy 구성이 있고, 메서드별 DirtiesContext는 없음 |
| 두 테스트의 `setUp()` | 컨텍스트 준비 이후 데이터 정리와 fixture 생성 | 이번 최초 연결 실패 지점보다 뒤에 실행되는 작업 |
| 기존 재측정의 관측 JAR·init script·수집기 | 클래스·메서드·컨텍스트·자원·격리 경계 기록 | 기존 흐름을 유지하되 연결 경계·서버 상태 관측이 추가로 필요 |

Spring Boot 설치 소스에서는 Flyway의 ServiceConnection 정보가 컨테이너의 `getJdbcUrl()` 등을 통해 전달된다. 설계상 같은 컨테이너를 가리키지만, 실패 순간의 실제 Driver 접속 대상은 별도로 기록해야 한다.

### 다음 실험에서만 추가할 관측안 — 아직 미구현

| 관측 지점 | 수집할 정보 | 목적 |
| --- | --- | --- |
| Flyway 최초 연결 직전·성공·예외 | 컨텍스트·컨테이너 ID, 실제 DataSource 유형·URL, 연결 요청별 시작·종료, SQLState·suppressed 예외 | 실제 접속 대상과 약 50초의 구간 분해 |
| JDBC 연결 경계 | 실제 목적지 IP·포트, 적용 SSL/접속/read/login timeout, fallback 단계와 시각 | localhost 주소 선택과 SSL/fallback 시도 구분 |
| 컨테이너 시작·연결 지연·실패·종료 직전 | 타임스탬프가 포함된 서버 로그, Docker inspect의 상태·OOMKilled·ExitCode·RestartCount·포트 매핑 | 서버 응답 지연, 종료·재시작, 잘못된 대상 구분 |
| 연결이 5초 넘게 진행 중인 시점 | 테스트 JVM thread dump, 별도 프로세스의 서버 상태 수집 | 예외가 나기 전 대기 위치 보존. 5초는 수집 발동 기준이며 테스트 timeout 변경이 아님 |
| 호스트·Docker VM | 기존 heartbeat·swap·vm_stat·컨테이너 stats, 가능하면 VM 메모리·압박 지표 | 호스트 전체와 DB·VM의 상태 분리 |

관측은 별도 실험용 JAR·init script·외부 수집기에 둔다. 구체적인 hook은 Flyway 초기화 순서에서 URL을 읽을 수 있고, 추가 컨텍스트 생성이나 연결 재시도를 유발하지 않는지 확인한 뒤 선택한다. 외부 관측 실패도 기록하고 실제 테스트 예외를 그대로 보존한다.

연결 지연 시 진단용으로 두 경로를 비교하는 안을 둔다. 하나는 컨테이너 내부에서 `127.0.0.1:5432`에 TCP로 접속하는 SQL 검사, 다른 하나는 별도 프로세스에서 실제 매핑 주소로 접속하는 JDBC 검사다. 두 검사 모두 연결·인증·`SELECT 1`까지 확인하며, Unix socket 성공이나 `pg_isready` 성공만으로 대체하지 않는다. 별도 검사 프로세스에는 종료 한도를 둔다.

이 검사는 원래 연결을 성공시켜 주는 재시도가 아니다. 별도 연결이 관측 조건에 영향을 줄 수 있으므로 발동 시각을 남기고, 첫 증거 수집 실행에서는 새 SQL 검사 없이 로그·상태·스택부터 수집한다. 경로 비교는 같은 실패가 확보된 후의 진단 단계에서 사용한다.

## 4. 저장 기반과 실험 조건

스키마·마이그레이션 변경은 없다. 기존 검증, 실제 PostgreSQL/Flyway, 스케줄러 수정, rollback·데이터 격리를 유지한다.

- 기존 변경 후 후보와 테스트 649개를 고정하고 실제 클래스 실행 순서를 비교한다.
- 실행마다 새 테스트 JVM, 힙 1GiB·컨텍스트 캐시 8을 유지한다.
- 전원 연결·절전 방지와 기존 heartbeat를 유지한다.
- Gradle daemon·의존성·Docker 이미지 캐시 조건을 기록한다. 컴파일 준비와 측정 시간을 구분한다.
- 기존 공유 QA 프로세스·컨테이너를 임의 종료하지 않는다. 실험 대상 컨테이너만 추적한다.
- SSL 모드, timeout, Flyway retry, 테스트 순서, DB 이미지·자원 할당은 첫 관측 실험에서 변경하지 않는다.
- 새 관측은 오버헤드가 있으므로 기존 126초 실행과 바로 성능 개선율을 비교하지 않는다.

원본 `awake-recheck-20261016` 자료는 보존하고, 새 실험 자료는 별도 디렉터리에 저장한다. 연결 URL은 사용자 정보와 민감한 query parameter를 제거하며 비밀번호·전체 환경변수·전체 Properties를 출력하지 않는다.

## 5. 진단 실험과 검증 순서

이번 단계는 제품 동작 추가보다 원인 구분이 목적이다. 관측기의 기록·오류 보존을 먼저 검증하고 실제 PostgreSQL 실행으로 확인한다. 기존 실패를 통과시키기 위한 timeout 연장이나 자동 재실행을 먼저 넣지 않는다.

### 1단계 — 관측 준비 검증

기존 실패 XML·로그로 최초 실패와 후속 threshold 실패를 구분하는 집계를 확인한다. 관측기가 성공·연결 실패·수집 timeout을 각각 기록하고, 비밀번호를 남기지 않으며, 테스트 종료 코드를 바꾸지 않는지 검증한다. 이는 수집기 검증이며 DB 원인 증명이 아니다.

### 2단계 — 변경 후 전체 통합 테스트를 고정 3회 관측

실패가 서로 다른 클래스에서 나타났으므로 **전체 실행의 컨텍스트 교체와 자원 누적 조건을 유지하는 관측을 첫 대상으로 선택한다.** 단독 실행 성공만으로 전체 실행 문제를 배제하지 않는다.

기존 `integrationTest --rerun --no-build-cache` 실행 틀을 재사용한다. 실험별 보고서 경로와 고정 runtime·관측 JAR·수집 옵션을 확인한 뒤 최종 명령을 확정한다. 연결 관측은 추가하지만 준비 검사·재시도 등 동작 변경은 넣지 않는다.

| 관측·검증 | 기대 결과·판정 |
| --- | --- |
| 전체 3회 실행 | 성공·실패를 모두 보존. 실패를 새 성공 실행으로 교체하지 않음 |
| 두 실패 클래스의 컨텍스트·접속 대상 | Flyway 실제 주소와 해당 컨테이너 매핑이 일치하는지 확인 |
| 느린 연결의 경계 | 연결 요청·SSL/fallback·최종 예외 시각을 연결. 못 얻은 경계는 미확인으로 표시 |
| 서버 상태 | 컨테이너 제거 전에 로그·inspect 수집. 수집 불가도 명시 |
| 전체 테스트 inventory | 649개·오류·실패·스킵·실제 실행 순서 대조 |
| 개선안 5 대상 74개 | 기존 assertion 성공, 트랜잭션·rollback·관측 7개 테이블 잔여 행 0 확인 |
| 스케줄러 | 기존 deadlock 수정 이후 검증이 유지되는지 확인 |

3회 모두 성공해도 이 간헐 실패의 해결로 판정하지 않는다. 관측 조건에서 미재현으로 기록한다. 전체가 실패하면 74개 성공만으로 전체 회귀 통과라고 기록하지 않는다.

### 3단계 — 관측 결과에 따라 하나의 변수만 추가 확인

| 추가 관측 결과 | 좁혀지는 범위 | 다음 단일 변수 실험 후보 |
| --- | --- | --- |
| 실제 Driver 주소와 새 컨테이너 매핑이 다름 | 연결 정보 전달·생명주기 | 컨텍스트·Bean 생성/종료와 URL 전달 경로를 추적해 잘못된 참조를 재현 |
| 대상은 일치하고 내부 TCP SQL은 빠른데 호스트 JDBC만 느림 | 매핑 포트·주소 선택·Docker 전달 경로 | 실제 IPv4/IPv6 목적지를 확인하고 주소 경로 하나만 바꿔 비교 |
| 내부·호스트 TCP SQL이 함께 느리고 서버 지연 로그·VM 압박이 동반 | DB 서버·Docker VM·호스트 자원 | 다른 설정을 유지한 채 통제 가능한 자원 조건 하나를 분리해 비교 |
| 시작 직후 실제 JDBC 사용 불가 구간이 반복 관측됨 | 준비 완료 판정의 부족 가능성 | 제한 시간·실패 기록을 갖춘 JDBC 준비 검사 하나를 추가하는 후속 실험 검토 |
| SSL 응답 경로만 반복 지연하고 DB·주소 경로는 정상으로 관측됨 | SSL 협상 경로 | 테스트 환경에서 SSL 모드 하나만 바꾸는 진단 비교 검토. 관측만으로 기본 설정 변경을 채택하지 않음 |

내부·호스트 검사는 별도 시도이므로 결과가 시차를 두고 정상화됐을 가능성도 고려한다. 한 번의 성공으로 경로 원인을 확정하지 않는다. 이번 계획에서는 위 후보를 동시에 적용하지 않는다.

## 6. 완료 기준과 다음 작업

1. 최초 연결 실패와 그에 따른 후속 테스트 실패를 구분한다.
2. 실제 Flyway 연결 대상과 컨테이너·컨텍스트의 대응을 설명한다.
3. 약 50초 구간에서 확인된 대기·fallback·서버 상태와 여전히 모르는 부분을 구분한다.
4. 자원 압박·포트 경로·준비 판정·생명주기 가설 중 다음에 바꿀 변수 하나를 근거와 함께 선택한다. 재현되지 않으면 미재현으로 남긴다.
5. 모든 실행과 수집 실패를 보존하고, 기존 74개 격리·스케줄러·전체 649개 결과를 각각 기록한다.

원인을 좁혀 수정안을 선택한 뒤에 최소 변경과 반복·전체 회귀 검증을 별도로 진행한다. 안정적인 전체 성공 표본이 부족한 현재는 **개선안 5 채택 보류를 유지**한다. 이번 문서 작성에는 구현·테스트 재실행·commit·push를 포함하지 않았다.

### 근거 파일

- [실패 1 XML](../../backend/output/ci-715/awake-recheck-20261016/awake-after-1/xml/TEST-com.knot.backend.recording.application.RecordingTranscriptionFlowIntegrationTest.xml)
- [실패 2 XML](../../backend/output/ci-715/awake-recheck-20261016/awake-after-2/xml/TEST-com.knot.backend.document.application.DocumentGenerationResultIntegrationTest.xml)
- [1회 자원 기록](../../backend/output/ci-715/awake-recheck-20261016/awake-after-1/resources.jsonl)
- [2회 자원 기록](../../backend/output/ci-715/awake-recheck-20261016/awake-after-2/resources.jsonl)
- [전체 집계](../../backend/output/ci-715/awake-recheck-20261016/analysis.json)
- [컨테이너 설정](../../backend/src/test/java/com/knot/backend/testsupport/TestcontainersConfiguration.java)
- [녹음 흐름 테스트](../../backend/src/test/java/com/knot/backend/recording/application/RecordingTranscriptionFlowIntegrationTest.java)
- [문서 생성 결과 테스트](../../backend/src/test/java/com/knot/backend/document/application/DocumentGenerationResultIntegrationTest.java)
