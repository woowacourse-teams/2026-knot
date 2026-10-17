# 시작 관측의 동기·비동기 비교 결과

## 1. 실험 결론

**비동기 수집은 컨테이너 시작 hook에서 로그 등록·상태 조회 완료를 기다리는 경로를 제거했다. 다만 첫 JDBC 연결이 일관되게 빨라지거나 전체 테스트 시간이 개선되는 결과는 확인하지 못했다.**

예정한 동기 3회·비동기 3회를 모두 실행했다. 비동기는 3회 모두 649개가 통과했고, 동기는 2회 통과·1회 8개 실패였다. 실패 실행에는 독립적인 Flyway 연결 실패 1건과 같은 컨텍스트의 후속 초기화 실패 7건이 있었다. 성공 실행으로 교체하거나 추가 전체 실행을 넣지 않았다.

이번에는 실패한 컨테이너의 서버 로그·상태와 느린 연결 당시 thread dump를 보존했다. **동기 관측을 적용한 상태에서도 SSL 응답 timeout과 비SSL fallback 인증 EOF가 발생했다.** 따라서 앞선 관측 실행의 성공을 동기 관측의 대기 덕분이라고 확정할 수 없다. 비동기의 3회 성공도 간헐 장애 해결의 증거로 확대하지 않는다.

| 판단 대상 | 결과 |
| --- | --- |
| 시작 hook의 동기 대기 제거 | 확인 |
| 실제 첫 JDBC 연결 간격 감소 | 일관된 감소 확인 못 함 |
| 비동기 로그·상태 보존과 종료 정리 | 이번 전체 3회에서 충족 |
| 기존 연결 장애 | 동기 A3에서 같은 연결 단계의 장애 재현. 과거 약 50초와 이번 약 10초는 구분 |
| 전체 통합 테스트 안정성 | 6회 중 5회 성공·1회 실패. 전체 무실패 기준 미충족 |
| 채택 범위 | 비동기를 후속 로컬 진단용 관측 방식으로 사용. 개선안 5의 최종 성능·안정성 채택은 보류 유지 |

## 2. 배경과 비교 조건

기존 관측기는 PostgreSQL 컨테이너의 `start()` 종료 hook 안에서 로그 파일 생성, Docker 로그 스트림 등록, 상태 조회를 수행했다. 이 작업이 첫 연결을 늦추면서 간헐 장애의 재현 여부를 바꿨는지 확인하기 위해 실행 방식을 분리했다.

| 항목 | 조건 |
| --- | --- |
| 실행일·브랜치 | 2026-10-17, `be/refactor/#715` |
| HEAD | `1b7d001ecb29d9021659e270655d3bd364da8803` |
| 비교 대상 | A: 시작 수집 동기 실행 / B: 시작 수집 비동기 제출 |
| 실행 환경 | Apple M5 Pro·메모리 24GiB·macOS, Java 25.0.3·Gradle Wrapper 9.5.1 |
| 제품·테스트 코드 | 기존 개선안 5 변경 후 frozen runtime. 현재 소스·자원 hash 일치 확인 |
| 테스트 | 전체 통합 테스트 649개·동일 inventory와 클래스 순서 |
| DB | 실제 PostgreSQL, `pgvector/pgvector:pg18`, 기존 Flyway 유지 |
| JVM | 실행마다 새 테스트 JVM, 최대 힙 1GiB·컨텍스트 캐시 8 |
| 재실행 | `integrationTest --rerun --no-build-cache`, 준비·컴파일 4개 작업은 매회 `UP-TO-DATE` |
| 전원·절전 | AC·`caffeinate -i`. 실행 구간 OS Sleep/DarkWake/Wake 기록 0 |
| 유지한 관측 | 기존 연결 span·원래 예외·소켓·5초 watchdog·동기 event writer |
| 증거 보존 | 실행별 console·XML·HTML·연결 JSONL·서버 로그·상태·heartbeat·자원·종료 코드 |

A·B는 같은 소스와 같은 observer JAR을 사용하고 `ci715.captureMode`만 바꿨다. 연결 계측 본체를 포함한 기존 9개 Java 파일과 boundary JAR은 이전 실험과 byte 단위로 같았다.

**A는 과거 observer JAR 그대로가 아니다.** 공통 시간 경계, 생명주기 보호와 관측용 inspect의 제한을 양쪽에 적용한 새 동기 대조군이다. 과거 관측 3회를 이번 A 표본으로 재사용하지 않았다.

## 3. 구현한 변경과 사전 검증

### 시작 수집 분리

- 공통 경로에서 컨테이너 ID·호스트·포트와 실제 발생 시각을 등록한다.
- A는 시작 수집을 호출 스레드에서 실행하고, B는 daemon worker 2개·대기열 64개에 제출한다.
- 대기열 포화 시 호출 스레드에서 대신 실행하지 않고 관측 오류로 기록한다.
- 시작 작업의 취소·완료와 stream 설치·종료를 관리해 stop 이후 늦은 stream 설치와 중복 종료를 막는다.
- stop 전에는 진행 작업의 실제 완료를 최대 3초 기다리고, 최종 상태·서버 로그를 공통 경로에서 보존한다. 이 정리 시간은 시작 hook과 별도로 집계한다.

관측용 inspect는 설치된 Docker client의 transport 설정을 이용한 별도 읽기 전용 client로 수행하며, 연결·응답 한도를 각각 2초로 적용했다. 양쪽에 같은 설정이다. PostgreSQL 연결의 timeout·SSL·retry·준비 검사에는 변경이 없다. 전체 실행에 JDBC 준비 probe나 인위적인 대기를 추가하지 않았다.

`FOLLOW_REGISTERED`는 로그 요청을 제출했다는 경계다. 스트림 연결이나 초기 로그 수신이 모두 끝났다는 뜻으로 사용하지 않았다.

### 사전 검증 결과

| 검증 | 결과 |
| --- | --- |
| 수집 작업을 latch로 막은 동기·비동기 비교 | A는 완료까지 대기, B는 수집 작업이 막혀 있어도 반환 |
| queue 포화·취소·진행 중 stop·중복 stop | 호출 스레드 대체 실행 없이 오류·취소를 기록하고 정리 |
| 늦은 stream 응답·중복 close | 닫힌 stream 재개 방지 |
| 원래 SQL 예외·SQLState·suppressed 예외·비밀값 가림 | 기존 계측 selftest 통과 |
| 느린 연결 watchdog | 합성 검증 통과. 이후 A3에서 실제 dump도 생성 |
| 실제 Docker HTTP 응답 지연 | 응답을 주지 않는 로컬 endpoint에서 관측용 inspect가 제한 시간 내 실패 |
| 실제 PostgreSQL 정상 연결·즉시 stop | 두 모드 모두 초기·최종 로그/상태와 자원 정리 확인 |
| 지연된 상태 기록·포트 재사용 집계 fixture | 파일 기록 순서 대신 실제 발생 시각·컨테이너 생존 구간으로 대응 |
| 컴파일 | 실험 Java 16개 컴파일 성공·Python 구문 검증 통과 |

최초 비동기 사전 probe에서는 즉시 stop한 두 번째 컨테이너의 시작 작업이 취소돼 follow 파일에 초기 로그가 없었다. 최종 서버 로그와 상태는 보존됐지만, “모든 follow 파일에 초기 로그가 있어야 한다”는 최초 assertion은 실패했다. 원본을 보존하고, 의도적으로 취소된 시작 작업은 최종 증거 보존을 확인하도록 사전 probe 판정을 구분했다. 수정 후 양쪽 사전 probe를 다시 확인했다. **전체 테스트 6회에는 이 완화된 follow 판정을 사용하지 않았으며, 실제 생성된 모든 컨테이너에서 follow·최종 로그의 초기 기록을 확인했다.**

## 4. 측정 방법과 실행 이력

고정 순서 **A1 → B1 → B2 → A2 → A3 → B3**로 실행했다. 실행 3개를 모드별 비교 단위로 사용한다. 한 실행 안의 컨테이너나 Driver 호출 수를 독립적인 장애 실험 표본 수로 세지 않는다.

- hook 시간: 시작 종료 hook 진입부터 반환 직전 marker까지. marker 기록 후 실제 반환까지의 작은 공통 비용은 남는다.
- 첫 연결 간격: hook 진입부터 해당 컨테이너의 첫 `Driver.connect` 시작까지. 다른 Spring 준비 작업도 포함된다.
- 수집 시간: worker 또는 호출 스레드에서 로그 등록·inspect 작업을 수행한 구간.
- 전체 시간: Gradle 명령 시작부터 종료까지. wall과 monotonic을 함께 기록한다.
- XML 시간: 각 suite가 기록한 시간의 합. 전체 명령 시간이나 순수 테스트 본문 시간과 동일하지 않다.

JVM 안의 구간은 `System.nanoTime()`을 사용했다. 컨테이너 대응은 등록부터 실제 stop 요청까지의 생존 구간과 포트로 확인하며, 비동기 상태 조회가 늦게 끝난 시각을 생존 시작으로 삼지 않았다.

### 측정 중 정지·재개와 시계 차이

B2 종료 후 외부 수집기의 `ps`가 이미 종료한 테스트 JVM을 조회해 exit 1을 반환했다. 최초 audit은 이를 수집 오류로 보고 A2 시작 전에 멈췄다. 조회는 같은 PID의 JUnit `PLAN_END`보다 약 3.057초 뒤였고, stdout·stderr가 비어 있었으며 timeout도 아니었다.

원시 오류와 최초 audit을 보존한 뒤, **동일 PID·정확한 명령·종료 경계 이후·빈 출력·timeout 아님**을 모두 만족하는 경우만 종료 후 PID 조회 알림으로 구분했다. Java 소스·JAR·테스트 조건은 그대로 두고 남은 A2/A3/B3만 이어서 실행했다. B2 명령 종료부터 A2 시작까지 약 **93.996초**의 간격이 생겼다. 이를 숨기거나 B2를 재실행하지 않았다. 중간 휴지 구간은 호스트 상태 비교의 한계다.

B1에서는 wall 200.450초와 monotonic 198.452초 사이에 약 1.998초 차이가 있었다. 외부 heartbeat에서도 한 구간의 wall 3.003초·monotonic 1.009초가 함께 관찰됐다. 그 구간을 OS 절전이나 특정 시계 보정 원인으로 단정하지 않았다. 아래에는 두 값을 모두 남기고, 경과 시간 해석에는 monotonic을 우선했다.

## 5. 전체 실행 결과

| 순서 | 실행 | wall | monotonic | XML suite 합 | 테스트 결과 | Driver 연결 실패 |
| ---: | --- | ---: | ---: | ---: | --- | ---: |
| 1 | A1 동기 | 161.591초 | 161.588초 | 150.200초 | 649 성공 | 0/894 |
| 2 | B1 비동기 | 200.450초 | 198.452초 | 189.644초 | 649 성공 | 0/911 |
| 3 | B2 비동기 | 164.506초 | 164.503초 | 153.407초 | 649 성공 | 0/899 |
| 4 | A2 동기 | 144.579초 | 144.576초 | 134.650초 | 649 성공 | 0/895 |
| 5 | A3 동기 | 157.382초 | 157.379초 | 146.513초 | **641 성공·8 실패** | **1/862** |
| 6 | B3 비동기 | 148.367초 | 148.364초 | 136.791초 | 649 성공 | 0/895 |

총 **3,894건의 테스트 실행 중 3,886건 성공·8건 실패**, 오류·스킵 0건이다. 전체 실행은 5회 성공·1회 실패였다. Driver 연결 5,356건 중 실패 1건을 기록했다.

전체 monotonic 중앙값은 A 157.379초·B 164.503초지만, **이 숫자로 개선율이나 악화율을 계산하지 않는다.** A3는 연결 실패와 후속 초기화 생략 때문에 수행한 작업량이 달라졌다. 성공한 A 두 개만 골라 새 기준으로 바꾸지도 않았다. 성공 실행끼리도 시간 변동이 컸고, 호스트 부하와 중간 정지·재개가 완전히 통제되지 않았다.

| 초기화 지표 | 성공한 5회 각각 | A3 실패 실행 |
| --- | ---: | ---: |
| 성공적으로 저장된 서로 다른 컨텍스트 key | 32 | 31 |
| 실제 저장된 컨텍스트 | 39 | 36 |
| Spring 시작 완료 | 39 | 36 |
| PostgreSQL 시작 완료 | 47 | 45 |
| Flyway 적용 완료 로그 | 117 | 114 |

A3의 초기화 감소는 최적화 효과가 아니다. 실패 이후 캐시·재생성 흐름이 달라졌으며, 다른 실행에 있던 `MemberServiceIntegrationTest`와 `SearchConversationListSnapshotIntegrationTest`의 컨테이너 생성 구간이 없었다. 전체 컨테이너 생성 순서 일치 검증도 실패로 남겼다. 테스트 inventory·클래스 실행 순서 자체는 6회 모두 같았다.

## 6. 시작 hook과 첫 연결 시점

아래 시간은 각 실행에서 생성된 컨테이너별 구간의 중앙값이다. A3는 45개, 나머지는 각각 47개로 모집단 차이가 있다.

| 실행 | hook | 첫 Driver 시작까지 | 수집 작업 | 제출→수집 시작 | stop 전 정리 | 수집 완료 전 첫 Driver 시작 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| A1 | 5.968ms | 45.325ms | 5.309ms | 0.035ms | 10.624ms | 0/47 |
| B1 | 0.807ms | 53.612ms | 6.852ms | 0.184ms | 11.130ms | 2/47 |
| B2 | 0.514ms | 39.846ms | 5.385ms | 0.155ms | 9.402ms | 0/47 |
| A2 | 6.096ms | 44.646ms | 5.669ms | 0.033ms | 10.061ms | 0/47 |
| A3 | 6.876ms | 50.090ms | 5.685ms | 0.036ms | 9.962ms | 0/45 |
| B3 | 0.502ms | 42.678ms | 6.199ms | 0.169ms | 9.158ms | 2/47 |

동기의 제출→수집 시작은 같은 스레드의 호출 간격이며 queue 대기가 아니다. 비동기의 해당 구간에는 worker 스케줄링이 포함된다.

실행별 hook 중앙값은 A 5.968~6.876ms, B 0.502~0.807ms였다. 실행별 중앙값의 중앙값은 **6.096ms → 0.514ms**였다. 합성 latch 검증과 실제 반환 경계 양쪽에서 시작 수집의 대기 제거를 확인했다.

첫 연결 간격은 A1/B1에서는 B가 8.286ms 길었고, A2/B2에서는 4.800ms 짧았다. A3/B3는 실패로 전체 초기화 흐름이 달라져 동일한 정상 실행 비교로 사용하기 어렵다. B에서도 141개 중 137개는 수집 완료 후 첫 연결이 시작됐다. 4개는 완료 전에 시작했지만, 수집 시작 전 연결은 없었다. **비동기는 수집 완료를 기다리지 않도록 만들었을 뿐, 모든 첫 연결을 수집보다 먼저 실행시킨 것은 아니다.**

### 이전 실패 클래스의 첫 연결 간격

단위는 ms이며 같은 클래스의 여러 컨테이너는 그 실행에서 생성된 순서대로 나열했다. 클래스·생성 순번의 대응이며, A3 실패 뒤의 전체 컨텍스트 흐름이 동일하다는 보장은 아니다.

| 실행 | DocumentGenerationResult | RecordingTranscriptionFlow 생성 1 / 2 / 3 / 4 |
| --- | ---: | --- |
| A1 | 47.910 | 62.066 / 36.653 / 34.677 / 40.665 |
| B1 | 49.642 | 86.323 / 80.219 / 140.436 / 62.791 |
| B2 | 41.120 | 155.327 / 36.877 / 34.446 / 29.949 |
| A2 | 77.333 | 44.778 / 32.293 / 39.582 / 50.725 |
| A3 | 44.250 | 73.328 / 223.547 / 48.209 / 44.690 |
| B3 | 54.181 | 49.587 / 42.678 / 38.381 / 49.432 |

두 클래스의 연결은 이번 6회에서 모두 성공했다. 이번 최초 실패 대상은 `DocumentConfirmationSnapshotIntegrationTest`였다. 특정 기존 클래스의 로직에만 한정된 장애라는 설명은 약해지지만, 공통 원인을 특정한 것은 아니다.

## 7. A3 연결 실패 분석

실패 컨테이너는 `2b704937…0692e`, 실제 목적지는 `localhost/127.0.0.1:52829`였다. 첫 연결과 fallback의 소켓을 같은 컨테이너의 유효한 생존 구간에 대응시켰다.

| 경계 | 시작 hook 진입 기준 | 관측 |
| --- | ---: | --- |
| 시작 수집 완료 | +6.289ms | 로그 요청·inspect 완료 |
| hook 반환 직전 | +6.330ms | 동기 수집 대기는 이미 끝남 |
| 첫 Driver 연결 시작 | +45.545ms | Flyway → SimpleDriverDataSource → Driver |
| SSL 응답 읽기 시작 | +46.164ms | `sslmode=PREFER`, 실제 목적지 IPv4 loopback |
| SSL 시도 실패 | 약 +5,050ms | `enableSSL` 5,003.337ms 후 `SocketTimeoutException` |
| 비SSL fallback 시작 | +5,058.192ms | `tryConnectWithoutSsl`·새 소켓 |
| 인증 응답 읽기 시작 | +5,059.879ms | `doAuthentication` |
| 느린 연결 상태 조회 | 약 +5,646ms | running·OOMKilled=false·RestartCount=0 |
| Driver 실패 반환 | 약 +10,064ms | 전체 10,018.554ms, SQLState `08001` |
| stop 요청 | +10,077.798ms | 제거 전 증거 수집 시작 |
| 최종 로그 저장·stream 정리 | 약 +10,085ms | 최종 저장 완료·worker 종료·stream close |

fallback 인증은 약 **5,002.070ms** 후 `EOFException`으로 끝났다. 실제 thread dump의 Test worker도 `doAuthentication → PGStream.receiveChar → socket read`에서 대기 중이었다. 다른 마이그레이션 테스트의 의도된 실패 span은 이 연결 장애에 합산하지 않았다.

### 서버 쪽에서 확인한 사실

- 최종 서버 로그에는 entrypoint의 임시 DB 초기화·정상 종료 후, PID 1 PostgreSQL의 TCP 5432 listen과 최종 `ready to accept connections`가 있었다.
- 시작·느린 연결 당시·제거 직전 조회에서 대상 컨테이너는 running, OOMKilled=false, RestartCount=0이었다.
- 보존한 로그에서 FATAL·PANIC이나 이번 연결 실패에 대응하는 서버 오류를 찾지 못했다. entrypoint의 정상 임시 서버 종료를 장애로 세지 않았다.
- follow와 제거 직전 최종 로그는 byte 단위로 일치했다.

이 자료는 “컨테이너가 종료·재시작·OOM으로 사라져 연결이 실패했다”는 설명을 지지하지 않는다. 다만 running 상태와 ready 로그만으로 해당 호스트 소켓의 요청이 PostgreSQL까지 전달됐음을 증명하지는 못한다. 현재 서버 로그 설정에는 모든 연결 수신·인증 요청 기록이 없으므로 Docker Desktop의 포트 전달 경로, 서버 내부 수신·응답, 일시적인 호스트 자원 경쟁을 분리할 자료는 부족하다.

이번 실패는 SQL 실행 전 연결 수립 단계다. Hikari 풀 획득 대기나 테스트의 TRUNCATE deadlock으로 분류하지 않는다. 과거 장애와 SSL timeout·fallback EOF의 단계는 같지만, 과거 약 50초와 이번 약 10초를 같은 소요 시간의 재현이라고 쓰지 않는다.

## 8. 기존 검증·격리와 관측 품질

| 검증 항목 | 결과 |
| --- | --- |
| 기존 테스트 목록·클래스 실행 순서 | 6회 모두 동일, 테스트·assertion·스킵 정책 변경 없음 |
| 대상 JPA 74개 | 매회 활성 테스트 트랜잭션 74·rollback 74 |
| 트랜잭션 밖 전후 확인 | 매회 각각 74번, 대상 7개 테이블 잔여 행 0 |
| 대상 공유 컨텍스트 | 매회 같은 실행 안에서 1개 |
| 스케줄러 3개 | 6회 모두 기존 검증 통과 |
| 전체 테스트 | 5회 649 성공, A3 641 성공·8 실패. 전체 성공으로 표시하지 않음 |
| 계측 대상·span·실제 주소 | 6개 계측 대상과 ENTER/EXIT 대응, Driver·소켓의 live 컨테이너 대응 확인 |
| DB 서버 로그 | 컨테이너 280개 각각 follow·최종 파일 보존, 280쌍 byte 일치·초기 로그 포함 |
| 상태 기록 | startup 280·before-stop 280·watchdog 5 = 총 565건 |
| 실제 느린 연결 증거 | A3 `slow-255.threads.txt`, 해당 연결의 상태·서버 로그·XML 보존 |
| 종료 정리 | 280개 모두 worker 완료·stream close. queue 거부·미완료 worker 기록 없음 |
| 원시 관측 종료 알림 | `ClosedByInterruptException` 280건 보존. 최종 저장 후 stream 종료 알림으로 구분 |
| 예상 밖 관측 오류 | 0건. 원시 오류 0건이라는 뜻은 아님 |
| 외부 수집 오류 | B2의 종료 후 PID 조회 1건 보존·별도 분류, 예상 밖 오류 0건 |

격리 검증은 기존 개선안 5의 대상 74개 범위에 대한 증거다. 이를 A3에서 초기화하지 못한 테스트들의 성공·격리 증거로 확대하지 않는다.

기록 대조 audit은 **153개 중 150개 충족·3개 미충족**이다. 미충족은 A3 전체 성공, A3 기대 초기화 수, 전체 컨테이너 생성 순서 일치다. 실패를 허용해 모두 PASS로 바꾸지 않았다. audit 수는 제품 테스트 수와 다르다.

### 호스트 자원과 비교 한계

| 실행 | 테스트 JVM CPU 표본 중앙값 | 테스트 JVM RSS 최대 | 호스트 swap 사용 표본 범위 |
| --- | ---: | ---: | ---: |
| A1 | 43.9% | 944MiB | 22,761~24,905M |
| B1 | 54.6% | 903MiB | 23,503~25,748M |
| B2 | 54.1% | 1,088MiB | 24,281~26,174M |
| A2 | 58.6% | 1,227MiB | 23,457~23,671M |
| A3 | 19.8% | 1,051MiB | 23,556~23,954M |
| B3 | 37.1% | 1,173MiB | 23,486~23,986M |

CPU는 `ps` 표본이며 단일 코어 기준 100%를 넘을 수 있다. RSS는 JVM 전체 상주 메모리여서 힙 제한과 같지 않다. swap은 호스트 전체 값이며 JVM에 귀속시키지 않았다. 매회 swapin/out 변화도 기록했다. 이 수치만으로 B1 지연이나 A3 장애의 원인을 메모리로 확정하지 않는다.

외부 monotonic heartbeat의 최대 간격은 실행별 약 1.009~1.015초였고 OS 절전 기록은 없었다. 이전에 관찰한 긴 공통 관측 공백은 이번에 재현되지 않았다. 소표본·높은 호스트 swap·비무작위 순서·B2 이후 휴지 구간·B1 시계 차이·실패로 바뀐 A3 작업량 때문에 전체 성능의 인과 해석은 제한된다.

## 9. 최종 판단과 다음 단계

**관측 방식은 비동기를 후속 로컬 진단에 사용한다.** 이번 B 3회에서 시작 hook의 대기를 줄였고, 기존 연결 관측·전체 검증·대상 격리·필수 서버 증거·종료 정리를 유지했다. 실제 첫 연결이 대체로 수집보다 늦게 시작됐고 동기 event writer도 남아 있으므로 관측 영향이 모두 제거됐다고 표현하지 않는다.

**DB 연결 timeout의 근본 원인과 개선안 5의 최종 채택은 미확정이다.** 동기에서도 실패했으므로 동기 관측이 장애를 완전히 가린다는 설명은 성립하지 않는다. 비동기에서 3회 성공했다는 사실만으로 장애를 고쳤거나 동기가 원인이라고 결론 내리지 않는다. GitHub Actions의 실행 시간을 줄였다는 근거도 아니다.

다음 실험을 설계한다면, 이번 실패 컨테이너의 타임라인을 기준으로 호스트 포트로 들어간 요청이 실제 PostgreSQL에 수신됐는지를 구분하는 것이 우선이다. 서버 연결 기록과 호스트·컨테이너 경로의 관측 방법을 먼저 정하고, 그 관측 자체의 영향을 검토한 뒤 별도 계획으로 진행한다. SSL 비활성화·timeout 증가·재시도 추가로 이번 결과를 대체하지 않았다.

## 10. 산출물과 재현 근거

- [승인한 실험 계획](../implement-plan/715-db-connection-observer-async.md)
- [전체 집계·실행별 경계·실패 trace](../../backend/output/ci-715/connection-async-20261017/analysis.json)
- [원시 실행 요약·실제 Gradle 명령](../../backend/output/ci-715/connection-async-20261017/cohort.json)
- [153개 기록 대조 audit](../../backend/output/ci-715/connection-async-20261017/audit.json)
- [시작 수집·종료 증거 보존 구현](../../backend/output/ci-715/connection-async-20261017/java/ContainerCapture.java)
- [worker·대기열 구현](../../backend/output/ci-715/connection-async-20261017/java/StartupDispatcher.java)
- [실행기](../../backend/output/ci-715/connection-async-20261017/compare.py) · [집계기](../../backend/output/ci-715/connection-async-20261017/analyze_async.py) · [최종 집계](../../backend/output/ci-715/connection-async-20261017/finalize.py)
- [사전 검증 결과](../../backend/output/ci-715/connection-async-20261017/selftest.json)
- [B2 최초 audit](../../backend/output/ci-715/connection-async-20261017/audit-B2-initial.json) · [재개 기록](../../backend/output/ci-715/connection-async-20261017/resume.json)
- [즉시 stop 사전 probe의 최초 판정](../../backend/output/ci-715/connection-async-20261017/probe-async-initial-assertion.json)
- [A3 실패 HTML 리포트](../../backend/output/ci-715/connection-async-20261017/awake-after-A3/html/index.html)
- [A3 최초 실패 XML](../../backend/output/ci-715/connection-async-20261017/awake-after-A3/xml/TEST-com.knot.backend.document.application.DocumentConfirmationSnapshotIntegrationTest.xml)
- [A3 연결 원본](../../backend/output/ci-715/connection-async-20261017/awake-after-A3/connection/connections.jsonl) · [실제 느린 연결 thread dump](../../backend/output/ci-715/connection-async-20261017/awake-after-A3/connection/slow-255.threads.txt)
- [실패 DB 최종 서버 로그](../../backend/output/ci-715/connection-async-20261017/awake-after-A3/connection/2b7049372b8066930d89b75bbbdb1d43049e14355103901d192f3e598cb0692e.server-before-stop.log)
- [B3 성공 HTML 리포트](../../backend/output/ci-715/connection-async-20261017/awake-after-B3/html/index.html)

실험 디렉터리는 `backend/output/ci-715/connection-async-20261017`이며 과거 자료를 덮어쓰지 않았다. 고정 JAR SHA-256은 `938680d3192789d8bb6aaa916f43bd770be2a09d63f49dc4c4f23d6bf03ea785`다. 소스·의존성·runtime hash와 실제 명령을 함께 보존했으며, 고정한 여섯 실행을 다시 실행하는 명령은 산출물을 덮어쓰지 않도록 거부한다.

이번 범위에서는 제품·기존 테스트 소스 수정, commit·push, CI·운영 환경 실행을 하지 않았다. Java LSP는 timeout, Python LSP는 미설치여서 해당 도구 검증과 실제 컴파일·테스트 결과를 구분했다.

Persona의 implementation/review report-filled는 모두 성공했지만 `workflow finish implement`는 exit 1·`source-read-runtime-unavailable`로 종료했다. Persona 최종 인증은 미완료이며 보고서는 diagnostic-only다. 이를 실제 로컬 실행 결과나 audit의 전체 성공으로 바꿔 표현하지 않았다. 실행기·테스트 JVM·절전 방지 프로세스 종료와 기존 공유 QA 컨테이너 3개 유지, 문서 링크·`git diff --check`를 확인했다.
