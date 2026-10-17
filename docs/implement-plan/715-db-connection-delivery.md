# DB 연결 요청의 PostgreSQL 도달 구간 확인 실험 계획

## 1. 범위와 브랜치

- 상태: **계획 작성 완료. 구현·컨테이너 실행·패킷 수집·테스트 재실행 전**
- 확인일: 2026-10-17
- 이슈: [#715 — Backend CI 실행 시간 개선](https://github.com/woowacourse-teams/2026-knot/issues/715)
- 브랜치·HEAD: `be/refactor/#715`, `1b7d001ecb29d9021659e270655d3bd364da8803`
- 선행 결과: [시작 관측의 동기·비동기 비교](../experiments/715-db-connection-observer-async.md)
- 전체 현황: [CI 개선 진행표와 남은 작업](../experiments/715-ci-improvement-progress.md)

**다음 실험의 목적은 “연결 실패가 호스트 전송, 컨테이너 수신, PostgreSQL의 연결 수락·SSL 요청 해석, 응답 복귀 중 어느 구간까지 관측되는가”를 구분하는 것이다.** 기존 비동기 관측을 유지하고 서버 연결·협상 로그와 제한된 양쪽 TCP 관측을 추가한다. 장애 해결이나 성능 개선 자체를 이번 실험의 성공 조건으로 삼지 않는다.

### A3에서 확인한 사실

| 항목 | 관측 결과 |
| --- | --- |
| 최초 실패 클래스 | `DocumentConfirmationSnapshotIntegrationTest` |
| 실패 수 | 최초 Flyway 연결 실패 1건·같은 컨텍스트의 후속 실패 7건 |
| 첫 연결 | hook 진입 +45.545ms, `127.0.0.1:52831 → 127.0.0.1:52829` |
| SSL 단계 | 응답 읽기에서 약 5.003초 후 timeout |
| fallback | 다른 소켓 `127.0.0.1:52857 → 127.0.0.1:52829`, 인증 응답 읽기 약 5.002초 후 EOF |
| Driver 전체 | 약 10.019초, SQLState `08001` |
| 대상 DB 상태 | 시작·watchdog·제거 직전 running, OOMKilled=false, RestartCount=0 |
| 서버 로그 | 최종 PostgreSQL 기동·TCP listen·ready는 있음. 해당 연결 수신·SSL 요청 해석 여부는 확인 불가 |

소켓의 연결 성공은 Docker가 공개한 호스트 endpoint까지 연결됐다는 증거다. 그 사실만으로 PostgreSQL이 SSLRequest를 읽었다고 볼 수 없다. 기존 로그에 연결 기록이 없다는 사실도 “요청이 DB에 도착하지 않았다”는 증거가 아니다.

### 근거와 확인 수준

| 자료 | 이번 계획에 적용하는 사실 | 확인 수준 |
| --- | --- | --- |
| Issue #715 본문 | 기존 검증·격리 유지, 반복 측정·실패 원본 보존 | 2026-10-17 직접 조회 |
| A3 `analysis.json`·연결 JSONL·서버 최종 로그·thread dump | 실제 주소·두 소켓·SSL timeout·인증 EOF·DB 상태 | 기존 실제 실행 관측 |
| 현재 `TestcontainersConfiguration`·실패 테스트 | Spring Bean으로 PG 생성, snapshot 검증 전에 Flyway 연결에서 실패 | 현재 코드 관측 |
| 실제 classpath의 Testcontainers 2.0.5와 sources JAR | 기본 PG 명령은 `postgres -c fsync=off`. `containerIsStarting(info, reused)`는 wait strategy 이전에 호출 | 설치 패키지 소스 확인 |
| PostgreSQL 18 로그 문서·18.6 소스 | 연결 수신과 SSL 요청 해석을 별도로 기록 가능 | 공식 문서·버전 지정 소스 확인. 현재 이미지의 설정 동작은 사전 검증 필요 |
| 로컬 도구 | Docker client/server 29.2.1, `/usr/sbin/tcpdump` 4.99.1 존재. `tshark`는 PATH에서 찾지 못함 | 버전·경로 읽기 확인. 수집 권한·실제 수집 가능 여부는 미검증 |

제품/API/DB 스키마 변경이 없어 운영 Notion 원문이나 관련 PR을 새로 제품 계약으로 사용하지 않았다. `.persona/project-profile.jsonc`, 백엔드 지침과 Notion 탐색 지침을 확인했다. 이번에는 계획 문서만 작성하므로 구현 workflow를 시작하지 않는다.

## 2. 관측할 흐름과 판정 방법

```text
JVM의 Driver / 물리 소켓
→ macOS loopback의 전송·수신
→ Docker Desktop의 연결 중계
→ 대상 컨테이너 네트워크의 수신·응답
→ PostgreSQL connection received
→ PostgreSQL SSLRequest 해석
→ PostgreSQL 인증·인가
→ 응답이 호스트와 JVM으로 돌아옴
```

Docker Desktop에서는 호스트와 Linux VM 사이를 중계하는 경로가 있다. 양쪽의 소스 포트와 TCP sequence 번호가 같다고 가정하지 않는다. 중간 전체 경로를 직접 관측한 것처럼 표시하지 않는다. [Docker Desktop 네트워크 설명](https://docs.docker.com/desktop/features/networking/)

### 추가할 PostgreSQL 로그

실험용 컨테이너의 시작 인자에 다음 설정을 **기존 명령 뒤에 추가**한다. `fsync=off` 등 원래 설정을 덮어쓰지 않는다.

| 설정 제안 | 목적 |
| --- | --- |
| `log_connections=receipt,authentication,authorization,setup_durations` | 연결 수신·인증·인가·연결 준비 기록 |
| `log_disconnections=on` | 정상 세션 종료 기록 |
| `log_line_prefix=%m [%p] session=%c remote=%r app=%a ` | 서버 세션·PID·원격 주소·포트로 로그 연결 |
| `trace_connection_negotiation=on` | SSL/GSS 협상 요청을 해석한 지점 확인 |

`log_connections`의 항목과 prefix는 [PostgreSQL 18 로그 문서](https://www.postgresql.org/docs/18/runtime-config-logging.html)를 따른다. SQL 전체 로깅이나 전역 DEBUG 로깅은 추가하지 않는다. 기존 stderr·서버 로그 최종 보존 방식을 유지한다.

`trace_connection_negotiation`은 PostgreSQL 18.6의 [설정 소스](https://github.com/postgres/postgres/blob/REL_18_6/src/backend/utils/misc/guc_tables.c)에서 서버 시작 시 적용하는 개발용 설정으로 확인했다. 일반 문서에 나온 안정된 사용자 설정과 같은 수준으로 가정하지 않고, 현재 이미지에서 설정 인식·실제 로그 발생을 사전 확인한다.

**SSLRequest accepted/rejected 로그는 서버가 요청을 읽고 해석했다는 증거다. 응답 전송 완료의 증거는 아니다.** 18.6 [협상 처리 소스](https://github.com/postgres/postgres/blob/REL_18_6/src/backend/tcop/backend_startup.c)에서는 이 로그를 남긴 다음 응답 바이트를 쓰기 시작한다. `rejected`는 SSL 미사용 응답을 선택했다는 뜻일 수 있으므로 연결 장애 자체로 세지 않는다. 연결 수신 로그는 그보다 앞선 단계다.

### 호스트·컨테이너 TCP 관측

서버 로그는 모든 실험 PG에 적용하되, 패킷 요약은 A3의 **공유 snapshot 컨텍스트 그룹**에 속한 DB로 제한한다.

- `DocumentConfirmationSnapshotIntegrationTest`
- `DocumentListSnapshotIntegrationTest`
- `DocumentTranscriptSnapshotIntegrationTest`
- `SearchConversationListSnapshotIntegrationTest`
- `SearchMessageListSnapshotIntegrationTest`

이 다섯 클래스는 기존 개선안 4의 8개 테스트 그룹이다. 정상 전체 실행에서는 이 그룹에 속한 컨테이너가 두 번 생성된 기록이 있으므로 첫 생성만 대상으로 고정하지 않는다. 실제 컨텍스트·컨테이너 ID와 생성 순번을 기록한다.

| 위치 | 관측 제안 | 증명 범위 |
| --- | --- | --- |
| macOS 호스트 | 실제 mapped port만 필터링한 loopback TCP 요약 | 호스트에서 송수신한 SYN/ACK/FIN/RST·데이터 구간 |
| 대상 PG 네트워크 | `--network container:<ID>`의 별도 수집 컨테이너에서 5432 TCP 요약 | 컨테이너 네트워크에 도착·발신한 TCP 구간 |
| PostgreSQL | 연결·협상 로그의 세션/PID/원격 endpoint | 서버 프로세스가 연결을 수락하고 협상 요청을 해석한 단계 |

다른 컨테이너의 네트워크를 공유하는 방식은 [Docker 공식 네트워크 설명](https://docs.docker.com/engine/network/#container-networks)에 있다. 실제 Docker Desktop·권한 조합에서의 동작은 사전 검증 대상이다. 보조 이미지와 도구 버전·digest를 먼저 고정한다. DB 이미지 내부에 패키지를 설치하거나 DB 이미지를 교체하지 않는다.

패킷 본문·인증 데이터·원시 pcap은 저장하지 않는다. 시각, 양쪽 주소·포트, 방향, TCP flags, sequence/ack 구간, 데이터 길이만 허용 목록으로 정규화한다. 비밀번호·SQL·사용자 데이터는 저장 필드에 없다. 인터페이스·필터·수집 시작/종료·drop 통계와 오류는 함께 남긴다. 수집 권한은 구현 시 먼저 확인하며, 가능 여부 확인 전 전체 테스트를 시작하지 않는다.

### 연결 대응과 관측 공백 처리

1. JVM에서는 Driver ID 아래에 있는 SSL 시도와 fallback을 **별개의 물리 소켓**으로 연결한다. A3의 두 소스 포트가 예다.
2. 호스트 소켓은 실제 local/remote tuple로 호스트 TCP 흐름에 대응한다.
3. 컨테이너 쪽 흐름은 컨테이너 ID·서버가 본 remote tuple·서버 세션 ID로 연결한다.
4. 호스트↔컨테이너 중계의 대응은 시각 오차 범위, 연결 순서, 방향별 바이트 구간·종료 패턴으로 후보를 좁힌다. 서로 다른 흐름의 sequence 번호를 직접 비교하지 않는다.
5. Testcontainers 준비 접속·풀의 추가 접속과 겹쳐 후보가 둘 이상이면 **대응 불명**으로 남긴다. 가장 가까운 시각의 세션을 정답으로 고르지 않는다. `application_name`은 인증 전 SSL 시도를 식별하는 만능 key로 사용하지 않는다.

호스트·VM·PostgreSQL 로그의 시계는 같다고 가정하지 않는다. 각 수집 위치의 시각과 수신 시각, clock domain·정렬 오차 범위를 기록한다. 같은 JVM의 구간만 nanoTime으로 계산한다. 로그 행 순서만으로 양쪽 전송의 선후나 단방향 지연을 확정하지 않는다.

패킷 수집은 `start()` 완료 뒤가 아니라 **wait strategy 전의 containerIsStarting 경계**에서 비동기로 요청한다. JDBC는 수집 준비를 기다리지 않는다. 수집기가 첫 Driver 시작보다 늦게 준비됐다면 해당 구간은 불완전 수집이다. “패킷 없음”을 “전송 없음”으로 해석하지 않는다.

대상 컨테이너의 첫 Driver 호출과 그 안의 fallback이 끝날 때까지 수집하고 짧은 drain 후 종료한다. 안전 한도는 컨테이너당 120초를 제안한다. 이는 수집 한도이며 DB timeout을 바꾸는 값이 아니다. 초과·비정상 종료·패킷 drop·파일 상한 도달은 숨기지 않고 해당 구간을 비교 불가로 표시한다. 실제 한도와 drain 값은 사전 검증 후 본 실험 전에 고정한다.

### 실패 시 판정표

아래의 부재 판단은 수집이 대상 소켓 시작 전부터 종료까지 정상 동작하고, drop·필터 오류·시계/세션 대응 모호성이 없을 때만 사용한다.

| 관측 | 이번에 말할 수 있는 것 | 남는 한계 |
| --- | --- | --- |
| 호스트 전송 있음·컨테이너 수신 없음 | 두 관측 지점 사이 전달 경로를 우선 조사 | Docker 중계 내부의 정확한 원인은 미확정 |
| 컨테이너 TCP 데이터 수신 있음·서버 receipt 없음 | 컨테이너 네트워크까지 도달. 서버 수락·backend 시작 또는 로그 경로 조사 | PostgreSQL이 데이터를 읽었다고 확정 못 함 |
| 서버 receipt 있음·SSLRequest trace 없음 | 서버가 연결을 수락한 단계까지 확인 | SSL 요청이 아직 도착하지 않았는지, 처리가 멈췄는지 추가 구분 필요 |
| 서버 SSLRequest trace 있음·컨테이너 응답 데이터 없음 | SSL 요청 해석은 확인. 그 뒤 응답 생성·전송 구간 조사 | trace만으로 응답 write 완료를 증명 못 함 |
| 컨테이너 응답 있음·호스트 응답 없음 | 응답 복귀 경로를 우선 조사 | 중간 어느 구성요소인지는 미확정 |
| 호스트에 응답 있음·JVM은 읽기 timeout | 해당 소켓 대응을 재확인한 뒤 클라이언트 읽기·스케줄링 조사 | 호스트 TCP 수신만으로 JVM 소비 완료를 증명 못 함 |
| 인증·인가 로그 있음·Driver 실패 | 세션 대응을 확인하고 연결 후반 단계 조사 | 성공한 다른 연결의 로그와 혼동하지 않아야 함 |
| 설정 미적용·수집 지연·drop·대응 모호 | 미확인 또는 관측 불완전 | 전달 실패·장애 해결로 판정하지 않음 |

TCP 데이터 길이만 보고 SSLRequest나 인증 메시지의 내용까지 해석했다고 쓰지 않는다. 서버 trace가 없는 경우의 프로토콜 단계는 더 약한 증거로 남긴다.

## 3. 기존 코드와 변경할 책임

모든 추가 코드와 이름은 **제안**이다. 기존 `connection-async-20261017` 원본을 덮어쓰지 않고 `backend/output/ci-715/connection-delivery/`에 새 실험을 둔다.

| 기존 위치·책임 | 제안할 변경 |
| --- | --- |
| `ContainerStartAdvice`·`ContainerCapture` | start 진입 때 실험 PG 명령에 로깅 인자만 한 번 추가. 기존 command parts·SSL·timeout 보존 검증 |
| `ConnectionAgent` | 설치된 2.0.5의 두 인자 `containerIsStarting`에만 새 경계 추가. 한 인자 위임과 중복 계측하지 않음 |
| 새 `ContainerStartingAdvice` | ID·실제 port mapping·발생 시각 등록 후 비동기 수집 요청. 새 대기·JDBC probe 없음 |
| 기존 `ConnectionObserver`·`SocketAdvice` | 원래 연결 span·소켓·예외·watchdog 유지. 첫 연결과 fallback의 대응 근거로 사용 |
| 기존 비동기 시작 수집·beforeStop | 서버 follow·최종 로그·상태 보존 유지. 새 수집기의 bounded 종료와 최종 통계도 보존 |
| 새 `delivery.py` 실행기 | `prepare`, `validate`, `cohort` 책임 분리. 전원·hash·조건 확인, 수집 프로세스 소유권·종료 관리 |
| 새 `analyze_delivery.py` | `matchFlows()`로 후보/모호성 반환, `classifyDelivery()`로 증거별 도달 단계·제약 반환 |

설정 추가는 원래 PG 실행 명령을 인식할 때만 허용한다. 기존 옵션과 충돌하거나 다른 entrypoint라면 임의로 덮지 않고 준비 단계에서 중단한다. 적용한 명령과 설정 hash를 manifest에 기록한다. 실제 설정 확인은 독립 사전 DB에서 먼저 수행하고, 본 실행의 첫 JDBC 전에는 추가 SQL을 보내지 않는다.

## 4. 저장·환경·실험 범위

스키마·migration·운영 데이터 변경은 없다. 실험 파일만 새 디렉터리에 저장한다.

| 보존 자료 | 용도 |
| --- | --- |
| 원래 console·XML·HTML·연결 JSONL·thread dump·서버 로그·상태 | 기존 증거 연속성 |
| `capture-manifest.json` | 컨테이너·mapping·이미지·설정·도구·권한·수집 PID·시각·종료·drop·완전성 |
| `host-tcp.jsonl`, `container-tcp.jsonl` | 본문 없는 TCP 요약 |
| `server-sessions.json`, `delivery-analysis.json` | 서버 세션·후보 대응·도달 단계·미확정 이유 |
| heartbeat·CPU/RSS·swap·전원·수집기 자원 | 관측 부하와 환경 변화 확인 |

제품 소스·자원·frozen runtime·649개 inventory·실행 순서·스케줄러 수정·대상 JPA 공유·캐시 8·힙 1GiB를 유지한다. 전원과 환경은 실행 직전에 다시 확인한다. 준비 과정에서 실행되지 않은 도구 권한이나 현재 배터리 상태를 이미 확보한 사실로 쓰지 않는다.

서버 로깅과 수집기는 타이밍·CPU·I/O에 영향을 줄 수 있다. 패킷 범위를 해당 그룹의 첫 연결로 제한하고 초기 수집을 비동기로 수행한다. 공유 QA 컨테이너는 수집·설정 변경·종료 대상에서 제외한다. helper가 필요하면 최소 권한부터 검증하며 privileged 모드나 Docker 전체 설정 변경으로 자동 우회하지 않는다.

서버 로깅은 모든 PG에 적용하므로 다른 클래스에서 실패해도 서버 증거는 남는다. 그러나 양쪽 TCP 수집이 없는 다른 그룹의 실패를 같은 정밀도로 분류했다고 주장하지 않는다. 대상 확대는 별도 계획으로 판단한다.

## 5. 구현 시 검증 순서

### 1단계 — 잘못된 도달 판정을 막는 집계 fixture

실패하는 fixture를 먼저 만들고 최소 집계를 구현한다. 서버 로그만 있는 경우, 수집 시작이 늦은 경우, port 재사용, 동시에 같은 패턴의 소켓 두 개, SSL→fallback, clock 차이, drop이 있는 경우를 다룬다. **증거가 부족한 입력은 미확인으로 반환**해야 한다.

### 2단계 — 실제 도구·설정·증거 수집 사전 검증

| 검증 | 기대 결과 |
| --- | --- |
| 현재 이미지 설정 인식 | 추가 GUC 인식·prefix·서버 trace 발생 확인. 미지원이면 본 실험 중단 |
| 정상 실제 JDBC 연결 | 양쪽 TCP·서버 receipt/협상/인증 로그와 JVM 소켓의 대응 확인 |
| 독립 DB의 잘못된 인증 | 서버가 받은 연결·인증 실패와 원래 JDBC 예외 구분. 제품 자격증명 변경 없음 |
| 독립 로컬 테스트 endpoint의 무응답 | 호스트 연결 성공만으로 PostgreSQL 도달이라고 판정하지 않음 |
| 의도적인 늦은 수집·수집기 종료 | 늦음/불완전 표기·원래 테스트 예외 보존·자원 정리 |
| 민감값 포함 fixture | 패킷 본문·비밀번호·SQL이 저장 산출물에 없음 |
| 전체 before/afterStop 경합 | 최종 서버 로그·drop 통계·수집기 종료 보존, 잔존 helper 없음 |

사전 검증의 의도된 실패는 장애 재현 빈도에 합산하지 않는다. helper 이미지 준비와 컴파일은 이 단계에서 끝내고 본 실행 중 pull·설치·빌드를 하지 않는다. 수집 준비를 기다려 성립시킨 독립 양성 검증과, 준비를 기다리지 않는 본 실행을 구분한다.

### 3단계 — 같은 비동기 관측 조건에서 전체 통합 테스트 3회

사전 검증이 충족되면 새 관측 JAR·도구·설정을 고정하고 **C1 → C2 → C3, 총 3회** 실행한다. 동기·비동기 비교나 개선안 5의 전후 성능 비교를 다시 수행하는 실험이 아니다.

- 기존 `integrationTest --rerun --no-build-cache`와 frozen runtime·실행별 리포트 분리 실행기를 바탕으로 새 실행기를 준비한다. 아직 없는 `delivery.py cohort`를 현재 실행 가능한 명령이라고 제시하지 않는다.
- 매회 전체 649개 결과, 대상 74개 활성 트랜잭션·롤백·트랜잭션 밖 전후 7개 테이블 0·공유 컨텍스트 1, 스케줄러 3개 결과를 기록한다.
- 실패한 실행을 성공 실행으로 교체하지 않는다. 일반 테스트 실패는 증거를 보존하고 다음 예정 회차를 진행한다. 수집 누수·필수 증거 유실·전원 조건 위반이면 후속 회차를 중단하고 미완료로 기록한다.
- 정상 성공 실행의 초기화 39/47/117은 대조용 참고값이다. 실패 때문에 줄어든 초기화 수를 개선으로 계산하지 않는다.
- 3회 모두 성공해 장애가 재현되지 않으면 그 결과로 마친다. 추가 반복으로 장애가 나올 때까지 실행하지 않는다.

실패가 재현되면 첫 SSL 시도와 fallback 각각에 대해 호스트 송신→컨테이너 수신→서버 receipt→협상 trace→서버 응답→호스트 수신→JVM 결과 표를 만든다. 각 칸에 관측/부재/미확인과 근거 파일을 기록한다.

## 6. 완료 기준과 다음 작업

1. 실제 설정·양성/음성 사전 검증에서 전달 단계와 미확인을 구분한다.
2. 예정한 전체 3회의 성공·실패·격리·관측 완전성과 자원 정리를 보존한다. 중단됐다면 완료로 바꾸지 않는다.
3. 재현 실패는 소켓별 마지막으로 확인한 도달 단계와 남은 공백을 설명한다. 재현되지 않으면 미재현·원인 미확정으로 결론 낸다.
4. 결과는 `docs/experiments/715-db-connection-delivery.md`에 별도 작성한다. 이 결과 문서는 아직 없다.

이번 계획의 성공은 **해결했다고 선언하는 것**이 아니라 **증거가 가리키는 구간과 확인할 수 없는 구간을 구별하는 것**이다. 이 실험만으로 관측 부하가 제거됐거나 CI가 빨라졌다고 결론 내리지 않는다.

특정 구간이 좁혀지면 그 구간의 최소 수정·재현 검증을 별도 계획으로 정한다. SSL 끄기·timeout 늘리기·재시도 추가·캐시 확대·DB 이미지 변경은 이번 실험의 변수가 아니다. 원인 미확정이면 개선안 5 보류를 유지하거나 후보를 제외하고 검증된 변경만 마무리하는 선택도 남긴다.

현재 요청에서는 문서 작성까지만 수행했다. 구현·패킷 수집·테스트 재실행·commit·push·원격 설정 변경은 하지 않았다.
