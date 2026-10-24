# #936 진행 중 작업 취소 검증 기록

확인일: 2026-10-24 KST. 브랜치: `be/feature/#936`.
기반: #934 `4aa3d7e44`와 #935 `8e301205d`. 연결 계획은 [#936 구현 계획](../implement-plan/936-document-topic-cancellation.md)이다.

## 검증 범위

취소 POST 하나와 기존 재정리의 취소·최종 적용 경계, 취소 완료, APPLYING 복구를 검증한다. 실제 PostgreSQL/Flyway·Security/MockMvc·별도 Docker 앱 HTTP 검증을 구분한다. #937 복원 실행 엔진과 실제 LM Studio 모델은 이번 검증에 포함하지 않는다.

## 테스트 우선 기록

- 도메인 테스트를 먼저 작성하고 requestCancellation·beginApplying 등 미구현 메서드의 컴파일 실패를 확인한 뒤 구현했다.
- API 인수 테스트를 먼저 추가했다. 최초 실행은 새 JPA 필드에 대응하는 migration 누락으로 schema validate가 실패했다. migration 추가 후에는 미구현 취소 경로의 응답 불일치로 실패했고, HTTP 구현 후 통과했다.
- 적용 경계·취소 완료 통합 테스트는 beginApply 미구현 상태의 컴파일 실패부터 확인했다.
- 취소 이후 LLM_INPUT_LIMIT_EXCEEDED 응답이 도착하면 기존 배치 분할이 모델을 두 번 호출하는 것을 DB 통합 테스트로 재현했다. 분할 전에 claim을 확인하도록 바꾸고 취소 후 추가 호출 없이 폐기함을 검증했다.
- 실제 Docker 실행에서 다른 워커를 끄고 주제 재정리만 켰을 때 스케줄링이 활성화되지 않아 QUEUED에 머무는 것을 관측했다. 별도 ApplicationContextRunner 테스트도 스케줄 미등록으로 실패했다. 주제 정리 Scheduler의 조건부 EnableScheduling 등록으로 보완했다.
- 실제 HTTP 최초 취소 응답과 재요청의 cancelRequestedAt 정밀도가 달랐다. 나노초 Clock으로 PostgreSQL 왕복 테스트를 추가해 실패를 재현한 뒤 취소 접수·완료 시각을 MICRO 정밀도로 저장하도록 보완했다.
- 새 Mockito 테스트의 Long 리터럴 타입과 중첩 stubbing 작성 오류도 수정했다. 제품 오류·의도한 TDD RED·검증 코드 오류를 구분한다.

## 사용자·장애 시나리오 결과

| 상황 | 검증 방식 | 확인 결과 |
| --- | --- | --- |
| 대기·실행 중 취소와 반복 클릭 | 도메인·실제 Security·DB | CANCEL_REQUESTED/202, 최초 시각 고정, 완료 후200 |
| 동시에 두 번 취소 | 서로 다른 PostgreSQL 트랜잭션 | 한 접수 시각으로 수렴, 같은 취소 결과 |
| 요청 본문·requestId 없음 | HTTP 인수 | 정상 접수. 추가 입력 계약 없음 |
| 로그인 없음·invalid JWT | 실제 Security, 정상 CSRF와 분리 | 각각 UNAUTHENTICATED·INVALID_JWT/401 |
| CSRF 없음·불일치 | 실제 Security | 403. 본문 없는 POST도 보호 |
| 일반 멤버·비멤버 | 인수·단위 | OWNER 거절·접근 거절. 작업 정보 미조회 |
| 다른 Workspace의 operationId | 인수 | 없는 작업과 같은 TOPIC_OPERATION_NOT_FOUND/404 |
| Workspace 없음·삭제됨 | 인수·DB | 외부404. 이미 접수된 취소는 내부 완료 가능 |
| OWNER 변경 | 실제 DB | 새 OWNER만 요청·재요청 가능, 작업 요청자와 관리 권한 분리 |
| 양수 위반·문자·Long 초과 경로 | HTTP 인수 | 400, 명시된 오류 변환 |
| AI가 응답하지 않는 동안 취소 | DB의 지연 fixture·Docker HTTP | 모델 완료를 기다리지 않고 취소 완료 가능 |
| 취소 후 늦은 AI 성공 | 지연 fixture·Docker 유효 JSON HTTP | 저장·배정 변경 없음, CANCELLED 유지 |
| 취소 후 늦은 timeout/unavailable/input limit | DB 매개변수 테스트 | 재시도·분할 호출 없음, 취소를 FAILED로 덮지 않음 |
| 취소가 적용보다 먼저 commit | latch·두 DB 트랜잭션 | beginApply 거절, 공개 배정 변경0 |
| 적용이 취소보다 먼저 commit | latch·두 DB 트랜잭션 | 취소409, APPLYING 유지 |
| 이미 SUCCEEDED/FAILED/APPLYING | 실제 Security | TOPIC_OPERATION_NOT_CANCELLABLE/409 |
| 취소 정리 DELETE 실패 | PostgreSQL trigger 오류 | 완료 상태와 삭제가 함께 rollback, 다음 시도 성공 |
| 취소 완료 후 새 작업 시작·이전 실패 콜백 | DB | 새 작업과 기존 복원 포인터 불변 |
| 취소 중 30분 경과 | 도메인·DB | 이미 접수한 취소를 FAILED로 바꾸지 않음 |
| 이전 복원본·작업 중 생긴 새 문서 | DB | 복원 숫자 ID·배정 보존, 취소 완료 후 canRestore 일치 |
| APPLYING 직후 중단·lease 만료 | DB 재인계 | 새 token만 적용, LLM 재호출0, APPLYING 경계 유지 |
| 적용 UPDATE 실패·5초 경계·두 번째 실패 | 실제 trigger·Clock | 전체 rollback, 한 번 재시도, 한도 초과 시 기존 분류 유지 |
| 전체 deadline 도달 | 실제 DB·Clock | 적용 재인계 없이 FAILED, 부분 적용 없음 |
| 실행 슬롯 포화·한 취소 정리 저장 오류 | Scheduler 단위·Docker 실행 | 취소 정리는 LLM executor와 독립, 다른 정리 계속 |
| 앱 재시작 | 별도 Docker 컨테이너 | 아래 실제 HTTP 관측 참고 |
| 나노초 시계의 최초 시각 재전송 | 실제 DB 왕복 | 최초 응답과 재조회 시각 정확히 일치 |

Concurrency 테스트는 TransactionTemplate·다른 연결·CountDownLatch로 commit 순서를 통제한다. Mockito만으로 DB lock·rollback을 증명했다고 해석하지 않는다. RESTORE kind 취소 응답과 상태는 검증하지만 복원 엔진 전체 사용자 흐름은 #937 후속 검증이다.

## 실제 Docker HTTP 관측

Docker 29.2.1에서 별도 프로젝트 `knot-cancel-936`을 사용한다. 앱은 Java 25 실행 이미지, DB는 pgvector/pgvector:pg18이며 Flyway 전체 적용·Hibernate validate로 시작한다. 앱 포트는 `127.0.0.1:18936`이다. 다른 로컬 컨테이너와 운영 DB는 사용하지 않는다.

로컬 LLM HTTP 스텁은 정상 분류 JSON을 14초 늦게 반환한다. 앱은 실제 LmStudioClient·응답 parser를 사용한다. 외부 모델 품질 또는 공급자 측 요청 강제 중단을 검증한 것은 아니다.

1. DB에 실제 녹음·전사·생성 작업·문서 fixture를 저장한다.
2. 실제 재정리 시작 API를 호출하고 스케줄러가 RUNNING으로 전환해 LLM HTTP를 호출하는 것을 기다린다.
3. 쿠키 인증·실제 CSRF로 취소 POST를 호출한다. 최초202와 재요청의 최초 접수 시각 일치를 확인한다.
4. CANCELLED 이후에도 지연된 정상 LLM 응답이 돌아올 때까지 기다린다. 추가 배정0·복원 포인터0·취소 유지·완료 재요청200을 확인한다.
5. 새 재정리의 취소를 접수하고 DB의 CANCEL_REQUESTED를 확인한 뒤 앱 컨테이너를 실제 restart한다. 재시작 후 CANCELLED 수렴과 최초 시각 보존을 확인한다.

두 시나리오 모두 실제 Docker HTTP에서 통과했다. 재시작 관측을 위한 10초 polling 설정에서는 operation 1의 CANCEL_REQUESTED가 2026-10-24 01:53:27.079481 UTC, CANCELLED가 01:53:36.953351 UTC였다. operation 2는 01:53:41.749032 UTC에 취소를 접수하고 실제 컨테이너 restart 후 01:53:49.350820 UTC에 완료했으며 최초 접수 시각이 유지됐다.

기본 polling 1초 설정으로 별도 실행한 결과 취소 요청부터 완료 확인까지 0.841초였다. 이 실행에서도 LLM fixture 요청은 1회, 지연된 응답 이후 문서 배정 변경0·복원 포인터0을 확인했다. 한 번의 로컬 관측이며 운영 지연 시간 SLA나 부하 측정 결과로 일반화하지 않는다.

최초 compose의 HTTP LLM hostname은 기존 설정 검증에서 거절됐다. 스텁과 앱이 같은 네트워크 namespace를 사용하고 127.0.0.1:1234로 통신하도록 로컬 구성만 고쳤다. 제품의 URL 검증 규칙은 약화하지 않았다.

## 전체 검증 결과

Java 25에서 `./gradlew clean spotlessCheck check bootJar` 단일 실행이 **BUILD SUCCESSFUL, 4분30초**에 완료됐다. 최신 수정 후 수행했으며 12개 task 모두 실행했다.

| 검증 | 테스트 수 | 실패·오류 | skip |
| --- | --- | --- | --- |
| 전체 단위 | 2,078 | 0 | 기존 실모델 opt-in 2 |
| PostgreSQL/Flyway 통합 | 938 | 0 | 기존 환경 조건 4 |
| Security·API 인수 | 627 | 0 | 0 |
| 합계 | 3,643 | 0 | 6 |

#935의 전체 3,603건 대비 새 검증 40건을 추가했다. 단위 14, 실제 DB 통합 17, 인수 9건이다. #934·#935 전체 회귀를 포함한다. Spotless·bootJar·git diff --check도 통과했다. 기존 녹음 logging 테스트의 javac varargs warning은 이번 변경과 구분하며 오류로 처리하지 않았다.

중간에 겹쳐 실행한 Gradle 검증이 integrationTest의 binary 결과 파일을 덮어써 NoSuchFileException이 발생했다. 제품 테스트 실패와 구분하며, 최종 판정은 다른 Gradle 실행이 없는 상태에서 clean 후 단일 실행한 결과만 사용한다.

## 로컬 파일·보존 경계

실행용 compose·스텁·검증 스크립트·원시 결과는 `backend/ops/document-topic-local/936/`에만 두었다. 기존 backend/.gitignore의 `/ops/document-topic-local/` 규칙으로 제외되며 git check-ignore로 확인했다. 공용 compose.yml이나 테스트를 광범위하게 ignore하지 않았다.

실제 토큰·운영 원문은 사용하지 않았다. Docker fixture의 인증키·토큰은 로컬 검증 전용이다. 이 문서에는 토큰·개인정보·LLM 원문을 기록하지 않는다. 개발·검증 후 사용자 요청으로 #935 기반 commit·push·별도 PR 게시를 진행한다. 운영 배포·운영 데이터 복구는 포함하지 않는다.

게시 준비에서 Governance 단위 테스트 10건도 통과했다. PR에는 Closes #936만 지정하며 선행 #934·#935는 자동 종료하지 않는다.

검증 후 이 작업이 만든 Docker 프로젝트의 앱·DB·스텁 컨테이너와 네트워크만 내려 정리했다. 기존 로컬 컨테이너는 건드리지 않았다. 로컬 실행 파일과 원시 결과는 ignored 경로에 보존했다. 위 결과는 로컬 검증이며 원격 CI·운영 배포·실제 모델 공급자 검증과 구분한다.
