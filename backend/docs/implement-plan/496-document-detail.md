# #496 문서 상세 조회 구현 계획

상태: 상세 API의 기존 검증 완료, 음성 도메인 담당 경계를 반영한 연결 계획·미구현, Persona 완료 인증 미통과 · 확인일: 2026-10-06 · 브랜치: `be/feature/#496`.
기준: 사용자 최신 API, 대화에서 확정한 제품 결정, Issue #496과 현재 코드. 사용자 결정과 팀 승인 여부는 구분한다. 아래 본문은 구현 전 계획이며 실제 진행 사항은 마지막 절에 기록한다.

## 1. 범위와 브랜치

[#496](https://github.com/woowacourse-teams/2026-Knot/issues/496)은 `GET /api/v1/workspaces/{workspaceId}/documents/{documentId}`를 완성하는 작업이다. 현재 체크아웃한 `be/feature/#496`에서 진행한다.

현재 Document·Transcript·DocumentGenerationJob·DocumentConfirmation 구현이 없어 상세 조회에 필요한 최소 저장 기반도 함께 만든다. 이번 완료 범위는 저장된 문서의 본문·출처·내 확인 상태·확인 집계를 실제 인증과 PostgreSQL을 거쳐 반환하는 것까지다.

문서 API 다섯 개는 `DocumentController implements DocumentApi`로 모으고, 이번에는 상세 GET 메서드만 작성한다. Job API 두 개는 이후 `DocumentGenerationJobController implements DocumentGenerationJobApi`에 둔다. 자동 생성 실행은 #501, 확인 처리는 #498, 원문 구간 조회는 #499에 연결한다.

구현량은 아직 산정하지 않았다. 저장 기반이 포함되므로 실제 변경량을 보고 PR 범위를 판단한다. 열린 PR #480·#483·#485의 읽기/저장 제약/업로드 완료 패턴은 확인했지만 현재 브랜치에 병합된 구현으로 취급하지 않는다. 업로드 완료가 STT와 문서 생성 완료를 뜻하지 않는다.

## 2. 구현할 흐름

로그인한 사용자가 문서를 선택하면 현재 Workspace 멤버 여부를 검사한 뒤 해당 Workspace의 문서와 확인 기록을 읽는다. 녹음 참여 여부는 문서 조회 권한의 조건이 아니다.

```text
GET /api/v1/workspaces/{workspaceId}/documents/{documentId}
  → 기존 Security: 로그인 검사
  → DocumentController: path ID와 인증된 memberId 전달
  → DocumentDetailService: 현재 Workspace 멤버 검사
  → DocumentDetailQuery: 문서·녹음·원문 연결·확인 집계 조회
  → 내 확인 상태 판정 및 DocumentDetailResult 구성
  → DocumentDetailResponse 변환
  → HTTP 200
```

| 조건 | 결과 |
| --- | --- |
| 로그인하지 않음 | 401 UNAUTHENTICATED |
| 현재 Workspace 멤버가 아님·탈퇴·Workspace 삭제 | 403 WORKSPACE_ACCESS_DENIED |
| 문서 없음 또는 다른 Workspace의 문서 | 404 DOCUMENT_NOT_FOUND |
| 숫자 경로의 형식 오류 | 기존 handler의 400 INVALID_PARAMETER |
| 현재 멤버이고 해당 Workspace 문서 존재 | 200, 상세 응답 |

예를 들어 생성 당시 대상 A·B·C 중 A가 확인했고 B는 활성 미확인, C는 미확인 탈퇴라면 집계는 confirmed=1/pending=1/excluded=1이다. 이후 가입한 D는 문서를 읽을 수 있지만 NOT_REQUIRED다. A는 CONFIRMED, B는 PENDING이다.

이 GET은 확인 기록이나 문서 status/archivedAt을 변경하지 않는다. 성공 문서는 다른 주제 Job의 실패·만료와 관계없이 즉시 조회한다. `recordingSessionId`는 응답 필드이며 path에는 workspaceId/documentId만 둔다. 예시 JSON의 누락된 쉼표와 `No해당` 오타는 명세 정리 시 수정한다.

## 3. 나올 코드와 메서드 초안

기존 `WorkspaceOwnershipTransferCandidateApi/Controller`의 Swagger·HTTP 경계, `WorkspaceOwnershipTransferCandidateQuery/QueryAdapter`의 다중 테이블 조회, `WorkspaceMemberRepository`의 활성 멤버 검사, `GlobalExceptionHandler`의 오류 처리를 재사용한다. 문서 읽기에 녹음 쓰기용 잠금 검사를 적용하지 않는다.

아래 클래스와 메서드는 새로 작성할 제안이다. 위치는 `backend/src/main/java/com/knot/backend/` 기준이며 이름은 실제 구현에서 역할이 겹치지 않도록 조정한다.

| 위치·클래스 | 책임 | 추가할 메서드 후보 |
| --- | --- | --- |
| document/presentation/DocumentApi | Swagger의 문서 설명·응답·오류·쿠키 인증 계약 | findDetail(workspaceId, documentId, authenticatedMember) |
| document/presentation/DocumentController | 실제 GET 매핑, 인증 memberId 전달, 응답 변환 | findDetail(...) |
| document/application/DocumentDetailService | 활성 권한 검사, 문서 없음 404, 내 상태와 Result 구성 | findDetail(workspaceId, memberId, documentId) |
| document/application/DocumentDetailQuery | Workspace 범위 상세와 집계를 읽는 조회 계약 | findDetail(workspaceId, documentId, memberId) → Optional<DocumentDetailSnapshot> |
| document/infrastructure/DocumentDetailQueryAdapter 및 DocumentJpaRepository | 실제 DB 조회와 application 조회 결과 변환 | findDetail(...) 및 findDetailSnapshot(...) |
| document/domain/MyConfirmationState | 대상 여부·확인 시각으로 내 3상태 판정 | resolve(required, confirmedAt) |
| document/application/dto/result의 DocumentDetailSnapshot/DocumentDetailResult | 조회한 사실과 외부에 반환할 application 결과 | Snapshot은 대상 여부/확인 시각, Result는 판정된 상태를 보유 |
| document/presentation/dto/response/DocumentDetailResponse | API 필드와 Schema, Entity 직접 노출 방지 | from(DocumentDetailResult) |
| 같은 response 패키지의 DocumentConfirmationSummaryResponse | 확인·미확인·제외 집계 record, 별도 파일 | 단순 응답 값 구성 |
| document/domain/DocumentErrorCode·DocumentException | DOCUMENT_NOT_FOUND를 기존 ProjectException에 연결 | 기존 도메인 예외 생성 관례 적용 |

`DocumentApi`는 Swagger 계약을 모으는 인터페이스이고 Controller가 이를 구현한다. Query/Adapter는 실제 다중 테이블 조회를 위해 기존 관례대로 분리한다. Service는 읽기 전용 트랜잭션이며 외부 호출과 저장 변경 없이 Result를 반환한다. GET Request/Command나 별도 UseCase 인터페이스는 필요하지 않다.

## 4. 저장 기반과 주의할 점

| 모델 | 이번에 필요한 저장 내용 |
| --- | --- |
| Transcript | ID, recordingSessionId, 전체 원문 텍스트, 생성 시각. recording 도메인 배치 제안 |
| DocumentGenerationJob | ID, 입력 Transcript, Job 상태·시각. Document의 필수 Job FK를 성립시키는 최소 모델 |
| Document | Workspace·녹음·원문·Job ID, topic/title/summary/content, DRAFT/ARCHIVED, createdAt/archivedAt |
| DocumentConfirmation | documentId/memberId 복합 키와 nullable confirmedAt. 문서 생성 시 대상 스냅샷 |
| DocumentConfirmationId | 복합 키용 별도 JPA class |

Job 모델이 먼저 필요한 이유는 ERD의 Document→Job 필수 연결 때문이다. 이번에 Job 실행·자동 재시도·보존 실행기까지 구현하지는 않는다. 생성 시 Document와 확인 대상의 동시 저장은 #501의 실행 경계에서 보장한다. GET에서 현재 멤버 전원으로 대상 행을 다시 만들지 않는다.

DB에는 `UNIQUE(generation_job_id)`와 `UNIQUE(recording_session_id, topic)`를 둔다. 같은 Job과 같은 녹음·같은 확정 주제의 중복 문서를 차단하고 다른 녹음의 같은 주제는 허용한다. 출처와 Workspace 일치는 FK 설계와 저장 경계에서 검증하며 읽기 JOIN으로 모순을 숨기지 않는다.

필수 컬럼·복합 키·FK·문서 상태/보관 시각의 CHECK를 PostgreSQL에서 검증한다. summary는 nullable, DRAFT의 archivedAt은 null이다. 성공 문서의 연결 원문을 연쇄 삭제하는 cascade를 넣지 않는다.

confirmedAt이 있으면 confirmed, 없고 현재 활성 대상이면 pending, 없고 탈퇴한 대상이면 excluded다. 확인한 뒤 탈퇴한 사람은 confirmed에 남는다. 탈퇴·재가입 이력은 EXISTS 또는 활성 행 조건으로 한 사람당 한 번만 집계한다. 기존 ERD의 현재 멤버십 정책에 따라 DRAFT의 원래 대상이 재가입하면 pending에 포함한다. GET은 ARCHIVED를 되돌리지 않으며 보관 후 상태 전이 정책은 쓰기 작업에서 다룬다.

문서 상태와 집계는 하나의 SQL 결과로 읽는다. readOnly만 선언해서 여러 SELECT의 조회 시점이 같다고 가정하지 않는다.

| 구현 전 확인할 항목 | 현재 근거와 처리 방법 |
| --- | --- |
| Flyway 번호·적용 순서 | 현재 저장소 파일은 V24까지, 열린 #483에 V26 존재. 운영 DB 적용 이력은 미관측. 구현 직전 병합·배포 순서와 번호를 확인하며 outOfOrder로 우회하지 않음 |
| 녹음 길이 출처 | 현재 RecordingSession.accumulatedRecordingMillis/1000 사용 제안. 일시정지 제외·소수 초 버림. 실제 오디오 메타데이터를 정본으로 정한 계약이 있으면 구현 전 맞춤 |
| Persona 작업 카드 | 현재 backlog의 #427 로그인 이력을 보존하며 #496 작업 카드로 연결 |
| #496 ADR | backend/docs/adr/496-document-my-confirmation-state.md가 아직 없음. 원래 결정 snapshot 복구 또는 실제 논의한 대안/선택 근거 재검증 후 materialize_adr.py로 Proposed 생성. 3상태 결정을 재인터뷰하거나 대안을 추측하지 않음 |

Migration은 Transcript→Job→Document→Confirmation 순서로 만든다. 기존 Member/Workspace/Recording 데이터와 JPA validate를 검증한다. 원문의 실제 startMillis·구간 생산은 STT 선행 작업 및 #499에서 연결하며 TEXT에서 시간·화자를 추정하지 않는다.

## 5. TDD와 검증 순서

각 단계는 실패하는 테스트를 만든 뒤 통과시키는 최소 구현으로 진행한다.

1. **시작 준비**: Persona #496 작업 카드, Proposed ADR, migration 적용 순서를 연결한다.
2. **저장 제약**: 필수 필드·FK·복합 키·중복 제한 테스트 → 목적별 migration과 Entity를 만든다.
3. **내 확인 상태**: 미확인·확인·비대상 테스트 → MyConfirmationState 판정을 만든다.
4. **상세 DB 조회**: DRAFT/ARCHIVED·null·확인 집계·Workspace 범위·재가입 이력 테스트 → Query/Adapter를 만든다.
5. **서비스 권한**: 비멤버 403 및 조회 미호출·문서 없음 404·정상 결과 테스트 → DocumentDetailService를 만든다.
6. **HTTP 계약**: ID 전달·응답 JSON·오류 테스트 → DocumentApi/Controller/Response를 만든다.
7. **전체 연결**: 실제 쿠키·Security·PostgreSQL·OpenAPI를 검증하고 구현 기반 API 문서와 Persona 보고서를 정리한다.

| 테스트 위치·방식 | 검증할 동작 | 기대 결과 |
| --- | --- | --- |
| MyConfirmationStateTest, JUnit | 대상 없음·확인 시각 있음·대상 미확인 | NOT_REQUIRED·CONFIRMED·PENDING |
| DocumentDetailServiceTest, Mockito | 정상·권한 없음·문서 없음 | Result·403 및 상세 조회 미호출·404 |
| DocumentMigrationIntegrationTest, PostgreSQL/Flyway | 필수 필드·FK·복합 키·같은 Job/녹음 주제 중복 | 잘못된 저장 거절, 다른 녹음의 같은 주제 허용, 기존 데이터 보존 |
| DocumentDetailQueryIntegrationTest, PostgreSQL | 상태/null·0명 집계·가입/탈퇴/재가입·부분 성공 | 정확한 필드와 중복 없는 집계, 실패 Job과 관계없이 성공 문서 조회 |
| 위 Query 통합 테스트 | 녹음 시간의 1초 미만·초 경계·일시정지 제외 | 정한 밀리초→초 변환 결과 |
| DocumentControllerTest, standalone MockMvc | 경로/principal 전달·JSON·숫자 형식 오류 | 올바른 서비스 인자·응답·400/404 |
| DocumentDetailAcceptanceTest, 실제 Security/PostgreSQL | 쿠키·미인증·비멤버/탈퇴/삭제 Workspace·타 Workspace 문서 | 200/401/403/404, 비참여 현재 멤버도 200 |
| 위 인수 테스트 | GET 전후 저장값·CSRF 없는 GET·시각 표현 | 문서/확인 기록 불변, GET 성공, UTC 표현 |
| 실제 /v3/api-docs | 경로·인증 scheme·응답 필드·enum/null·오류 | 최신 계약과 일치, principal 숨김 |

Mockito는 DB 제약·커밋을, standalone MockMvc는 실제 Security 필터를 증명하지 않는다. 이를 각각 PostgreSQL 통합 테스트와 실제 Security 인수 테스트로 검증한다. 커밋 요청이 있을 때는 스키마·판정·조회 adapter·서비스·HTTP·문서를 작은 단위로 나눈다. 불필요한 refactor 단계는 추가하지 않는다.

현재 build.gradle에서 확인한 검증 명령이다. 아직 실행하지 않았다.

```bash
./gradlew test --tests 'com.knot.backend.document.*'
./gradlew integrationTest --tests 'com.knot.backend.document.*'
./gradlew acceptanceTest --tests 'com.knot.backend.document.*'
./gradlew spotlessCheck
```

공유 schema 변경의 병합 준비에는 전체 test/integrationTest/acceptanceTest와 Persona의 implementation/review 보고서 및 finish implement를 수행한다.

## 6. 완료 기준과 다음 작업

실제 인증된 요청이 저장된 DRAFT/ARCHIVED 상세를 정확히 반환하고, 권한/범위 오류와 내 확인 3상태·집계·DB 제약·조회 불변·Swagger 계약의 검증이 통과하면 #496을 완료한다.

이번 테스트는 저장 fixture를 이용한 조회 검증이다. 실제 업로드→STT→자동 문서 생성의 전체 흐름과 공급자 타임스탬프 지원은 #501 및 STT 작업에서 별도로 검증한다. API 테스트만으로 생성 실행까지 완료했다고 판단하지 않는다.

후속 순서는 **#496 → #495 → #497 → #498 → #499 → #493 → #494 → #501**이다. 기존 docs/harness/notion-alignment.md와 backend/docs/product/current-v2-mvp.md의 사용자 변경을 보존한다.

## 실제 구현 진행 사항

- DocumentApi/DocumentController의 메서드는 findDocument, 상세 Service/Query는 find로 구현했다.
- 다중 테이블 조회는 QueryAdapter에서 JdbcClient로 단일 SQL을 실행한다. 전용 JPA 조회 Repository와 Row는 추가하지 않았다. Entity는 JPA mapping/validate와 실제 저장·재조회로 검증한다.
- V27__create_document_read_models.sql에 최소 4테이블, 출처 복합 FK, Job·녹음 주제 UNIQUE, 확인 복합 키와 보관 상태 CHECK를 구현했다.
- #496 Proposed ADR을 검증된 현재 결정 근거로 materialize했다. 이전/최신 계약 두 대안을 기록했고 팀 승인으로 표현하지 않았다.
- 저장 제약 11개, 상세 Query 8개, HTTP/Security/OpenAPI 13개 및 관련 단위 테스트가 집중 실행에서 통과했다. 신규 테스트는 총 54개다.
- `./gradlew check --console=plain` 통과: 단위 391개, 통합 170개, 인수 262개, 총 823개. 실패·오류·skip은 0이며 spotlessCheck도 통과했다.
- API 구현 계약은 [문서 상세 API](../api/document-detail.md)에 저장했다. V26 선행 합류·배포와 서버 누적 녹음 시간 기준을 명시했다.
- Persona 구현·검토 보고서에 현재 작업을 추가했다. `workflow finish implement`는 기존 작업 상태와 도구의 읽기 근거 경로 문제로 미통과이며 제품 테스트 성공과 구분한다. 과거 #427 pending 카드를 이번 작업 완료로 처리하지 않았다.
- 사용자 후속 요청으로 작업 단위 commit·push와 #496 Draft PR 게시를 진행한다. 배포는 범위에 포함하지 않는다. 다음 구현 대상은 #495이며 이번 작업에서 시작하지 않는다.

## ERD 원본 연결 보완 검토 이력 — 담당 범위 확인 후 미채택

상태: 미채택·미구현. 확인일: 2026-10-06. 사용자가 STT 및 음성 도메인은 본인의 담당 범위가 아니라고 명시했다. 아래의 #496 내 TranscriptionJob 신규 구현 제안은 실행하지 않는다. 현재 작업 기준은 마지막의 담당 경계 반영 계획이며, 아래 내용은 비교한 설계 이력이다. 기존 테스트 성공은 아래 관계가 구현되었다는 근거가 아니다.

### 1. 범위와 브랜치

기존 `be/feature/#496`에서 Document 상세 조회의 출처 저장 관계를 보완한다. Draft PR [#502](https://github.com/woowacourse-teams/2026-Knot/pull/502)의 기존 변경에 이어 작업할 제안이다. 이번 요청은 계획 설명이며 제품 코드 변경·commit·push·원격 문서 수정은 수행하지 않는다.

Notion MCP로 확인한 [Entity 관계도](https://app.notion.com/p/2200691a002383aea63301d4e6043f36)와 [컬럼 관계도](https://app.notion.com/p/4f10691a0023828f8e4101e2d14e6f42)는 RecordingSession → TranscriptionJob → Transcript를 정한다. 현재 코드는 Transcript가 RecordingSession을 직접 참조하며 전사 Job 출처가 없다. 녹음당 Job 최대 하나와 Job당 원문 최대 하나도 구현되지 않았다. 최신 API·사용자 결정의 STT 성공 후 자동 문서 생성과 생성된 Document의 멤버별 확인은 유지한다.

GitHub의 현재 develop tree와 열린 업로드 PR #483·#485에는 TranscriptionJob/Transcript 모델이 없다. #485 본문은 STT 접수를 명시적으로 범위에서 제외한다. 이 관측은 별도 로컬·미게시 STT 작업이 없다는 증거는 아니다. 현재 공개 코드에서 재사용할 전사 Job 모델은 발견되지 않았다. 실제 STT 호출·스케줄링·재시도 실행은 후속 작업에서 담당한다.

### 2. 구현할 흐름

```text
RecordingSession
  → TranscriptionJob: 녹음당 최대 하나
  → Transcript: 전사 Job당 최대 하나
  → DocumentGenerationJob
  → Document DRAFT
  → DocumentConfirmation: 생성된 문서의 확인 기록
```

이번 보완은 이 출처 관계를 저장할 기반과 상세 조회 회귀 검증이다. HTTP 상세 경로·DocumentApi/Controller의 findDocument·DocumentDetailService/Query의 find와 응답 필드는 유지한다. 생성된 문서를 원문과 비교하며 확인하는 기능은 DocumentConfirmation에 연결하며, Transcript 사전 검토 필드를 추가하지 않는다.

### 3. 나올 코드와 메서드 초안

기존 Transcript와 Document의 직접 녹음 ID는 조회 및 같은 녹음·주제 중복 방지에 쓰인다. 이를 모두 제거하는 안은 상세 JOIN과 중복 제약을 다시 설계해야 한다. 이번 제안은 전사 Job을 필수 출처로 추가하되 기존 직접 ID를 DB 일치 제약으로 묶어 유지하는 방식이다. 이는 ERD의 관계를 보완하는 물리 설계 제안이며 ERD 컬럼 표와 완전히 동일한 구조라고 표현하지 않는다.

| 위치·클래스 | 책임 | 메서드 후보 |
| --- | --- | --- |
| recording/domain/TranscriptionJob, TranscriptionJobStatus (신규 제안) | 녹음에 귀속되는 전사 작업 저장 모델과 네 상태 | queue(recordingSessionId, createdAt): 양수 ID·시각을 검사해 초기 작업 구성 |
| recording/domain/Transcript (기존 변경) | 전사 Job의 결과 출처 명시 | create(transcriptionJobId, recordingSessionId, content, createdAt): 출처 ID·본문·시각 검사 |
| document/infrastructure/DocumentDetailQueryAdapter (기존) | 기존 상세 조회와 집계 | find(...) 유지 가능 여부를 실제 DB 회귀 테스트로 확인 |
| 테스트 DocumentFixtures와 JPA 저장 테스트 | 전사 Job까지 포함한 저장 경로 구성 | 녹음 → 전사 Job → 원문 순서로 데이터 생성 |

전사 실행 서비스·공급자 adapter·Job 생성 HTTP API·사용하지 않는 Repository는 추가하지 않는다. Job 성공 데이터는 저장/조회 테스트의 fixture이며 실제 STT 실행 성공을 증명하지 않는다.

### 4. 저장 기반과 주의할 점

| 저장 대상 | 제안 제약 |
| --- | --- |
| transcription_jobs.recording_session_id | NOT NULL FK → recording_sessions, UNIQUE: 녹음당 전사 Job 최대 하나 |
| transcription_jobs 상태·실행 시각 | ERD의 status, attempt_count, created_at, updated_at, finished_at 저장 규칙을 맞춤. 상태 네 값·시도 수 비음수·완료 상태/시각 CHECK |
| transcripts.transcription_job_id | NOT NULL FK → transcription_jobs, UNIQUE: Job당 원문 최대 하나 |
| transcripts.recording_session_id | 기존 중복 출처 컬럼 유지 제안. (transcription_job_id, recording_session_id) 복합 FK로 Job의 녹음과 원문의 녹음이 같음을 보장 |
| documents 출처 | 기존 복합 FK를 통해 문서·생성 Job·Transcript·RecordingSession·Workspace 일치를 유지 |

transcription_jobs에는 복합 FK의 참조 대상으로 (id, recording_session_id) UNIQUE가 필요하다. 기존 직접 ID는 전사 Job 연결을 대체하는 출처가 아니다. Transcript.recording_session_id 및 Document.recording_session_id/source_transcript_id를 유지한다면 컬럼 관계도·물리 ERD에도 추가 컬럼의 이유와 제약을 기록하고 팀 리뷰에서 확인해야 한다. 이 계획에서 Notion 원격 수정이나 팀 승인으로 처리하지 않는다.

Migration이 필요하다. develop은 V24까지, #483·#485에는 V26이 있고 현재 브랜치에는 V27이 있다. 공유·운영 DB 적용 이력은 관측하지 않았다. 적용된 migration은 바꾸지 않고 후속 migration으로 보정한다. 실행 전 최신 번호 충돌과 적용 순서를 다시 확인하며 새 버전을 현재 시점에 확정하지 않는다.

기존 Transcript 데이터가 있다면 실제 전사 Job 출처와 녹음당 원문 중복 여부를 확인해야 한다. 연결 정보가 없는 데이터에 성공한 전사 Job 이력을 임의로 만들어 backfill하거나 중복 원문을 삭제하지 않는다. 알려진 원본 매핑이 없으면 데이터 전환은 별도 확인 대상이다. 빈 DB 생성 테스트와 데이터가 있는 DB 전환 검증을 구분한다. V26 선행 배포 조건도 유지한다.

### 5. TDD와 검증 순서

1. 녹음당 전사 Job 중복 거절·전사 Job당 원문 중복 거절·없는 Job 참조 거절 테스트 → 전사 Job 테이블과 출처 FK/UNIQUE 구현.
2. Job의 녹음과 Transcript의 녹음 불일치 거절 테스트 → 복합 FK 구현.
3. 전사 Job 초기 생성 및 Transcript 출처 입력 검증 테스트 → 최소 Entity·factory 구현.
4. 기존 상세 fixture를 올바른 연결 경로로 변경 → JPA mapping과 상세 조회 회귀 검증.
5. 권한·3상태·집계·원본 ID·동일 주제 중복·성공 자료 삭제 보호 회귀 검증 → API 문서·이 계획과 Persona 보고서 갱신.

| 방식 | 검증 내용 | 기대 결과 |
| --- | --- | --- |
| JUnit 단위 | queue/create 정상·0 이하 출처 ID·필수 값 누락 | 정상 모델 생성, 도메인 오류 |
| PostgreSQL/Flyway | 정상 출처 체인, 중복 Job/Transcript, 없는 FK·다른 녹음 연결 | 정상 저장, 잘못된 저장 DB 거절 |
| PostgreSQL/JPA | 신규 Job/Transcript 저장·재조회, schema validate | 정확한 필드와 연결 |
| Query 통합 테스트 | 기존 DRAFT/ARCHIVED 상세, recordingSessionId/sourceTranscriptId, 확인 집계 | 기존 계약 유지 |
| 실제 Security/DB 인수 테스트 | 200·401·403·404, 읽기 전후 상태·확인 기록 | 기존 HTTP 계약과 조회 불변 유지 |

집중 검증은 해당 domain 테스트, DocumentSchemaIntegrationTest와 DocumentDetailQueryIntegrationTest, DocumentDetailAcceptanceTest부터 실행한다. 공유 schema 변경이므로 최종 `./gradlew check --console=plain` 및 Persona 보고서·`npx ph workflow finish implement`를 수행한다. 실제 구현 전 `npx ph workflow implement`를 실행하며 기존 Persona 미통과 상태를 임의로 완료 처리하지 않는다. 아직 이 보완안의 테스트는 실행하지 않았다.

### 6. 완료 기준과 다음 작업

전사 Job 없이 원문을 저장할 수 없고, 녹음당 Job·Job당 원문 최대 하나와 출처 일치가 PostgreSQL에서 보장되며 기존 상세 API가 유지되면 이번 연결 보완이 완료된다. 보완된 논리 관계와 유지한 추가 물리 컬럼을 문서에서 구분한다. 원문 구간 생산·실제 STT·자동 문서 생성은 이번 저장/조회 검증으로 완료했다고 판단하지 않는다.

실제 STT 연결, 원문 구간과 #499, 자동 문서 생성 #501은 후속 작업이다. 계획만 저장했으며 제품 코드는 변경하지 않았다.

## 현재 계획 — 음성 도메인 담당 경계 반영

상태: 계획·미구현. 확인일: 2026-10-06. 사용자는 문서 도메인을 담당하며 STT·음성 도메인은 담당하지 않는다. 이 절이 앞의 전사 Job 구현 제안을 대체한다.

### 1. 범위와 브랜치

#496 및 Draft PR #502는 Document·DocumentConfirmation·문서 상세 조회의 범위로 유지한다. DocumentGenerationJob은 문서 생성 도메인의 저장 기반이며 실제 생성 실행은 #501이다. TranscriptionJob·Transcript 및 전사 결과 구간의 모델·migration·생산은 음성 도메인 담당자가 소유한다. 음성 도메인 구현을 #496의 완료 조건으로 추가하지 않는다.

현재 #502에 포함된 Transcript 모델과 테이블은 이미 만들어진 임시 기반이다. 사용자 범위 확인 후 이를 확정 음성 도메인 모델로 취급하지 않는다. 담당자 소유 schema와 연결하기 전 임의로 삭제하거나 덮어쓰지 않는다. 문서 조회 구현 완료와 공유 원본 schema 정합/병합 준비를 구분한다.

### 2. 구현할 흐름

```text
음성 도메인: RecordingSession → TranscriptionJob → Transcript
                                      ↓ 저장된 원본 제공
문서 도메인: DocumentGenerationJob → Document → DocumentConfirmation
```

문서 API는 저장된 원본을 읽고 연결하며 STT를 실행하거나 전사 Job을 생성하지 않는다. DocumentApi/Controller와 상세 응답 계약은 유지한다.

### 3. 코드와 책임

| 담당 경계 | 책임 |
| --- | --- |
| 음성 담당 | TranscriptionJob·Transcript·원문 구간의 모델/테이블/제약과 STT 결과 저장 |
| 문서 담당 | Document·문서 생성 Job·확인 기록 및 문서 API |
| 문서 담당의 연결 수정 | 음성 담당자가 제공하는 저장 구조에 맞춰 Document 원본 FK·조회 SQL·테스트 fixture 조정 |

새로운 원본 조회 API를 임의로 요구하지 않는다. 같은 백엔드의 저장 구조를 읽는 연결 계약으로 우선 맞춘다. 현재 공개 PR에서 음성 모델이 발견되지 않았다는 사실만으로 그 구현을 문서 담당자가 인수하지 않는다. 담당자에게 메시지는 전송하지 않았다.

### 4. 선행 연결 계약과 저장 기반

연결에 필요한 정보는 저장된 Transcript ID, 해당 원문의 원본 RecordingSession/Workspace를 찾는 경로, 녹음 길이 출처다. #499에서는 전체 텍스트·실제 구간과 startMillis 필수/endMillis·speakerNumber nullable 계약도 공유한다. 사용자 사전 검토 없이 STT 성공 후 자동 문서 생성이라는 결정은 유지한다.

음성 담당자의 테이블·컬럼·migration 소유권과 반영 PR이 확인되면 현재 Transcript 임시 기반을 담당자 모델로 통합하고 중복 생성 migration을 제거/보정하는 방식을 결정한다. 적용 이력 관측 전 기존 migration을 삭제·변경하지 않으며 이미 적용된 schema는 새 migration으로 전환한다. 실제 전사 Job 이력을 임의로 생성해 기존 원문을 연결하지 않는다.

Document의 추가 직접 원본 컬럼과 중복/출처 제약은 음성 schema가 확정되면 문서 쪽 물리 설계로 조정한다. 이 확인이 남아 있는 동안 #502를 ERD 완전 정합 또는 최종 병합 준비 완료로 표현하지 않는다.

### 5. 검증 순서

1. 음성 담당자 소유 모델·schema·반영 PR과 원본 연결 계약 확인.
2. 제공된 schema를 기준으로 문서 원본 연결 및 조회 fixture 조정. 음성 작업 생명주기의 단위 테스트는 이 범위에서 추가하지 않음.
3. PostgreSQL에서 잘못된 원본 참조·Workspace 불일치·같은 녹음/주제 중복 거절을 검증.
4. 기존 상세 ID·녹음 길이·3상태·확인 집계와 200/401/403/404 회귀 검증.
5. 공유 schema 전환 시 데이터 보존과 migration 적용 순서를 검증하고 최종 Gradle/Persona 결과를 기록.

### 6. 완료 기준과 다음 작업

문서 코드는 합의된 음성 schema에 연결되고 기존 상세 API와 문서 저장 불변식이 유지되어야 한다. 음성 schema 제공/통합은 병합 의존성으로 표시한다. 실제 STT 구현을 사용자의 문서 작업에 포함하지 않는다. 현재는 계획 문서만 수정했으며 제품 코드·원격 PR/Issue/Notion은 변경하지 않았다.
