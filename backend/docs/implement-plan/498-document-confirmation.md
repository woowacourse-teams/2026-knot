# #498 내 문서 확인 완료 처리 구현 계획

2026-10-07 [#517](https://github.com/woowacourse-teams/2026-Knot/issues/517) 후속 변경: 사용자가 코드 흐름을 쉽게 읽을 수 있도록 두 탈퇴 서비스가 `DocumentArchivalService.archiveAfterMemberDeparture(workspaceId, memberId, leftAt)`를 직접 호출한다. 기존 동기 이벤트도 같은 트랜잭션에서 처리했지만, 발행·수신 연결을 따라가야 하는 구조를 직접 의존으로 단순화했다. Workspace → Document의 application 서비스 호출을 이 범위에서 허용하며, 기존 `MANDATORY`·잠금·멤버 flush·보관 실패 롤백을 유지한다. `WorkspaceMemberLeft`, `ApplicationEventPublisher`, `@EventListener` 연결을 제거한다. 아래 계획의 이벤트 설계와 #498 실행 이력은 당시 기록이며, 현재 호출 구조는 이 후속 결정이 대체한다. **(이부분 수정됨)**

2026-10-07 후속 변경: 갱신된 `be/feature/#497`을 정상 머지해 상세·확인 현황의 Spring Data JPA/JPQL 조회를 반영했다. 명령 서비스와 동기 탈퇴 이벤트는 기존 JPA 저장·Workspace/Document 잠금·flush 경계를 유지하며 같은 트랜잭션의 JPA 집계 결과로 보관 여부를 판단한다. 목록 브랜치 #495는 독립 분기로 유지한다. 후속 공통 계획은 [문서 조회 JPA 정합성 작업](document-jpa-queries.md)을 따른다.

- 상태: 구현·전체 제품 검사 완료. 커밋·Draft PR 전달과 Persona 완료 판정을 별도로 기록한다.
- 확인일: 2026-10-06
- 대상: [#498](https://github.com/woowacourse-teams/2026-Knot/issues/498), `PUT /api/v1/workspaces/{workspaceId}/documents/{documentId}/confirmations/me`
- 계약 기준: 사용자가 제공한 최신 API와 #498의 TODO. 개인 명세·사용자 결정과 팀 승인 여부는 구분한다.

## 1. 범위와 브랜치

계획은 `be/feature/#497`의 실제 코드를 읽어 작성했다. 디스코드 작업 종료 후 #505의 tracked 변경이 없고 원격과 일치함을 확인하고, 사용자 승인에 따라 `origin/be/feature/#497`에서 `be/feature/#498`을 분기했다. #505 브랜치는 보존했다.

| 기반 | 확인한 상태 | 이번 작업과의 관계 |
| --- | --- | --- |
| [#502](https://github.com/woowacourse-teams/2026-Knot/pull/502), `be/feature/#496` → `develop` | OPEN | Document·DocumentConfirmation·V27 저장 기반과 상세 API |
| [#504](https://github.com/woowacourse-teams/2026-Knot/pull/504), `be/feature/#495` → `be/feature/#496` | OPEN | 목록 API. #498의 직접 기반으로 가져올 필요는 없음 |
| [#506](https://github.com/woowacourse-teams/2026-Knot/pull/506), `be/feature/#497` → `be/feature/#496` | OPEN | 확인 대상·현재 멤버 기준 집계 Query 재사용 |

실제 분기 기준은 원격 `be/feature/#497`의 커밋 `591f19e7f8b6920a5d228144109e5a58beb54bb2`다. `git switch --no-track -c 'be/feature/#498' 'origin/be/feature/#497'`로 분기했으며 별도 worktree는 생성하지 않았다.

선행 PR이 아직 미병합이므로 PR 비교 대상도 `be/feature/#497`로 설정해 #498 변경만 보여준다. #496 → #497 → #498 의존 순서를 유지한다. 이후 선행 PR 병합 시 실제 병합 커밋을 확인하고 기반을 정리한다. 목록 #495의 변경을 합쳐서 분기하지 않았다.

완료 범위는 최초 확인·반복 확인·마지막 대상 확인에 따른 보관·탈퇴에 따른 확인 대상 제외와 보관이다. #498은 탈퇴와 확인의 경합도 요구하므로 기존 일반 탈퇴와 OWNER 승계 후 탈퇴의 연결을 포함한다. STT·AI 생성·오디오·Job 재시도·보존 실행은 별도 작업이다.

이 계획 파일은 디스코드 작업 이후 #498 구현 브랜치에 포함한다. 현재 #505의 코드와 다른 계획 문서는 그대로 보존한다.

## 2. 구현할 흐름

브라우저가 확인 버튼을 누르면 로그인한 Member의 기존 확인 대상 기록을 갱신한다. 클라이언트가 Member ID나 보관 상태를 지정하지 않는다.

```text
PUT /workspaces/{workspaceId}/documents/{documentId}/confirmations/me
  → Security: access cookie·CSRF 검사
  → DocumentController.confirmDocument(...)
  → DocumentConfirmationCommandService.confirm(...)
  → Workspace 잠금 → 현재 멤버 검사 → Workspace 범위의 Document 잠금
  → 기존 DocumentConfirmation 대상 검사
  → 최초 confirmedAt 기록 → flush → 현재 확인 집계 조회
  → pendingCount = 0이면 Document.archive(...)
  → 같은 트랜잭션 커밋 → 200 응답
```

`flush`는 JPA가 모아 둔 변경을 DB에 전달하는 동작이다. 커밋은 아직 하지 않는다. 현재 집계는 JdbcClient SQL이므로 확인 변경을 먼저 flush해야 같은 트랜잭션에서 변경된 집계가 보인다.

예를 들어 확인 대상이 A·B이고 A만 확인했다면 DRAFT다. B가 확인하면 B의 최초 확인 시각과 문서 보관 시각을 저장하고 ARCHIVED를 반환한다. B가 다시 누르면 최초 시각과 보관 시각을 유지한다.

반복 요청의 멱등성은 최초 `confirmedAt`을 덮어쓰지 않는다는 뜻으로 적용한다. 그 사이 다른 대상이 확인하거나 탈퇴할 수 있으므로 `documentStatus`, `archivedAt`, `confirmationSummary`는 현재 저장 결과를 반환한다. 응답 JSON 전체를 최초 값으로 고정하지 않는다.

탈퇴에 따른 보관도 쓰기 경로에서 처리한다.

```text
WorkspaceLeaveService.leave(...) 또는
WorkspaceOwnershipTransferService.transferOwnership(...)
  → 기존 Workspace 잠금·멤버 탈퇴·녹음 정리
  → WorkspaceMemberRepository.flush()
  → WorkspaceMemberLeft 이벤트를 같은 트랜잭션에서 발행
  → DocumentArchivalService.handleMemberDeparture(...)
  → 해당 멤버가 미확인 대상인 DRAFT 문서 잠금
  → 현재 pendingCount = 0인 문서 보관
  → 탈퇴·승계·문서 보관을 함께 커밋
```

위 이벤트 연결은 구현한 내부 설계다. 동기 처리하고 기존 트랜잭션에 참여한다. 비동기나 커밋 후 이벤트로 처리하면 탈퇴와 보관이 분리되므로 이번 계약에 사용하지 않는다.

## 3. 나올 코드와 메서드 초안

현재 `DocumentApi`는 Swagger 계약, `DocumentController`는 실제 HTTP 매핑을 담당한다. 두 클래스에 PUT 메서드를 추가한다. #497의 `DocumentConfirmationService.find(...)`는 읽기 전용 REPEATABLE_READ 서비스이므로 확인 명령은 별도 쓰기 서비스로 둔다.

현재 `DocumentConfirmationQuery.findSummary(...)`는 확인·미확인·제외 집계를 제공한다. 확인한 대상은 탈퇴해도 confirmed, 미확인 대상은 현재 멤버이면 pending, 탈퇴했다면 excluded라는 기존 기준을 그대로 재사용한다. 새 집계 정의를 만들지 않는다.

아래 위치는 저장소 루트 기준이며 구현에 적용한 클래스와 메서드다. 저장 계약은 조회와 flush만 추가했고 새 대상 생성이나 별도 save API는 추가하지 않았다.

| 위치·클래스 | 책임 | 추가·변경할 메서드 후보 |
| --- | --- | --- |
| `backend/.../document/presentation/DocumentApi`, `DocumentController` | 계약 문서와 PUT 요청 전달 | `confirmDocument(workspaceId, documentId, authenticatedMember)` |
| `document/application/DocumentConfirmationCommandService` | 권한·대상 검사, 확인 저장·집계·보관을 한 트랜잭션으로 조정 | `confirm(workspaceId, memberId, documentId)` |
| `document/domain/DocumentConfirmation` | 최초 확인 시각 보존 | `confirm(Instant confirmedAt)` |
| `document/domain/Document` | DRAFT에서 ARCHIVED 전환, 이미 보관된 시각 보존 | `archive(Instant archivedAt)` |
| `document/domain/DocumentRepository`와 JPA adapter | Workspace 범위의 문서 잠금과 저장 | `findByWorkspaceIdAndIdForUpdate(...)`, `findAffectedDraftsForUpdate(workspaceId, memberId)`, 필요한 저장·flush 메서드 |
| `document/domain/DocumentConfirmationRepository`와 JPA adapter | 기존 대상 조회와 확인 변경 반영 | `findByDocumentIdAndMemberId(...)`, 필요한 저장·flush 메서드 |
| `document/application/dto/result/DocumentConfirmationResult` | 확인 작업 결과 | 문서 ID·최초 확인 시각·현재 상태·보관 시각·기존 집계 Result |
| `document/presentation/dto/response/DocumentConfirmationResponse` | 명세에 맞는 JSON 응답 | 기존 DTO 관례에 맞춘 변환 |
| `document/domain/DocumentErrorCode` | 대상이 아닌 멤버의 오류 | `CONFIRMATION_NOT_REQUIRED` 추가 |
| `workspace/domain/WorkspaceMemberLeft` | 탈퇴 사실 전달용 일반 클래스. 저장 Entity가 아님 | Workspace ID·Member ID·탈퇴 시각 |
| 기존 `WorkspaceLeaveService`, `WorkspaceOwnershipTransferService` | 실제 탈퇴가 기록된 경우 동기 이벤트 발행 | 기존 public 메서드에 최소 연결 추가 |
| `document/application/DocumentArchivalService` | 탈퇴로 마지막 필수 대상이 제외된 문서 보관 | `handleMemberDeparture(WorkspaceMemberLeft)` |

쓰기 서비스에서 조회 서비스를 호출하지 않고 기존 Query를 직접 재사용한다. API 본문이 없으므로 Request DTO는 추가하지 않는다. Repository의 인터페이스·adapter는 현재 Workspace 구현 패턴에 맞춘다. 메서드 후보는 구현 중 실제 필요한 계약만 남긴다.

조건은 `validateWorkspaceAccess`, `requireConfirmationTarget`, `archiveIfNoPendingTargets`처럼 의도가 드러나는 private 메서드로 나눈다. 삼항 연산자·길게 결합한 if·중첩 타입을 사용하지 않고 인터페이스 선언 다음에는 빈 줄을 둔다.

## 4. 저장 기반과 주의할 점

| 기존 저장 모델 | 사용할 필드·제약 | 변경 계획 |
| --- | --- | --- |
| `document_confirmations` | 복합 PK `(document_id, member_id)`, nullable `confirmed_at` | 기존 대상 행의 confirmed_at만 최초 한 번 설정 |
| `documents` | `status`, `archived_at`, `created_at` | DRAFT → ARCHIVED, 보관 시각 저장 |
| V27 CHECK | DRAFT이면 archived_at null, ARCHIVED이면 archived_at non-null이고 created_at 이상 | 도메인 변경과 DB 제약이 함께 유지되도록 검증 |
| `workspace_members` | 현재 활성 여부 `left_at` | 대상 행을 삭제하지 않고 집계에서 미확인 탈퇴자를 제외 |

필요한 컬럼이 V27에 있으므로 현재 계획은 신규 migration 없이 진행한다. 이는 저장소의 SQL 파일을 확인한 판단이며 실제 운영 DB의 V27 적용을 확인한 것은 아니다. 작업 시작 시 선행 PR과 migration 상태를 다시 확인한다. 추가 인덱스는 실제 조회와 실행 계획에서 필요가 드러났을 때 별도 검토한다.

### 잠금·트랜잭션

- 확인 명령도 기존 탈퇴·승계와 같은 Workspace 행을 먼저 잠근다. 잠금 후 현재 멤버 여부를 검사하고 해당 문서를 잠근다. 동시 확인과 탈퇴가 서로 다른 기준으로 마지막 대상을 계산하지 않도록 한다.
- 여러 문서를 처리하는 탈퇴 경로는 문서 ID 오름차순으로 잠근다. 기존 탈퇴·승계의 Workspace → 멤버 → 녹음 잠금 흐름과 함께 실제 PostgreSQL 경합 테스트로 확인한다.
- 새 쓰기 서비스는 READ_COMMITTED와 명시적 잠금을 기준으로 한다. 기존 GET 서비스의 REPEATABLE_READ 설정을 옮기지 않는다.
- JPA 확인 변경과 멤버 탈퇴 변경은 JdbcClient 집계 전에 flush한다. `WorkspaceMemberRepository.flush()`는 이미 있으므로 재사용한다.
- `DocumentArchivalService`의 이벤트 처리는 동기로 실행하고 `MANDATORY` 트랜잭션 참여를 제안한다. 보관 중 실패하면 확인이나 탈퇴·승계까지 롤백된다.
- 마지막 멤버 탈퇴는 Workspace를 soft delete한다. 이벤트 처리에서는 이미 검증된 탈퇴 사실로 해당 문서를 조회하므로 활성 Workspace만 찾는 조회를 다시 적용하여 보관을 누락하지 않게 한다.
- 이미 ARCHIVED인 문서는 재합류나 반복 확인으로 DRAFT로 되돌리지 않는다. 최초 확인·보관 시각도 유지한다.
- Workspace 단위 잠금은 같은 Workspace의 확인 요청을 직렬화한다. MVP에서 일관성을 우선하는 기술 제안이며 트랜잭션 안에 외부 호출을 넣지 않는다. 실제 경합 비용은 DB 검증에서 관찰한다.

### API 문구와 실제 보안 계약의 정리

| 위치 | 반영할 의미 |
| --- | --- |
| 처리 규칙의 “반복 요청은 같은 결과” | 최초 confirmedAt은 유지하고 문서 상태·보관 시각·집계는 현재 값 반환 |
| Response의 archivedAt 설명 | 현재 문서가 ARCHIVED이면 저장된 최초 보관 전환 시각 반환. 반복 요청에도 같은 시각 반환 |
| Error Response | 기존 보안 핸들러의 CSRF 실패는 `403 FORBIDDEN`. Workspace 권한 실패와 구분하여 문서화 |
| Path 입력 오류 | 잘못된 Long 바인딩 등 실제 `400 INVALID_PARAMETER` 계약을 HTTP 테스트로 확인하여 명시 |
| 빈 요청 본문과 Content-Type | RequestBody 없이 매핑하고 application/json 요청을 수용. 헤더만 강제하려고 새 415 계약을 추가하지 않으며 필수 표시와 실제 처리를 구현 문서 작성 때 맞춤 |

HTTP 401 검증에는 유효한 CSRF를 보내 인증 실패만 확인한다. CSRF 자체가 없으면 보안 필터에서 먼저 403이 날 수 있으므로 두 실패를 분리한다.

현재 `backend/docs/product/current-v2-mvp.md`도 사용자 최신 API를 우선 기준으로 지정하며 DRAFT·ARCHIVED 규칙을 반영한다. 이번 작업은 최신 API와 #498을 따르며 다른 Notion 기획 문서는 수정하지 않았다. #498에는 새 ADR 생성 요구나 예정 경로가 없으며 기존 #496 Proposed ADR은 선행 자산으로 유지한다.

## 5. TDD와 검증 순서

각 단계는 실패하는 테스트를 먼저 작성하고 그 동작을 통과시키는 최소 구현을 한다. 메서드·저장 adapter·HTTP 계약·탈퇴 경로 단위로 나누며 커밋·push는 별도 요청을 받은 뒤 실행한다.

1. `DocumentConfirmation.confirm(...)`: 최초 확인과 반복 요청 시각 보존 테스트 → 도메인 메서드.
2. `Document.archive(...)`: 최초 보관·반복 보관 시각 유지·createdAt 이전 시각 거절 테스트 → 도메인 메서드.
3. 문서와 기존 대상 조회·저장 adapter: Workspace 범위와 저장 반영 PostgreSQL 테스트 → JPA Repository/adapter.
4. `DocumentConfirmationCommandService.confirm(...)`: 권한·404·409·최초/반복 확인·마지막 확인 테스트 → 서비스와 기존 집계 연결.
5. `DocumentApi`·Controller·Response: HTTP 매핑과 JSON·인증·CSRF 테스트 → PUT endpoint와 Swagger.
6. `WorkspaceLeaveService.leave(...)`: 탈퇴자의 기존 대상 기록 유지, 마지막 미확인자 제외 시 보관·실패 시 전체 롤백 테스트 → 동기 이벤트와 보관 처리.
7. `WorkspaceOwnershipTransferService.transferOwnership(...)`: OWNER 탈퇴에 따른 보관과 승계·녹음 처리 유지 테스트 → 동일 이벤트 연결.
8. PostgreSQL 동시성: 같은 대상 반복 확인·다른 두 대상 확인·확인과 일반 탈퇴·확인과 OWNER 승계 경합 테스트 → 필요한 잠금 조정.
9. 실제 Security·DB·Swagger 확인 및 기존 문서·Workspace 영향 범위 회귀 → API 명세·Persona 구현/리뷰 보고서 정리.

| 테스트 위치·방식 | 검증할 동작 | 기대 결과 |
| --- | --- | --- |
| `document/domain`, 단위 | 최초/반복 확인과 보관 | 최초 시각 유지, 보관 전환 한 번, 잘못된 보관 시각 거절 |
| `document/application`, Mockito | 비멤버·탈퇴자, 없는/다른 Workspace 문서, 대상이 아닌 현재 멤버 | 403·404·409, 대상 행을 임의 생성하지 않음 |
| `document/application`, DB 통합 | 확인 기록 후 집계, 마지막 대상 확인 | JPA flush 이후 실제 집계 반영, ARCHIVED·archivedAt 커밋 |
| `document/presentation`, MVC | PUT·빈 본문·Response 형식 | HTTP 200과 전체 계약 필드, confirmedAt 유지 |
| 실제 Security + DB | 정상 cookie/CSRF, 인증 실패, CSRF 실패 | 성공 200, 인증 401, CSRF 403, 실패 시 DB 변경 없음 |
| `workspace/application`, 단위·DB | 일반 탈퇴와 OWNER 승계 후 탈퇴 | 확인 대상 행 유지, confirmed는 유지, pending만 excluded로 변함 |
| Workspace DB 통합 | 마지막 멤버 탈퇴·중복 탈퇴 | soft delete와 필요한 문서 보관 함께 저장, 중복 처리로 시각 변경 없음 |
| PostgreSQL/Testcontainers | 같은 대상·마지막 두 대상 동시 확인 | 확인 시각 하나, pending 0인데 DRAFT로 남지 않음, 보관 시각 유지 |
| PostgreSQL/Testcontainers | 확인과 두 종류 탈퇴 경합 | 커밋 순서에 맞는 확인 또는 권한 거절, 최종 집계·보관 상태 일치 |
| PostgreSQL/Testcontainers | 보관 처리 중 실패 | 확인 또는 탈퇴·승계·보관 변경 전체 롤백 |
| 문서 DB 통합 | 다른 주제 Job 실패·실행 중·만료 | 성공 문서 확인 가능, 기존 확인·보관 시각 영향 없음 |

Mockito가 통과해도 실제 잠금과 커밋이 검증된 것은 아니다. 경합은 PostgreSQL에서 두 트랜잭션의 진행을 제어하고 최종 저장 상태까지 확인한다. API 반복 요청은 첫 확인 후 다른 대상이 확인한 경우도 포함해 최초 시각 보존과 현재 집계를 함께 검증한다.

디스코드 작업이 끝나고 구현을 시작하면 `backend/`에서 프로젝트 지침대로 `npx ph workflow implement`를 실행한다. 기존 Gradle/JUnit 테스트 경로를 사용해 단계별로 대상 테스트를 실행하고, 공유 Workspace 쓰기 경로 변경 이후 관련 회귀 범위를 넓힌다. 완료 시 구현·리뷰 보고서를 채우고 `npx ph workflow finish implement` 결과를 기록한다. ignored Persona 파일은 현재 프로젝트 파일을 읽어 사용하며 기존 로컬 상태를 덮어쓰지 않는다.

## 6. 완료 기준과 다음 작업

- API 한 번으로 최초 확인·현재 집계·필요한 보관이 저장되고 응답한다.
- 반복 확인은 최초 confirmedAt과 archivedAt을 유지한다. 최신 상태와 집계는 조회 시점 기준으로 반환한다.
- 일반 탈퇴와 OWNER 승계 후 탈퇴 모두 마지막 필수 대상이 사라지면 같은 트랜잭션에서 보관한다.
- Workspace 권한·문서 범위·대상 여부·CSRF가 각각 올바른 응답으로 구분되고 실패 시 데이터가 변경되지 않는다.
- 실제 PostgreSQL에서 동시 확인·탈퇴·롤백을 검증하고 기존 상세·확인 현황·Workspace 동작이 유지된다.
- 성공 문서의 확인 처리가 다른 생성 Job 상태나 보존 정리와 독립적으로 동작한다.

이 완료 기준은 저장된 문서 fixture와 실제 HTTP/Security/DB 검증 범위다. STT 공급자·자동 생성부터 이어지는 실제 녹음 전체 흐름은 #501 등 생성 실행 작업에서 연결한다.

사용자 후속 승인에 따라 이 계획을 구현하고 메서드·저장 adapter·HTTP·두 탈퇴 경로 단위로 test → feat 커밋을 작성했다. 기존 Document 생성의 긴 조건은 의미별 private 검증 메서드로 분리한 별도 refactor 커밋에 담았다. FE 계약은 `backend/docs/api/document-confirmation.md`에 저장했다. 전체 검사·Persona finish·원격 게시 결과는 아래 실행 결과에 기록한다.

### 실행 결과

- 실제 PostgreSQL에서 서로 다른 대상·같은 대상의 동시 확인, 확인이 먼저인 두 탈퇴 경로, 탈퇴가 먼저인 두 확인 경로를 검증했다. pg_stat_activity와 pg_blocking_pids로 잠금 대기를 관측했다.
- 확인 기록을 flush한 뒤 집계 실패 시 롤백을 검증했다. 두 종류 탈퇴의 보관 실패는 멤버십과 OWNER 승계까지 함께 롤백된다.
- 실제 Security·MockMvc·DB 경로에서 cookie/CSRF·401/403/404/409·반복 시각·부분 성공 문서·OpenAPI를 검증했다. 운영 호스트와 STT·자동 생성 실행기를 실행한 결과로 확대하지 않는다.
- `./gradlew check bootJar --console=plain` 성공. 단위 500·통합 218·인수 320, 총 1,038개이며 실패·오류·skip 0이다. #497 기반 대비 신규 46개(단위 15·통합 21·인수 10)다.
- Governance 단위 8개·git diff --check·FE 명세 JSON 예시 2개 파싱이 통과했다. 제품 코드의 삼항 연산자 추가는 없다.
- Persona implement rail은 통과했고 변경 Java 27개·profile·plan·roles의 evidence read를 기록했다. `npx ph workflow finish implement`는 exit 1이다. report-coverage-missing, java-role-read-coverage-missing, convention-toolchain-missing, workflow-loop-state-stale, pending-ticket이 남아 하네스 완료 인증은 미통과다. 기존 #497 등에도 기록된 로컬 하네스 상태이며 제품 테스트 성공과 구분한다. 과거 pending 티켓과 사용자 설정을 초기화하지 않았다.
