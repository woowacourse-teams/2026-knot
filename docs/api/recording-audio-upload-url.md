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
  "uploadUrl": "https://example-audio-bucket.s3.ap-northeast-2.amazonaws.com/recordings/10/...?X-Amz-Signature=...",
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

## AWS S3 설정

저장소는 [ADR 739](../adr/739-recording-audio-aws-s3-default-credentials.md)의 사용자 결정으로 AWS S3를 사용한다.
이 결정은 ADR 482의 공급자 설정 교체 동인을 대체하며 직접 업로드 방식은 유지한다. NCP 등 호환 저장소용 endpoint·path-style 강제 설정과
애플리케이션 전용 `RECORDING_AUDIO_STORAGE_ACCESS_KEY`·`RECORDING_AUDIO_STORAGE_SECRET_KEY` 설정은 제거했다.

| 환경변수 | 용도 | 기본값 |
| --- | --- | --- |
| `RECORDING_AUDIO_STORAGE_REGION` | AWS 리전 | 없음, 예: `ap-northeast-2` |
| `RECORDING_AUDIO_STORAGE_BUCKET` | 오디오 버킷 | 없음 |
| `RECORDING_AUDIO_STORAGE_KEY_PREFIX` | PUT·HEAD·GET·삭제에 공통으로 붙일 경로 접두사 | 빈 문자열 |
| `RECORDING_AUDIO_STORAGE_UPLOAD_URL_TTL` | 업로드·전사 입력 URL의 유효 시간 | `PT15M` |

리전이나 버킷이 없으면 서버는 기동하되 저장소 요청만 `AUDIO_STORAGE_UNAVAILABLE`로 거절한다.
인증은 [AWS SDK 기본 자격 증명 체인](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html)을
사용한다. EC2 역할을 사용할 때 AWS 표준 시스템 속성·환경변수·프로필 자격 증명이 먼저 선택될 수 있다는 점을 확인한다.
endpoint도 [SDK 기본 설정](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/endpoint-config.html)을 따르며,
위 애플리케이션 설정과 별도로 AWS 표준 endpoint override가 적용될 수 있다.

접두사를 바꾸면 기존 오디오를 조회·삭제하는 위치도 달라진다. 테스트 JVM의 합성 AWS 자격 증명은
오프라인 URL 서명에만 사용하고, HEAD·GET·삭제 통신 테스트는 로컬 HTTP stub이나 저장소 대역을 사용한다.

## 이번 범위 밖

- 업로드 완료 확인(별도 문서)과 STT 접수
- 조각 업로드, 완료되지 않은 업로드 파일 정리(저장소 수명주기 규칙으로 처리)
