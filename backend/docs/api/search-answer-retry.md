# 탐색 실패 답변 재시도 API

`POST /api/v1/workspaces/{workspaceId}/search/conversations/{conversationId}/questions/{questionId}/retry`

- #569 구현 계약. 구형 ChatSession API와 별개다.
- 브라우저는 `credentials: "include"`로 쿠키를 전송한다. 운영 기본 허용 Origin은 `https://knoted.kr`이며 배포 설정으로 바꿀 수 있다.
- 먼저 `GET /api/v1/auth/csrf`로 CSRF 토큰과 쿠키를 받고 변경 요청에 토큰 헤더를 보낸다.

## Header

| Key | Type | Required | Example | Description |
| --- | --- | --- | --- | --- |
| Content-Type | String | Yes | application/json | UUID를 담은 JSON 요청 |
| Accept | String | Yes | text/event-stream | POST 응답의 SSE를 fetch 스트림으로 읽음 |
| Cookie | String | Yes | __Host-KNOT_ACCESS_TOKEN=…; XSRF-TOKEN=… | 로그인·CSRF 쿠키. 실제 토큰을 JS가 직접 조립하지 않음 |
| X-XSRF-TOKEN | String | Yes | csrf-token-value | CSRF 조회 응답에서 받은 토큰 |

## Path Parameter

| Key | Type | Required | Example | Description |
| --- | --- | --- | --- | --- |
| workspaceId | Long | Yes | 1 | 현재 멤버가 참여한 Workspace |
| conversationId | Long | Yes | 10 | 현재 멤버 소유의 대화 |
| questionId | Long | Yes | 21 | 기존 USER 질문 ID. 바로 다음 sequence의 FAILED ASSISTANT 답변을 재사용 |

## Query Parameter

| Key | Type | Required | Example | Description |
| --- | --- | --- | --- | --- |
| 없음 | - | - | - | Query Parameter 없음 |

## Request Body

| Field | Type | Required | Nullable | Description | Example |
| --- | --- | --- | --- | --- | --- |
| requestId | String | Yes | No | 엄격한 8-4-4-4-12 형식 UUID. 같은 재시도 요청 재전송 때 유지 | 550e8400-e29b-41d4-a716-446655440000 |

### Request Example

```json
{"requestId":"550e8400-e29b-41d4-a716-446655440000"}
```

질문 content는 보내지 않는다. 기존 질문 원문을 다시 사용한다.

## Response

### Status Code

| Status | Description |
| --- | --- |
| 200 | Content-Type: text/event-stream;charset=UTF-8. Cache-Control: no-cache, X-Accel-Buffering: no |
| 400/401/403/404/409/500 | 접수 전 오류는 JSON. 아래 오류 표 참고 |

### Response Body

| Event | Field | Type | Nullable | Description | Example |
| --- | --- | --- | --- | --- | --- |
| accepted | questionId | Long | No | 재사용한 USER 질문 ID | 21 |
| accepted | answerMessageId | Long | No | 재사용한 ASSISTANT 답변 ID | 22 |
| accepted | status | String | No | 접수 상태. 현재 종료 상태는 후속 이벤트로 확인 | STREAMING |
| progress | stage | String | No | 생성 진행 상태 | SEARCHING / GENERATING |
| delta | answerMessageId, text | Long, String | No | 이 시도의 답변 본문 추가분 | 22, 답변 내용 |
| evidence | answerMessageId, items | Long, Array | No | 실제 사용 근거 중 관련도 순 표시 카드 최대 3개. 없으면 빈 배열 | 22, [] |
| excluded_documents | count | Integer | No | 생성 완료 DRAFT·ARCHIVED 직접 검색 기준 0. 생성 Job 수를 세지 않음 | 0 |
| completed | answerMessageId, status | Long, String | No | 정상 종료 | 22, COMPLETED |
| failed | answerMessageId, status, code | Long, String, String | No | 접수 후 생성 실패. 저장된 부분 본문 유지 | 22, FAILED, ANSWER_GENERATION_FAILED |
| stopped | answerMessageId, status | Long, String | No | 사용자 명시 중지 | 22, STOPPED |

근거 카드 `items[]`의 필드는 `documentId: Long`, `title: String`, `topic: String`, `rank: Integer`이며 모두 null이 아니다. 실제 사용 근거 연결은 전체 보존하고 화면 카드만 최대 세 개를 반환한다. Transcript·인용 원문을 별도 근거 필드로 반환하지 않는다.

### Response Example

```text
event:accepted
data:{"questionId":21,"answerMessageId":22,"status":"STREAMING"}

event:progress
data:{"stage":"GENERATING"}

event:delta
data:{"answerMessageId":22,"text":"문서에서 확인한 내용입니다."}

event:evidence
data:{"answerMessageId":22,"items":[]}

event:completed
data:{"answerMessageId":22,"status":"COMPLETED"}

```

위 예시는 필드 형식을 설명한다. progress는 polling 시점에 따라 중간 단계를 생략할 수 있다. excluded_documents도 현재 시도에 저장된 수가 있을 때 전송한다. completed/failed/stopped 뒤 연결을 닫는다.

## Error Response

| Status | Error Code | Description |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | requestId 누락·null·UUID 형식 오류 |
| 400 | INVALID_REQUEST_BODY | JSON 파싱 오류 또는 requestId가 문자열이 아님 |
| 400 | INVALID_PARAMETER | 경로 ID 형식 오류 또는 0 이하 |
| 401 | UNAUTHENTICATED | 로그인하지 않음 |
| 403 | FORBIDDEN | CSRF 검증 실패 |
| 403 | SEARCH_ACCESS_DENIED | 현재 Workspace 멤버가 아니거나 대화 소유자가 아님 |
| 404 | QUESTION_NOT_FOUND | 대화·질문이 없거나 해당 대화의 USER 질문과 다음 ASSISTANT 쌍이 아님 |
| 409 | ANSWER_NOT_FAILED | 신규 재시도 대상 답변이 FAILED가 아님 |
| 409 | ANSWER_ALREADY_IN_PROGRESS | 같은 대화에 STREAMING 답변이 있음 |
| 409 | REQUEST_ID_CONFLICT | 같은 Member·Workspace의 RETRY requestId를 다른 질문·대화에 사용 |
| 500 | ANSWER_GENERATION_UNAVAILABLE | 생성 adapter 또는 SSE 처리 자리가 준비되지 않음 |
| 500 | INTERNAL_SERVER_ERROR | 접수 저장 등 예상하지 못한 오류 |

오류는 `code`, `message`를 가진 JSON이며 비어 있는 `fieldErrors`는 생략한다.

## 접수·복구 규칙

- 2026-10-14 #670: 재시도는 새 실행 시작 시각까지 생성된 현재 Workspace의 DRAFT·ARCHIVED를 개인 확인 여부와 무관하게 다시 탐색한다. 확인·보관 상태를 변경하지 않는다.

1. 현재 멤버십·소유권은 매 요청에서 검증한다. 동일 요청 복구도 접근 검사를 건너뛰지 않는다.
2. 새 requestId는 기존 FAILED 답변의 content·오류·근거와 생성 진행 정보를 원자적으로 초기화한다. 질문·답변 ID, sequence, createdAt은 유지한다.
3. 동일 RETRY requestId·대상은 진행 중 검사와 생성 가용성 검사보다 먼저 기존 접수를 반환한다. 작업·USER·ASSISTANT를 추가하지 않는다. 기록은 대화 보존 기간 동안 유지한다.
4. `accepted`를 받으면 같은 answerMessageId의 기존 실패 본문을 화면에서 비우고 이 시도의 delta부터 표시한다. 동일 요청 재전송도 버퍼를 교체한다.
5. 답변이 다른 재시도로 교체된 오래된 요청은 accepted 뒤 새 시도의 본문·근거·종료 이벤트 없이 EOF로 닫힌다. 현재 답변이 필요하면 `GET /api/v1/workspaces/{workspaceId}/search/conversations/{conversationId}/answers/{answerId}/events`의 snapshot으로 복구한다.
6. 연결 종료는 생성을 취소하지 않는다. 실패·timeout 시 부분 본문을 보존하고 사용자가 새 requestId로 명시 재시도한다. 자동 재전송은 없다.
7. 첫 실패 대화는 재접수만으로 목록에 노출하지 않는다. 재시도 성공 완료 후 목록에 다시 나타난다. 과거 질문의 재시도는 해당 질문보다 앞선 COMPLETED·STOPPED 문맥만 사용한다.

## 확인 근거

- SearchQuestionController.retry·SearchQuestionApi·SearchAnswerRetryRequest, SearchQuestionExceptionHandler, SecurityConfig, JwtProperties 및 application.properties.
- SearchAnswerRetryIntegrationTest, SearchAnswerRetryConnectionTest, SearchAnswerRetryAcceptanceTest의 PostgreSQL·실제 로컬 HTTP/SSE 관측.
- 운영 proxy·배포와 실제 LLM의 의미적 답변 품질은 이번 API 검증 범위에 포함하지 않는다.
