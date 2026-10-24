# 녹음 최종 오디오 업로드 완료 확인 API

이 문서는 [Issue #484](https://github.com/woowacourse-teams/2026-Knot/issues/484)의 구현 계약이다. 기준 결정은
[ADR 482](../adr/482-recording-audio-direct-upload.md)이고, 2026-10-05 결정에 따라 아래 두 가지가 Notion 명세와 다르다.

- 업로드 완료 응답은 `200`과 기존 필드를 유지한다. #639에서 전사 활성화 시 비동기 전사 접수를 연결했으며, 전사 상태는 녹음 단건 조회로 확인한다.
- 최초 탭임을 증명하지 않는다. 녹음 시작자라면 어느 탭에서든 요청할 수 있다.

팀 Notion에 반영하거나 승인받은 문서는 아니다.

## 흐름

1. [업로드 URL 발급 API](recording-audio-upload-url.md)로 `uploadId`와 `uploadUrl`을 받는다.
2. 녹음 파일을 `uploadUrl`에 `PUT`한다.
3. `PUT`이 성공하면 이 API에 `uploadId`를 보낸다.
4. 서버는 저장소에 예약한 형식·크기의 파일이 실제로 있는지 확인한 뒤 완료로 기록한다. 전사가 활성화돼 있으면 같은 트랜잭션에 전사 시도를 등록하고 커밋 뒤 실행한다.

## 요청

`POST /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/audio-upload-complete`

인증 쿠키와 `X-XSRF-TOKEN`을 함께 보낸다. 요청자는 현재 Workspace의 멤버이면서 녹음 시작자여야 한다.

```json
{ "uploadId": 300 }
```

| 필드 | 형식 | 설명 |
| --- | --- | --- |
| `uploadId` | 양의 정수, 필수 | 업로드 URL 발급 API가 돌려준 값 |

## 성공 응답 `200 OK`

```json
{
  "recordingId": 10,
  "uploadId": 300,
  "uploadStatus": "COMPLETED",
  "completedAt": "2026-10-05T10:16:00Z"
}
```

- 이미 완료된 업로드에 다시 요청해도 `200`이다. 이때는 저장소를 다시 확인하지 않고 처음 기록한 `completedAt`을 그대로 돌려준다.
- 따라서 응답을 받지 못했다면 같은 요청을 다시 보내도 안전하다.
- 완료된 뒤 URL 재발급은 `409 AUDIO_UPLOAD_ALREADY_COMPLETED`다. 이미 발급한 PUT URL은 만료 전까지 유효하다.
- 반복 완료는 실패한 전사도 다시 실행하지 않는다. [전사 재시도 API](recording-transcription-retry.md)를 사용한다.
- 다른 녹음이 전사 중이면 새 녹음의 전사는 `FAILED/BUSY`로 저장된다. 업로드 완료 자체는 `200`이다.
- 업로드 완료 때 확인한 S3 versionId를 저장하고, 전사는 그 버전으로 GET URL을 발급한다. 완료 뒤 남은 PUT URL로 같은 key를 덮어써도 전사 입력은 바뀌지 않는다. 버전이 없는 객체는 전사 실패로 기록한다.

## 오류

| 상태 | code | 의미와 FE 처리 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | `uploadId` 누락, 0 이하 |
| 400 | `INVALID_WORKSPACE_ID`·`INVALID_RECORDING_DATA`·`INVALID_PARAMETER` | 잘못된 ID |
| 401 | `UNAUTHENTICATED` | 로그인 필요 |
| 403 | `WORKSPACE_ACCESS_DENIED` | Workspace 멤버가 아님 |
| 403 | `RECORDING_CONTROL_DENIED` | 녹음 시작자가 아님 |
| 403 | `FORBIDDEN` | CSRF 검증 실패 |
| 404 | `WORKSPACE_NOT_FOUND`·`RECORDING_NOT_FOUND` | 없거나 다른 Workspace의 녹음 |
| 404 | `AUDIO_UPLOAD_NOT_FOUND` | 없는 `uploadId`이거나 이 녹음의 예약이 아님 |
| 409 | `RECORDING_NOT_ENDED` | 아직 녹음 중·일시정지 |
| 409 | `RECORDING_ALREADY_DISCARDED` | 탈퇴·삭제로 폐기된 녹음 |
| 409 | `AUDIO_UPLOAD_NOT_COMPLETED` | 저장소에 파일이 없거나 형식·크기가 예약과 다름. URL을 다시 발급받아 `PUT`부터 다시 한다 |
| 500 | `AUDIO_STORAGE_UNAVAILABLE` | 저장소 미설정 또는 일시 장애. 잠시 뒤 같은 요청을 다시 보낸다 |

## 이번 범위 밖

- 문서 생성 결과 조회
- 완료되지 않은 업로드 파일 정리(저장소 수명주기 규칙으로 처리)
