# 문서에 연결된 전사 원문 조회 API

`GET /api/v1/workspaces/{workspaceId}/documents/{documentId}/transcript`

현재 Workspace 멤버가 문서 생성에 사용한 저장 원문 전체와 발화 구간을 읽는다.
녹음 소유자·참여 여부·문서 확인 대상 여부는 조회 조건이 아니다.
브라우저 요청은 `credentials: "include"`를 사용한다. GET은 CSRF 헤더를 요구하지 않는다.

구현 범위: #499의 Entity·구간 테이블·JPA 조회·HTTP 계약. 실제 STT 결과 저장 실행은
음성 담당 작업, AI 문서 생성과 보존 정리는 #501에서 연결한다. 운영 배포·실제 STT 지원을
확인했다는 의미는 아니다. **(추가됨)**

## Header

| Key | Type | Required | Example | Description |
| --- | --- | --- | --- | --- |
| Cookie | String | Yes | `__Host-KNOT_ACCESS_TOKEN=…` | 로그인 access 쿠키. Secure=false 로컬 설정에서는 `KNOT_ACCESS_TOKEN`. HttpOnly로 발급되어 브라우저가 자동 전송한다. |

## Path Parameter

| Key | Type | Required | Example | Description |
| --- | --- | --- | --- | --- |
| workspaceId | Long | Yes | 12 | 문서가 속한 Workspace. 1 이상 |
| documentId | Long | Yes | 301 | 원문을 조회할 Document. 1 이상 |

## Query Parameter

| Key | Type | Required | Example | Description |
| --- | --- | --- | --- | --- |
| 없음 | - | - | - | Query Parameter 없음 |

## Request Body

| Field | Type | Required | Nullable | Description | Example |
| --- | --- | --- | --- | --- | --- |
| 없음 | - | - | - | Request Body 없음 | - |

### Request Example

`GET /api/v1/workspaces/12/documents/301/transcript` — 본문 없음.

## Response

### Status Code

| Status | Description |
| --- | --- |
| 200 | 문서 연결 원문 조회 성공 |
| 400 | 경로 ID 형식 또는 범위 오류 |
| 401 | 로그인하지 않음 |
| 403 | 현재 Workspace 멤버가 아님 |
| 404 | 문서 또는 연결 원문이 없거나 Workspace 범위가 다름 |
| 500 | 저장된 원문·구간 계약 위반 또는 내부 오류 |

### Response Body

| Field | Type | Nullable | Description | Example |
| --- | --- | --- | --- | --- |
| transcriptId | Long | No | Document.sourceTranscriptId에 연결된 원문 ID | 81 |
| isPartial | Boolean | No | 현재 최종 파일 하나 업로드 범위에서는 false | false |
| recordingDurationSeconds | Integer | No | 서버 누적 녹음 밀리초를 초로 변환. 소수 초 버림 | 1850 |
| transcriptText | String | No | 저장된 전체 원문. 조회 중 구간 문장을 재합성하지 않음 | 아래 예시 |
| segments | Array | No | 발화가 있는 성공 원문의 비어 있지 않은 실제 구간. startMillis·동률 position 오름차순 **(이부분 수정됨)** | 아래 예시 |
| segments[].startMillis | Long | No | 최종 업로드 오디오 시작을 0으로 하는 실제 발언 시작 위치(ms). 0 이상 **(이부분 수정됨)** | 1200 |
| segments[].endMillis | Long | Yes | 실제 발언 종료 위치(ms). 미상이면 null, 값이 있으면 startMillis 이상 | 4600 |
| segments[].speakerNumber | Integer | Yes | 동일 원문 내 익명 화자 번호. 1 이상, 미상이면 null | 1 |
| segments[].text | String | No | 읽기 전용 발언 문장 | 문서 보관 기준부터 정리하겠습니다. |

### Response Example

```json
{
  "transcriptId": 81,
  "isPartial": false,
  "recordingDurationSeconds": 1850,
  "transcriptText": "문서 보관 기준부터 정리하겠습니다.\n좋습니다.",
  "segments": [
    {
      "startMillis": 1200,
      "endMillis": 4600,
      "speakerNumber": 1,
      "text": "문서 보관 기준부터 정리하겠습니다."
    },
    {
      "startMillis": 4800,
      "endMillis": null,
      "speakerNumber": null,
      "text": "좋습니다."
    }
  ]
}
```

종료 시각·화자가 null이어도 필드는 응답에 포함된다.

## Error Response

| Status | Error Code | Description |
| --- | --- | --- |
| 400 | INVALID_PARAMETER | workspaceId·documentId가 Long 형식이 아니거나 0 이하 |
| 401 | UNAUTHENTICATED | 로그인 access 인증 없음·유효하지 않음 |
| 403 | WORKSPACE_ACCESS_DENIED | 비멤버·탈퇴자·삭제된 Workspace |
| 404 | TRANSCRIPT_NOT_FOUND | 문서 또는 연결 원문 없음·다른 Workspace의 문서 |
| 500 | INVALID_TRANSCRIPT_DATA | 연결 원문의 전체 텍스트 또는 실제 발화 구간이 누락된 저장 데이터 오류 **(이부분 수정됨)** |
| 500 | INTERNAL_SERVER_ERROR | 예상하지 못한 내부 오류 **(이부분 수정됨)** |

```json
{
  "code": "TRANSCRIPT_NOT_FOUND",
  "message": "문서에 연결된 원문을 찾을 수 없습니다"
}
```

내부 예외나 원문을 오류 응답에 노출하지 않는다. fieldErrors는 없으면 생략된다.

저장된 원문의 필수 텍스트·구간이 누락되면 아래와 같이 반환한다. 사용자 입력 오류인 400이나 원문 없음인 404로 처리하지 않는다. **(이부분 수정됨)**

```json
{
  "code": "INVALID_TRANSCRIPT_DATA",
  "message": "문서 원문을 불러올 수 없습니다"
}
```

## 처리 규칙

- 현재 활성 Workspace 멤버를 먼저 확인하고 해당 범위 안의 문서 원문을 읽는다.
- DRAFT·ARCHIVED 모두 조회한다. 조회는 확인 기록·문서 상태·Job·원문을 변경하지 않는다.
- 최신 원문 대신 선택한 Document에 연결된 저장 원문만 반환한다.
- 전체 텍스트는 Transcript.content, 발화 구간은 같은 Transcript의 TranscriptSegment에서 읽는다. **(이부분 수정됨)**
- 실제 시작 시각을 추정하거나 전체 텍스트를 나눠 구간을 생성하지 않는다.
- 원문 헤더와 구간을 하나의 읽기 트랜잭션에서 같은 DB 스냅샷으로 읽는다.
- 익명 화자를 실제 Member·녹음 참여자와 연결하지 않는다. 번호가 있으면 화자 1처럼, 없으면 미상으로 표시한다.
- 녹음 전사의 진행 상태와 빈 구간 응답은 이 API에 포함하지 않는다. 발화가 있는 성공 문서 원문에서 구간 누락을 빈 성공 응답으로 처리하지 않는다. **(이부분 수정됨)**
- Document 본문과 원문의 문장별 대응 관계는 제공하지 않는다.
- STT·AI·S3를 조회 중 호출하지 않는다. 원본 오디오 존재나 다른 주제 Job의 상태·만료 여부를 읽기 조건으로 사용하지 않는다.
- 성공 문서 원문의 실제 보존·정리는 #501에서 연결한다. 이 조회 API가 삭제를 수행하지 않는다.

## 연결 시 확인할 항목

- 음성 담당자는 전체 원문·실제 구간을 같은 저장 트랜잭션으로 확정하고 완성된 원문만 문서 생성에 제공한다.
- 실제 STT 공급자의 시작 시각 지원과 저장 결과는 fixture 기반 API 테스트와 별도로 확인한다.
- 녹음 원문 API가 구현되면 같은 transcriptId의 구간·단위·화자 번호·null 규칙을 대조한다.
- 배포 전 전체 텍스트만 있는 기존 데이터의 존재와 실제 타임스탬프 재수집·전환 필요성을 확인한다.
