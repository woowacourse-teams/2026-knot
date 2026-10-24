# 내 문서 확인 완료 처리 API

`PUT /api/v1/workspaces/{workspaceId}/documents/{documentId}/confirmations/me`

현재 Workspace 멤버 중 문서 생성 당시 확인 대상으로 고정된 Member가 확인을 완료한다.
대상은 로그인 Member로 결정하며 브라우저 요청에는 `credentials: "include"`를 사용한다.
최초 확인 시각을 보존하고, 마지막 필수 대상의 확인 또는 제외가 끝나면 문서를 자동 보관한다.

## Header

| Key | Type | Required | Example | Description |
|---|---|---|---|---|
| Cookie | String | Yes | `__Host-KNOT_ACCESS_TOKEN=…; XSRF-TOKEN=…` | 로그인 access 쿠키와 CSRF 쿠키. access는 HttpOnly이며 로컬 Secure=false 설정에서는 `KNOT_ACCESS_TOKEN` |
| X-XSRF-TOKEN | String | Yes | `csrf-token-value` | `GET /api/v1/auth/csrf`에서 발급한 CSRF 토큰. 함께 전송하는 XSRF-TOKEN 쿠키와 일치해야 함 |
| Content-Type | String | No | `application/json` | 요청 본문이 없어 필수로 검사하지 않음. application/json으로 전송해도 본문은 필요 없음 |

## Path Parameter

| Key | Type | Required | Example | Description |
|---|---|---|---|---|
| workspaceId | Long | Yes | 12 | 문서가 속한 Workspace |
| documentId | Long | Yes | 301 | 확인할 Document ID |

## Query Parameter

| Key | Type | Required | Example | Description |
|---|---|---|---|---|
| 없음 | - | - | - | Query Parameter 없음 |

## Request Body

| Field | Type | Required | Nullable | Description | Example |
|---|---|---|---|---|---|
| 없음 | - | - | - | Request Body 없음. Member ID·보관 상태를 받지 않음 | - |

### Request Example

```http
PUT /api/v1/workspaces/12/documents/301/confirmations/me
Cookie: __Host-KNOT_ACCESS_TOKEN=…; XSRF-TOKEN=csrf-token-value
X-XSRF-TOKEN: csrf-token-value
```

## Response

### Status Code

| Status | Description |
|---|---|
| 200 | 확인 완료 또는 반복 확인 성공 |
| 400 | 경로 ID 형식 오류 |
| 401 | 유효한 로그인 쿠키가 없음. 유효한 CSRF를 보낸 요청 기준 |
| 403 | CSRF 검사 실패 또는 현재 Workspace 접근 불가 |
| 404 | 문서가 없거나 지정 Workspace에 속하지 않음 |
| 409 | 문서 생성 당시 확인 대상이 아닌 Member |

### Response Body

| Field | Type | Nullable | Description | Example |
|---|---|---|---|---|
| documentId | Long | No | 확인한 문서 ID | 301 |
| confirmedAt | Instant | No | 최초 확인 시각, UTC. 반복 요청에도 유지 | `2026-10-06T00:01:00Z` |
| documentStatus | String | No | 현재 저장 상태: DRAFT 또는 ARCHIVED | DRAFT |
| archivedAt | Instant | Yes | 현재 문서의 최초 보관 시각, UTC. DRAFT이면 null이며 이미 보관된 문서의 반복 요청에도 기존 시각 반환 | null |
| confirmationSummary | Object | No | 전체 고정 대상 기준의 현재 집계 | 아래 예시 |
| confirmationSummary.confirmedCount | Integer | No | 확인 완료 인원. 확인 후 탈퇴해도 유지 | 2 |
| confirmationSummary.pendingCount | Integer | No | 현재 활성 Workspace 멤버인 미확인 대상 수 | 1 |
| confirmationSummary.excludedCount | Integer | No | 미확인 상태에서 탈퇴해 제외된 대상 수 | 0 |

### Response Example

```json
{
  "documentId": 301,
  "confirmedAt": "2026-10-06T00:01:00Z",
  "documentStatus": "DRAFT",
  "archivedAt": null,
  "confirmationSummary": {
    "confirmedCount": 2,
    "pendingCount": 1,
    "excludedCount": 0
  }
}
```

## Error Response

| Status | Error Code | Description |
|---|---|---|
| 400 | INVALID_PARAMETER | 숫자가 아니거나 Long 범위를 벗어난 경로 ID |
| 401 | UNAUTHENTICATED | 유효한 로그인 access 쿠키가 없음 |
| 403 | FORBIDDEN | CSRF 쿠키·헤더 누락 또는 토큰 불일치 |
| 403 | WORKSPACE_ACCESS_DENIED | 비멤버·탈퇴자 또는 없는/삭제된 Workspace |
| 404 | DOCUMENT_NOT_FOUND | 문서가 없거나 지정 Workspace 범위가 다름 |
| 409 | CONFIRMATION_NOT_REQUIRED | 기존 DocumentConfirmation 대상 기록이 없는 Member |

```json
{
  "code": "CONFIRMATION_NOT_REQUIRED",
  "message": "문서 확인 대상이 아닙니다"
}
```

오류 message는 처리 경로에 따라 달라질 수 있으므로 클라이언트는 code로 분기한다.
CSRF 필터가 인증 검사보다 먼저 실패하면 로그인하지 않은 요청도 403을 받을 수 있다.

## 처리 규칙

- 현재 멤버 권한을 검사한 뒤 지정 Workspace의 문서와 기존 확인 대상 기록을 조회한다. 이후 가입자를 새 확인 대상으로 추가하지 않는다.
- 첫 요청만 confirmedAt을 기록한다. 반복 요청은 최초 confirmedAt·archivedAt을 유지하며 상태와 집계는 현재 값으로 반환한다. 응답 JSON 전체를 최초 요청 값으로 고정하지 않는다.
- 확인 기록 반영·남은 대상 집계·마지막 대상의 DRAFT → ARCHIVED 전환을 한 트랜잭션으로 처리한다.
- 일반 탈퇴와 OWNER 승계 후 탈퇴도 같은 트랜잭션에서 미확인 대상을 제외하고 필요한 문서를 보관한다. 탈퇴자의 기존 확인 대상 행을 삭제하지 않는다.
- 확인한 탈퇴자는 confirmed, 미확인 탈퇴자는 excluded다. 미확인 대상이 재가입하면 현재 활성 멤버십 기준으로 pending에 포함되며 이미 ARCHIVED인 문서는 DRAFT로 되돌리지 않는다.
- 같은 Workspace의 확인·탈퇴 요청은 Workspace 행 잠금으로 순서를 정한다. 잠금을 얻은 뒤 현재 멤버 여부를 확인하므로 먼저 탈퇴가 커밋되면 뒤의 확인 요청은 거절된다.
- 다른 주제 Job의 실행·실패·삭제 여부는 이미 생성된 문서의 확인과 보관을 차단하지 않는다.
- 확인 취소·문서 내용 수정·수동 보관 요청은 제공하지 않는다.

## 구현·검증 상태

2026-10-06 #498 브랜치의 Controller·DTO·Security·오류 처리와 실제 PostgreSQL/Testcontainers 및 MockMvc/OpenAPI 테스트를 기준으로 작성했다.
운영 배포·FE 브라우저 연동·STT 공급자·자동 생성·보존 실행기의 동작은 이 문서의 검증 범위에 포함하지 않는다.
확인 대상 조회 계약은 [document-confirmations.md](document-confirmations.md)를 따른다.
