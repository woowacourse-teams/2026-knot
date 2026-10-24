# #497 문서 확인 대상과 진행 현황 조회 구현 계획

2026-10-07 후속 변경: `DocumentConfirmationReadJpaRepository`의 JPQL과 집계·대상 Row projection으로 조회를 통일했다. 전체 집계와 대상 페이지를 기존 REPEATABLE_READ 안에서 읽는다. 현재 활성 멤버십만 조인하며 대상 페이지의 상태 정렬·memberId 동률 경계·size+1 제한을 유지한다. 아래 JdbcClient 설명은 최초 구현 이력이다. 후속 공통 계획은 [문서 조회 JPA 정합성 작업](document-jpa-queries.md)을 따른다.

상태: 테스트 선행 구현·전체 검증 완료. Persona 인증 결과는 아래 실행 결과에 기록한다.
확인일: 2026-10-06.
브랜치: `be/feature/#497`.
분기 기준: `origin/be/feature/#496`의 `91c0b99af9f8444f84d0a9179990bf7b6cc9f928`.

근거는 [Issue #497](https://github.com/woowacourse-teams/2026-Knot/issues/497), 사용자 확정 결정, 기존 #496 코드·V27·Proposed ADR이다. 현재 V2 기획 기준, Notion 정합성 워크플로, 로컬 Persona profile·개발 지침 및 기존 상세 테스트를 함께 확인했다. 최신 API를 우선한다는 사용자 지시는 유지한다. Notion MCP로 개인 명세 root와 운영 API root를 읽으려 했으나 모두 404 NOT_FOUND였으므로 원문 재확인이나 원문과의 최신 일치를 주장하지 않는다. 구현 전 접근 가능한 최신 확인 현황 API와 아래 계약을 대조한다. 현재 계획의 기능 계약은 #497 본문에 명시된 항목이다.

## 1. 범위와 브랜치

대상은 `GET /api/v1/workspaces/{workspaceId}/documents/{documentId}/confirmations` 하나다. 확인자 팝오버에 생성 당시 고정된 확인 대상과 현재 확인·미확인·제외 상태를 제공한다. 이 조회는 대상 생성, 확인 처리, 보관 전환, 탈퇴 처리를 수행하지 않는다. 확인 쓰기는 #498, 최초 대상 저장은 #501과 연결한다.

작업 트리가 깨끗한 상태에서 원격을 fetch하고 #496 기준으로 새 브랜치를 생성했다. 자동으로 붙은 #496 upstream은 해제했다. 이후 승인된 첫 push 때 #497 자체 원격 브랜치를 연결한다. #495와 #497은 #496에서 각각 분기하며 #495 전체 변경을 가져오지 않는다.

확인 당시 [PR #502](https://github.com/woowacourse-teams/2026-Knot/pull/502)는 OPEN이며 develop 기준, head는 위 분기 SHA다. #497 PR을 게시할 때는 base를 `be/feature/#496`으로 두어 이번 API 변경만 리뷰한다. #496 병합 후 실제 merge 방식과 ancestry를 확인하여 develop 기준으로 정리한다. 지금 PR 생성·commit·push·merge는 하지 않는다.

fetch 후 origin/develop은 `77e2d927`이다. #496에 포함되지 않은 최신 develop 변경은 최종 오디오 저장소의 EC2 역할 인증 지원이며, 두 revision 사이 migration 파일 변경은 없었다. #497은 현재 #496 기반을 유지한다. 실제 공유 DB의 migration 적용 상태는 이번 계획에서 관측하지 않았다.

## 2. 구현할 흐름

```text
GET /api/v1/workspaces/{workspaceId}/documents/{documentId}/confirmations
  → 기존 Security: 로그인 검사
  → DocumentController.findDocumentConfirmations: 경로·query·memberId 전달
  → DocumentConfirmationParameters: 기본 size=50, 범위 1..100, 빈 cursor 검증
  → DocumentConfirmationService.find: 현재 Workspace 멤버 검사
  → 커서가 있으면 형식과 Workspace·문서·조회 멤버 범위 검증
  → Query.findSummary: Workspace 범위 안의 문서 존재·전체 집계·내 확인 여부 조회
  → Query.findPage: 고정 대상의 상태 순서로 size+1명 조회
  → 최대 size명과 마지막 반환 대상 기준 nextCursor 구성
  → DocumentConfirmationsResponse 반환
```

| 상황 | 계획한 결과 |
| --- | --- |
| 정상 조회 | 200, documentId·세 집계·confirmedByMe·items·nextCursor |
| 문서가 있고 대상이 0명 | 200, 세 집계 0·confirmedByMe=false·items=[]·nextCursor=null |
| 생성 후 합류한 현재 Workspace 멤버 | 조회 허용. 대상에 추가하지 않고 confirmedByMe=false |
| 미로그인 | 401 UNAUTHENTICATED |
| 비멤버·탈퇴한 요청자·삭제 Workspace | 403 WORKSPACE_ACCESS_DENIED |
| 없는 문서·다른 Workspace 문서 | 404 DOCUMENT_NOT_FOUND |
| cursor 또는 size의 형식·범위 오류 | 400 INVALID_PARAMETER |

응답의 집계는 `confirmedCount`, `pendingCount`, `excludedCount`를 최상위 필드로 제공한다. 각 item은 `memberId`, `nickname`, nullable `profileImageUrl`, nullable `confirmedAt`, `state`다. #497의 `confirmedByMe` Boolean 계약은 유지하고 목록·상세의 `myConfirmationState` 3상태와 같은 대상·확인 기록으로 계산한다.

대상의 상태는 확인 시각이 있으면 현재 가입 여부와 관계없이 CONFIRMED, 미확인이며 현재 활성 멤버면 PENDING, 미확인이며 현재 비활성 멤버면 EXCLUDED다. 대상이 아닌 사람은 items에 넣지 않는다. DRAFT 재가입자는 같은 대상 행을 유지하며 현재 활성 여부에 따라 PENDING으로 다시 판정한다. 이 GET에서 Document 상태는 재판정하지 않는다.

## 3. 나올 코드와 메서드 초안

현재 #496에 있는 DocumentApi·DocumentController·DocumentConfirmation·복합 ID, DocumentConfirmationSummaryResult, WorkspaceMemberRepository와 JdbcClient 상세 조회 관례를 재사용한다. 현재 이 브랜치에는 #495의 목록 Service·Query·Cursor·Parameters가 없다. 문서 본문까지 읽는 DetailService를 확인 현황 서비스에서 호출하지 않는다.

아래 신규 타입과 메서드 이름은 구현 제안이다.

| 위치·클래스 | 책임 | 추가·변경할 메서드 후보 |
| --- | --- | --- |
| 기존 document/presentation/DocumentApi | Swagger 입력·응답·오류·쿠키 인증 계약 | `findDocumentConfirmations(...)` |
| 기존 document/presentation/DocumentController | GET 매핑, Parameters 생성, Service 호출, Response 변환 | `findDocumentConfirmations(...)` |
| 신규 document/application/DocumentConfirmationService | 현재 멤버 인가, 문서 404, 페이지와 커서 구성 | `find(workspaceId, memberId, documentId, parameters)` |
| 신규 document/application/DocumentConfirmationQuery 및 infrastructure/DocumentConfirmationQueryAdapter | 문서 존재와 전체 집계·내 확인 여부, 대상 페이지 SQL | `findSummary(...)`, `findPage(...)` |
| 신규 application/dto/query/DocumentConfirmationParameters | size 기본값·범위·빈 cursor 검증 | `of(...)`; private 규칙별 검증과 기본값 결정 |
| 신규 domain/DocumentConfirmationState | 대상의 CONFIRMED·PENDING·EXCLUDED 판정 | `resolve(activeMember, confirmedAt)` |
| 신규 domain/DocumentConfirmationCursor | 정렬 경계와 요청 범위의 인코딩·복원 | `of(...)`, `encode()`, `parse(...)` |
| 신규 application/dto/result의 Summary·Item·Confirmations Result | Query 결과와 최종 페이지 전달 | 필요한 record만 별도 파일에 작성 |
| 신규 presentation/dto/response의 Item·Confirmations Response | 공개 필드와 Schema, Result 변환 | `from(...)` |
| 기존 DocumentErrorCode | #496에 아직 없는 쿼리 오류 코드 | `INVALID_PARAMETER` 추가 |

Query.findSummary는 `Optional`로 문서 없음과 대상 0명을 구분한다. Query.findPage는 대상 Result 목록을 반환하며 DB 상태를 변경하지 않는다. 집계 Result 내부에서는 기존 DocumentConfirmationSummaryResult를 사용할 수 있지만 공개 응답은 #497의 최상위 세 집계 필드를 유지한다.

기존 MyConfirmationState의 NOT_REQUIRED는 현재 요청자가 확인 대상인지 나타낸다. 이번 EXCLUDED는 생성 당시 대상이었던 사람의 현재 제외 여부다. 서로 다른 의미이므로 기존 enum에 EXCLUDED를 추가하지 않는다.

새 코드와 수정하는 코드에는 삼항 연산자를 사용하지 않는다. 검증·기본값·선택값 변환·페이지 구성은 의미 있는 private 메서드와 if/조기 반환으로 표현한다. 인터페이스 선언 뒤와 메서드 사이에 한 줄 공백을 둔다. #495의 가독성 변경 전체를 가져오는 추가 의존성은 만들지 않는다.

## 4. 저장 기반과 주의할 점

| 기존 저장 기반 | 사용하는 정보 |
| --- | --- |
| documents | 문서 ID·Workspace 범위, 기존 상태 |
| document_confirmations | 생성 당시 고정 대상, member_id, nullable confirmed_at |
| members | 현재 nickname, nullable profile_image_url |
| workspace_members | 현재 활성 멤버십의 존재 여부 |

V27의 `(document_id, member_id)` 복합 PK와 기존 FK를 사용한다. 새 Entity·컬럼·migration은 현재 계획에서 필요하지 않다. 닉네임·이미지는 Member에서 읽으며 과거 프로필 스냅샷을 새로 저장하지 않는다. 활성 멤버 여부는 EXISTS로 판단해 재가입 이력이 여러 행이어도 사람·집계가 중복되지 않게 한다. 탈퇴한 대상도 FK가 유지하는 Member와 대상 기록을 읽는다.

### 전체 집계와 대상 페이지

findSummary는 문서가 있으면 대상이 0명이어도 결과를 반환한다. 전체 집계와 confirmedByMe에 cursor·size를 적용하지 않는다. findPage는 동일 Document의 고정 대상에만 경계와 LIMIT을 적용한다. 대상별 DetailService 호출이나 추가 DB 조회로 N+1을 만들지 않는다.

두 SQL의 집계와 items가 같은 요청에서 서로 다른 상태를 읽지 않게, 신규 Service에만 `readOnly=true`와 `REPEATABLE_READ`를 적용하는 기술안을 사용한다. DB 전체 기본 설정은 바꾸지 않는다. 실제 PostgreSQL 트랜잭션 중 다른 연결의 확인·탈퇴 commit을 삽입해 검증한다. 여러 HTTP 페이지 요청 전체를 같은 snapshot으로 묶는 계약은 아니다.

### 정렬과 커서

Issue의 상태 순서를 CONFIRMED → PENDING → EXCLUDED로 적용한다. 각 상태 안에서는 대상의 memberId 오름차순을 사용한다는 기술안을 둔다. 복합 PK에 별도 대상 ID가 없으므로 대상 식별자는 memberId다. confirmedAt이나 nickname을 정렬 키로 사용하지 않는다.

다음 페이지는 `(상태 순위, memberId)`가 커서 경계보다 큰 대상으로 이어 조회한다. size+1명을 읽고 추가 대상이 있을 때만 마지막 반환 대상의 상태·memberId로 cursor를 만든다. 정확히 size명이며 추가 대상이 없거나 빈 페이지면 nextCursor=null이다.

커서는 version·workspaceId·documentId·조회 memberId·상태·대상 memberId를 포함한 Base64URL 형식을 제안한다. 길이·버전·필드 수·식별자·상태와 요청 범위를 검증하고 size는 묶지 않아 다음 요청에서 변경할 수 있게 한다. 상태 변화로 경계 대상이 이동해도 경계 행의 현재 상태를 찾아 고쳐 쓰지 않는다. 매번 인가하며 cursor를 권한 증거로 사용하지 않는다.

동시 확인·탈퇴·재가입으로 대상 상태가 페이지 사이 바뀌면 대상이 다른 정렬 위치로 이동할 수 있다. 정적 대상 상태에서의 안정적인 순서와 한 응답 내부의 일관성을 검증하며, 변경 중 여러 페이지 전체의 중복·누락 방지를 보장한다고 기록하지 않는다. 원문 명세에 조회 전체 snapshot 요구가 있다면 구현 전 별도 계약을 확인한다.

다른 생성 Job의 상태를 필터나 집계에 섞지 않는다. 부분 성공으로 이미 저장된 Document의 확인 현황은 바로 공개된다. #498의 확인·보관 규칙과 같은 데이터 판정을 유지하지만 #497에서 쓰기·잠금·탈퇴 후 보관 전환을 구현하지 않는다. 기존 #496 Proposed ADR은 유지하며 #497 Issue에 별도 ADR 예정 경로는 없다.

## 5. TDD와 검증 순서

2026-10-06 사용자 개발 승인 후 Persona 구현 rail과 #497 작업 카드를 연결했다. 아래 순서로 테스트를 먼저 작성하고 최소 구현을 진행했다.

1. Parameters: 기본 50, 1/100 허용, 0/101·빈 cursor 거절 테스트 → 기본값 및 규칙별 검증 구현.
2. State.resolve: 활성/탈퇴 대상의 세 상태와 확인 후 탈퇴 사례 테스트 → 순서가 명확한 판정 구현.
3. Cursor.of/encode/parse: 각 public 메서드의 정상·오류·요청 범위 불일치 테스트 → 단계별 최소 구현.
4. Query.findSummary: 문서 존재/없음·0명·전체 세 집계·내 확인 여부·Workspace 격리 테스트 → 문서 범위와 집계 SQL 구현.
5. Query.findPage: 상태/memberId 정렬·size+1·다음 페이지·프로필 null·가입 이력 중복 방지 테스트 → 대상 페이지 SQL 구현.
6. Service.find: 현재 멤버 검사·404·빈 페이지·정확한 size·추가 대상·마지막 반환 경계 테스트 → 인가와 페이지 조정 구현.
7. PostgreSQL snapshot: 두 조회 사이 확인·탈퇴 commit 테스트 → readOnly 트랜잭션 일관성 검증.
8. HTTP: 입력 바인딩·200/400/401/403/404·GET 저장 불변·OpenAPI 테스트 → 기존 Api/Controller 및 응답 DTO 구현.
9. FE용 API 문서, 기존 상세 회귀, 전체 Gradle check, 구현/검토 보고서 및 Persona finish 결과 기록.

| 테스트 위치·방식 | 검증할 동작 | 기대 결과 |
| --- | --- | --- |
| Parameters·State·Cursor 단위 테스트 | 정상·경계·빈 값·불량 cursor·다른 요청 범위 | 기본값과 세 상태 유지 또는 INVALID_PARAMETER |
| Query PostgreSQL/Testcontainers | 없는 문서/다른 Workspace/문서에 대상 없음 | Optional.empty 또는 0명 Summary·빈 페이지를 구분 |
| Query PostgreSQL/Testcontainers | 확인 후 탈퇴·미확인 탈퇴·재가입·이후 가입자 | 세 상태·대상 행 보존·신규 가입자 제외·중복 없는 집계 |
| Query PostgreSQL/Testcontainers | 상태 안 ID 순서·상태 경계·여러 페이지·프로필 null | 정적 상태에서 누락/중복 없이 연결, null 계약 유지 |
| Service Mockito | 정상·비멤버·404·추가 대상·마지막/빈 페이지 | 올바른 결과/오류, 비멤버의 Query 미호출, 올바른 cursor |
| 실제 PostgreSQL Service 트랜잭션 | 집계와 페이지 사이 확인·탈퇴 commit | 한 응답의 집계/items 일치, 다음 요청에서 변화 반영 |
| MockMvc·실제 Security/DB 인수 테스트 | 쿠키 인증·멤버 인가·문서 범위·입력 오류·CSRF 없는 GET | 계약의 200/400/401/403/404 |
| 실제 Security/DB 인수 테스트 | 대상 없는 조회·부분 성공 문서·GET 전후 snapshot·OpenAPI | 정확한 DTO, 다른 Job 영향 없음, 확인/보관/대상 행 불변 |

커밋은 의미 있는 public 동작·메서드별 test → feat 순서로 제안한다. Cursor의 of/encode/parse, Query의 findSummary/findPage를 별도 단위로 다룬다. refactor는 통과한 동작에 실제 정리가 필요할 때만 추가한다. 검증 중 커밋 경계를 이유로 다른 작업의 파일을 포함하지 않는다.

예정 명령은 기존 Gradle 설정의 단위·통합·인수 task를 사용한다. 초반에는 새 테스트 클래스만 선택하고 마지막에는 상세 회귀와 필수 check를 실행한다.

```bash
./gradlew test --tests 'com.knot.backend.document.*' --console=plain
./gradlew integrationTest --tests 'com.knot.backend.document.*' --console=plain
./gradlew acceptanceTest --tests 'com.knot.backend.document.*' --console=plain
./gradlew spotlessApply check --console=plain
```

## 6. 완료 기준과 다음 작업

완료 기준은 #497의 HTTP 계약·고정 대상·세 상태·전체 집계·정렬·커서·인가·GET 불변이 실제 PostgreSQL 및 Security 테스트에서 확인되는 것이다. FE용 문서는 구현 후 실제 코드·OpenAPI를 기준으로 작성한다. 모의 확인/탈퇴 데이터의 조회 테스트를 #498의 실제 확인·탈퇴 연계 완료나 #501의 실제 대상 생성 검증으로 표현하지 않는다.

이후 #498에서 최초 confirmedAt·반복 요청·마지막 대상의 ARCHIVED 전환과 탈퇴 경합을 구현한다. #499는 음성 담당자의 원문 구간 저장 계약과 연결한다.

Notion 원문 재접근과 실제 운영 DB 적용 상태는 미확인이다. 현재 API는 승인된 계획의 상태 순서·memberId 오름차순과 cursor·트랜잭션 설정을 구현했다. 이를 Notion 원문의 최신 일치나 팀 승인으로 표현하지 않는다.

### 실행 결과

- Parameters·State·Cursor·Query·Service 테스트를 구현보다 먼저 작성했다. 최초 RED는 타입 부재에 따른 compileTestJava 실패였으며 모든 단위 테스트의 행동 실패를 관찰했다고 주장하지 않는다.
- PostgreSQL Query 테스트 8개와 Service 단위 테스트 통과. 이후 가입자 제외, 탈퇴·재가입 이력 중복 방지, 확인 후 탈퇴 상태 유지, 전체 집계 및 상태/memberId 페이지 순서를 확인했다.
- 집계 후 별도 트랜잭션에서 확인·탈퇴를 커밋하는 테스트 2개가 처음에는 실제 상태 불일치로 실패했다. 신규 Service에 REPEATABLE_READ를 적용한 뒤 통과했고 다음 호출은 변경 상태를 읽었다.
- endpoint 연결 전 HTTP/OpenAPI 테스트 20개 중 19개 실패(미인증 401은 기존 Security에서 통과). 연결 후 20개 및 기존 상세 인수 테스트 통과.
- 새 Entity·migration·음성/STT 구현 없이 기존 Document·DocumentConfirmation·Member·WorkspaceMember 테이블을 읽는다. FE 명세는 [document-confirmations.md](../api/document-confirmations.md)에 저장했다.
- 전체 Gradle check 992개 PASS(단위 485·통합 197·인수 310), 신규 79개. 실패·오류·skip 0. Spotless·diff whitespace·API JSON 예시 파싱 PASS.
- Persona finish exit 1: 보고서/Java read coverage·convention toolchain·stale workflow·pending-ticket. 기존 카드/도구 상태를 초기화하지 않았으며 제품 테스트 PASS와 하네스 미인증을 구분한다. 상세 결과는 로컬 구현·검토 보고서에 기록했다.
- commit·push·PR·Notion 쓰기는 이번 요청에서 수행하지 않는다.
