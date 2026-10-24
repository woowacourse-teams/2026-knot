# 탐색 답변 생성 중지 API

`POST /api/v1/workspaces/{workspaceId}/search/conversations/{conversationId}/answers/{answerId}/stop`

확인일: 2026-10-11. #568 브랜치의 Controller·Security·저장 서비스 및 실제 로컬 HTTP 테스트를 기준으로 한다. 운영 배포 상태를 의미하지 않는다.

## Header

| Key | Type | Required | Example | Description |
| --- | --- | --- | --- | --- |
| Cookie | String | Yes | `__Host-KNOT_ACCESS_TOKEN=…; XSRF-TOKEN=…` | 로그인 access cookie와 CSRF cookie. 로컬 access 이름은 설정된 `KNOT_ACCESS_TOKEN` |
| X-XSRF-TOKEN | String | Yes | `csrf-token-value` | `GET /api/v1/auth/csrf`로 발급된 XSRF-TOKEN cookie의 값 |
| Content-Type | String | No | `application/json` | 본문이 없으므로 필수 아님. 명세 초안의 헤더를 보내도 허용 |
| Accept | String | No | `application/json` | JSON 응답 |

브라우저는 `credentials: "include"`로 요청한다. access cookie는 브라우저가 자동 전송하고 CSRF cookie 값은 header에도 전송한다. CSRF가 먼저 거절되면 미인증 요청도 403일 수 있다.

## Path Parameter

| Key | Type | Required | Example | Description |
| --- | --- | --- | --- | --- |
| workspaceId | Long | Yes | 1 | 현재 Workspace |
| conversationId | Long | Yes | 40 | 현재 Member의 개인 대화 |
| answerId | Long | Yes | 50 | 해당 대화의 ASSISTANT 메시지 |

## Query Parameter

| Key | Type | Required | Example | Description |
| --- | --- | --- | --- | --- |
| 없음 | - | - | - | Query Parameter 없음 |

## Request Body

| Field | Type | Required | Nullable | Description | Example |
| --- | --- | --- | --- | --- | --- |
| 없음 | - | - | - | Request Body 없음 | - |

### Request Example

```http
POST /api/v1/workspaces/1/search/conversations/40/answers/50/stop
Cookie: __Host-KNOT_ACCESS_TOKEN=…; XSRF-TOKEN=…
X-XSRF-TOKEN: csrf-token-value
Content-Type: application/json
```

## Response

### Status Code

| Status | Description |
| --- | --- |
| 200 | 중지 커밋 성공 또는 이미 STOPPED인 중복 요청. JSON 응답 |

### Response Body

| Field | Type | Nullable | Description | Example |
| --- | --- | --- | --- | --- |
| answerId | Long | No | 중지된 답변 ID | 50 |
| status | String | No | STOPPED | `STOPPED` |
| content | String | No | 중지 커밋까지 저장된 본문. 출력 전 중지는 빈 문자열 | `결정은 A` |

### Response Example

```json
{"answerId":50,"status":"STOPPED","content":"결정은 A"}
```

## Error Response

| Status | Error Code | Description |
| --- | --- | --- |
| 400 | INVALID_PARAMETER | path가 Long으로 변환되지 않음 |
| 401 | UNAUTHENTICATED | 유효한 access cookie 없음. CSRF 검증 통과 기준 |
| 403 | FORBIDDEN | CSRF cookie/header가 없거나 일치하지 않음 |
| 403 | SEARCH_ACCESS_DENIED | Workspace·Member 소유 범위 불일치 또는 현재 멤버십 없음 |
| 404 | ANSWER_NOT_FOUND | 대화·답변 없음, USER 역할 또는 다른 대화의 답변 |
| 409 | ANSWER_NOT_RUNNING | COMPLETED 또는 FAILED가 먼저 저장됨 |
| 500 | ANSWER_GENERATION_UNAVAILABLE | 답변과 실행 metadata의 연결이 없거나 깨짐 |
| 500 | INTERNAL_SERVER_ERROR | 저장 실패 등 예기치 않은 오류 |

오류 JSON은 `code`, `message`, 필요할 때 `fieldErrors`를 반환한다. 빈 fieldErrors는 생략한다.

## FE 처리 규칙

- 중지는 본문과 STOPPED를 함께 저장한 뒤 200을 반환한다. 중복 요청은 본문·활동 시각을 변경하지 않는다.
- 완료·실패가 먼저 커밋됐으면 409다. 기존 메시지 조회나 재연결 snapshot으로 확정된 상태를 복구한다.
- 200의 content로 화면 본문을 확정하고 해당 답변의 이후 출력을 추가하지 않는다. 중지 전에 전송된 delta가 네트워크 지연으로 늦게 도착할 수 있다.
- 기존 생성 SSE는 남은 저장 본문 뒤 `stopped {answerMessageId,status}`를, 재연결 SSE는 `stopped {answerId,status}`를 보내고 닫는다.
- 화면 이동·SSE 종료는 중지 요청이 아니다. 녹음 세션은 유지하고 STOPPED의 부분 답변은 다음 질문 문맥에 포함한다.
- 첫 답변 실패로 숨겨진 대화도 재시도 답변을 중지하면 목록에 복원한다. 생성 전 중지로 content가 비어 있어도 동일하다. STOPPED는 실패 재시도 대상이 아니며 새 질문을 전송할 수 있다. 2026-10-12 사용자 결정과 PostgreSQL 통합 테스트 기준이다.
- 공급자 연결 취소는 중지 커밋 후 전달한다. 기본 감시 주기는 250ms이며 DB 조회·스케줄링 지연이 추가될 수 있다. 공급자 내부 계산이 끝나는 시점까지 200으로 보장하지 않는다.
- 중지 시 새로운 근거를 후보 문서에서 만들어 저장하지 않는다. 실제 사용 근거 전체 보존 및 카드 최대 3개 규칙을 유지한다.
