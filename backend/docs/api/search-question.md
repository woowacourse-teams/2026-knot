# 기존 탐색 대화 후속 질문 API

`POST /api/v1/workspaces/{workspaceId}/search/conversations/{conversationId}/questions`

확인일: 2026-10-12. `be/feature/#566`의 로컬 구현 계약이다. develop 병합·운영 배포 상태를 뜻하지 않는다.
기존 개인 대화에 질문·답변을 추가하고 첫 질문의 생성 작업·SSE를 재사용한다.
2026-10-14 #670: 현재 Workspace의 생성 완료 DRAFT·ARCHIVED Document를 개인 확인 여부와 무관하게 탐색한다.
새 탐색 실행 시작 시각까지 생성된 문서를 포함한다. Transcript·오디오·생성 중/실패 Job은 직접 검색하지 않는다.
별도 색인이 없어 `excluded_documents.count=0`이다. 현재 기본 시스템 프롬프트의 실제 참조 문서는 최대 3개이며,
저장 구조는 전체 사용 근거를 보존하고 응답 카드는 질문 관련도 순으로 최대 3개다.

브라우저는 `credentials: "include"`로 쿠키를 전송하고 fetch 응답 스트림으로 POST SSE를 읽는다.
먼저 `GET /api/v1/auth/csrf`에서 `token`과 `XSRF-TOKEN` cookie를 받은 뒤 변경 요청에 `X-XSRF-TOKEN`을 보낸다.
허용 Origin은 서버 CORS 설정을 따른다. 기본 worker·agent·LLM은 비활성이라 활성화 전 신규 접수는 500이다.

## Header

| Key | Type | Required | Example | Description |
| --- | --- | --- | --- | --- |
| Cookie | String | Yes | access cookie와 XSRF-TOKEN cookie | access cookie 기본 이름 __Host-KNOT_ACCESS_TOKEN, 로컬 테스트 KNOT_ACCESS_TOKEN. 브라우저 자동 전송 |
| Content-Type | String | Yes | application/json | JSON 본문 |
| Accept | String | Yes | text/event-stream | 성공 SSE, 접수 전 오류 JSON |
| X-XSRF-TOKEN | String | Yes | CSRF 조회 응답 token | XSRF-TOKEN cookie와 함께 전송 |

## Path Parameter

| Key | Type | Required | Example | Description |
| --- | --- | --- | --- | --- |
| workspaceId | Long | Yes | 12 | 현재 활성 멤버인 Workspace. 1 이상 |
| conversationId | Long | Yes | 301 | 같은 Workspace에 속한 본인 대화. 1 이상 |

## Query Parameter

| Key | Type | Required | Example | Description |
| --- | --- | --- | --- | --- |
| 없음 | - | - | - | Query Parameter 없음 |

## Request Body

| Field | Type | Required | Nullable | Description | Example |
| --- | --- | --- | --- | --- | --- |
| content | String | Yes | No | 공백이 아닌 질문, 공백 포함 최대 2,000 Unicode 코드 포인트. 원문 보존 | 그 결정은 왜 바뀌었어? |
| requestId | UUID String | Yes | No | 엄격한 8-4-4-4-12 UUID. 대화 보존 기간 동안 접수 기록 유지 | aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee |

### Request Example

```json
{"content":"그 결정은 왜 바뀌었어?","requestId":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"}
```

## Response

### Status Code

| Status | Description |
| --- | --- |
| 200 | 접수 커밋 뒤 SSE 연결. 생성 실패도 failed 이벤트로 종료 |
| 400 / 401 / 403 / 404 / 409 / 500 | 접수 전 JSON 오류. 새 질문·답변·작업을 남기지 않음 |

응답 헤더: `Content-Type: text/event-stream;charset=UTF-8`, `Cache-Control: no-cache`, `X-Accel-Buffering: no`.

### Response Body

| Event | Data 필드 | Nullable | Description | Example |
| --- | --- | --- | --- | --- |
| accepted | conversationId, questionMessageId, answerMessageId: Long | No | 첫 이벤트. 기존 대화 ID와 이번 접수의 질문·답변 ID | 아래 예시 |
| progress | stage: String | No | SEARCHING / GENERATING. 빠른 작업은 중간 단계 생략 가능 | GENERATING |
| delta | answerMessageId: Long, text: String | No | 저장된 부분 본문. 인접 조각은 합쳐질 수 있음 | 회의 기록에 따르면… |
| evidence | answerMessageId: Long, items: Array | No | 실제 사용 문서 중 관련도 순 최대 3개. 없으면 [] | 아래 예시 |
| excluded_documents | count: Integer | No | 이번 DB 직접 검색은 0. 생성 Job 수를 세지 않음. 재연결 API에서는 전송하지 않음 | 0 |
| completed | answerMessageId: Long, status: String | No | 정상 종료 COMPLETED | COMPLETED |
| failed | answerMessageId: Long, status: String, code: String | No | FAILED, 부분 본문·질문 보존. 공급자 오류 원문 노출 안 함 | ANSWER_GENERATION_FAILED |
| stopped | answerMessageId: Long, status: String | No | 명시적 중지로 STOPPED 종료 | STOPPED |

`evidence.items[]` 필드:

| Field | Type | Nullable | Description | Example |
| --- | --- | --- | --- | --- |
| documentId | Long | No | 현재 Workspace의 실제 사용 Document ID | 81 |
| title | String | No | 현재 문서 제목 | 보관 정책 |
| topic | String | No | 현재 문서 주제 | 문서 정책 |
| rank | Integer | No | 반환 순위 1..3 | 1 |

### Response Example

```text
event:accepted
data:{"conversationId":301,"questionMessageId":603,"answerMessageId":604}

event:excluded_documents
data:{"count":0}

event:delta
data:{"answerMessageId":604,"text":"회의 기록에 따르면 조건 변경으로 결정이 수정됐습니다."}

event:evidence
data:{"answerMessageId":604,"items":[{"documentId":81,"title":"보관 정책","topic":"문서 정책","rank":1}]}

event:completed
data:{"answerMessageId":604,"status":"COMPLETED"}

```

필드 출력 순서·delta 개수·중간 progress 단계는 보장하지 않는다. 종료 이벤트 뒤 EOF다.

## Error Response

| Status | Error Code | Description |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | 공백·누락·2,000 코드 포인트 초과 또는 UUID 오류 |
| 400 | INVALID_REQUEST_BODY | 잘못된 JSON, 문자열 필드의 숫자·불리언·배열·객체 |
| 400 | INVALID_PARAMETER | 경로 타입·양수 범위 오류 |
| 401 | UNAUTHENTICATED | 유효 CSRF가 있으나 access cookie 인증 없음 |
| 403 | FORBIDDEN | CSRF 누락·불일치. 인증도 빠지면 Security 필터 우선순위 적용 |
| 403 | SEARCH_ACCESS_DENIED | 현재 Workspace 멤버 아님, 삭제된 Workspace, 다른 소유자 또는 Workspace 대화 |
| 404 | CONVERSATION_NOT_FOUND | 현재 멤버이나 해당 대화 없음 |
| 409 | ANSWER_ALREADY_IN_PROGRESS | 새 requestId인데 같은 대화의 ASSISTANT가 STREAMING. QUEUED 작업도 포함 |
| 409 | REQUEST_ID_CONFLICT | 같은 범위·requestId에 다른 질문 원문 사용 |
| 500 | ANSWER_GENERATION_UNAVAILABLE | 신규 생성 준비 또는 SSE 용량 부족 |
| 500 | INTERNAL_SERVER_ERROR | 접수 저장 실패·깨진 저장 대화. 전체 rollback |

오류 JSON의 `code`, `message`를 처리한다. 접수 전 오류에서는 입력을 복원하고 자동 재전송하지 않는다.

### 재전송·문맥·생성 수명

- 멱등 범위는 Workspace·Member·QUESTION·conversationId·requestId다. FIRST_QUESTION 및 다른 대화와 독립적이다.
- 같은 키·같은 원문은 새 메시지나 작업을 만들지 않고 기존 접수 ID를 반환한다. 진행 중 검사·생성 가용성보다 먼저 복구한다. 권한 검사는 항상 먼저 수행한다.
- 재전송의 accepted 뒤에는 해당 답변의 저장된 전체 본문부터 delta로 보낸다. **클라이언트는 해당 answerMessageId의 기존 버퍼를 교체하고 새 delta를 붙인다.** 기존 버퍼에 그대로 더하면 중복된다.
- 단절 후 answerId를 알면 [답변 재연결 API](search-answer-events.md)의 snapshot + 이후 delta로 복구할 수 있다.
- 후속 질문에는 이전 COMPLETED·STOPPED와 그 질문을 최신 20개 메시지 범위에서 제공한다. 현재 질문·생성 중·FAILED 쌍·미래 메시지·다른 대화는 제외한다. 이력은 질문 해석용이며 사실의 근거는 현재 읽은 문서다.
- 같은 대화에서 새 질문 하나만 접수한다. 다른 대화는 같은 Workspace에서도 독립적으로 생성할 수 있다.
- 연결 종료·화면 이동은 생성을 취소하지 않는다. 명시적 [답변 중지 API](search-answer-stop.md)만 생성 작업을 중지한다.
- 후속 실패는 USER와 부분 본문·FAILED/code를 보존하고 기존 성공 대화를 숨기지 않는다. 최초 답변 실패의 목록 숨김 정책은 유지한다.
- 탐색·중지·실패는 녹음 세션 상태를 변경하지 않는다.

로컬 검증은 실제 HTTP·PostgreSQL과 로컬 MCP/LLM 공급자 stub이다. 실모델 답변 품질·운영 배포를 검증한 결과로 해석하지 않는다.
