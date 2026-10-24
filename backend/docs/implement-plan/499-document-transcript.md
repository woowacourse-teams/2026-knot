# #499 문서에 연결된 전사 원문 조회 구현 계획

## 1. 범위와 브랜치

- 상태: 사용자 요청으로 개발을 재개해 Entity·V31·JPA 조회·GET 구현과 로컬 검증을 완료했다. commit/push/Draft PR 게시도 후속 요청으로 승인됐다. **(이부분 수정됨)**
- 확인일: 2026-10-07.
- 대상: [Issue #499](https://github.com/woowacourse-teams/2026-Knot/issues/499), `GET /api/v1/workspaces/{workspaceId}/documents/{documentId}/transcript`.
- 근거: 사용자가 붙여준 API·ERD, 대화에서 확정한 결정, GitHub #499, 현재 코드, 제품 기준·정합성 지침·Persona 프로필. 이번에 운영 Notion을 직접 다시 읽거나 수정하지 않았다.
- 현재 브랜치: `be/feature/#499`. HEAD와 확인한 `origin/develop`은 모두 `54b506667fd2248ce6e74ea1e272a5ec8cc77ad5`다.

### 기존 계획에서 개선한 범위 **(이부분 수정됨)**

**jyt6640이 TranscriptSegment Entity·테이블·JPA 저장/조회 기반과 문서 원문 GET을 구현한다.** 음성 담당자는 이 기반에 실제 STT 결과를 저장하는 흐름을 연결한다. 음성 담당자가 구간 Entity를 먼저 만들어야 한다는 선행 조건은 두지 않는다.

| 작업 | 이번 #499 | 후속 연결 |
| --- | --- | --- |
| TranscriptSegment Entity·테이블·저장/정렬 조회 기반 | jyt6640 구현 | 음성 담당자가 실제 결과 저장에 사용 |
| 문서의 sourceTranscriptId로 전체 원문·구간 조회 | jyt6640 구현 | FE 원문 패널에서 사용 |
| STT 호출·TranscriptionJob·실제 결과 추출/저장 실행 | 포함하지 않음 | 음성 담당 작업 |
| Transcript → TranscriptionJob 연결로 기존 FK 변경 | 포함하지 않음 | 음성 담당 작업과 별도 조율 |
| AI 문서 생성·NO_CONTENT·보존 정리 | 포함하지 않음 | #501 및 음성 연결 작업 |

문서 PR #502·#504·#506·#508·#511·#512와 탈퇴 직접 호출 수정 #518은 develop에 병합됐다. 연결 Issue #493–#498·#517은 CLOSED이며 #499·#501은 OPEN이다. #499 구현 PR은 없다. 조회 API 병합은 실제 STT·AI 실행이나 운영 배포 완료를 뜻하지 않는다.

현재 열린 PR은 FE #513·#477이며 열린 BE migration PR은 확인되지 않았다. 이미 만든 #499 브랜치를 재사용하고 PR base는 `develop`으로 한다. 개발 재개 직전에 fetch·diff로 최신 상태를 확인하며, 새 develop 변경이 있다면 사용자 변경을 보존한 상태에서 merge한다. 과거 기능 브랜치를 다시 쌓거나 force-push하지 않는다.

개발 전 두 테스트는 신규 타입이 없어 컴파일되지 않는 초안이었다. 재개 후 실제 컴파일 실패를 확인하고 초안을 완성했다. Entity·저장/조회·스냅샷·HTTP 테스트도 추가했으며 현재는 실행 가능한 상태다. 사용자 변경인 `docs/harness/notion-alignment.md`는 별도로 보존한다.

## 2. 구현할 흐름

사용자가 문서에서 원문 보기를 누르면 **그 문서를 만드는 데 사용한 Transcript의 전체 텍스트와 모든 발화 구간**을 읽는다. 같은 녹음의 최신 원문을 대신 선택하지 않는다. 현재 Workspace 멤버이면 녹음 소유자·참여 여부·문서 확인 대상 여부와 관계없이 DRAFT와 ARCHIVED 원문을 조회할 수 있다.

```text
FE: 문서에서 원문 보기
  → GET /api/v1/workspaces/{workspaceId}/documents/{documentId}/transcript
  → Security: 로그인 검사
  → DocumentController.findTranscript(...)
  → DocumentTranscriptService.find(workspaceId, memberId, documentId)
      1. 식별자와 현재 활성 Workspace 멤버 검사
      2. Workspace 범위 안의 Document.sourceTranscriptId 조회
      3. 연결 Transcript.content + 원본 녹음 길이 조회
      4. 해당 transcriptId의 구간 전체를 startMillis, position 순으로 조회
  → DocumentTranscriptResult → DocumentTranscriptResponse
  → 200: 전체 텍스트와 시각·익명 화자·문장 표시
```

### findAll의 정확한 의미 **(추가됨)**

`findAll()`로 테이블 전체를 가져오는 것이 아니다. 문서에 연결된 원문 ID로 제한해 그 원문의 모든 구간을 읽는다. 구간에는 커서 페이지네이션을 추가하지 않는다.

```java
findAllByTranscriptIdOrderByStartMillisAscPositionAsc(transcriptId)
```

긴 이름은 Spring Data JPA 내부 메서드에 두고, 도메인 Repository 인터페이스에서는 `findAllByTranscriptId(transcriptId)`로 노출하는 안을 사용한다. 이 인터페이스의 계약은 시간·동률 저장 순서 정렬을 포함한다. 전체 텍스트는 별도로 `Transcript.content`에서 읽는다.

### 붙여준 명세와 확정 계약 대조 **(이부분 수정됨)**

붙여준 명세에 남은 아래 문장은 이전 제안이다. GitHub #499와 기존 사용자 결정의 실제 시작 시각 필수 계약을 대체하는 지시로 해석하지 않는다. 아래는 반영할 수정안이며 운영 Notion에 직접 반영한 내용은 아니다.

| 명세 위치 | 붙여준 기존 내용 | 반영할 내용 |
| --- | --- | --- |
| Response → segments[].startMillis | Nullable Yes, 알 수 없으면 null | **Nullable No. 최종 오디오 기준 실제 시작 위치(ms), 0 이상. 서버가 추정하지 않는다.** |
| Response → segments / 처리 규칙 | 구간 없으면 []이며 성공 시 보장은 논의 중 | **문서에 연결된 발화가 있는 성공 원문은 비어 있지 않은 실제 구간을 반환한다.** 녹음 전사 진행 중의 []와 구분한다. |
| 결정 메모 / 논의 중 → 저장 방식 | ERD는 content TEXT만 있고 저장 포맷 미정 | **전체 원문은 Transcript.content, 발화 구간은 TranscriptSegment에 저장한다.** 확정 구조는 §4와 연결 계약을 따른다. |
| 원문 공통 계약 → transcriptId | nullable 설명을 두 응답에 공통 적용 | **문서 원문 transcriptId는 non-null.** 아직 원문 없는 녹음 응답의 nullable과 구분한다. |
| 원문 공통 계약 → 시각·구간 | startMillis와 endMillis 모두 null 허용 | **저장 완료 원문의 startMillis는 필수, endMillis·speakerNumber만 nullable.** |
| 논의 중 → 성공 시 구간 보장 | 성공 시 필수인지 다시 결정 | **필수로 확정된 요구사항으로 이동.** 실제 STT 공급자 지원 검증만 후속 연결 항목으로 남긴다. |
| Response Example → transcriptText | 두 구간 중 첫 문장만 있음 | **전체 텍스트에 두 문장을 모두 포함한다.** 구간 문자열을 조회 시 재조합하지 않는다. |
| QA | NOT_STARTED/QUEUED/RUNNING/FAILED 및 구간 없는 성공 응답 | **문서 원문 성공 구간·권한·404·읽기 전용을 검증한다.** 진행 상태의 빈 배열은 녹음 API QA로 분리한다. |
| 문서 응답 추가 필드 | 표에 있는 필드를 하단에서 다시 추가 설명 | 본문 표와 공통 규칙에 통합하고 중복 절을 제거한다. |

응답 예시는 다음과 같다. 전체 텍스트의 실제 줄바꿈·구두점은 저장된 content를 유지하며, 구간들을 합친 문자열과 바이트 단위로 같아야 한다는 새 제약은 추가하지 않는다.

```json
{
  "transcriptId": 81,
  "isPartial": false,
  "recordingDurationSeconds": 1850,
  "transcriptText": "문서 보관 기준부터 정리하겠습니다.\n좋습니다.",
  "segments": [
    {
      "startMillis": 1200,
      "endMillis": 4600,
      "speakerNumber": 1,
      "text": "문서 보관 기준부터 정리하겠습니다."
    },
    {
      "startMillis": 4800,
      "endMillis": 5700,
      "speakerNumber": 2,
      "text": "좋습니다."
    }
  ]
}
```

| 조건 | 응답 |
| --- | --- |
| 로그인하지 않음 | 401 UNAUTHENTICATED |
| 비멤버·탈퇴자·삭제된 Workspace | 403 WORKSPACE_ACCESS_DENIED. 원문 조회를 먼저 하지 않는다. |
| 문서 없음·다른 Workspace 문서·연결 원문 없음 | 404 TRANSCRIPT_NOT_FOUND |
| 잘못된 식별자 형식 또는 0 이하 | 400 INVALID_PARAMETER. 형식은 기존 전역 처리, 양수 검사는 서비스 검증 제안 |
| 유효한 저장 완료 원문과 구간 | 200, non-null 전체 텍스트·구간 배열 |

문서 내용과 원문의 문장별 대응 관계는 제공하지 않는다. 화자는 같은 원문 내 익명 번호이며 Member와 연결하지 않는다. GET은 확인 기록·보관 상태·Job·원문을 변경하지 않고 STT·AI·S3를 호출하지 않는다. CSRF 헤더는 이 GET에 추가하지 않는다.

## 3. 나올 코드와 메서드 초안

기존 `DocumentApi`에 Swagger 설명을, `DocumentController`에 실제 경로와 서비스 호출을 추가한다. `WorkspaceMemberRepository.existsByWorkspaceIdAndMemberId(...)`로 기존 활성 멤버 검사를 재사용한다. 기존 상세의 확인 집계는 호출할 필요가 없다.

아래 구조를 이번 브랜치에서 구현했다. Workspace 및 기존 문서 API의 JPA Repository/Adapter 패턴을 따른다. production JdbcClient나 새로운 전역 조회 프레임워크는 추가하지 않는다. **(이부분 수정됨)**

| 위치·클래스 | 책임 | 메서드 후보 |
| --- | --- | --- |
| recording/domain/TranscriptSegment | 구간의 유효한 값과 원문 연결을 보존하는 JPA Entity class | `create(transcriptId, position, startMillis, endMillis, speakerNumber, text)` |
| recording/domain/TranscriptSegmentRepository | 구간 저장과 해당 원문의 정렬 조회 계약 | `saveAll(segments)`, `findAllByTranscriptId(transcriptId)` |
| recording/infrastructure/TranscriptSegmentJpaRepository | Spring Data JPA 저장·정렬 조회 | `findAllByTranscriptIdOrderByStartMillisAscPositionAsc(...)` |
| recording/infrastructure/TranscriptSegmentRepositoryAdapter | Repository 계약을 JPA로 구현 | `saveAll(...)`, `findAllByTranscriptId(...)` |
| recording/application/dto/result/TranscriptSegmentResult | 공통 시간·익명 화자·문장 조회 값 | `from(segment)` |
| document/application/DocumentTranscriptService | 식별자·활성 멤버 검사와 조회 사용 사례 | `find(workspaceId, memberId, documentId)` |
| document/application/DocumentTranscriptQuery | 문서에 연결된 원문 조회 계약 | `find(workspaceId, documentId) → Optional<DocumentTranscriptSnapshot>` |
| document/infrastructure/DocumentTranscriptReadJpaRepository | Workspace·Document·Transcript·녹음 길이 헤더 projection | `findSource(workspaceId, documentId)` |
| document/infrastructure/DocumentTranscriptQueryAdapter | 헤더 조회 후 구간 Repository 호출·결과 조합 | `find(...)` |
| document/application/dto/result | 원문 조회 스냅샷과 초 단위 서비스 결과 | `DocumentTranscriptSnapshot`, `DocumentTranscriptResult.from(snapshot)` |
| document/presentation/dto/response | 전체 응답 및 별도 파일의 구간 응답 DTO | `DocumentTranscriptResponse.from(result)` |
| document/presentation/DocumentApi·DocumentController | Swagger·경로·응답 변환 | `findTranscript(...)` |
| document/domain/DocumentErrorCode | 원문 없음 오류 | `TRANSCRIPT_NOT_FOUND` 추가 |

헤더 JPQL projection에는 필요한 `DocumentTranscriptSourceRow` 한 타입만 추가한다. sourceTranscriptId를 기준으로 연결하고 녹음 길이는 `RecordingSession.accumulatedRecordingMillis`에서 읽는다. Query Adapter가 녹음 도메인의 구간 Repository를 사용하므로 document의 Repository에 구간 저장 책임을 복제하지 않는다.

Entity는 현재 녹음 Entity처럼 `transcriptId` 식별자로 연결하고, 이번 조회를 위해 Transcript에 양방향 컬렉션을 추가하지 않는다. 공통 구간 Result는 recording 안에 두며, 문서 Response는 응답 형태만 변환한다. 아직 없는 녹음 원문 API의 전체 Response·Controller까지 선제 구현하지 않는다.

`DocumentTranscriptService.find(...)`는 식별자 검증 → 멤버 검사 → 연결 원문 조회 → 저장 계약 검사 → 응답용 결과 반환 순서다. `validateIdentifiers`, `validateWorkspaceAccess`, `validateStoredTranscript`처럼 규칙별 private 메서드로 표현한다. 삼항 연산자·긴 복합 if·중첩 타입을 사용하지 않으며 클래스/인터페이스 선언 뒤 빈 줄과 Workspace 필드 배치 컨벤션을 따른다.

### 트랜잭션 경계 **(이부분 수정됨)**

| 경계 | 함께 묶을 동작 | 적용 위치 |
| --- | --- | --- |
| #499 읽기 | 멤버 검사 + 원문 헤더 + 해당 원문의 구간 조회 | DocumentTranscriptService의 `readOnly=true, isolation=REPEATABLE_READ` |
| 실제 전사 결과 저장 | 전체 Transcript + 모든 구간 저장 + 저장 완료 결과 공개 | 음성 담당자의 저장 사용 사례. #499에 실행 서비스를 만들지 않음 |
| 생성·보존 정리 | Document 생성/연결 및 참조 보호·정리 | #501 후속 작업 |

두 조회가 서로 다른 시점의 데이터로 섞이지 않도록 기존 DocumentConfirmationService와 같은 읽기 격리 수준을 제안한다. 쓰기 잠금과 비동기 이벤트는 필요하지 않다. readOnly는 입력 자료가 완전함을 보장하지 않으므로 저장 측의 원자적 공개와 조회 시 유효성 검사를 함께 둔다.

## 4. 저장 기반과 주의할 점

작업 시작 시 develop의 Transcript는 `id`, `recordingSessionId`, `content`, `createdAt`만 가진다. 전체 텍스트와 Document의 연결은 V27에 있으며 TranscriptSegment는 없었다. 이번 브랜치에 Entity·Repository·V31을 추가했다. Transcript의 기존 연결과 content를 유지하고 구간 테이블을 추가한다. **(이부분 수정됨)**

| 컬럼 | PostgreSQL 타입 | NULL | 제약·의미 |
| --- | --- | --- | --- |
| id | BIGINT GENERATED ALWAYS AS IDENTITY | NOT NULL | PK |
| transcript_id | BIGINT | NOT NULL | FK → transcripts.id, ON DELETE RESTRICT |
| position | INTEGER | NOT NULL | 0 이상, UNIQUE(transcript_id, position). 공급자 결과의 저장 순서 |
| start_millis | BIGINT | NOT NULL | 0 이상. 실제 발언 시작 위치(ms) |
| end_millis | BIGINT | NULL | NULL 또는 start_millis 이상 |
| speaker_number | INTEGER | NULL | NULL 또는 1 이상. 원문 내 익명 번호 |
| text | TEXT | NOT NULL | 공백만 있는 값 금지. Java 검증과 DB CHECK의 허용 범위를 맞춰 테스트 |

테이블명은 `transcript_segments`, 관계는 Transcript 1:N TranscriptSegment다. `(transcript_id, start_millis, position)` 인덱스를 정렬 조회에 맞춰 추가하는 안이다. Entity와 DB에서 숫자·종료 범위·빈 문장 규칙을 각각 검증한다. 중복 position과 없는 Transcript 참조는 DB에서도 거절한다.

**Transcript.content에 전체 텍스트는 계속 저장한다.** 구간 조회마다 전체 텍스트를 합성하거나 기존 content를 삭제하지 않는다. `isPartial=false`는 현재 응답 계약에 적용하며 이 값을 반환하려고 신규 is_partial 컬럼을 추가하지 않는다.

행별 CHECK와 FK만으로 원문에 최소 한 구간이 존재한다는 조건을 보장할 수는 없다. 저장 담당자는 전체 텍스트와 구간을 한 트랜잭션으로 저장하고 완성된 결과만 문서 생성에 넘긴다. 빈 성공 구간을 방지하기 위한 전체 저장 실행기·완료 상태는 음성 연결 작업에서 구현한다.

### 기존 데이터와 오류 처리 제안

발화가 있는 저장 원문에 전체 텍스트 또는 구간이 없다면 정상 200이나 404로 감추지 않는다. 조회 서비스는 기존 도메인 오류 패턴에 맞춰 DocumentException(DocumentErrorCode.INVALID_TRANSCRIPT_DATA)을 던지고, GlobalExceptionHandler가 ErrorCategory.INTERNAL_SERVER_ERROR를 HTTP 500으로 변환한다. 응답 코드는 INVALID_TRANSCRIPT_DATA, 공개 메시지는 “문서 원문을 불러올 수 없습니다”다. 예상하지 못한 일반 예외는 기존 INTERNAL_SERVER_ERROR로 처리한다. 응답에는 원문 내용이나 누락 위치 등 내부 정보를 노출하지 않는다. 2026-10-07 사용자가 요청한 도메인 예외 통일을 반영했다. **(이부분 수정됨)**

새 테이블 migration이 기존 전체 텍스트만 있는 Transcript를 자동으로 보정한다고 가정하지 않는다. 배포 전 실제 기존 데이터의 존재·실제 타임스탬프 재수집 가능성을 확인하고, 필요하면 음성 담당자의 데이터 전환 작업을 연결한다. 그 확인을 못 했다는 이유로 가짜 0 시각을 넣거나 필수 계약을 완화하지 않는다. 실제 운영 DB와 STT 지원은 아직 관측하지 않았다.

### Migration·보존

확인한 최신 버전은 V30이며 V27 문서 기반·V28 실패 보존·V30 재시도 횟수를 재사용한다. 미병합 제목용 V29와 실행 접수 테이블용 V31은 현재 없다. `V31__create_transcript_segments.sql`을 추가했고 Testcontainers의 Flyway 적용과 Entity 매핑을 검증했다. 운영 DB 적용은 관측하지 않았다. 병합 전 다른 migration PR이 생기면 번호 충돌을 다시 확인한다. 테이블 migration과 조회 코드는 같은 #499 PR에서 리뷰하고 배포 시 migration이 먼저 적용돼야 한다.

FK 삭제 정책은 확정한 RESTRICT를 적용한다. 이 GET이 구간이나 원문을 삭제하지 않는다. #501은 삭제 가능한 원문의 구간부터 정리해야 하며, 성공 Document 또는 실행 중 Job이 참조하는 원문·구간은 보호해야 한다. 원본 오디오 존재·다른 Job 만료 여부를 조회의 조건으로 넣지 않는다. NO_CONTENT는 Document를 만들지 않는 별도 결과이므로 이 API의 빈 성공 응답으로 표현하지 않는다.

## 5. TDD와 검증 순서

`npx ph workflow implement`와 프로필·컨벤션을 확인한 뒤 아래 순서로 개발했다. 테스트 선행 컴파일 실패와 HTTP 경로 미구현 실패를 확인하고 구현 후 통과했다. 모든 단계의 런타임 RED를 각각 관측했다는 의미는 아니다. **(이부분 수정됨)**

1. **TranscriptSegment.create 실패 테스트 → Entity 구현.** 정상 구간·시작 0·nullable 종료/화자 성공, 음수·종료 역전·화자 0·빈 문장 실패를 검증한다.
2. **PostgreSQL 저장 제약 테스트 → migration·saveAll 구현.** FK, 중복 position, NOT NULL, CHECK, RESTRICT와 저장 후 값 보존을 검증한다. 실패 입력은 DB가 실제 거절해야 한다.
3. **findAllByTranscriptId 정렬 테스트 → JPA 조회·Adapter 구현.** 다른 원문의 구간을 제외하고, 역순 저장·동률에서도 startMillis/position 순으로 전부 반환한다.
4. **문서 원문 Query 테스트 → 헤더 JPQL·Query Adapter 구현.** 같은 녹음의 여러 원문 중 Document.sourceTranscriptId만 선택하고 Workspace 범위를 적용한다. 원문이 없으면 Optional.empty다.
5. **DocumentTranscriptService.find 테스트 → Service·Result 구현.** 멤버 검사 순서·403/404·식별자·구간 필수·초 변환을 검증한다. 기존 초안을 완성하고 타입·테스트 실행 상태를 기록한다.
6. **Response·HTTP 테스트 → DTO·DocumentApi·Controller 구현.** JSON 필드와 nullable을 맞추고 기존 Security를 포함한 인수 테스트로 로그인·후발 멤버·탈퇴자·DRAFT/ARCHIVED·CSRF 없는 GET을 검증한다.
7. **읽기 일관성·부작용 검증 → 명세·보고서 정리.** 새 변경에 필요한 공통 검증을 완료하고 구현 기준 API 문서, Persona implementation/review 보고서와 finish 결과를 남긴다.

| 테스트 위치·방식 | 검증할 동작 | 기대 결과 |
| --- | --- | --- |
| TranscriptSegment 단위 테스트 | create 값 검증 | 정상 값 보존, 각 의미 있는 잘못된 값 거절 |
| TranscriptSegmentRepositoryIntegrationTest, PostgreSQL/Testcontainers | FK·UNIQUE·CHECK·삭제 보호·저장/정렬 조회 | DB 제약 적용, 원문 하나의 구간만 정렬 반환 |
| DocumentTranscriptQueryIntegrationTest, PostgreSQL | sourceTranscriptId와 Workspace 범위 | 최신 원문 대신 정확한 연결 원문, 다른 원문 구간 혼입 없음 |
| DocumentTranscriptServiceTest, Mockito | 권한·없음·구간 필수·식별자·단위 변환 | 조회 없는 403, 원문 없음 404, 저장 위반 예외, 1,850,999ms → 1850초 |
| DocumentTranscriptResponseTest | 전체 텍스트·구간 DTO·불변 목록 | 저장된 원문 유지, startMillis=0 보존, endMillis/speakerNumber null 보존 |
| Controller/MVC 및 실제 Security 인수 테스트 | 정상 JSON·401·403·404·400·비참여/후발 멤버 | 명세대로 응답, 현재 멤버 읽기 허용, GET에 CSRF 요구 없음 |
| PostgreSQL 읽기 검증 | 헤더와 구간 조회 사이 다른 트랜잭션 변경 | REPEATABLE_READ 제안이 동일 시점 조회를 보장하는지 검증. Mockito로 입증하지 않음 |
| DB 인수 테스트의 전후 상태 비교 | 문서·확인·Job·Transcript·segments | GET으로 상태/시각/구간/행 수를 바꾸지 않음 |
| 음성 연결 및 #501 후속 검증 | 실제 STT 결과·두 원문 API 일치·보존 정리 | 실제 시각/화자/문장 일치 및 성공 문서 원문 보호 |

통합 테스트는 현재 프로젝트의 PostgreSQL 18 Testcontainers와 Flyway를 사용한다. 실제 DB를 테스트 대상으로 사용하거나 H2로 대체하지 않는다. 현재 Gradle이 integration/acceptance를 기본 test에서 제외하므로 따로 실행한다. 타입과 테스트가 구현된 뒤의 집중 검증 명령은 다음과 같다.

```bash
./gradlew test --tests '*TranscriptSegment*' --tests '*DocumentTranscript*'
./gradlew integrationTest --tests '*TranscriptSegmentRepositoryIntegrationTest' --tests '*DocumentTranscriptQueryIntegrationTest'
./gradlew acceptanceTest --tests '*DocumentTranscriptAcceptanceTest'
```

마지막에는 관련 공통 변경을 확인하는 `./gradlew check bootJar`와 현재 Eclipse 설정의 Spotless 검사, `npx ph workflow finish implement`를 수행한다. refactor는 통과한 동작을 개선할 필요가 생길 때만 추가한다. 커밋 요청을 받으면 위 공개 메서드·schema·adapter·HTTP·문서 경계로 test → feat → 필요한 refactor를 나누며, 이번 계획만으로 commit/push/PR 권한을 해석하지 않는다.

## 6. 완료 기준과 다음 작업

#499의 완료는 **확정 저장 기반을 만들어 문서의 정확한 전체 원문과 모든 구간을 현재 멤버에게 읽기 전용으로 반환하고, JPA/PostgreSQL·실제 Security·JSON 검증과 API 문서가 일치하는 것**이다. 저장 완료 원문의 실제 시작 시각·구간 필수 계약을 빈 배열로 대신하지 않는다.

개발 재개 순서는 **기존 #499 브랜치 최신 상태 확인 → Entity·테이블 → 원문 ID별 구간 조회 → 문서 연결 Query → Service·DTO → 기존 Api/Controller → 인수 검증·문서 정리**다. 음성 담당자가 실제 STT 저장 실행을 연결하기 전에도 fixture로 이 범위를 개발·검증할 수 있다. **(이부분 수정됨)**

후속 순서는 음성 담당자의 실제 STT 타임스탬프 검증·저장 연결 → #501의 저장 완료 원문 기반 자동 문서 생성 → 녹음/문서 두 원문 API와 FE의 실제 전체 흐름 확인이다. fixture 기반 #499 완료와 실제 녹음 → 전사 → 문서 → 원문 패널의 완료를 구분해 보고한다.

[전사 저장 연결 계약](499-transcript-storage-handoff.md)은 이 계획과 같은 schema·필수 시각·담당 경계를 사용한다. 현재 제품 코드·V31·테스트·구현 기준 명세를 작성했다. `spotlessApply check bootJar`는 단위 641·통합 288·인수 386 = 1,315개, 실패·오류·skip 0으로 통과했다. Governance 단위 검사 8개와 문서 JSON·diff 공백 검사도 통과했다. Persona finish는 기존 전역 보고서·role coverage·toolchain·stale loop·pending-ticket으로 exit 1이다. 기존 전역 상태는 초기화하지 않았으며 제품 검사와 종료 인증을 구분한다. 원격 PR 상태는 게시 완료 후 별도 기록한다. Notion 직접 게시·실제 STT 지원·운영 배포는 수행하지 않았다.
