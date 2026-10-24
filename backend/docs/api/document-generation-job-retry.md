# 실패한 문서 생성 작업 재시도 API

`POST /api/v1/workspaces/{workspaceId}/document-generation-jobs/{jobId}/retry`

구현 기준: `be/feature/#494`, 2026-10-07. 이 문서는 실제 Controller·Service·도메인·Security·DB 계약을 설명한다. 운영 배포와 실제 AI 실행 연결 완료를 뜻하지 않는다.

## Header

| Key | Type | Required | Example | Description |
| --- | --- | --- | --- | --- |
| Cookie | String | Yes | `__Host-KNOT_ACCESS_TOKEN=…; XSRF-TOKEN=…` | 로그인 access 쿠키와 CSRF 쿠키. 로컬 access 쿠키 이름은 `KNOT_ACCESS_TOKEN`이다 |
| X-XSRF-TOKEN | String | Yes | `csrf-token-value` | `GET /api/v1/auth/csrf`로 얻은 토큰. XSRF-TOKEN 쿠키와 함께 전송 |
| Content-Type | String | No | `application/json` | 본문 없는 API이므로 서버는 이 헤더를 필수로 검사하지 않는다 |

브라우저 요청은 `credentials: "include"`로 쿠키를 전송한다. access 쿠키는 HttpOnly이므로 FE가 값을 읽어 헤더로 옮기지 않는다. 먼저 CSRF 조회에서 토큰과 쿠키를 얻고 변경 요청에 헤더를 붙인다. 운영 기본 access 쿠키는 Secure·HttpOnly·SameSite=Lax·Path=/ 설정이며 로컬 Secure 설정은 다르다.

## Path Parameter

| Key | Type | Required | Example | Description |
| --- | --- | --- | --- | --- |
| workspaceId | Long | Yes | `10` | 현재 활성 멤버인 Workspace ID. 양수 |
| jobId | Long | Yes | `88` | 재접수할 기존 DocumentGenerationJob ID. 양수 |

## Query Parameter

| Key | Type | Required | Example | Description |
| --- | --- | --- | --- | --- |
| 없음 | - | - | - | Query Parameter 없음 |

## Request Body

| Field | Type | Required | Nullable | Description | Example |
| --- | --- | --- | --- | --- | --- |
| 없음 | - | - | - | 요청 본문 없음. 로그인 Member와 저장된 입력 원문을 사용한다 | - |

### Request Example

본문 없음. 새 원문·주제·Member ID를 보내지 않는다.

## Response

### Status Code

| Status | Description |
| --- | --- |
| 202 Accepted | 같은 Job의 QUEUED 상태·시도 횟수를 한 트랜잭션으로 확정함 |
| 400 Bad Request | 경로 형식 또는 양수 범위 오류 |
| 401 Unauthorized | 인증되지 않음 |
| 403 Forbidden | 현재 Workspace 접근 권한 없음, 녹음 소유자가 아님 또는 CSRF 오류 |
| 404 Not Found | 해당 Workspace에 Job 없음 |
| 409 Conflict | 재시도 조건을 충족하지 못함 |

### Response Body

| Field | Type | Nullable | Description | Example |
| --- | --- | --- | --- | --- |
| jobId | Long | No | 재접수한 기존 Job ID | `88` |
| status | String | No | 접수 후 상태. 항상 QUEUED | `QUEUED` |
| attemptCount | Integer | No | 최초·사용자·내부 자동 시도를 포함한 접수 후 누적 시도 수. 사용자 한도는 별도 | `2` |

### Response Example

```json
{
  "jobId": 88,
  "status": "QUEUED",
  "attemptCount": 2
}
```

202는 접수 완료다. 실제 문서 생성 완료나 AI 호출 성공을 의미하지 않는다. 실제 생성 실행기는 #501에서 연결한다.

## Error Response

| Status | Error Code | Description |
| --- | --- | --- |
| 400 | INVALID_PARAMETER | Long 형식·범위 오류 또는 workspaceId/jobId가 0 이하 |
| 401 | UNAUTHENTICATED | 로그인 access 쿠키 없음 또는 유효하지 않음 |
| 403 | WORKSPACE_ACCESS_DENIED | 비멤버·탈퇴자·삭제 또는 없는 Workspace |
| 403 | RECORDING_CONTROL_DENIED | 현재 Workspace 멤버이지만 해당 녹음을 시작한 본인이 아님 |
| 403 | FORBIDDEN | CSRF cookie/header 누락 또는 불일치 |
| 404 | DOCUMENT_GENERATION_JOB_NOT_FOUND | Job이 없거나 다른 Workspace에 속함. 보존 정리로 삭제된 Job 포함 |
| 409 | RETRY_NOT_ALLOWED | FAILED 아님, 사용자 3회 소진, 실패 후 168시간 만료, 입력 원문을 사용할 수 없음 |

```json
{
  "code": "RETRY_NOT_ALLOWED",
  "message": "문서 생성 작업을 재시도할 수 없습니다"
}
```

`fieldErrors`는 필요할 때만 반환한다. 인증과 CSRF가 둘 다 없으면 Security 필터 순서에 따라 CSRF 403이 먼저 반환될 수 있다. 401 인수 시나리오는 유효한 CSRF 조건에서 확인한다. 접수 저장 장애는 성공 응답을 반환하지 않고 트랜잭션을 롤백한다.

## 처리 규칙

- 현재 Workspace 멤버이며 원본 녹음을 시작한 본인만 재시도할 수 있다. 기존 `RecordingSession.validateControlledBy` 권한 규칙을 재사용한다. 다른 멤버의 요청은 403이며 Job 상태와 횟수를 변경하지 않는다. **(이부분 수정됨)**
- 같은 FAILED Job을 QUEUED로 전환한다. 새 Job으로 복제하지 않으며 입력은 저장된 Transcript다. 오디오 재전사·원문 검토·새 주제 제출은 하지 않는다.
- 사용자 재시도는 Job당 최대 3회다. 최초 실행과 내부 자동 시도는 사용자 한도에 포함하지 않는다. 거절된 요청은 횟수에 포함하지 않는다.
- 최초 실행만 있으면 전체 시도는 1, 첫 사용자 접수 후 2다. 사용자 3회만 사용했다면 전체는 4이며 자동 시도가 있으면 그만큼 포함한다.
- `now < expiresAt`일 때만 접수한다. 7일은 168시간이며 정확한 만료 시각부터 409다. 잠금 대기 뒤 시각으로 검사한다.
- Workspace·Job·입력 잠금 조회와 녹음 소유자 확인 뒤, Job의 QUEUED 상태와 전체·사용자 시도 횟수를 같은 쓰기 트랜잭션에 저장한다. 저장 또는 commit 실패 시 상태와 횟수도 롤백한다. 별도 실행 접수 테이블을 만들지 않는다. **(이부분 수정됨)**
- 동일 FAILED 상태의 동시 요청 중 하나만 접수한다. QUEUED로 바뀐 뒤의 중복 요청은 409다. 새로 실패한 주기는 남은 한도·기한 안에서 다시 접수할 수 있다.
- 재실패하면 마지막 실패 시각과 168시간 기한만 갱신한다. 사용자 누적 횟수는 초기화하지 않는다.
- 재접수된 QUEUED·RUNNING은 과거 실패 기한이 지나도 진행 목록에 남는다. 실패 기한 정리는 FAILED 대상에만 적용하는 #501의 계약이다.
- 다른 Job의 성공 Document·확인 기록·연결 Transcript는 변경하지 않는다. 실제 자료 삭제는 이 API가 하지 않는다.

## 실행기 연결·DB 이행

재시도 접수 사실은 기존 Job의 `status=QUEUED`와 `attempt_count`·`user_retry_count` 변경으로 저장한다. 별도 실행 접수 Entity·Repository·V31은 제거했다. 소비·실행·복구·중복 실행 방지와 보존 정리 방식은 #501에서 함께 설계한다. #494는 실행 큐나 삭제 순서를 고정하지 않는다. **(이부분 수정됨)**

현재 운영 코드에는 최초 Job 생성·실패 기록 호출 및 AI 실행 소비 경로가 없다. 따라서 이 API만 배포해도 실행 가능한 FAILED Job이 자동으로 생기거나 실제 문서 재생성이 시작되지는 않는다. #501 연계 전에는 저장된 테스트 데이터로 접수 계약을 검증한 상태다.

V30은 횟수 컬럼·CHECK를 추가한다. 선행 문서/Job 저장 기반 V27·실패 기한 V28에 의존한다. 부모 #493의 리뷰 수정까지 병합했으며 녹음 제목 컬럼·V29는 제거된 상태다. V30 번호의 공백은 삭제한 V29/V31을 다시 만드는 이유가 아니다. 기존 Job의 횟수가 미확인인 경우 V30은 상태로 횟수를 추정하지 않고 중단한다. 실제 실행 기록으로 확인한 횟수만 사전 이행하며 기존 값은 유지한다. 운영 DB 적용 여부는 미확인이다. **(이부분 수정됨)**

## 구현 근거

- `DocumentGenerationJobApi`·`DocumentGenerationJobController`: POST·빈 본문·202·Swagger.
- `DocumentGenerationJobRetryService`: 현재 멤버·녹음 소유자·입력·잠금·동일 트랜잭션 접수.
- `DocumentGenerationJob.retryByUser(...)`: 상태·사용자 한도·기한·횟수 전이.
- V30 및 JPA Repository: 횟수 합계, Job 잠금에 따른 단일 접수, 기존 원문 참조 보호.
- 도메인·Mockito·MVC·PostgreSQL·실제 Security 인수 검증은 구현 계획의 실행 기록에 둔다.
