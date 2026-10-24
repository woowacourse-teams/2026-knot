# #935 주제 정리 상태 조회 로컬 검증

## #934 최신 변경 반영 후 별도 PR 검증 · 2026-10-24

사용자 승인으로 #935의 미커밋 작업을 백업하고 #934 `4aa3d7e44`를 fast-forward 반영했다. 이미 선행에 들어간 시작·인증·ADR 변경을 중복 복원하지 않고, 상태 조회 전용22파일만 해시 일치로 복원했다. #934에 추가된 잘못된 인증 쿠키의 시작 API 인수 테스트도 보존했다.

- 이번 PR은 GET 상태 조회 하나와 직접 필요한 실행 시작 시각·숫자 복원 버전의 저장 지원만 포함한다. 선행 #938이 OPEN이므로 base는 be/feature/#934다. #938 병합 후 develop 기준 차이를 확인하고 base를 변경해야 한다.
- 선행 반영 후 관련 단위·PostgreSQL 통합·상태/시작 API 인수가 통과했다. 최종 Java25 `./gradlew spotlessApply check bootJar`는4분50초에 BUILD SUCCESSFUL이다.

| 검증 | 건수 | 실패·오류 | 기존 조건부 skip |
| --- | ---: | ---: | ---: |
| 전체 단위 | 2064 | 0 | 2 |
| 전체 PostgreSQL 통합 | 921 | 0 | 4 |
| 전체 인수 | 618 | 0 | 0 |
| 합계 | 3603 | 0 | 6 |

Spotless·git diff --check와 governance 단위10건도 통과했다. 이 결과는 이전3602건 검증과 구분한다. 이전에는 없던 #934 인증 인수1건을 함께 검증했다. 운영 배포·실제 FE·실제 모델 추론·원격 CI 결과로 표현하지 않는다. Notion 원문은 수정하지 않았다. 취소·되돌리기 실행과 복원 작업의 targetVersionId 영속 연결은 #936·#937 후속 범위다.

## Notion 우선 지시에 따른 정합 수정 · 2026-10-24

최신 사용자 지시가 이전 UUID 유지 결정을 대체한다. 이 절은 아래 초기 구현 기록보다 우선한다. 운영 Notion 4개 API를 실시간 읽고 시작·조회 코드를 맞췄으며 원격 명세는 변경하지 않았다.

- 시작 응답은 operationId(Long), workspaceId, type(REORGANIZATION), status, createdAt의 5개 필드다. 내부 counts/deadlineAt/UUID는 노출하지 않는다.
- 상태 조회의 작업·복원 버전·원본 작업 ID는 Long이며 문서 수는 targetDocumentCount/processedDocumentCount/changedDocumentCount를 사용한다. 성공 전 변경 수는 null이다. requestId만 UUID다.
- V65는 기존 복원 UUID를 보존하며 별도 숫자 id를 추가한다. 복원 포인터 교체 시 숫자 ID도 갱신하며, 무변경 성공은 이전 숫자 ID도 유지한다.
- 시작 충돌은 진행 중 TOPIC_OPERATION_IN_PROGRESS, requestId 충돌 TOPIC_REQUEST_ID_CONFLICT, 빈 대상 TOPIC_REORGANIZATION_TARGET_EMPTY로 분리한다. 빈 대상은 접수 트랜잭션을 롤백한다. 없는/삭제 Workspace는 404다.
- requestId 누락·잘못된 UUID는 VALIDATION_ERROR다. 파싱 불가 JSON과 경로 타입 오류는 기존 공통 처리기에 맡긴다.
- 주제 정리 경로에만 인증 entry point를 분기해 인증 쿠키 없음은 UNAUTHENTICATED, 쿠키가 있지만 인증되지 못한 요청은 INVALID_JWT로 반환한다. 기존 경로의 인증 응답은 유지한다. 설치된 Spring Security 7.1 소스의 builder API를 사용한다.
- 중첩 응답 record를 파일별 타입으로 분리했다. 7상태·동일 snapshot·재시도 RUNNING·OWNER 권한 정책은 유지한다. #936 취소와 #937 복원 실행은 구현 범위에 포함하지 않았다.

실패 우선 검증: 새 HTTP 계약 테스트 24건 중 기존 구현에서 15건이 실패했다. 수정 후 PostgreSQL 통합과 Security 인수 집중 검증이 통과했다. 빈 대상 거절로 바뀌면서 기존 성공 전제의 통합 테스트 5건도 실패했고, 정상 흐름에는 실제 문서 fixture를 추가하고 빈 대상 사례는 롤백·오류 코드 검증으로 교체했다. 새 INVALID_JWT 인수 테스트는 기존 UNAUTHENTICATED 응답 때문에 실패한 뒤 주제 정리 경로 분기에서 통과했다. assertions를 제거하거나 테스트를 skip하지 않았다.

최종 검증: Java25·실제 PostgreSQL/Flyway·Security/MockMvc를 사용했다. `check bootJar`는 BUILD SUCCESSFUL이다. 아래 초기 구현의 총계는 이번 변경 후의 결과가 아니다.

| 검증 | 이번 결과 |
| --- | --- |
| 전체 단위 | 2064건, 실패0·오류0·기존 조건부 skip2 |
| 전체 PostgreSQL 통합 | 921건, 실패0·오류0·기존 조건부 skip4 |
| 전체 인수 | 617건, 실패0·오류0·skip0 |
| 합계 | 3602건, 실패0·오류0·skip6 |
| Spotless·check·bootJar | 최종 PASS |
| git diff --check | PASS |

전체 실행이 중단되어 단위·통합 결과를 재사용하고 미완료 전체 인수를 이어 검증했다. 재개 직후 기본 checkout에서 잘못 시작한 검증1회는 중단했으며 이 표의 결과에 사용하지 않았다. 소스 수정은935 worktree에만 수행했다. 이후 모든 경로를 절대 경로로 명시했다.

마지막 Spotless 검사에서 `There were 0 lint error(s)`로 실패했다. 설치된 Spotless8.9 소스에서 포맷 출력 파일은 남았지만 실제 소스 차이는 없을 때도 해당 실패 분기로 들어갈 수 있음을 확인했다. Java25를 명시하고 `spotlessJava --rerun-tasks spotlessCheck`로 산출물을 다시 생성하자 소스 수정 없이 PASS했다. 캐시와 JVM 변경의 개별 기여를 분리 측정하지 않았으므로 JVM만을 근본 원인으로 단정하지 않는다. 이후 최종 `check bootJar`는11개 작업 UP-TO-DATE와 BUILD SUCCESSFUL을 반환했다. 이1초 검사는 전체 테스트를 새로 실행한 시간으로 표현하지 않는다.

Persona 구현·리뷰 보고서를 갱신하고 report-filled 두 건이 통과했다. 종료 검사는 기존 `evidence-path-unsafe`로 실패했으며 제품 Gradle 검증과 구분한다. prod·실제 FE·실제 모델·원격 CI는 검증하지 않았다. commit/push/PR·Notion 원격 수정은 수행하지 않았다.

## 초기 구현 기록 · 이전 UUID 결정 기준

확인일: 2026-10-23 KST. 브랜치: `be/feature/#935`.
선행: `266239b84d822f82449d79943877dc0640bfb27f` (#934 PR938, 분기 당시 OPEN).

## 구현과 경계

- OWNER 전용 GET `/api/v1/workspaces/{workspaceId}/document-topics/status`를 구현했다. Cookie 인증을 사용하고 GET에 CSRF를 요구하지 않는다. no-store 응답이다.
- 활성 작업 우선·최근 접수 작업 조회, 직전 복원 정보와 canRestore를 readOnly REPEATABLE_READ로 조립한다. 쓰기 잠금·LLM·작업 만료·복구를 조회에서 실행하지 않는다.
- 공개 ID는 UUID이며 내부 kind를 REORGANIZATION/RESTORATION으로 변환한다. counts의 의미와 null, 최종 실패용 고정 안내를 적용한다. 내부 오류 문자열은 반환하지 않는다.
- 자동 재시도의 내부 QUEUED/retry_count>0는 외부 RUNNING으로 변환한다. 같은 요청의 시작 응답도 같은 변환을 사용한다.
- V64는 nullable started_at을 추가한다. 최초 claim만 기록하고 재시도 시각으로 덮어쓰지 않는다. 기존 미기록 시각은 추정하지 않는다.
- 7개 상태 표와 FE의 3초 폴링·진행 수/완료 구분을 OpenAPI 설명에 반영했다. 진행 수가 대상 수와 같아도 완료는 SUCCEEDED로 판정한다.
- 취소·복원 실행 API는 #936·#937 범위다. 현재 복원 실행이 없으므로 operation.targetVersionId는 null이며 #937에서 영속 연결할 예정이다. 이미 존재하는 직전 복원 버전은 정상 반환한다. 이 구현으로 취소·복원 실행이나 FE 화면 구현을 완료했다고 주장하지 않는다.

## 실패 우선 증거

1. 첫 인수 테스트는 Docker daemon이 중지되어 Testcontainers 초기화에 실패했다. Docker Desktop을 시작한 뒤 다시 실행했다. 환경 오류는 기능 RED 증거로 사용하지 않았다.
2. GET 구현 전 `DocumentTopicStatusAcceptanceTest.find_success_empty`가 HTTP 기대 불일치로 실패했다. 최초 실행 시각·상태 변환 테스트도 미구현 메서드로 컴파일 실패한 뒤 최소 구현을 추가했다.
3. `find_success_concurrentRecoveryReplacement`에서 operation 조회 직후 별도 DB 트랜잭션이 새 성공 작업과 복원 포인터를 commit하도록 구성했다. READ_COMMITTED에서는 이전 작업과 새 복원 UUID가 섞여 assertion이 실패했다. REPEATABLE_READ로 변경한 뒤 같은 테스트와 다음 조회 검증이 통과했다. sleep은 사용하지 않았다.
4. 테스트 fixture의 시각은 MICRO 정밀도를 사용한다. 고정 시계와 PostgreSQL 반올림에 의한 #938 인수 테스트 결함을 재도입하지 않았다.

## 검증 결과

Java 25·Gradle wrapper·Docker의 실제 PostgreSQL/Flyway를 사용했다.

| 검증 | 결과 |
| --- | --- |
| 초기 관련 단위·DB·Security 인수 | 단위9·통합22·인수20 PASS. #934 회귀 포함 |
| `./gradlew spotlessApply check bootJar` | BUILD SUCCESSFUL, 4분24초 |
| 전체 단위 | 2062개, 실패0·오류0, 실모델 opt-in2개 skip |
| 전체 통합 | 920개, 실패0·오류0, 기존 환경 조건4개 skip |
| 전체 인수 | 612개, 실패0·오류0·skip0 |
| 합계 | 3594개, 실패0·오류0·skip6 |

전체 검증 후 OpenAPI 설명의 상태 표만 보강했다. `./gradlew spotlessApply acceptanceTest --tests '*DocumentTopicStatusAcceptanceTest.find_success_openApi' spotlessCheck bootJar`가 10초에 성공했다. 새 업무 테스트 26개를 포함한다. 최종 `git diff --check`도 통과했다.

## 확인한 사용자 시나리오

작업 없음, 7개 상태, 자동 재시도 중 failure=null, 최종 실패도 HTTP200, 알려지지 않은 내부 오류 미노출, 성공0건의 변경 수0, 이전 성공 복원 정보 유지, 활성 작업 중 canRestore=false, Workspace 격리, 현재 OWNER 승계·기존 OWNER 권한 상실·탈퇴, 삭제 Workspace404, 비인증401·비멤버/MEMBER403·잘못된 경로400을 검증했다.

DB 테스트는 같은 created_at의 접수 순서, 활성 작업 우선, 최초 startedAt 실제 저장 왕복과 복원 포인터 동시 교체를 검증했다. Mockito 테스트는 접근 거절 시 Query 미호출과 DB 오류를 빈 상태로 숨기지 않는 것을 확인했다. 실제 Security/MockMvc로 GET 무CSRF 및 JSON과 OpenAPI를 확인한다.

## 한계와 전달

- 로컬 DB·Security/MockMvc 증거다. prod 배포·실제 브라우저 FE·실제 모델 추론·원격 CI 증거가 아니다.
- 이 조회 API 검증은 기존 재정리의 1,000개·5분 목표나 LLM 분류 품질을 검증하지 않는다.
- 원격 Notion·Issue 수정, commit/push/PR·merge·배포는 수행하지 않았다.
- 편집 후 LSP가 30초 응답 시간 초과를 반복했다. 파일 적용 여부와 실제 Gradle 컴파일·테스트 결과를 구분했고 테스트 실패를 숨기지 않았다.
- Persona 보고서 두 개를 채우고 evidence 읽기를 기록했으나 `npx ph workflow finish implement`는 기존 `evidence-path-unsafe`로 실패했다. Gradle 제품 검증과 구분하며 하네스 완료를 주장하지 않는다.
