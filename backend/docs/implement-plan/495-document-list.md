# #495 주제별 문서 폴더와 카드 목록 조회 구현 계획

2026-10-07 후속 변경: 조회는 `DocumentListJpaRepository`의 JPQL과 별도 Row projection으로 통일했다. 주제 집계는 전체 조건 기준이며 카드 페이지는 `Pageable`로 size+1개만 읽는다. 확인 인원은 페이지 문서 ID 목록에 대해 한 번에 집계한다. 기존 REPEATABLE_READ 안에서 세 SELECT가 같은 snapshot을 사용한다. 아래의 JdbcClient·두 SQL 설명은 최초 구현 계획의 이력이다. 후속 공통 계획은 [문서 조회 JPA 정합성 작업](document-jpa-queries.md)을 따른다.

상태: 사용자 승인 후 테스트 선행 구현·로컬 검증 완료. 전체 Gradle check 통과, Persona finish 미인증.
확인일: 2026-10-06.
브랜치: `be/feature/#495`.
분기 기준: `be/feature/#496`의 `91c0b99af9f8444f84d0a9179990bf7b6cc9f928`.

기준은 사용자가 이번 요청에 붙여준 최신 문서 목록 API와 [Issue #495](https://github.com/woowacourse-teams/2026-Knot/issues/495), 기존 #496 구현이다. 2026-10-06 사용자의 테스트 선행 구현 요청으로 개발을 시작했다. 아래에는 최초 계획과 최종 구현 차이를 함께 기록한다. 팀 승인·운영 배포와 로컬 구현/검증은 구분한다. 음성/STT 도메인은 사용자의 담당 범위 밖이다.

## 1. 범위와 브랜치

이번 작업은 `GET /api/v1/workspaces/{workspaceId}/documents` 하나다. 현재 Workspace의 주제별 폴더·문서 카드·필터·커서 페이지를 제공한다. 목록 조회는 Document/Confirmation/Job 상태를 변경하지 않는다. 제목·본문 편집, 폴더 Entity/편집, 상태 필터와 페이지 없는 전체 조회 API는 추가하지 않는다.

사용자 요청에 따라 기존 #496 위에서 `be/feature/#495`를 생성하고 체크아웃했다. 분기 직전 작업 트리는 깨끗했으며 사용자 변경을 stash하거나 commit하지 않았다. 현재 [PR #502](https://github.com/woowacourse-teams/2026-Knot/pull/502)는 OPEN이고 Draft 해제 상태다. 아직 develop에는 문서 모델이 병합되지 않았다. #495 PR을 열 때 base는 `be/feature/#496`으로 잡아 목록 변경만 표시한다. #496 병합 후 병합 방식과 최신 develop을 확인해 브랜치를 정렬하고 base를 develop으로 바꿔 회귀 검증한다. 현재 요청에서 push/PR 생성은 수행하지 않는다.

최신 develop SHA는 `1f0d6bee58a06e622923f6b810d07495ce5c1ec5`이며 현재 #496에 합류되어 있다. 업로드 PR #483·#485는 병합됐고 V26이 develop에 존재한다. 이전의 '열린 V26 PR 대기' 설명은 이번 계획의 현재 상태가 아니다. 실제 공유/운영 DB 적용 이력은 별도로 미관측이다.

#496의 Document·DocumentConfirmation·MyConfirmationState·확인 집계 DTO·활성 멤버 조회·JdbcClient 조회 관례를 재사용한다. Transcript/TranscriptionJob 임시 기반과 음성 schema 통합은 #496의 선행 연결 문제로 유지하며 #495에서 별도 전사 모델·migration을 만들지 않는다. 선행 모델 수정이 생기면 목록의 연결·fixture에 필요한 변경만 가져온다.

## 2. 구현할 흐름

```text
GET /api/v1/workspaces/{workspaceId}/documents
  → 기존 Security: 로그인 검사
  → DocumentController.findDocuments: 경로·query·인증 memberId 전달
  → 목록 파라미터의 기본값/범위와 cursor 형식 검증
  → DocumentListService.find: 현재 Workspace 멤버 검사
  → 전체 필터 결과의 주제 목록·문서 수 조회
  → 동일 필터 + cursor로 카드 size + 1개 조회
  → 내 확인 상태·집계·녹음 길이 변환
  → 최대 size개 반환, 추가 카드가 있을 때만 nextCursor 생성
  → DocumentListResponse: topics / items / nextCursor
```

| 조건 | 응답 계획 |
| --- | --- |
| 미로그인 | 기존 인증 경계의 401 UNAUTHENTICATED |
| 비멤버·탈퇴자·삭제 Workspace | 403 WORKSPACE_ACCESS_DENIED |
| size 비숫자·1 미만·100 초과, 잘못된 enum, recordingSessionId 비숫자·0 이하 | 400 INVALID_PARAMETER |
| cursor 문법·버전·값·조회 조건 불일치 | 400 INVALID_PARAMETER |
| 필터에 맞는 문서 없음 | 200, topics=[], items=[], nextCursor=null |
| 존재하지 않거나 다른 Workspace의 양수 recordingSessionId | 현재 Workspace 조건과 AND로 검색하여 빈 결과 반환하는 구현안. 녹음 존재 여부를 별도 오류로 노출하지 않음 |
| 마지막 카드 페이지 | nextCursor=null |

카드는 DRAFT/ARCHIVED 모두 포함한다. myConfirmation과 recordingSessionId는 함께 지정하면 AND로 적용한다. 내 확인 판정은 기존 MyConfirmationState.resolve와 같다. 생성 시 대상 행이 없으면 NOT_REQUIRED, 대상이고 confirmed_at이 없으면 PENDING, 확인 시각이 있으면 CONFIRMED다. 확인 집계는 기존 상세처럼 확인 완료/현재 활성 미확인/현재 비활성 미확인으로 나누며 재가입 이력은 EXISTS로 한 사람을 중복 집계하지 않는다.

예를 들어 조회 조건에 맞는 '운영 정책' 문서가 12개이고 size=5라면 topics의 documentCount는 12이고 items만 5개다. 다음 cursor로 이어 읽어도 topics는 페이지 밖 문서를 포함한 전체 필터 결과를 집계한다. 요청 사이 문서 생성·확인·멤버십 변화가 생기면 다음 응답의 집계와 필터 결과는 바뀔 수 있다. 여러 HTTP 요청 전체를 하나의 고정 snapshot으로 묶는 계약은 아니다.

### 붙여준 JSON 예시의 수정 위치

recordingSessionId는 confirmationSummary의 내부 필드가 아니라 items 원소의 필드다. 현재 예시에는 excludedCount 뒤 쉼표도 누락되어 있다. DTO/OpenAPI/FE용 문서에서는 아래 구조를 사용한다. (이부분 수정됨)

```json
{
  "items": [
    {
      "id": 301,
      "recordingSessionId": 42,
      "confirmationSummary": {
        "confirmedCount": 1,
        "pendingCount": 2,
        "excludedCount": 0
      }
    }
  ]
}
```

위 JSON은 필드 위치를 보여주는 발췌이며 전체 응답 예시가 아니다. 전체 응답은 명세의 카드 필드와 topics/nextCursor를 포함한다.

## 3. 나올 코드와 메서드 초안

현재 DocumentApi는 Swagger 계약, DocumentController는 상세 GET 매핑, DocumentDetailService는 활성 멤버 검사, DocumentDetailQueryAdapter는 단일 상세 SQL을 담당한다. 이 책임을 유지하고 목록 서비스와 조회만 추가한다. Controller를 별도로 늘리거나 상세 API를 카드마다 호출하지 않는다.

아래 신규 이름은 제안이다. 타입마다 별도 파일을 만들며 DTO는 record, domain의 커서 값은 class로 작성한다.

| 위치·클래스 | 현재/변경 | 책임·메서드 후보 |
| --- | --- | --- |
| document/presentation/DocumentApi | 기존에 목록 메서드 추가 | findDocuments(...): query 4개·카드·집계·오류·쿠키 인증의 Swagger 계약 |
| document/presentation/DocumentController | 기존에 목록 매핑 추가 | findDocuments(...): @RequestParam cursor/size/myConfirmation/recordingSessionId, 인증 memberId 전달, 응답 변환 |
| document/application/DocumentListService | 신규 제안 | find(workspaceId, memberId, parameters): 멤버 검사, 두 조회 조정, size+1 판정·커서와 Result 구성 |
| document/application/dto/query/DocumentListParameters | 신규 제안 | 기본 size·상한·양수 ID·빈 cursor 검증. 조회 필터와 raw cursor를 묶음 |
| document/domain/DocumentCursor | 신규 제안·Entity 아님 | parse(...), encode(...): 페이지 경계와 조회 범위를 복원·검증하고 다음 cursor 구성 |
| document/application/DocumentListQuery | 신규 제안 | findTopics(scope, filters), findPage(scope, filters, cursor, limit): 전체 주제 집계와 size+1 카드 조회 계약 |
| document/infrastructure/DocumentListQueryAdapter | 신규 제안 | JdbcClient로 두 SQL 실행, 기존 상세 집계 규칙 적용. 바인딩 파라미터로 필터와 경계 전달 |
| document/application/dto/result의 DocumentCardResult·DocumentTopicResult·DocumentListResult | 신규 구현 | 폴더/카드/nextCursor 구성. Query adapter에서 기존 MyConfirmationState.resolve로 내 상태를 판정하고 녹음 초를 변환하며 별도 CardSnapshot은 추가하지 않음 |
| document/presentation/dto/response의 DocumentCardResponse·DocumentTopicResponse·DocumentListResponse | 신규 제안 | 카드/주제/목록 Schema와 from(...) 변환. 본문 content는 카드에 포함하지 않음 |
| 기존 DocumentConfirmationSummaryResult/Response, MyConfirmationState | 재사용 | 상세와 같은 필드·판정 기준 유지 |
| 기존 DocumentErrorCode | 필요한 오류 항목 추가 제안 | 잘못된 범위/cursor를 외부 계약의 INVALID_PARAMETER로 응답 |

숫자·enum 형식 오류는 기존 GlobalExceptionHandler의 type mismatch 매핑을 사용한다. size 범위·양수 ID·cursor 오류도 INVALID_PARAMETER로 맞춘다. 현재 @Valid 기반 제약 오류는 VALIDATION_ERROR로 응답하므로 이를 그대로 붙여 명세와 다른 오류 코드가 나오지 않게 한다. 공통 handler의 전체 동작은 바꾸지 않는다.

## 4. 저장 기반과 주의할 점

### 필터·집계와 조회 일관성

전체 주제 SQL은 Workspace·recordingSessionId·현재 사용자의 대상/확인 필터만 적용하며 GROUP BY topic으로 집계한다. cursor와 LIMIT은 이 SQL에 넣지 않는다. 카드 SQL에는 같은 필터와 (created_at, id) 경계를 적용하고 최신순으로 size+1개를 읽는다. 확인 인원 집계는 페이지의 문서들에만 수행하며 카드별 상세 Service 호출로 N+1을 만들지 않는다. 폴더 순서는 topic 오름차순을 기술 제안으로 두며 API가 보장하는 카드 최신순과 구분한다.

두 SELECT 사이에 새 문서·확인 기록이 들어와 같은 응답의 주제 수와 카드가 서로 다른 시점을 읽지 않도록, 목록 Service에만 readOnly + REPEATABLE_READ를 적용하는 안을 제안한다. DB 전체 기본 격리수준이나 기존 상세 Service는 바꾸지 않는다. PostgreSQL의 readOnly 선언만으로 여러 SELECT가 같은 snapshot을 읽는다고 가정하지 않는다. 실제 HTTP Service 트랜잭션의 격리수준과 두 조회 사이 동시 변경을 PostgreSQL에서 검증한다.

확인 근거: 프로젝트 테스트 DB는 pgvector/pgvector:pg18이며 [PostgreSQL 18의 transaction isolation 문서](https://www.postgresql.org/docs/18/transaction-iso.html)는 REPEATABLE READ의 연속 SELECT가 같은 snapshot을 읽고 READ COMMITTED에서는 다른 시점을 읽을 수 있음을 설명한다. 하나의 SQL로 주제/카드를 함께 반환하는 대안도 가능하지만, 우선 기존 JdbcClient DTO 매핑을 유지하는 두 SQL과 국소 트랜잭션 설정으로 계획한다.

### 커서

- 정렬 키는 createdAt DESC, id DESC다. 다음 페이지는 `created_at < cursor.createdAt OR (created_at = cursor.createdAt AND id < cursor.id)`로 조회한다.
- 전달할 카드 중 마지막 카드의 DB에서 읽은 시각·ID로 nextCursor를 만든다. size+1번째 카드는 경계로 쓰지 않는다.
- 실제로 추가 카드가 있을 때만 nextCursor를 만든다. size보다 적거나 정확히 size이고 추가 카드가 없으면 null이다.
- 구현안은 버전·workspaceId·memberId·myConfirmation·recordingSessionId·경계 시각/ID를 검증하는 Base64URL cursor다. 신규 의존성·비밀키는 도입하지 않는다. 필드 구분이 명확한 고정 형식을 사용하고 허용 길이·숫자 범위·버전·시각·양수 경계를 검사한다.
- 시각은 DB에서 읽은 초·나노초 정밀도를 보존한다. 문자열 밀리초 절삭으로 같은 시각 페이지가 빠지지 않게 한다.
- 다른 Workspace·사용자·필터에 cursor를 재사용하면 INVALID_PARAMETER로 처리하는 기술 제안이다. size는 경계 의미를 바꾸지 않으므로 다음 요청에서 변경할 수 있다.
- cursor는 인증 수단이 아니다. 매 요청마다 로그인·현재 Workspace 멤버를 검사한다. 경계 문서가 없어져도 cursor 값으로 조회하며, 그 문서의 존재를 필수로 검사하지 않는다.

### 페이지 크기·schema·담당 경계

Issue #495는 기본 50/최대 100을 TODO로 명시하지만 붙여준 API는 아직 제안으로 표기한다. 구현 계획은 이 크기를 기준으로 한다. 기본 50은 초기 카드 로딩과 요청 수를 조절하고 최대 100은 카드 DTO와 확인 집계의 요청당 부하를 제한하기 위한 구현 근거다. 실측 최적값이나 팀 승인 완료로 표현하지 않는다. size 허용 범위는 1~100으로 검증한다.

이번에는 새로운 Entity·테이블·migration이 필요하지 않다. V27의 (workspace_id, created_at DESC, id DESC) 인덱스와 확인 복합 키를 먼저 사용한다. 실제 조회 계획에 근거 없이 필터 조합별 인덱스를 추가하지 않는다. 성능 검증에서 필요성이 드러나면 별도 schema 변경으로 분리하고 최신 migration 번호/적용 이력을 확인한다.

카드는 기존 상세와 동일하게 RecordingSession.accumulatedRecordingMillis/1000을 사용하며 소수 초를 버린다. 부분 성공 문서는 목록과 주제 집계에 즉시 포함하고 다른 Job의 상태·보존 기간을 문서 필터로 사용하지 않는다. 조회 중 확인/보관 전환을 수행하지 않는다. STT 공급자·구간 저장·전사 Job 모델은 음성 담당 범위로 유지한다.

## 5. TDD와 검증 순서

각 단계는 실패하는 테스트 → 해당 동작의 최소 구현으로 진행한다. 실제 구현을 시작할 때 `npx ph workflow implement`와 작업 카드를 연결하며 이번 계획 단계에서 구현 워크플로를 시작하지 않는다. Issue #495에는 별도 ADR 예정 경로가 없으며 기존 #496 확인 3상태 Proposed ADR을 재사용한다.

1. 파라미터: 생략 기본값·1/100 경계·0/101·양수 녹음 ID·빈 cursor 테스트 → DocumentListParameters와 INVALID_PARAMETER 처리.
2. 커서: 정상 왕복·동일 시각/정밀도·잘못된 형식/버전/값·범위 및 필터 불일치 테스트 → DocumentCursor.
3. 카드 DB 조회: 두 상태·두 필터 단독/AND·세 확인 상태·부분 성공·타 Workspace 격리 테스트 → findPage SQL.
4. 주제 집계: 필터된 전체 수·0개 주제 제외·size/다음 cursor와 무관한 전체 집계 테스트 → findTopics SQL.
5. 목록 Service: 멤버 검사·조회 미호출·size+1 경계·마지막 cursor·읽기 일관성 테스트 → Service와 Snapshot/Result 변환.
6. HTTP: query 전달·400/401/403·빈 결과/카드·OpenAPI 테스트 → 기존 DocumentApi/Controller에 목록 메서드와 Response 추가.
7. 문서와 회귀: backend/docs/api/document-list.md 작성·잘못된 JSON 수정·기존 상세 회귀·Persona 보고서 및 finish implement 결과 기록.

| 테스트 위치·방식 | 검증 동작 | 기대 결과 |
| --- | --- | --- |
| DocumentListParametersTest, JUnit | 기본값·경계·잘못된 범위 | 기본 50, 1/100 허용, 나머지 INVALID_PARAMETER |
| DocumentCursorTest, JUnit | 정상 왕복·소수 초·불량 값·조건 불일치 | 경계 복원 또는 INVALID_PARAMETER |
| DocumentListQueryIntegrationTest, PostgreSQL/Flyway | myConfirmation 각 값·녹음 필터·AND·DRAFT/ARCHIVED·다른 Workspace | 해당 카드만 반환, 비대상도 조회 가능 |
| 위 Query 통합 테스트 | 동일 createdAt의 여러 페이지·다른 날짜·마지막/정확한 size·빈 결과 | id DESC 연결, 정적 데이터에서 중복/누락 없음, nextCursor 판정 |
| 위 Query 통합 테스트 | 필터별 주제 수·cursor/size 변화·0개 주제·부분 성공 | 전체 필터 결과 집계, 성공 문서 즉시 포함 |
| 위 Query 통합 테스트 | 확인 후 탈퇴·미확인 탈퇴·DRAFT 재가입·nullable summary·녹음 시간 | 기존 상세와 같은 내 상태/집계/초 변환 |
| DocumentListServiceTest, Mockito | 정상·비멤버·추가 카드·마지막 카드 | 결과·403 및 SQL 미호출·적절한 nextCursor |
| DocumentListSnapshotIntegrationTest, 실제 PostgreSQL 트랜잭션 | 외부 commit이 두 조회 사이에 발생 | 한 응답의 topics/items 동일 snapshot. 다음 요청은 변경 결과 반영 |
| DocumentControllerTest, MockMvc | query binding·enum/숫자/range/cursor 오류·recordingSessionId 위치 | 명세대로 200/400, recordingSessionId는 카드 필드 |
| DocumentListAcceptanceTest, 실제 Security/DB | 인증·탈퇴·삭제 Workspace·타 Workspace 녹음·CSRF 없는 GET | 401/403 또는 빈 200, 원본 범위 우회 없음 |
| 위 인수 테스트 | GET 전후 Document/Confirmation 불변·OpenAPI | 저장 변경 없음, query/DTO/오류 계약 일치 |
| 기존 DocumentDetail 테스트 | 공유 API 클래스·DTO·모델 사용 | 상세 응답·확인 3상태·인가 유지 |

MockMvc/Mockito는 실제 DB snapshot과 제약을 증명하지 않으므로 PostgreSQL 검증과 구분한다. 동시 조회 테스트는 sleep으로 순서를 추정하지 않고 latch/트랜잭션 경계로 외부 commit 시점을 제어한다. 테스트 전용 외부 변경만 수행하며 제품 GET에는 잠금·쓰기·STT 호출을 넣지 않는다.

build.gradle에서 확인한 검증 명령이며 아직 실행하지 않았다.

```bash
./gradlew test --tests 'com.knot.backend.document.*' --console=plain
./gradlew integrationTest --tests 'com.knot.backend.document.*' --console=plain
./gradlew acceptanceTest --tests 'com.knot.backend.document.*' --console=plain
./gradlew spotlessCheck --console=plain
./gradlew check --console=plain
```

집중 테스트를 먼저 실행하고 최종 공유 Controller/API/transaction 회귀 확인 시 전체 check를 수행한다. commit을 요청받으면 파라미터·cursor·카드 SQL·주제 SQL·Service·HTTP·문서 단위로 test/feat를 분리하며 현재 요청을 commit 권한으로 해석하지 않는다.

## 6. 완료 기준과 다음 작업

실제 인증된 요청에서 주제·전체 필터 집계·최신순 카드·정확한 커서가 반환되고, 잘못된 입력·권한·범위·읽기 불변 및 기존 상세 회귀가 통과해야 한다. 구현 기반 명세와 OpenAPI를 맞추고 Persona 결과를 테스트 결과와 구분해 기록한다. 음성 원본 schema의 연결 상태와 #496 병합 의존성은 계속 명시한다.

### 2026-10-06 구현 결과

- 기존 DocumentApi/Controller에 findDocuments를 추가하고 DocumentListService/Query/Adapter를 연결했다. 새 Entity·migration 없이 #496 기반을 재사용한다.
- 파라미터·커서 테스트의 클래스 미구현 컴파일 실패 → 구현 후 통과를 확인했다. Query/Service 테스트도 미구현 컴파일 실패 후 PostgreSQL·단위 테스트가 통과했다.
- endpoint 구현 전 HTTP/OpenAPI 인수 테스트 16개 실패 → 실제 인증·DB·Swagger 테스트 16개 통과를 확인했다. 이후 기본 50/최대 100 검증을 추가했다.
- 동시 생성·확인 완료를 주제 조회와 카드 조회 사이에서 별도 트랜잭션으로 커밋했다. 같은 응답은 기존 스냅샷을 유지하고 다음 호출에는 변경이 보이는 PostgreSQL 테스트 2개가 통과했다.
- FE 계약은 backend/docs/api/document-list.md에 저장했다. recordingSessionId는 집계 객체 밖 카드 필드다.
- 전체 ./gradlew spotlessApply check는 BUILD SUCCESSFUL이다. Persona finish의 최종 결과는 아래에 추가한다. STT·AI 생성 실행 및 운영 배포를 검증했다고 주장하지 않는다.
- Issue/PR/Notion 변경·commit/push는 수행하지 않았다.

### 최종 검증과 전달 상태

- 전체 Gradle check는 단위 467·통합 194·인수 307, 총 968개 PASS(신규 55개)이며 실패·오류·skip은 0이다. 이후 인수 테스트의 deprecated JsonNode 접근을 Response 역직렬화로 바꾼 focused acceptance 17개 및 Spotless도 PASS다.
- git diff --check·변경 Java 공백 검사·API 명세 JSON 예시 2개 파싱 PASS.
- Persona finish exit 1: report-coverage-missing, java-role-read-coverage-missing, convention-toolchain-missing, workflow-loop-state-stale, pending-ticket. 변경 Java·profile·roles·plan의 read evidence는 기록됐으나 기존 보고서/역할 도구/과거 카드 상태를 초기화하지 않았다. 제품 검증과 하네스 인증을 구분한다.
- 상세 근거는 backend/.persona/workflow/work/495-document-list/implementation-report.md와 review-report.md에 저장했다. 이 보고서는 로컬 ignored 파일이며 Git에는 제품 코드·테스트·API 명세·계획을 남긴다.
- 현재 be/feature/#495의 제품 작업은 완료했다. 후속 문서 작업은 #497 확인 현황 조회다. #496 또는 음성 schema 변경이 들어오면 브랜치 기반 정합과 회귀를 다시 확인한다.

### 사용자 승인된 커밋·게시 준비

2026-10-06 후속 요청으로 atomic 커밋·push·Draft PR 게시를 승인받았다. 최초 구현 단계의 'commit/push 없음'은 당시 상태이며 이 후속 요청을 막는 조건이 아니다.

- 메서드/동작 기준으로 입력 of, 커서 of·encode·parse, Query findPage·findTopics, Service find·조회 snapshot, HTTP findDocuments와 응답 변환을 나눴다. 각 동작의 test를 관련 feat 바로 앞에 배치하며 불필요한 refactor 커밋은 만들지 않는다.
- 커서 생성·인코딩을 독립적으로 검증하는 테스트 4개를 추가했다. 최종 ./gradlew spotlessApply check PASS: 단위 471·통합 194·인수 307, 총 972개. 신규 59개, 실패·오류·skip 0.
- Governance 검증기 테스트 8개 PASS. 중간 test 커밋은 다음 구현 전까지 RED일 수 있으며 모든 중간 커밋이 GREEN이라고 주장하지 않는다. 최종 제품 코드와 테스트는 위 전체 검증을 통과했다.
- #496 PR #502는 아직 OPEN이며 head가 분기 기준 91c0b99a다. 게시할 Draft PR은 be/feature/#496을 base, be/feature/#495를 head로 사용한다. #495의 상위/하위 Issue는 없다.
- API 명세와 구현 계획은 별도 docs 커밋으로 저장한다. 원격 push·게시 후 실제 URL·HEAD·Draft·base와 Governance 검증을 확인하며 Issue 자동 종료·merge는 수행하지 않는다.
