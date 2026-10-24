# 주제별 문서 폴더와 카드 목록 조회 API

`GET /api/v1/workspaces/{workspaceId}/documents`

현재 Workspace의 읽기 전용 DRAFT·ARCHIVED 문서를 조회한다. 브라우저는 `credentials: "include"`를 사용한다.
이 GET은 CSRF 헤더와 요청 본문을 요구하지 않는다.

## Header

| Key | Type | Required | Example | Description |
|---|---|---|---|---|
| Cookie | String | Yes | `__Host-KNOT_ACCESS_TOKEN=…` | 로그인 access 쿠키. Secure=false 환경은 `KNOT_ACCESS_TOKEN` |

## Path Parameter

| Key | Type | Required | Example | Description |
|---|---|---|---|---|
| workspaceId | Long | Yes | 12 | 현재 Workspace |

## Query Parameter

| Key | Type | Required | Example | Description |
|---|---|---|---|---|
| cursor | String | No | 이전 `nextCursor` 값 | 같은 Workspace·로그인 멤버·필터에서 이어 조회. 클라이언트가 직접 구성하지 않음 |
| size | Integer | No | 50 | 기본 50, 허용 1~100. 이어 조회 시 변경 가능 |
| myConfirmation | String | No | PENDING | PENDING, CONFIRMED, NOT_REQUIRED. 생략하면 내 확인 상태로 제한하지 않음 |
| recordingSessionId | Long | No | 42 | 양수 원본 녹음 ID. 생략하면 녹음으로 제한하지 않음 |

두 필터를 함께 지정하면 AND로 적용한다. 없는 녹음 또는 다른 Workspace의 녹음 ID는 빈 결과를 반환한다.

## Request Body

| Field | Type | Required | Nullable | Description | Example |
|---|---|---|---|---|---|
| 없음 | - | - | - | Request Body 없음 | - |

### Request Example

`GET /api/v1/workspaces/12/documents?size=50&myConfirmation=PENDING&recordingSessionId=42`

이어 조회할 때 같은 필터와 이전 `nextCursor`를 쿼리로 전달한다.

## Response

### Status Code

| Status | Description |
|---|---|
| 200 | 문서 목록 조회 성공. 빈 결과 포함 |
| 400 | 쿼리 형식·범위 또는 커서 오류 |
| 401 | 미인증 |
| 403 | 현재 Workspace 멤버가 아님 |

### Response Body

| Field | Type | Nullable | Description | Example |
|---|---|---|---|---|
| topics | Array | No | 모든 조회 조건을 만족하는 전체 주제 폴더. 0개 주제 제외 | 아래 예시 |
| topics[].topic | String | No | AI 주제 폴더 이름 | 운영 정책 |
| topics[].documentCount | Integer | No | 필터를 만족하는 주제 전체 문서 수. 페이지 범위 제외 | 12 |
| items | Array | No | 최신순 문서 카드 페이지 | 아래 예시 |
| items[].id | Long | No | Document ID | 301 |
| items[].recordingSessionId | Long | No | 원본 RecordingSession ID. 같은 녹음의 문서 조회에 사용 | 42 |
| items[].topic | String | No | 주제 폴더 이름 | 운영 정책 |
| items[].title | String | No | 읽기 전용 제목 | 문서 보관 정책 |
| items[].summary | String | Yes | AI 요약. 없으면 null | null |
| items[].status | String | No | DRAFT 또는 ARCHIVED | DRAFT |
| items[].createdAt | Instant | No | 생성 시각, UTC | `2026-10-06T00:00:00Z` |
| items[].recordingDurationSeconds | Integer | No | 서버 누적 녹음 밀리초를 초로 변환. 일시정지 제외, 소수 초 버림 | 1850 |
| items[].myConfirmationState | String | No | PENDING=대상 미확인, CONFIRMED=확인 완료, NOT_REQUIRED=비대상 | PENDING |
| items[].confirmationSummary | Object | No | 생성 시 고정된 확인 대상의 현재 집계 | 아래 예시 |
| items[].confirmationSummary.confirmedCount | Integer | No | 확인한 대상 수. 이후 탈퇴해도 포함 | 1 |
| items[].confirmationSummary.pendingCount | Integer | No | 현재 활성 Workspace 멤버인 미확인 대상 수 | 2 |
| items[].confirmationSummary.excludedCount | Integer | No | 미확인 상태에서 탈퇴한 대상 수 | 0 |
| nextCursor | String | Yes | 다음 페이지 커서. 다음 카드가 없으면 null | null |

### Response Example

```json
{
  "topics": [
    {"topic": "운영 정책", "documentCount": 1}
  ],
  "items": [
    {
      "id": 301,
      "recordingSessionId": 42,
      "topic": "운영 정책",
      "title": "문서 보관 정책",
      "summary": null,
      "status": "DRAFT",
      "createdAt": "2026-10-06T00:00:00Z",
      "recordingDurationSeconds": 1850,
      "myConfirmationState": "PENDING",
      "confirmationSummary": {
        "confirmedCount": 1,
        "pendingCount": 2,
        "excludedCount": 0
      }
    }
  ],
  "nextCursor": null
}
```

`summary`와 `nextCursor`가 null이어도 해당 필드를 포함한다. `recordingSessionId`는 카드 필드이며 집계 객체 내부에 두지 않는다. **(이부분 수정됨)**

## Error Response

| Status | Error Code | Description |
|---|---|---|
| 400 | INVALID_PARAMETER | 숫자·enum 형식/범위 오류, 잘못된 커서 형식·버전·경계·조회 조건 |
| 401 | UNAUTHENTICATED | 로그인 쿠키가 없거나 유효하지 않음 |
| 403 | WORKSPACE_ACCESS_DENIED | 비멤버·탈퇴자 또는 삭제된 Workspace |

```json
{
  "code": "INVALID_PARAMETER",
  "message": "조회 조건이 올바르지 않습니다"
}
```

## 처리 규칙

- 폴더 Entity와 폴더 편집 API를 만들지 않는다. Document.topic으로 그룹화한다.
- `topics`와 `documentCount`에는 필터만 적용하며 `cursor`·`size`를 적용하지 않는다. `items`에만 페이지 범위를 적용한다.
- 카드는 `createdAt DESC, id DESC`로 정렬한다. 화면은 nextCursor가 null일 때까지 이어 조회한다. size는 한 응답의 카드 수다.
- 추가 카드가 실제로 있을 때만 마지막 반환 카드 기준으로 nextCursor를 만든다. 정확히 size개이더라도 추가 카드가 없으면 null이다.
- 확인 대상은 생성 시점에 고정한다. 이후 가입자는 NOT_REQUIRED로 조회하며 대상 행을 추가하지 않는다.
- DRAFT의 원래 미확인 대상이 재가입하면 현재 활성 소속 기준으로 pending에 포함한다. 가입·탈퇴 이력은 중복 집계하지 않는다.
- 한 응답의 주제와 카드는 동일 DB 스냅샷으로 읽는다. HTTP 요청 사이 생성·확인·멤버십 변화는 다음 응답에 반영될 수 있다.
- 다른 주제의 FAILED·RUNNING Job은 이미 저장된 성공 문서를 숨기지 않는다. 목록 조회로 확인·보관 전환을 수행하지 않는다.
- 커서는 인증 수단이 아니다. 매 요청마다 로그인과 활성 Workspace 멤버를 검사한다.
- 별도 페이지 없는 전체 조회 API를 제공하지 않는다.

## 구현과 검증 범위

#495는 #496의 V27 저장 기반을 재사용하며 새 Entity·migration은 추가하지 않는다.
조회 대상 데이터 생성·STT·자동 문서 생성 실행기·실패 보존 정리는 후속 작업이다.
단위 테스트, 실제 PostgreSQL/Flyway 통합 테스트, Security/DB/OpenAPI를 포함한 MockMvc 인수 테스트로 조회 동작을 검증한다.
운영 배포와 실제 STT 공급자·AI 생성 전체 흐름은 이 검증에 포함하지 않는다.
