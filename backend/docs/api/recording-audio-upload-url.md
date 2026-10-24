# 녹음 최종 오디오 업로드 URL 발급 API

[Issue #482](https://github.com/woowacourse-teams/2026-Knot/issues/482)의 구현 계약이다.
[Notion 업로드 URL 명세](https://www.notion.so/3dfb4351752280509595cebea0f7bbd8)를 기준으로 하되, 2026-10-05 결정에 따라
최초 탭 증명 없이 녹음 시작자면 어느 탭에서든 요청할 수 있다. 결정 기록은
[ADR 482](../adr/482-recording-audio-direct-upload.md)다. 팀 Notion의 실시간 반영·승인을 확인한 문서는 아니다.

## 흐름

1. `POST .../recordings/{recordingId}/end`로 녹음을 종료한다.
2. 이 API로 `uploadId`와 `uploadUrl`을 받는다.
3. 녹음 파일을 `uploadUrl`에 그대로 `PUT`한다. 백엔드는 오디오 바이너리를 받지 않는다.
4. [업로드 완료 확인 API](recording-audio-upload-complete.md)에 `uploadId`를 보낸다.

## 요청

`POST /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/audio-upload-url`

인증 쿠키와 `X-XSRF-TOKEN`을 보낸다. 요청자는 현재 Workspace 멤버이면서 녹음 시작자여야 한다.

| 필드 | 형식 | 설명 |
| --- | --- | --- |
| `contentType` | 문자열, 필수 | 최종 파일의 Content-Type. `audio/webm`만 허용 |
| `contentLength` | 양의 정수, 필수 | 최종 파일 크기(byte). 최대 524,288,000(500MB) |

## 성공 응답

```json
{
  "uploadId": 300,
  "uploadUrl": "https://kr.object.ncloudstorage.com/bucket/recordings/10/...?X-Amz-Signature=...",
  "expiresAt": "2026-10-05T10:15:00Z"
}
```

- `201 Created`: 새 업로드 예약을 만들었다.
- `200 OK`: 아직 완료되지 않은 기존 예약의 URL을 다시 발급했다. `uploadId`와 저장 위치는 같다. 형식·크기는 처음 예약과 같아야 하며, 다르면 `400 INVALID_AUDIO_UPLOAD`다.
- URL은 기본 15분 뒤 만료된다. 만료되면 같은 요청을 다시 보낸다.

## PUT 업로드 규칙

- `uploadUrl`에 **같은 `Content-Type`과 `Content-Length`**로 `PUT`한다. 두 값은 서명 조건이라 다르면 저장소가 거절한다.
- 인증 쿠키나 `X-XSRF-TOKEN`을 붙이지 않는다. URL 자체가 권한이다.
- 저장 위치(key)는 서버가 정하며 클라이언트가 바꿀 수 없다.

## 오류

| 상태 | code | 의미 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | 필드 누락, 0 이하 크기 |
| 400 | `INVALID_AUDIO_UPLOAD` | 허용되지 않은 형식, 최대 크기 초과, 재발급 요청의 형식·크기가 처음 예약과 다름 |
| 400 | `INVALID_WORKSPACE_ID`·`INVALID_RECORDING_DATA`·`INVALID_PARAMETER` | 잘못된 ID |
| 401 | `UNAUTHENTICATED` | 로그인 필요 |
| 403 | `WORKSPACE_ACCESS_DENIED` | Workspace 멤버가 아님 |
| 403 | `RECORDING_CONTROL_DENIED` | 녹음 시작자가 아님 |
| 403 | `FORBIDDEN` | CSRF 검증 실패 |
| 404 | `WORKSPACE_NOT_FOUND`·`RECORDING_NOT_FOUND` | 없거나 다른 Workspace의 녹음 |
| 409 | `RECORDING_NOT_ENDED` | 아직 녹음 중·일시정지 |
| 409 | `RECORDING_ALREADY_DISCARDED` | 탈퇴·삭제로 폐기된 녹음 |
| 409 | `AUDIO_UPLOAD_ALREADY_COMPLETED` | 업로드가 이미 완료되어 교체할 수 없음 |
| 500 | `AUDIO_STORAGE_UNAVAILABLE` | 서버에 저장소가 설정되지 않음 |

## 이번 범위 밖

- 업로드 완료 확인(별도 문서)과 STT 접수
- 조각 업로드, 완료되지 않은 업로드 파일 정리(저장소 수명주기 규칙으로 처리)
