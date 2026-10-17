# DB 연결 관측 추가 후 전체 통합 테스트 3회 실행 결과

- 확인일: 2026-10-16
- 관련 이슈: [#715 — Backend CI 실행 시간 개선](https://github.com/woowacourse-teams/2026-knot/issues/715)
- 실험 계획: [DB 연결 timeout 원인 분석과 다음 실험 계획](../implement-plan/715-db-connection-timeout.md)
- 선행 결과: [절전 방지 후 전체 통합 테스트 3회 재측정](715-ci-awake-recheck.md)
- 판정: **전체 649개 테스트가 3회 모두 통과했다. JDBC 연결 timeout은 미재현이며, 과거 실패의 근본 원인은 아직 확정하지 못했다.**

## 1. 실험 배경과 목적

이전 절전 방지 재측정에서는 두 실행이 PostgreSQL 초기 연결에 실패했다.

| 이전 실행 | 실패 클래스 | 관측된 실패 |
| --- | --- | --- |
| 1회 | RecordingTranscriptionFlowIntegrationTest | 최초 컨텍스트 로딩 실패 1건과 후속 failure threshold 실패 2건 |
| 2회 | DocumentGenerationResultIntegrationTest | 최초 컨텍스트 로딩 실패 1건과 후속 failure threshold 실패 28건 |

두 실패 모두 컨테이너 시작 완료 이후 약 50초가 지나 Spring 초기화가 중단됐다. 예외는 Flyway의 초기 JDBC 연결에서 SSL 응답을 읽는 중 발생한 timeout과 후속 비암호화 연결의 EOF였다. SQL 실행이나 테스트 본문의 TRUNCATE보다 앞선 연결 수립 단계의 문제로 좁혔지만, 당시 서버 로그·실제 접속 대상·제거 전 상태는 없었다.

이번에는 연결 단계와 서버 상태를 추가로 관측해, 같은 실패가 발생하면 어느 경로에서 응답이 멈추는지 구분하고자 했다. 실패하지 않는 실행에서는 실제 연결 대상과 서버 상태가 정상인지 확인했다.

## 2. 실행 조건과 추가한 관측

| 항목 | 조건 |
| --- | --- |
| 브랜치·HEAD | `be/refactor/#715`, `1b7d001ecb29d9021659e270655d3bd364da8803` |
| 실행 후보 | 이전 실험의 변경 후 고정 runtime 재사용. 현재 main·test·test resources와 기존 snapshot의 파일별 hash 일치 확인 |
| 실행 횟수 | 전체 통합 테스트 정확히 3회. 추가 전체 워밍업·실패 대체 실행 0회 |
| 실행 틀 | `integrationTest --rerun --no-build-cache`, 실행별 리포트 경로 분리 |
| Java·도구 | OpenJDK 25.0.3, Gradle Wrapper 9.5.1 |
| JVM·캐시 | 실행마다 새 테스트 JVM, 최대 힙 1GiB, 컨텍스트 캐시 최대 8 |
| DB | 실제 `pgvector/pgvector:pg18` PostgreSQL과 Flyway 유지 |
| 전원 | 사용자 재개 요청 후 AC Power·충전 중 확인. `caffeinate -i` 적용. AC 전원 모드 0 기록 |
| 준비 작업 | 측정 3회 모두 compile·resource 작업 4개 UP-TO-DATE |
| 기존 검증 | 대상 74개 격리 listener, 컨텍스트 캐시·트랜잭션 로그, JVM·외부 heartbeat 유지 |
| 변경하지 않은 조건 | 테스트 본문·assertion·스케줄러 수정·SSL·timeout·retry·컨테이너 준비 검사 |

제품 코드·테스트 소스·스키마·마이그레이션은 변경하지 않았다. 기존 boundary observer JAR은 byte 단위로 같고, classpath에는 새 연결 관측 JAR 하나만 추가됐다. 외부 의존성 JAR 목록도 동일했다. 관측 소스와 JAR의 hash가 3회 실행 동안 바뀌지 않았음을 대조했다.

추가 관측은 별도 Java agent로 다음 지점을 기록했다.

- Flyway migrate 호출, DataSource 식별자와 실제 연결 URL
- PostgreSQL Driver 연결 요청의 시작·성공·예외와 parent 호출
- SSL 모드·협상·인증·fallback 단계 및 실제 소켓 IP·포트
- PostgreSQL 컨테이너별 서버 로그 연속 수집과 제거 전 최종 로그
- 컨테이너 시작 완료·종료 직전 상태, 포트 매핑·OOMKilled·재시작 횟수
- 연결이 5초를 넘으면 thread dump와 서버 상태를 수집하는 관측기

준비 검사나 SQL probe를 추가하지 않았고, 연결 실패를 자동 재시도하거나 성공으로 바꾸지 않았다. URL의 민감한 부분과 비밀번호는 가렸다.

### 관측기 사전 검증

javac 컴파일, 대상 6개 클래스의 계측 적용, 원래 SQLException 객체·SQLState·suppressed 예외 보존, 느린 연결 감지·thread dump, 소켓 주소 기록과 비밀번호 가림을 검증했다. 이는 합성 실패와 loopback 소켓을 사용한 **수집기 검증**이며, 실제 PostgreSQL 장애 재현을 뜻하지 않는다.

사전 검증을 반복하면서 이전 thread dump가 남아 파일 개수 검사 한 항목이 실패했다. 실행별 selftest 디렉터리로 분리한 뒤 최종 검증을 통과했다. 이 준비 과정은 실제 전체 통합 테스트 3회와 구분하며, 기존 준비 자료도 남겼다.

## 3. 전체 실행 결과

| 실행 | Gradle 명령 전체 | monotonic 시간 | XML 클래스 Duration 합계 | 성공 | 실패·오류·스킵 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1회 | **187.797초** | 187.802초 | 173.218초 | 649 | 0 |
| 2회 | **163.754초** | 163.759초 | 152.679초 | 649 | 0 |
| 3회 | **183.597초** | 183.602초 | 171.319초 | 649 | 0 |

명령 전체 시간의 중앙값은 183.597초, 범위는 163.754~187.797초였다. 이 값은 관측이 추가된 로컬 진단 실행의 시간이다. 이전 126초 성공 실행이나 변경 전 결과와 직접 비교해 개선율로 사용하지 않는다.

3회 모두 이전과 같은 649개 inventory와 실제 클래스 실행 순서를 확인했다. 다음 초기화 지표도 동일했다.

| 지표 | 1회 | 2회 | 3회 |
| --- | ---: | ---: | ---: |
| 서로 다른 컨텍스트 설정 key | 32 | 32 | 32 |
| 실제 저장된 컨텍스트·Spring 시작 로그 | 39 | 39 | 39 |
| PostgreSQL 시작 로그 | 47 | 47 | 47 |
| Flyway 적용 완료 로그 | 117 | 117 | 117 |

이번 실행에서 새 테스트 실패 로그는 발생하지 않았다. 이전 실패 XML·로그는 원래 디렉터리에 그대로 보존했고, 이번 실행별 console·XML·HTML·연결 로그를 별도 보존했다.

## 4. JDBC 연결과 PostgreSQL 서버 상태

### 연결 수립 결과

| 지표 | 1회 | 2회 | 3회 |
| --- | ---: | ---: | ---: |
| PostgreSQL Driver 연결 호출 | 899 | 904 | 909 |
| 그중 Flyway migrate 아래의 연결 호출 | 240 | 240 | 240 |
| 연결 실패 | **0** | **0** | **0** |
| 1초를 넘긴 연결 | 0 | 0 | 0 |
| 가장 긴 Driver 연결 호출 | **81.696ms** | **117.106ms** | **94.294ms** |
| 5초 지연 관측 발동 | 0 | 0 | 0 |
| 살아 있는 컨테이너 매핑과 대응되지 않은 연결 | 0 | 0 | 0 |

총 2,712건의 Driver 연결과 그중 720건의 Flyway 연결이 성공했다. 이 호출 수에는 초기화 이후 풀·테스트 실행 중의 연결도 포함되므로 컨테이너 초기화 횟수나 독립적인 장애 재현 횟수로 해석하지 않는다.

모든 관측 소켓은 `127.0.0.1`로 연결됐고 포트는 해당 시점에 살아 있는 테스트 컨테이너와 일치했다. 이미 종료를 시작한 컨테이너의 포트를 대응 대상으로 사용하지 않았다. 이번 실행에서 잘못된 고정 URL이나 IPv6 경로를 사용한 증거는 없었다.

실제 관측된 SSL 모드는 `PREFER`, Flyway connect retries는 0이었다. Driver에 전달된 Properties에서는 SSL 모드·접속/read/login/SSL 응답 timeout의 명시적 override가 없었다. 설치된 드라이버 기본값과 실제 관측값은 구분한다.

**이전 약 50초의 연결 실패는 이번 3회에서 재현되지 않았다.** 실제 실패 중의 SSL 대기·fallback 시간을 새로 분해한 결과는 확보하지 못했다. 느린 연결 시 서버·스택 수집 경로도 이번 실제 DB 실행에서는 발동하지 않았다.

### 서버 로그와 제거 전 상태

| 확인 항목 | 1회 | 2회 | 3회 |
| --- | ---: | ---: | ---: |
| 서버 로그를 보존한 PostgreSQL 컨테이너 | 47/47 | 47/47 | 47/47 |
| 시작 완료·종료 직전 상태 기록 | 94건 | 94건 | 94건 |
| 연속 로그와 제거 전 최종 로그의 내용 일치 | 47/47 | 47/47 | 47/47 |
| 보존한 서버 로그의 FATAL | 0 | 0 | 0 |
| 관측 상태의 OOMKilled·Restarting·재시작 횟수 이상 | 0 | 0 | 0 |

세 실행의 총 141개 컨테이너에 대해 서버 로그와 총 282건의 상태 기록을 보존했다. 상태 기록은 시작 완료와 **실제 stop 직전**의 조회 결과이며, 종료 후 최종 exit 상태를 관측한 것은 아니다. 이 정상 실행의 상태로 과거 실패 컨테이너의 OOM·재시작을 배제하지 않는다.

### 로그 수집 종료 알림 처리

각 실행에서 연속 로그 스트림을 닫을 때 `ClosedByInterruptException` 47건이 `OBSERVER_ERROR`로 기록됐다. 원시 기록을 삭제하거나 오류 0건으로 표현하지 않았다.

141건 모두 같은 컨테이너의 최종 로그 저장 완료 이후 2초 이내에 발생했다. 최종 로그 저장 성공과 연속·최종 로그의 byte 일치를 대조해 **최종 저장 이후 스트림을 닫는 과정의 알림**으로 분류했다. 그 밖의 observer 오류, stderr의 기록 실패, 누락된 연결 종료 기록은 없었다. 향후 관측기에서는 의도적 종료 알림을 별도 종류로 기록하도록 정리할 필요가 있다.

## 5. 기존 검증과 데이터 격리

| 항목 | 1회 | 2회 | 3회 |
| --- | ---: | ---: | ---: |
| 개선안 5 대상 테스트 성공 | 74 | 74 | 74 |
| 활성 테스트 트랜잭션 | 74 | 74 | 74 |
| rollback 로그 | 74 | 74 | 74 |
| 트랜잭션 전·rollback 후 관측 | 각 74 | 각 74 | 각 74 |
| 관측 7개 테이블의 잔여 행 | 0 | 0 | 0 |
| 대상 테스트의 실제 공유 컨텍스트 | 1 | 1 | 1 |

관측 테이블은 members·workspaces·documents·document_confirmations·document_generation_jobs·transcripts·transcript_segments다. 테스트 트랜잭션 밖에서 전후 행 수를 확인했다.

이전 실패 클래스와 스케줄러도 다음과 같이 성공했다.

| 클래스 | 매 실행 테스트 수 | 1회 Duration | 2회 Duration | 3회 Duration | 판정 |
| --- | ---: | ---: | ---: | ---: | --- |
| RecordingTranscriptionFlowIntegrationTest | 4 | 8.365초 | 10.731초 | 15.958초 | 모두 성공 |
| DocumentGenerationResultIntegrationTest | 29 | 5.282초 | 3.130초 | 4.408초 | 모두 성공 |
| DocumentGenerationSchedulerIntegrationTest | 1 | 3.051초 | 2.157초 | 3.572초 | 모두 성공 |
| RecordingConnectionExpirySchedulerIntegrationTest | 1 | 2.498초 | 2.890초 | 2.670초 | 모두 성공 |
| RetentionCleanupSchedulerIntegrationTest | 1 | 2.832초 | 1.653초 | 2.148초 | 모두 성공 |

이번 전체 통합 실행 범위에서는 기존 검증과 관측한 데이터 격리가 유지됐다. 별도의 단독·역순·단위·인수 전체·bootJar 회귀를 이번 진단에 추가하지 않았다.

## 6. 자원 관측과 해석 한계

세 실행 모두 실행 구간의 OS Sleep·DarkWake·Wake는 0건, Hikari starvation/clock-leap 경고는 0건이었다.

| 지표 | 1회 | 2회 | 3회 |
| --- | ---: | ---: | ---: |
| 독립 JVM heartbeat 최대 간격 | 1.157초 | 1.026초 | 1.065초 |
| 외부 heartbeat 최대 간격 | 1.020초 | 1.008초 | 1.019초 |
| 호스트 swap 사용량 범위 | 16,940.94~19,653.75M | 18,620.06~19,836.25M | 19,419.75~20,702.19M |

자원 수집 명령 오류·timeout은 없었다. swap 사용량이 이전 실패 구간과 겹치거나 더 큰 상태에서도 연결이 성공했으므로 **swap 사용량 자체만으로 연결 실패를 설명하기 어렵다.** 순간적인 호스트·Docker VM 압박의 개입까지 배제하는 결과는 아니다. 호스트 전체 지표를 PostgreSQL이나 테스트 JVM에 귀속하지 않는다.

### 관측이 연결 시점에 준 영향

새 agent는 컨테이너 시작 이후 로그 스트림 등록과 상태 조회를 수행한다. 이 작업은 원래 DB 설정을 바꾸지 않지만, 첫 연결까지의 시간에 영향을 줄 수 있다.

| 컨테이너 준비 관측 → 첫 Driver 호출 | 1회 | 2회 | 3회 |
| --- | ---: | ---: | ---: |
| 중앙값 | 49ms | 45ms | 57ms |
| 범위 | 7~296ms | 8~321ms | 8~368ms |

이는 새 `CONTAINER_READY` 관측 시각부터 첫 연결 호출까지의 간격이지, 추가 관측만의 순수 비용은 아니다. 이전 실패 실행에는 같은 Driver 시작 경계가 없어서 정확한 전후 비교도 할 수 없다.

따라서 새 3회의 성공을 관측 코드의 안정화 효과로 해석하지 않는다. 연결 시점 변화와 호스트 상태 변화가 간헐 실패의 재현율에 영향을 줬을 가능성을 남긴다.

## 7. 원인 가설별 판단과 다음 단계

| 가설 | 이번 결과 | 현재 판단 |
| --- | --- | --- |
| 잘못된 연결 대상·컨텍스트 생명주기 불일치 | 관측한 모든 연결이 살아 있는 컨테이너 매핑과 일치 | 이번 정상 실행에서는 미관측. 과거 실패 순간의 대상은 여전히 미확인 |
| 준비 로그 이후 호스트 연결이 아직 사용 불가 | 첫 연결까지 모두 빠르게 성공 | 실패가 없어 검증하지 못함. 로그 기반 준비 판정의 한계는 그대로 남음 |
| SSL 설정 자체의 상시 오류 | 같은 PREFER 모드에서 2,712건 성공 | 고정 설정만으로 항상 실패하는 설명은 약해짐. 특정 시점의 협상 장애는 미확인 |
| 호스트·Docker VM 자원 압박 | 높은 swap 상태에서도 정상 연결 | swap 총량만으로 원인 확정 불가. 순간적인 압박·포트 전달 지연은 미확인 |
| 마이그레이션 SQL·TRUNCATE deadlock | 이전 실패 스택은 SQL 실행 전이며 이번 스케줄러도 모두 성공 | 이번 연결 timeout의 직접 원인으로 볼 근거 부족 |

**최종 판단은 “연결 관측을 추가한 조건에서 3회 미재현, 기존 통합·격리 검증 통과, 근본 원인 미확정”이다.** 새 실패가 없어 이전 50초 구간의 정확한 구성이나 원인을 확정하지 못했다.

다음 실험은 SSL 비활성화·timeout 연장·재시도 추가를 동시에 적용하기보다 **관측이 첫 연결 시점에 주는 영향을 분리하는 것**을 우선 권고한다. 연결 단계 기록을 유지하면서 컨테이너 로그 등록·inspect를 비동기로 분리한 경량 관측과 현재 관측을 같은 후보·조건에서 비교하는 방식이다. 이 후속 실험이나 구현은 이번에 수행하지 않았다.

기능·격리의 전체 3회 성공 표본은 확보했다. 다만 변경 전후 성능 실험이 아니므로 이번 결과로 개선안 5의 전체 CI 개선율이나 최종 성능 채택을 결정하지 않는다.

## 8. 산출물과 검증 범위

원시 자료 루트: `backend/output/ci-715/connection-observation-20261016/`

- 실행별 `console.log`, `xml/`, `html/`, `summary.json`
- `connection/connections.jsonl`: 연결·소켓·컨테이너 상태·종료 알림
- 컨테이너별 `*.server-follow.log`, `*.server-before-stop.log`
- `driver-spans.json`, `events.tsv`, `boundaries.tsv`, `heartbeat.jsonl`, `resources.jsonl`
- 전원·프로세스·Docker 목록·실제 classpath와 계약·selftest·hash
- 전체 `analysis.json`, `audit.json`, `report-details.json`, 실행기·관측 Java 소스·JAR

[기록 대조 audit](../../backend/output/ci-715/connection-observation-20261016/audit.json)의 56개 검사는 모두 통과했다. 이는 inventory·격리 기록·classpath·로그 보존·관측 일관성 검사이며, 별도의 제품 테스트 56개를 추가했다는 의미가 아니다.

- [1회 HTML 리포트](../../backend/output/ci-715/connection-observation-20261016/awake-after-1/html/index.html)
- [2회 HTML 리포트](../../backend/output/ci-715/connection-observation-20261016/awake-after-2/html/index.html)
- [3회 HTML 리포트](../../backend/output/ci-715/connection-observation-20261016/awake-after-3/html/index.html)
- [전체 집계](../../backend/output/ci-715/connection-observation-20261016/analysis.json)
- [추가 집계](../../backend/output/ci-715/connection-observation-20261016/report-details.json)
- [기존 실패 1 XML](../../backend/output/ci-715/awake-recheck-20261016/awake-after-1/xml/TEST-com.knot.backend.recording.application.RecordingTranscriptionFlowIntegrationTest.xml)
- [기존 실패 2 XML](../../backend/output/ci-715/awake-recheck-20261016/awake-after-2/xml/TEST-com.knot.backend.document.application.DocumentGenerationResultIntegrationTest.xml)

실험은 주 세션에서 수행했다. 기존 사용자 변경·공유 QA 컨테이너 3개와 이전 실험 자료를 보존했으며 commit·push는 수행하지 않았다. 실험용 관측기·테스트 JVM과 `caffeinate`가 종료됐음을 확인했다. `git diff --check`는 통과했다.

Persona의 implementation·review 보고서 등록은 통과했지만, `npx ph workflow finish implement`는 기존 `source-read-runtime-unavailable`로 종료 코드 1을 반환했다. 따라서 Persona 최종 인증은 미완료이며 해당 보고서는 diagnostic-only다. 실제 Gradle 649개×3 성공과 기록 대조 56개 통과는 별도의 실행 근거다. 기존 환경이나 작업 이력을 초기화해 이 제한을 우회하지 않았다.
