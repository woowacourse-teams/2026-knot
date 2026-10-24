# 문서 생성 작업 목록 조회 API

`GET /api/v1/workspaces/{workspaceId}/document-generation-jobs`

현재 Workspace에서 로그인한 멤버가 시작한 녹음의 진행·실패 문서 생성 작업을 조회한다. 브라우저는 `credentials: "include"`를 사용한다.
기준: #493 구현과 로컬 HTTP·PostgreSQL·OpenAPI 테스트. 운영 배포·STT/AI 실행 완료를 뜻하지 않는다.
2026-10-07 PR #511 리뷰 반영에 대한 사용자 결정으로 녹음 제목 필드를 제거하고 조회 범위를 내 녹음으로 제한했다. 이전 개인 API·Issue의 전체 Workspace 조회·제목 응답과 달라졌으며 Notion·GitHub 원문은 아직 갱신하지 않았다.

## Header

| Key | Type | Required | Example | Description |
| --- | --- | --- | --- | --- |
| Cookie | String | Yes | `__Host-KNOT_ACCESS_TOKEN=…` | 로그인 access 쿠키. 로컬 테스트의 Secure=false 설정에서는 `KNOT_ACCESS_TOKEN` |

GET은 `X-XSRF-TOKEN`과 `Content-Type`을 요구하지 않는다.

## Path Parameter

| Key | Type | Required | Example | Description |
| --- | --- | --- | --- | --- |
| workspaceId | Long | Yes | 12 | 현재 Workspace |

## Query Parameter

| Key | Type | Required | Example | Description |
| --- | --- | --- | --- | --- |
| cursor | String | No | 이전 응답의 nextCursor | 같은 Workspace·로그인 Member 조회에서 사용. 빈 문자열·잘못된 형식·다른 조회 범위의 커서는 400 |
| size | Integer | No | 20 | 기본 20, 최소 1, 최대 100. 화면의 전체 작업 수 제한이 아닌 응답당 항목 수 |

## Request Body

| Field | Type | Required | Nullable | Description | Example |
| --- | --- | --- | --- | --- | --- |
| 없음 | - | - | - | Request Body 없음 | - |

### Request Example

```http
GET /api/v1/workspaces/12/document-generation-jobs?size=20
Cookie: __Host-KNOT_ACCESS_TOKEN=…
```

## Response

### Status Code

| Status | Description |
| --- | --- |
| 200 | 작업 목록 조회 성공 |

### Response Body

| Field | Type | Nullable | Description | Example |
| --- | --- | --- | --- | --- |
| items | Array | No | 현재 Workspace에서 내가 시작한 녹음의 진행·기한 내 실패 Job. 없으면 [] | 아래 예시 |
| items[].jobId | Long | No | DocumentGenerationJob ID | 88 |
| items[].recordingSessionId | Long | No | 원본 RecordingSession ID | 42 |
| items[].status | String | No | QUEUED, RUNNING, FAILED | FAILED |
| items[].createdAt | Instant | No | 작업 생성 시각, UTC | 2026-10-06T09:32:10Z |
| items[].updatedAt | Instant | No | 최근 작업 상태 갱신 시각, UTC | 2026-10-06T09:36:18Z |
| nextCursor | String | Yes | 다음 페이지가 없으면 null | null |

### Response Example

```json
{
  "items": [
    {
      "jobId": 88,
      "recordingSessionId": 42,
      "status": "FAILED",
      "createdAt": "2026-10-06T09:32:10Z",
      "updatedAt": "2026-10-06T09:36:18Z"
    }
  ],
  "nextCursor": null
}
```

nextCursor가 null이어도 필드는 포함한다. 빈 결과는 `{"items":[],"nextCursor":null}`이다. recordingTitle은 응답하지 않는다.

## Error Response

| Status | Error Code | Description |
| --- | --- | --- |
| 400 | INVALID_PARAMETER | 경로·size 형식 오류, size 범위 오류, 빈/잘못된/다른 Workspace·Member의 cursor |
| 401 | UNAUTHENTICATED | 로그인 쿠키가 없거나 유효하지 않음 |
| 403 | WORKSPACE_ACCESS_DENIED | 현재 멤버가 아님. 탈퇴자·삭제된 Workspace도 접근 불가 |

## 처리 규칙

- 현재 Workspace 멤버임을 검사하고, `RecordingSession.memberId`가 로그인 Member ID와 같은 녹음의 Job만 반환한다. 다른 멤버의 작업은 페이지 크기·커서 판단에서도 제외한다.
- 현재 멤버라도 본인 녹음의 대상 Job이 없으면 빈 배열을 반환한다. 다른 멤버가 만든 Document를 읽는 목록·상세 권한과는 별개다.
- 녹음 제목 입력·저장 필드는 제공하지 않는다. 홈의 녹음 이름은 로그인한 사용자의 이름으로 ‘OO 님의 녹음’을 표시한다.
- QUEUED·RUNNING과 마지막 실패부터 7일 미만인 FAILED만 포함한다. SUCCEEDED는 제외한다.
- 실패 만료는 마지막 실패 시각+168시간이며, 정확히 만료 시각부터 제외한다. 실제 DB 삭제 전에도 적용한다.
- 새 실패가 기록되면 마지막 실패 기준으로 기한이 갱신된다. 재접수된 QUEUED·RUNNING은 이전 실패 기한으로 제외하지 않는다.
- 사용자 재시도 3회 제한은 재시도 API의 접수 조건이다. 이 목록은 3회 소진을 이유로 FAILED를 제외하지 않는다.
- `createdAt DESC, jobId DESC` 순서다. 같은 녹음의 여러 Job도 개별 항목으로 반환하며 FE가 recordingSessionId로 묶을 수 있다.
- items에만 커서 페이지네이션을 적용한다. 다음 페이지가 있으면 마지막 반환 항목을 기준으로 nextCursor를 생성한다.
- 페이지 요청 사이에 성공·실패·만료 상태가 바뀔 수 있다. 커서는 전체 실시간 목록의 고정 스냅샷이 아니다.
- GET은 상태·시각·Document·Transcript를 변경하거나 자료를 삭제하지 않는다.
- 성공 문서는 다른 Job의 실패·만료와 관계없이 조회할 수 있다. 목록에서 Job이 사라진 사실만으로 녹음 전체의 완료를 판단하지 않는다.
- 녹음 전체 결과 조회는 녹음 상세 API 담당 작업에서 연결한다. 이 API는 종합 상태를 반환하지 않는다.
- 홈은 내 녹음 카드 한 칸을 유지한다. 이 목록의 Job 개수나 여러 녹음을 그대로 홈 카드 목록으로 표시하지 않는다. 현재 녹음 선택·새 활성 녹음 우선·종합 처리 상태는 녹음 조회 계약에서 연결한다.
- 현재 구현된 `recordings/current`는 RECORDING·PAUSED만 반환한다. 종료 후 처리 상태·작업 식별자 제공은 후속 연계이며, 명세에 있는 필드를 이미 구현했다고 취급하지 않는다.
- Job 최초 등록·자동 실행·자료 정리는 #501, 실패 Job 재시도 접수는 #494에서 연결한다. 이 GET이 STT·AI 실행을 시작하지 않는다.

## 저장 기반과 연계

- Job은 기존 Transcript → RecordingSession 경로로 Workspace와 녹음 소유자를 확인한다.
- V28은 실패 시각·만료 필드와 DB 제약을 추가한다. 미병합 V29 녹음 제목 migration과 Entity 필드 추가는 리뷰 반영으로 제거했다.
- V28 적용 전에 기존 FAILED가 있으면 이행을 중단한다. updatedAt을 실제 실패 시각으로 추정하지 않으며, 실제 시각을 확인한 데이터 이행 설계를 먼저 마련해야 한다.
- 공유 DB 적용 이력·운영 자료 이행·실제 STT/AI 결과 생성은 이번 로컬 검증에서 관측하지 않았다.

구현 계획: [#493 구현 계획](../implement-plan/493-document-generation-job-list.md).
