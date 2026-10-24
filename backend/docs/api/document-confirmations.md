# 문서 확인 대상과 진행 현황 조회 API

`GET /api/v1/workspaces/{workspaceId}/documents/{documentId}/confirmations`

현재 Workspace 멤버가 문서 생성 당시 고정된 확인 대상과 진행 현황을 조회한다.
녹음 참여 여부나 본인의 확인 대상 여부는 조회 조건이 아니다. 브라우저에서는
`credentials: "include"`를 사용한다. 이 GET은 CSRF 헤더와 요청 본문을 요구하지 않는다.
인증 쿠키는 HttpOnly이며 브라우저가 전송한다. 허용 Origin은 `auth.cors.allowed-origins` 설정을 따른다.

## Header

| Key | Type | Required | Example | Description |
|---|---|---|---|---|
| Cookie | String | Yes | `__Host-KNOT_ACCESS_TOKEN=…` | 로그인 access 쿠키. 로컬 Secure=false 설정에서는 `KNOT_ACCESS_TOKEN` |

## Path Parameter

| Key | Type | Required | Example | Description |
|---|---|---|---|---|
| workspaceId | Long | Yes | 12 | 문서가 속한 Workspace |
| documentId | Long | Yes | 301 | 확인 현황을 조회할 Document ID |

## Query Parameter

| Key | Type | Required | Example | Description |
|---|---|---|---|---|
| cursor | String | No | 이전 응답의 nextCursor | 같은 Workspace·Document·로그인 Member의 다음 페이지. 빈 값·잘못된 형식·512자 초과·요청 범위 불일치는 400 |
| size | Integer | No | 50 | 생략 시 50, 허용 범위 1~100. 다음 요청에서 size 변경 가능 |

## Request Body

| Field | Type | Required | Nullable | Description | Example |
|---|---|---|---|---|---|
| 없음 | - | - | - | Request Body 없음 | - |

### Request Example

```http
GET /api/v1/workspaces/12/documents/301/confirmations?size=50
Cookie: __Host-KNOT_ACCESS_TOKEN=…
```

## Response

### Status Code

| Status | Description |
|---|---|
| 200 | 확인 현황 조회 성공. 확인 대상이 없으면 영 집계와 빈 items |
| 400 | 경로 ID 형식·size·cursor 오류 |
| 401 | 로그인하지 않음 |
| 403 | 현재 Workspace 멤버가 아니거나 Workspace가 삭제됨 |
| 404 | 문서가 없거나 지정 Workspace에 속하지 않음 |

### Response Body

| Field | Type | Nullable | Description | Example |
|---|---|---|---|---|
| documentId | Long | No | Document ID | 301 |
| confirmedCount | Integer | No | 전체 확인 대상 중 확인 완료 인원. 페이지 범위 미적용 | 1 |
| pendingCount | Integer | No | 전체 확인 대상 중 현재 활성 Workspace 멤버인 미확인 인원. 페이지 범위 미적용 | 1 |
| excludedCount | Integer | No | 전체 확인 대상 중 탈퇴한 미확인 인원. 페이지 범위 미적용 | 0 |
| confirmedByMe | Boolean | No | 현재 Member가 확인했는지 여부. 미확인·비대상이면 false | false |
| items | Array | No | 생성 당시 고정된 확인 대상의 페이지. 대상이 없으면 [] | 아래 예시 |
| items[].memberId | Long | No | 확인 대상 Member ID | 7 |
| items[].nickname | String | No | 대상의 현재 닉네임 | 흑곰 |
| items[].profileImageUrl | String | Yes | 현재 프로필 이미지 URL. 없으면 null | null |
| items[].confirmedAt | Instant | Yes | 최초 확인 시각, UTC. 미확인이면 null | `2026-10-06T00:00:30Z` |
| items[].state | String | No | CONFIRMED·PENDING·EXCLUDED | CONFIRMED |
| nextCursor | String | Yes | 다음 페이지 커서. 마지막 페이지면 null | null |

### Response Example

```json
{
  "documentId": 301,
  "confirmedCount": 1,
  "pendingCount": 1,
  "excludedCount": 0,
  "confirmedByMe": false,
  "items": [
    {
      "memberId": 7,
      "nickname": "흑곰",
      "profileImageUrl": null,
      "confirmedAt": "2026-10-06T00:00:30Z",
      "state": "CONFIRMED"
    },
    {
      "memberId": 8,
      "nickname": "확인 대상",
      "profileImageUrl": null,
      "confirmedAt": null,
      "state": "PENDING"
    }
  ],
  "nextCursor": null
}
```

## Error Response

| Status | Error Code | Description |
|---|---|---|
| 400 | INVALID_PARAMETER | 숫자가 아닌 경로 ID, size 범위·형식 오류, cursor 빈 값·길이·버전·필드·ID·상태 오류 또는 요청 범위 불일치 |
| 401 | UNAUTHENTICATED | 유효한 로그인 access 쿠키가 없음 |
| 403 | WORKSPACE_ACCESS_DENIED | 비멤버·탈퇴자 또는 삭제된 Workspace |
| 404 | DOCUMENT_NOT_FOUND | 문서가 없거나 지정 Workspace 범위가 다름 |

```json
{
  "code": "INVALID_PARAMETER",
  "message": "문서 조회 조건이 올바르지 않습니다"
}
```

오류의 message는 입력 바인딩과 검증 경로에 따라 달라질 수 있다. 클라이언트는 code로 분기한다.
빈 fieldErrors는 생략된다.

## 처리 규칙

- 확인 대상은 생성 시점의 `DocumentConfirmation` 기록으로 고정한다. 이후 가입자는 조회할 수 있지만 대상에는 추가하지 않는다.
- 확인 시각이 있으면 현재 멤버십과 관계없이 CONFIRMED다. 미확인·활성 대상은 PENDING, 미확인·탈퇴 대상은 EXCLUDED다.
- 탈퇴자의 대상 행은 유지한다. 미확인 대상이 재가입하면 현재 활성 멤버십 기준으로 PENDING이며 가입 이력으로 인원이나 대상이 중복되지 않는다.
- 순서는 CONFIRMED → PENDING → EXCLUDED이며 같은 상태 안에서는 memberId 오름차순이다.
- 집계와 confirmedByMe는 전체 고정 대상을 기준으로 계산하며 cursor·size를 적용하지 않는다. items에만 페이지 범위를 적용한다.
- 다음 페이지가 있으면 마지막 반환 대상 기준으로 nextCursor를 제공한다. 정확히 size명이어도 추가 대상이 없으면 null이다.
- 한 응답의 집계와 items는 같은 DB 스냅샷을 읽는다. 페이지 요청 사이에 확인·탈퇴·재가입이 일어나면 대상의 정렬 위치가 바뀔 수 있으며 전체 페이지의 고정 스냅샷을 제공하지 않는다.
- 성공한 Document는 다른 주제 Job의 실패·진행·재시도 만료에 관계없이 조회한다.
- GET은 확인 시각·대상 행·Document 상태·보관 시각을 변경하지 않는다. 실제 확인과 보관 전환은 별도 PUT의 범위다.

## 구현·검증 상태

2026-10-06 #497 브랜치의 Controller·DTO·Security·오류 처리와 PostgreSQL/Testcontainers 및 MockMvc/OpenAPI 테스트를 기준으로 작성했다.
운영 배포·FE 브라우저 연동·실제 생성 실행기 검증은 이 문서의 근거에 포함하지 않는다.
기존 상세 API는 [document-detail.md](document-detail.md)에 별도로 기록돼 있다.
