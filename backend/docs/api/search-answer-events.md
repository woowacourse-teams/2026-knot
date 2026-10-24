# 탐색 답변 SSE 재연결 API

`GET /api/v1/workspaces/{workspaceId}/search/conversations/{conversationId}/answers/{answerId}/events`

확인일: 2026-10-11. #567 브랜치의 Controller·Security·snapshot 조회·SSE 전송 및 로컬 HTTP 테스트를 기준으로 한다. 운영 배포 상태를 의미하지 않는다.

## Header

| Key | Type | Required | Example | Description |
| --- | --- | --- | --- | --- |
| Cookie | String | Yes | `__Host-KNOT_ACCESS_TOKEN=…` | access cookie. 로컬 테스트에서는 설정된 `KNOT_ACCESS_TOKEN` 사용 |
| Accept | String | Yes | `text/event-stream` | SSE 응답 선택. 브라우저는 credentials 포함 |

GET에는 X-XSRF-TOKEN과 Content-Type이 필요하지 않다.

## Path Parameter

| Key | Type | Required | Example | Description |
| --- | --- | --- | --- | --- |
| workspaceId | Long | Yes | 1 | 현재 Workspace |
| conversationId | Long | Yes | 40 | 현재 Member 소유 대화 |
| answerId | Long | Yes | 50 | 해당 대화의 ASSISTANT 메시지 |

## Query Parameter

| Key | Type | Required | Example | Description |
| --- | --- | --- | --- | --- |
| 없음 | - | - | - | 커서·이벤트 이력 replay 없음 |

## Request Body

| Field | Type | Required | Nullable | Description | Example |
| --- | --- | --- | --- | --- | --- |
| 없음 | - | - | - | 요청 본문 없음 | - |

### Request Example

```http
GET /api/v1/workspaces/1/search/conversations/40/answers/50/events
Accept: text/event-stream
Cookie: __Host-KNOT_ACCESS_TOKEN=…
```

## Response

### Status Code

| Status | Description |
| --- | --- |
| 200 | `text/event-stream;charset=UTF-8`, `Cache-Control: no-cache`, `X-Accel-Buffering: no` |

### Response Body

| Event | Fields | Nullable | Description |
| --- | --- | --- | --- |
| snapshot | answerId: Long, content: String, status: String | No | 첫 데이터 이벤트. 현재 저장 본문·상태. 빈 생성 본문 허용 |
| delta | answerId: Long, text: String | No | 같은 실행에서 snapshot 이후 저장된 추가 문자열 |
| evidence | answerId: Long, items: Array | No | 저장 근거가 있으면 관련도 순 최대 3개 카드 |
| completed | answerId: Long, status: String | No | status=COMPLETED, 이후 EOF |
| failed | answerId: Long, status: String, code: String | No | status=FAILED와 저장 오류 code. 기존 code 없는 답변은 ANSWER_GENERATION_FAILED |
| stopped | answerId: Long, status: String | No | status=STOPPED, 이후 EOF |

evidence.items의 필드는 `documentId: Long`, `title: String`, `topic: String`, `rank: Integer`이며 null을 허용하지 않는다. 실제 사용 근거 전체 저장과 카드 최대 3개 반환은 구분한다.

### Response Example

```text
event:snapshot
data:{"answerId":50,"content":"결정은","status":"STREAMING"}

event:delta
data:{"answerId":50,"text":" A야"}

event:completed
data:{"answerId":50,"status":"COMPLETED"}

```

FE는 snapshot으로 표시 본문을 교체한 뒤 delta를 붙인다. 이미 종료된 답변에는 snapshot, 근거가 있으면 evidence, 종료 이벤트를 보내고 닫는다. accepted/progress/excluded_documents와 answerMessageId는 이 API에 사용하지 않는다. Last-Event-ID로 이력을 재생하지 않는다.

연결별 DB polling으로 저장된 문자열을 묶어 보낼 수 있다. 정상 종료까지 받은 snapshot+delta는 해당 실행의 저장 본문과 같다. 실행 token 변경·본문 교체·권한 회수·조회 실패는 그 구독을 종료한다. FE는 종료 이벤트 없이 끊긴 연결을 답변 생성 실패로 단정하지 말고 현재 메시지 상태를 다시 조회한다.

무출력 중 comment heartbeat `:keepalive`를 보낼 수 있으며 데이터 이벤트로 처리하지 않는다. 기본 poll=100ms, stream timeout=3분, heartbeat=15초, stream 용량=8이며 설정으로 바뀔 수 있다. HTTP 연결이 끊겨도 생성 작업은 계속된다. 운영 proxy·모델 호스트에서의 전송 지연은 별도 검증 대상이다.

## Error Response

| Status | Error Code | Description |
| --- | --- | --- |
| 400 | INVALID_PARAMETER | 숫자 path 바인딩 오류 |
| 401 | UNAUTHENTICATED | access cookie 미인증 |
| 403 | SEARCH_ACCESS_DENIED | 현재 Workspace 멤버가 아니거나 요청 대화의 Workspace·소유자 불일치 |
| 404 | ANSWER_NOT_FOUND | 대화·답변 없음, USER 메시지, 소속 conversationId 불일치 |
| 500 | ANSWER_GENERATION_UNAVAILABLE | 저장 실행 metadata 누락·불일치 또는 전송 용량/실행기 사용 불가 |
| 500 | INTERNAL_SERVER_ERROR | 기타 시작 전 조회 오류 |

시작 전 오류는 공통 JSON 형식으로 반환한다. SSE 응답 시작 후에는 JSON 오류나 가짜 failed 이벤트를 추가하지 않고 구독을 종료한다. 전송 오류·timeout은 SearchMessage의 상태를 변경하지 않는다.
