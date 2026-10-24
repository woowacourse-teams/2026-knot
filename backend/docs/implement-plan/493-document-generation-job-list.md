# #493 워크스페이스 문서 생성 작업 목록 조회 구현 계획

상태: PR #511 제품 리뷰 수정·검증 완료. 제목 응답·저장 기반을 제거하고 내 녹음의 Job으로 제한했다. 아래 최초 구현 이력과 1~6절은 변경 전 기록이며, 현재 계약은 아래 리뷰 반영 절과 API 문서를 우선한다. Persona 종료 인증은 별도 기록한다.
확인일: 2026-10-07.
대상: [Issue #493](https://github.com/woowacourse-teams/2026-Knot/issues/493), 담당자 흑곰(jyt6640).
기준: 사용자가 제공한 최신 생성 작업 목록 API, 확정한 제품 결정, Issue 본문과 실제 저장소 코드.
Notion 실시간 원문과 운영 DB는 이번 계획에서 직접 관측하지 않았다. 개인 명세·사용자 결정을 팀 승인이나 배포 완료로 표현하지 않는다.

## 리뷰 반영 계약 (2026-10-07)

- 사용자 요청으로 두 리뷰 모두 수용한다. recordingTitle과 RecordingSession.title, 미병합 V29 추가를 제거한다.
- Workspace 멤버십 검사 후 RecordingSession.memberId로 호출자의 녹음을 제한하며, 필터를 적용한 결과에 pagination을 수행한다.
- Query.findPage(workspaceId, memberId, limit, cursor, now)로 로그인 Member를 명시적으로 전달한다. 커서의 기존 Member 범위 검사도 유지한다.
- 새 멤버라도 다른 사람 Job은 보이지 않으며 본인 녹음이 없으면 빈 목록이다. Document 읽기 권한은 현재 Workspace 멤버 기준 그대로다.
- 홈 카드 한 칸의 선택과 종합 상태는 녹음 조회 계약에서 연결한다. 이 Job 목록을 홈의 여러 카드·개수로 사용하지 않는다.
- DB 소유자 필터·필터 후 페이지·새 멤버 빈 목록·JSON/OpenAPI 제목 미노출·제목 컬럼 미추가를 테스트로 확인한다. 수정 전 DB 2개와 HTTP 4개 실패를 관측했다.
- 변경 전 개인 API·Issue #493과 달라진 계약이다. Notion·GitHub 원문은 이번 요청에서 수정하거나 댓글을 게시하지 않는다.
- #493에서 수정하며 #494는 부모 업데이트 후 동일 변경을 정상 merge로 전달해야 한다. 이번 요청에 commit·push는 포함하지 않는다.
- 실행 결과: 집중 73개 PASS, 전체 spotlessCheck/check/bootJar PASS(단위 486·통합 202·인수 302 = 990개), 실패·오류·skip 0. API 문서 JSON·필수 섹션과 diff --check도 통과했다. 실제 FE·운영 DB·STT/AI는 검증하지 않았다.
- Persona 종료 결과: review report-filled PASS, finish implement는 기존 coverage·toolchain·stale loop·pending ticket으로 exit 1이다. 이번 변경 Java 12개와 입력 evidence는 기록했고 과거 상태를 임의 초기화하지 않았다. 제품 Gradle 성공과 구분한다.

최초 실행 결과(2026-10-07, 아래 제목·전체 Workspace 목록 규칙은 이번 리뷰 수정으로 대체됨):

- `origin/be/feature/#496`의 `c9682f46`에서 `be/feature/#493`을 실제로 분기했다. 다른 문서 API 브랜치는 병합하지 않았다.
- 생성 작업 Api/Controller·Parameters·Cursor·Service·JPQL QueryAdapter·응답 DTO를 구현했다. 기본 20/최대 100, 생성 시각·ID 내림차순, size+1, 현재 멤버 인가를 적용했다.
- V28에 마지막 실패·168시간 만료와 DB CHECK, V29에 nullable 녹음 제목을 추가했다. 기존 V27은 수정하지 않았다.
- 기존 FAILED의 실제 시각을 추정하지 않도록 V28은 해당 기존 행이 있으면 중단한다. 별도 PostgreSQL schema에서 중단 시 행 보존과 기존 QUEUED 정상 이행을 검증했다. 실제 공유 DB 적용 이력은 미관측이다.
- Parameters/Cursor/실패 기록, Query·Service, Controller 순서로 미구현 컴파일 실패를 먼저 관측했다. 실제 PostgreSQL에서는 null 커서 시각의 파라미터 타입 오류를 재현하고 hasCursor 분기로 보정해 통과시켰다.
- `./gradlew check bootJar` PASS: 단위 486·통합 200·인수 301, 총 987개. 실패·오류·skip 0. #496의 919개 대비 신규 68개다. 생성 작업 suite 70개에는 기존 Job 테스트 2개가 포함된다.
- 검증한 핵심은 실패 7일 경계·재실패·진행 상태 재접수 이력, Workspace 격리·커서 연결, 성공 문서 보호·GET 저장 불변, 실제 쿠키 인증·OpenAPI다.
- 전체 검사 첫 실행은 bearshell 기본 30초 제한으로 종료됐다. 문서화된 PH_BEARSHELL_TIMEOUT_MS=300000으로 재실행해 성공했다. 자동 LSP 진단은 daemon timeout으로 미확인이다.
- FE 명세는 [생성 작업 목록 API](../api/document-generation-job-list.md)에 저장했다. 기존 사용자 수정 `docs/harness/notion-alignment.md`는 보존했다.
- 실제 STT/AI 실행·녹음 제목 입력·운영 배포는 이번 검증 범위가 아니다. #494·#501과 음성 담당 연계를 유지한다.
- 최초 구현 요청은 작업 트리 변경까지 완료했다. 후속 요청으로 동작·스키마·조회 adapter·HTTP 인수·문서를 나누어 커밋하고, `be/feature/#493`을 푸시한 뒤 `be/feature/#496` 기준 Draft PR로 게시한다. 원격 게시 결과는 해당 PR을 기준으로 확인한다.
- Persona report-filled review는 성공했지만 implementation은 기존 root 보고서의 template-like/incomplete 판정으로 거절됐다. finish implement는 exit 1: report-coverage-missing, java-role-read-coverage-missing, convention-toolchain-missing, workflow-loop-state-stale, pending-ticket. 변경 Java 25개·profile·plan·roles·현재 카드 evidence는 기록했고, 과거 사용자 보고서·pending 카드·state를 초기화하거나 허위 완료하지 않았다. 제품 Gradle PASS와 하네스 인증을 구분한다.

## 1. 범위와 브랜치

`GET /api/v1/workspaces/{workspaceId}/document-generation-jobs` 하나를 구현한다. 홈 복귀·새로고침 후 현재 Workspace의 진행 중·실패 작업을 복원하는 조회다. 기본 size=20, 최대 100은 #493 계약을 따른다.

현재 체크아웃은 `be/feature/#498`의 `c0f39ed1`이다. #493은 문서 저장 기반을 가진 `origin/be/feature/#496`의 `c9682f46`에서 독립적으로 분기한다. #495 목록, #497 확인 현황, #498 확인 처리의 변경을 선행으로 가져올 필요가 없다.

```text
develop
  └─ #496 문서 상세·공통 저장 기반 (PR #502)
       ├─ #495 문서 목록 (PR #504)
       ├─ #497 확인 현황 (PR #506)
       │    └─ #498 확인 처리 (PR #508)
       └─ #493 생성 작업 목록 ← 새 작업 제안
```

2026-10-07 확인 당시 위 PR은 모두 OPEN이다. 원격 develop은 `4d129ddc`이며 현재 #496에 포함되어 있다. #493 로컬·원격 브랜치는 아직 없다. 아래 명령은 실행할 때의 순서이며 이번 계획에서는 실행하지 않았다.

```bash
git fetch origin develop 'be/feature/#496'
git switch --no-track -c 'be/feature/#493' 'origin/be/feature/#496'
```

전환 전 작업 트리를 다시 확인한다. 기존 수정 중인 `docs/harness/notion-alignment.md`는 보존하고 이번 계획은 #493에 가져갈 문서로 구분한다. 전환 충돌이 생기면 원인을 확인하며 사용자 파일을 reset하거나 임의로 stash하지 않는다. 첫 push 시 #493 자체 upstream을 연결한다.

향후 PR base는 `be/feature/#496`으로 두어 #493 변경만 리뷰한다. #502 병합 후 실제 merge 방식과 공통 조상을 확인해 develop 기준으로 정리한다. 특히 squash 병합이면 기존 부모 커밋과 develop의 이력이 달라지므로 무조건 merge하거나 강제 push하지 않는다.

이번 범위는 조회·페이지네이션·실패 만료 판정 및 필요한 최소 저장 기반이다. STT 실행, 자동 Job 등록, AI 생성, 실제 재시도 접수, 자료 삭제는 각각 담당 작업과 연결한다. #493 메모의 연결 작업 “미등록” 표기는 오래된 내용이며 [#501](https://github.com/woowacourse-teams/2026-Knot/issues/501)이 등록되어 있다.

## 2. 구현할 흐름

```text
GET /api/v1/workspaces/{workspaceId}/document-generation-jobs?size=20&cursor=...
  → 기존 Security: 로그인 확인
  → DocumentGenerationJobController.findDocumentGenerationJobs
  → DocumentGenerationJobListParameters: size 기본값·범위와 빈 cursor 검증
  → DocumentGenerationJobListService.find: 현재 Workspace 멤버 확인
  → cursor가 있으면 형식과 Workspace·조회 Member 범위 검증
  → 주입된 Clock에서 현재 시각을 한 번 읽기
  → JPA·JPQL Query: Workspace·작업 상태·실패 기한·커서 조건으로 size+1개 조회
  → 최대 size개 반환, 다음 항목이 있으면 마지막 반환 항목으로 nextCursor 생성
  → DocumentGenerationJobListResponse: HTTP 200
```

| 상황 | 결과 |
| --- | --- |
| QUEUED·RUNNING | 생성 시각과 무관하게 진행 목록에 포함 |
| FAILED, 현재 시각 < 마지막 실패 시각+7일 | 실패 목록에 포함 |
| FAILED, 현재 시각 ≥ 마지막 실패 시각+7일 | DB 행이 남아 있어도 목록에서 제외 |
| SUCCEEDED | 제외 |
| 조회 대상 없음 | items=[], nextCursor=null |
| 미로그인 | 401 UNAUTHENTICATED |
| 비멤버·탈퇴한 요청자 | 403 WORKSPACE_ACCESS_DENIED |
| 잘못된 size·cursor 또는 커서 요청 범위 불일치 | 400 INVALID_PARAMETER |

정렬은 `createdAt DESC, id DESC`를 제안한다. `updatedAt`은 실행·재실패 때 바뀌므로 정렬 기준으로 사용하면 페이지 사이에서 항목이 이동하기 쉽다. 같은 녹음의 여러 Job도 개별 항목으로 반환하며 서버에서 하나로 축약하지 않는다.

예를 들어 마지막 실패가 `2026-10-01T09:00:00Z`이면 `2026-10-08T09:00:00Z` 직전까지 표시하고 정확히 그 시각부터 제외한다. 사용자 재시도 3회를 소진했어도 7일 내 FAILED 목록에는 남는다. 횟수 제한은 재시도 API의 접수 조건이며 이 목록의 제외 조건이 아니다.

GET은 Job 상태·시각·원문·문서를 변경하거나 삭제하지 않는다. 성공·만료로 목록에서 사라진 것을 녹음 전체의 완료로 해석하지 않도록 명세에 기록한다. 전체 결과는 녹음 상세 API 담당 작업에서 제공한다.

## 3. 나올 코드와 메서드 초안

현재 재사용할 코드는 `DocumentGenerationJob` 최소 Entity와 상태 Enum, `Transcript`, `RecordingSession`, `WorkspaceMemberRepository`, 기존 Clock bean이다. #496의 `DocumentReadJpaRepository` → `DocumentDetailQueryAdapter` → Service 패턴을 따른다. 기존 상세 Query는 JPQL Row projection으로 필요한 필드만 읽고, 서비스는 현재 멤버십을 확인한다.

아래 코드는 신규·변경 제안이다. 패키지는 `backend/src/main/java/com/knot/backend/` 기준이다.

| 위치·클래스 | 책임 | 메서드 후보·입출력 |
| --- | --- | --- |
| document/presentation/DocumentGenerationJobApi | Swagger 요청·응답·오류·쿠키 인증 계약 | findDocumentGenerationJobs(workspaceId, cursor, size, authenticatedMember) |
| document/presentation/DocumentGenerationJobController | GET 경로와 서비스 호출 | findDocumentGenerationJobs(...) → Response |
| document/presentation/dto/response/DocumentGenerationJobListResponse, DocumentGenerationJobItemResponse | 응답 필드와 items·nextCursor 직렬화 | from(result) |
| document/application/dto/query/DocumentGenerationJobListParameters | 기본 20·범위 1..100·빈 커서 검증 | of(cursor, size), resolveSize, validateSize, validateCursor |
| document/domain/DocumentGenerationJobCursor | 정렬 경계와 조회 범위를 표현 | of(...), encode(), parse(encoded, workspaceId, memberId) |
| document/application/DocumentGenerationJobListService | 현재 멤버 인가·Clock·페이지 구성 | find(workspaceId, memberId, parameters) → 목록 Result |
| document/application/DocumentGenerationJobListQuery | 목록 조회에 필요한 application 계약 | findPage(workspaceId, limit, cursor, now) → 항목 Result 목록 |
| document/infrastructure/DocumentGenerationJobReadJpaRepository | JPQL 필터·정렬·size+1 제한 | findPage(..., Pageable) → Row 목록 |
| document/infrastructure/DocumentGenerationJobListQueryAdapter, DocumentGenerationJobRow | JPA Row를 application Result로 변환 | findPage(...) |
| document/application/dto/result/DocumentGenerationJobItemResult, DocumentGenerationJobListResult | 항목 데이터와 페이지 결과 | 페이지 구성은 Service의 의미 있는 private 메서드로 분리 |
| document/domain/DocumentGenerationJob | 마지막 실패·만료 시각의 최소 저장 규칙 | recordFailure(failedAt): FAILED·updatedAt·실패 시각·만료 시각 기록 |
| recording/domain/RecordingSession | ERD의 nullable 녹음 제목을 조회 원천에 매핑 | title 필드 매핑 제안. 녹음 제어·STT 기능 변경은 별도 담당 범위 |

문서 리소스는 기존 `DocumentApi`·`DocumentController`를 유지한다. 작업 리소스는 `DocumentGenerationJobApi`·`DocumentGenerationJobController`로 묶고 후속 #494 재시도 메서드를 같은 두 클래스에 추가할 수 있다. `DetailController`나 상태별 Controller는 만들지 않는다.

커서는 버전·workspaceId·memberId·createdAt·jobId를 담는 URL-safe 형식을 제안한다. 크기 제한·필드 개수·버전·양수 ID·시각 범위·요청 범위를 검사한다. Base64는 서명이 아니며 인가는 항상 서버 멤버십과 Workspace WHERE 조건으로 수행한다. 삭제·완료된 경계 Job을 다시 조회할 필요 없이 커서에 담긴 값으로 이어 읽는다.

각 타입은 별도 파일에 둔다. 클래스·인터페이스 선언의 여는 중괄호 다음에는 빈 줄을 하나 둔다. Workspace 코드처럼 연속된 일반 필드는 붙여 쓰고, 상수와 인스턴스 필드 사이·필드와 생성자/메서드 사이·메서드 사이에는 빈 줄을 하나 둔다. JPA Entity의 애노테이션과 필드는 한 블록으로 묶는다. 삼항 연산자를 사용하지 않으며 기본값 결정·커서 검증·다음 페이지 구성은 의미 있는 private 메서드로 표현한다. 현재 JPA 패턴을 재사용하고 이 API의 production Query에 JdbcClient를 추가하지 않는다.

## 4. 저장 기반과 주의할 점

새 Job 테이블을 다시 만들지 않는다. #496의 `V27__create_document_read_models.sql`에 이미 Job·Transcript·Document·확인 대상 테이블과 FK가 있다. 현재 Job은 transcriptId·status·createdAt·updatedAt만 저장하며 최초 QUEUED 생성 메서드만 있다.

| 모델·테이블 | 관측한 현재 상태 | 보완 제안 |
| --- | --- | --- |
| document_generation_jobs | 마지막 실패 시각·만료 필드 없음 | last_failed_at, expires_at TIMESTAMPTZ 추가. 최초 작업에는 둘 다 null, FAILED에는 둘 다 필수 |
| RecordingSession / recording_sessions | 구현·migration에 title 없음. 사용자가 제공한 ERD에는 nullable title TEXT가 있음 | nullable title 컬럼·Entity 매핑만 최소 보완. 기존 녹음의 제목은 null, 저장된 제목은 그대로 반환 |
| Transcript → RecordingSession | 현재 transcript.recording_session_id로 연결 | Job → Transcript → RecordingSession 조인으로 workspaceId·recordingSessionId·제목 조회 |
| transcripts / documents | RESTRICT FK와 기존 문서 중복 방지 제약 있음 | 조회를 위해 참조 관계·UNIQUE를 느슨하게 변경하지 않음 |

실패 기한은 UTC에서 168시간으로 계산한다. `recordFailure`가 유효한 실패 시각을 받아 lastFailedAt·expiresAt·updatedAt을 함께 갱신하며 성공 작업을 실패로 덮어쓰거나 과거 시각을 기록하지 않도록 검증한다. 재실패가 발생하면 기한을 갱신한다. 실제 실행·재접수·자동 재시도 상태 전이와 횟수는 #501·#494에서 연결한다.

DB CHECK는 실패 시 두 필드 필수, 시각 쌍의 null 정합성, 만료 시각과 마지막 실패 시각의 168시간 관계를 보호한다. 재시도로 QUEUED·RUNNING이 된 Job은 이전 실패 이력이 남더라도 실패 만료 조건으로 제외하지 않는다. 사용자 재시도·최초 실행·내부 재시도 카운터는 이 GET 응답에 필요하지 않으므로 #494·#501에서 확장한다.

새 migration이 필요하다. 실패 보존 필드와 녹음 제목은 독립적인 스키마 변경으로 나눈다. 번호는 구현 시작 직전 최신 develop·관련 열린 PR·적용 이력을 확인해 결정하며 기존 V27은 수정하지 않는다. 이번에 읽은 저장소의 최신 파일은 V27이고 실제 공유 DB 적용 이력은 관측하지 않았다.

기존 FAILED 행이 있는 환경에서는 실제 마지막 실패 시각의 원천을 확인한 뒤 데이터 이행을 설계한다. updatedAt은 일반 상태 갱신 시각이므로 실패 시각으로 무조건 대체하지 않는다. 현재 코드에 실제 실행기가 없다는 사실만으로 공유 DB에 FAILED 데이터가 없다고 단정하지 않는다.

녹음 title은 ERD에 근거한 공유 필드 제안이다. 착수 시 녹음 담당자의 선행 migration·Entity 변경 여부를 확인해 중복 추가를 피한다. 제목 입력·변경 API와 음성 도메인 구현은 이 작업에 포함하지 않는다. 무조건 null을 반환하거나 Document 제목을 녹음 제목으로 대체하지 않는다.

Transcript → TranscriptionJob 연결은 기존 #496에서도 남아 있는 음성 담당 연계 사항이다. #493은 현재 연결을 사용하고 음성 담당 변경이 먼저 반영되면 조인 경로·fixture를 함께 맞춘다. 문서 작업에서 STT 모델 전체를 구현하지 않는다.

조회 트랜잭션은 Service의 `@Transactional(readOnly=true)`에 둔다. 현재 시각은 요청당 한 번 읽으며 여러 페이지 요청 사이의 상태 변경까지 고정하지 않는다. 불변 정렬 경계는 기존 항목의 순서 이동을 막지만, 요청 사이에 새 실패·성공·만료가 발생하는 실시간 목록 전체를 하나의 스냅샷으로 보장하지는 않는다.

기존 Transcript·Job FK 인덱스를 먼저 재사용한다. 정렬·필터 인덱스가 추가로 필요한지는 최종 JPQL과 PostgreSQL 실행 계획으로 판단한다. 현재 시각에 의존하는 만료 조건을 부분 인덱스 predicate에 넣지 않는다.

## 5. TDD와 검증 순서

각 단계는 실패하는 테스트를 먼저 실행하고 해당 동작을 통과시키는 최소 구현으로 진행한다. 커밋 권한을 받으면 public 동작·스키마·adapter·문서를 나누며 test → feat를 기본으로 한다. 컴파일되지 않는 중간 상태는 작업 중 확인하되 빌드가 불가능한 커밋으로 남기지 않는다. refactor는 테스트가 보호하는 실제 개선이 있을 때만 추가한다.

1. Parameters.of: 기본 20, 1·100 허용, 0·101·빈 cursor 거절 테스트 → 입력 규칙 구현.
2. Cursor.of/encode/parse: 왕복, 같은 생성 시각의 jobId 경계, 변형·과도한 길이·다른 Workspace/Member 거절 테스트 → 커서 구현.
3. Job.recordFailure: 실패 시각·7일 기한·재실패 갱신과 유효하지 않은 시각·성공 작업 변경 거절 테스트 → 최소 실패 기록 규칙 구현.
4. PostgreSQL schema: 실패 필드·nullable 제목·CHECK·기존 FK·데이터 이행 테스트 → 새 migration과 Entity 매핑 구현. 제목 변경과 실패 저장 변경은 별도 단위로 검증.
5. Query.findPage: 상태·기한·Workspace·정렬·여러 Job·제목·size+1·커서 연결 테스트 → JPA repository와 adapter 구현.
6. Service.find: 현재 멤버 접근, 비멤버 거절, 고정 Clock 전달, 빈/정확한 size/추가 항목/마지막 페이지 테스트 → 인가와 페이지 구성 구현.
7. HTTP: 200 응답, 형식·범위 오류 400, 실제 인증 401·멤버 인가 403, GET 저장 불변 테스트 → Api·Controller·Response 구현.
8. OpenAPI·FE용 명세에 정렬·7일 경계·nullable 제목·녹음 전체 결과와의 구분 기록 → 관련 회귀와 전체 빌드 검증·하네스 보고서 작성.

| 테스트 위치·방식 | 검증할 동작 | 기대 결과 |
| --- | --- | --- |
| application/dto/query, domain 단위 | 기본값·커서 형식/범위·실패 기록 | 정상 값 유지, 오류는 해당 DocumentException |
| infrastructure PostgreSQL/Testcontainers | 실제 JPQL·필드 mapping·FK·CHECK·페이지 경계 | 타 Workspace 제외, 같은 녹음의 모든 대상 Job 유지, 고정 데이터의 중복 없는 순회 |
| application Mockito | 회원 검사·Clock·size+1 페이지 구성 | 비멤버 Query 미호출, 최대 size개, 마지막 반환 경계의 커서 |
| infrastructure 고정 Clock 기반 입력 | 실패 7일 직전·정확한 경계·이후, 재실패 | 직전만 포함, 새 실패 시각 기준으로 기한 갱신 |
| presentation MVC | query 바인딩·Response JSON·오류 매핑 | recordingTitle null/문자열, createdAt/updatedAt ISO 8601, items 빈 배열 |
| presentation 실제 Security+PostgreSQL 인수 | 쿠키 인증·현재 멤버·탈퇴자·Workspace 격리·조회 부작용 | 200/400/401/403, 만료 행은 숨기되 DB에 유지 |

Mockito는 실제 DB 조회나 migration을 증명하지 않는다. 인수 테스트도 fixture 기반 목록 조회를 증명하며 STT·AI 실행이나 FE 폴링 전체 흐름을 증명하지 않는다. 테스트 데이터 준비용 기존 JdbcClient는 사용할 수 있다.

`build.gradle`에서 확인한 실행 task는 다음과 같다. 테스트 클래스 이름은 구현할 때 확정하고 아래 필터를 그 이름에 맞춘다. 아래 검증은 예정이며 이번 계획 작성에서 실행하지 않았다.

```bash
./gradlew test --tests '*DocumentGenerationJob*'
./gradlew integrationTest --tests '*DocumentGenerationJob*'
./gradlew acceptanceTest --tests '*DocumentGenerationJob*'
./gradlew check bootJar
```

실제 구현에 들어갈 때 `npx ph workflow implement`와 단일 AI rail을 먼저 실행한다. 구현·검토 보고서를 작성하고 `npx ph workflow finish implement` 결과를 기록한다. 계획 작성만으로 구현 workflow를 시작하지 않는다.

## 6. 완료 기준과 다음 작업

현재 Workspace 멤버가 계약에 맞는 작업 목록을 커서로 조회하고, 실패 7일 경계·재실패 갱신·다른 Workspace 격리·nullable 녹음 제목·읽기 전용 동작이 실제 PostgreSQL 및 HTTP 테스트에서 확인되면 #493 완료다. 기존 #496 기반의 상세 조회와 저장 제약 회귀도 통과해야 한다.

다음 연결 작업은 [#494](https://github.com/woowacourse-teams/2026-Knot/issues/494)의 실패 Job 재시도 접수와 #501의 자동 생성 실행·보존 정리다. #494는 같은 Job·최대 3회·7일 내 접수와 실행기의 재접수 계약을 연결하고, #501은 실제 Job 상태·마지막 실패 시각·만료 시각을 기록하고 만료 자료를 안전하게 정리한다. 녹음 상세의 종합 상태와 제목 입력은 녹음 담당 작업에서 연결한다.

이번 작업 결과는 계획 문서다. 브랜치 생성·체크아웃·product 코드 수정·테스트 실행·commit·push·PR 게시를 수행하지 않았다.
