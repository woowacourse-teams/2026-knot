# Dev 요청별 JDBC 시간 계측

상태: 구현 및 로컬 검증 완료 / 확인일: 2026-10-18 / 별도 GitHub Issue 및 원격 쓰기 없음

## 1. 범위와 브랜치

현재 backend 체크아웃의 기존 사용자 변경을 보존한다. 사용자가 승인한 계측 구현과
기존 loadtest worktree 실행기 수정을 수행한다. 배포와 실제 부하 실행은 후속이다.
제품 응답, 권한, 쿼리, 트랜잭션 경계는 바꾸지 않는다.

| 근거 | 적용 사실 | 상태 |
| --- | --- | --- |
| global/logging/RequestIdFilter.java, global/config/RequestIdConfig.java | Nginx의 X-Request-ID를 MDC와 응답에 연결 | 구현 확인 |
| Dev 프로세스, JAR, Prometheus, Nginx와 journal 실제 조회 | 풀 전체 Hikari 시간만 있으며 요청별 JDBC 시간 없음 | 호스트 관측 |
| loadtest/scripts/document-read-stages.js, stages/metrics.mjs | 응답 ID 미저장, 음수 timing은 통계에서 제외하지만 품질 실패 | 구현 확인 |
| datasource-proxy 공식 1.11.1 가이드와 소스 | getConnection/close/query listener와 unwrap/close 전달 지원 | 공급자 문서 확인 |

## 2. 구현할 흐름

Dev의 목록/상세 GET 요청 → 기존 요청 ID 필터 → JDBC 요청 컨텍스트 → 기존 인증과 서비스 →
연결 획득/SQL/반환 시간 수집 → 느린 요청 완료 시 한 로그 → k6 응답 ID와 연결.
SQL 원문, 바인드 값, 쿠키, 토큰은 기록하지 않는다. 쿼리는 해시와 실행 순서로 식별한다.
기본 임계값은 요청 전체 500ms이며 Dev에서 설정으로 조절한다. Prod에는 켜지지 않는다.

## 3. 나올 코드와 메서드 초안

| 클래스/파일 | 책임 |
| --- | --- |
| RequestJdbcTiming, RequestJdbcTimingContext | 요청별 합계/최대와 제한된 상세 표본, ThreadLocal 정리 |
| RequestJdbcTimingFilter | 문서 GET만 선택, 완료/예외 경계에서 ID 포함 로그 |
| JdbcTimingListener | 단조 시계로 획득/점유/SQL 실행 기록 |
| JdbcTimingDataSourcePostProcessor, JdbcTimingConfig | Dev에서만 풀 설정과 기존 메트릭을 보존해 DataSource 장식 |
| loadtest/scripts/lib/request-correlation.js | 응답 ID와 안전한 시간 표본 선택 |
| loadtest/scripts/stages/process.mjs | 느린 요청/오류의 상관 로그를 별도 JSONL에 보존 |

## 4. 저장 기반과 주의할 점

DB 스키마나 migration 변경 없음. SQL 시간은 연결 점유 시간의 일부이며 세 시간을 더하지 않는다.
JDBC execute 시간은 순수 PostgreSQL 실행 시간이 아니며 드라이버와 통신을 포함한다.
진단은 동기 문서 GET 전용이며 SSE/비동기 작업 전체 계측으로 표현하지 않는다.
datasource-proxy 1.11.1을 명시적으로 추가한다. Hibernate 통계는 요청별 획득/점유를 제공하지 않으며,
직접 JDBC 동적 프록시를 작성하는 대안보다 검증된 listener/unwrap 처리를 재사용한다.
자동 SQL/파라미터 로거는 사용하지 않는다. 제거 시 Dev 설정/장식/의존성을 함께 제거 가능하다.

## 5. TDD와 검증 순서

1. 음수 본 측정 표본의 품질 통과와 원본/제외 수 보존, 기존 오류/누락 차단을 Node로 검증한다.
2. JDBC 성공/실패, 연결 획득 실패, 반복 close, 요청 격리, 표본 상한을 단위 검증한다.
3. 실제 PostgreSQL/Hikari와 Servlet 필터에서 SQL 실행/풀 대기/반환 로그, ID, 예외와 기존 메트릭을 검증한다.
   기존 MVC 보안, SSE와 Prometheus 수집 동작도 회귀 검증한다.
4. k6 응답 ID, 음수 경고 보존, 느린 요청 JSONL 및 정상 단계 진행을 실제 Docker k6와 로컬 HTTP로 검증한다.
5. 변경 파일 포맷/구문, 관련 Gradle 회귀와 bootJar, Persona 종료 절차를 수행한다.

## 6. 완료 기준과 다음 작업

로컬 실제 Hikari/PostgreSQL 요청 로그에 획득/점유/SQL과 ID가 연결되고, Prod 비활성 및
음수 표본 제외 후 진행이 검증되면 구현 완료다. Dev 배포와 실제 400 RPS 관측은 별도다.

검증 결과: 단위 1,557개, 통합 687개, Prometheus 인수 5개 실패/오류/스킵 0.
bootJar, 전체 Spotless와 diff 공백 검사 통과. Node 단위 43개 통과.
실제 Docker k6의 느린 수신 응답 ID 저장과 본 측정 음수 timing 후 여섯 단계 완료를 확인했다.
로컬 풀 대기를 의도적으로 만든 시험에서 acquire 약 154ms, SQL execute 약 39ms,
usage 약 42ms로 구분됐다. 이 수치는 로컬 검증이며 Dev 부하 관측값이 아니다.
운영 방법은 backend/docs/operations/request-jdbc-timing.md를 따른다.
