# 문서 상세 조회 API

`GET /api/v1/workspaces/{workspaceId}/documents/{documentId}`

현재 Workspace 멤버가 저장된 읽기 전용 문서를 조회한다. 녹음 참여 여부는 조회 조건이 아니다.
브라우저 요청은 `credentials: "include"`를 사용한다. 이 GET은 CSRF 헤더와 요청 본문을 요구하지 않는다.

## Header

| Key | Type | Required | Example | Description |
|---|---|---|---|---|
| Cookie | String | Yes | `__Host-KNOT_ACCESS_TOKEN=…` | 로그인 access 쿠키. 로컬 Secure=false 설정에서는 `KNOT_ACCESS_TOKEN` |

## Path Parameter

| Key | Type | Required | Example | Description |
|---|---|---|---|---|
| workspaceId | Long | Yes | 12 | 문서가 속한 Workspace |
| documentId | Long | Yes | 301 | 조회할 Document ID |

`recordingSessionId`는 path가 아니라 응답 필드다.

## Query Parameter

| Key | Type | Required | Example | Description |
|---|---|---|---|---|
| 없음 | - | - | - | Query Parameter 없음 |

## Request Body

| Field | Type | Required | Nullable | Description | Example |
|---|---|---|---|---|---|
| 없음 | - | - | - | Request Body 없음 | - |

### Request Example

`GET /api/v1/workspaces/12/documents/301` — 본문 없음.

## Response

### Status Code

| Status | Description |
|---|---|
| 200 | 상세 조회 성공 |
| 400 | 경로 ID 형식 오류 |
| 401 | 미인증 |
| 403 | 현재 Workspace 멤버가 아님 |
| 404 | 해당 Workspace에서 문서를 찾을 수 없음 |

### Response Body

| Field | Type | Nullable | Description | Example |
|---|---|---|---|---|
| id | Long | No | Document ID | 301 |
| recordingSessionId | Long | No | 원본 녹음 ID. 같은 녹음 문서 조회에 사용 | 42 |
| topic | String | No | AI가 분류한 주제 폴더 이름 | 운영 정책 |
| title | String | No | 읽기 전용 제목 | 문서 보관 정책 |
| summary | String | Yes | AI 요약 | null |
| content | String | No | 읽기 전용 Markdown 본문 | `# 문서 보관 기준` |
| status | String | No | DRAFT 또는 ARCHIVED | DRAFT |
| createdAt | Instant | No | 생성 시각, UTC | `2026-10-06T00:00:00Z` |
| archivedAt | Instant | Yes | 보관 시각, UTC. DRAFT면 null | null |
| recordingDurationSeconds | Integer | No | 서버 누적 녹음 밀리초를 초로 변환. 일시정지 제외, 소수 초 버림 | 1850 |
| sourceTranscriptId | Long | No | 이 문서를 만든 저장 원문 ID | 81 |
| myConfirmationState | String | No | PENDING=확인 대상·미확인, CONFIRMED=확인 완료, NOT_REQUIRED=비대상 | PENDING |
| confirmationSummary | Object | No | 저장된 확인 대상의 현재 집계 | 아래 필드 |
| confirmationSummary.confirmedCount | Integer | No | 확인한 대상 수. 이후 탈퇴해도 포함 | 1 |
| confirmationSummary.pendingCount | Integer | No | 현재 활성 Workspace 멤버인 미확인 대상 수 | 1 |
| confirmationSummary.excludedCount | Integer | No | 미확인 상태에서 탈퇴한 대상 수 | 1 |

### Response Example

```json
{
  "id": 301,
  "recordingSessionId": 42,
  "topic": "운영 정책",
  "title": "문서 보관 정책",
  "summary": null,
  "content": "# 문서 보관 기준",
  "status": "DRAFT",
  "createdAt": "2026-10-06T00:00:00Z",
  "archivedAt": null,
  "recordingDurationSeconds": 1850,
  "sourceTranscriptId": 81,
  "myConfirmationState": "PENDING",
  "confirmationSummary": {
    "confirmedCount": 1,
    "pendingCount": 1,
    "excludedCount": 1
  }
}
```

summary·archivedAt이 null이어도 필드는 응답에 포함된다.

## Error Response

| Status | Error Code | Description |
|---|---|---|
| 400 | INVALID_PARAMETER | workspaceId 또는 documentId를 Long으로 해석할 수 없음 |
| 401 | UNAUTHENTICATED | 로그인 쿠키가 없거나 유효하지 않음 |
| 403 | WORKSPACE_ACCESS_DENIED | 비멤버·탈퇴자 또는 삭제된 Workspace |
| 404 | DOCUMENT_NOT_FOUND | 문서가 없거나 지정 Workspace 범위가 다름 |

```json
{
  "code": "DOCUMENT_NOT_FOUND",
  "message": "문서를 찾을 수 없습니다"
}
```

## 처리 규칙

- 생성 시 고정된 확인 대상을 읽는다. 이후 합류한 멤버는 문서를 조회할 수 있지만 NOT_REQUIRED이며 GET에서 대상 행을 추가하지 않는다.
- confirmedAt이 있는 대상은 confirmed, 없고 현재 활성 대상은 pending, 없고 탈퇴한 대상은 excluded다. 탈퇴·재가입 이력을 중복 집계하지 않는다.
- DRAFT의 원래 미확인 대상이 재가입하면 현재 활성 소속 기준으로 pending에 포함한다.
- GET은 본문·확인 시각·상태·보관 시각을 변경하지 않는다. 모든 확인 조건 충족에 따른 보관 전환은 확인 처리·탈퇴 연계의 쓰기 작업에서 수행한다.
- 다른 주제 Job의 실패·진행·만료 때문에 이미 저장된 성공 문서를 숨기지 않는다.
- 문서 생성 실행과 생성 시 확인 대상 저장, 원문 구간 조회 및 보존 정리 실행기는 후속 작업이다. 이 API의 테스트는 저장된 데이터의 조회를 검증한다.
- 녹음 길이는 실제 오디오 메타데이터 측정 결과가 아니라 현재 RecordingSession의 accumulatedRecordingMillis 기준이다.

## 마이그레이션 적용 순서

이번 저장 기반은 V27이다. 열린 #483의 V26이 아직 develop에 없어 로컬 테스트는 V24 다음 V27을 적용한다.
공유 환경에서는 #483의 V26을 먼저 합류·적용한 뒤 V27을 배포해야 한다. 높은 버전을 먼저 배포한 뒤 낮은 버전을 outOfOrder로 적용하는 우회는 사용하지 않는다.

## 검증 범위

단위 테스트, PostgreSQL/Flyway/JPA 통합 테스트와 실제 Spring Security를 포함한 MockMvc 인수 테스트에서 응답·오류·저장값 불변·OpenAPI를 검증한다. 운영 배포나 실제 STT 공급자·자동 문서 생성 흐름을 검증한 결과는 아니다.
