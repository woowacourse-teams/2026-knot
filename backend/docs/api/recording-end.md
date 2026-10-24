# 녹음 종료 API

이 문서는 [Issue #530](https://github.com/woowacourse-teams/2026-Knot/issues/530)의 구현 계약이다.
[Issue #381](https://github.com/woowacourse-teams/2026-Knot/issues/381)에서는 녹음 시작자라면 어느 탭에서든 종료할 수 있었다.
2026-10-08 녹음 담당자의 결정에 따라 이제는 녹음을 시작한 최초 탭만 종료할 수 있다.
팀 Notion에 반영하거나 승인받은 문서는 아니다.

## 요청

`POST /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/end`

인증 쿠키와 `X-XSRF-TOKEN`을 함께 보낸다. 요청자는 현재 Workspace의 멤버이면서 녹음 시작자여야 한다.
본문에는 녹음 시작 때 보낸 탭 ID와 제어 증명을 그대로 담는다. 일시정지·재개와 같은 형식이다.

```json
{
  "tabId": "0f8fad5b-d9cb-469f-a165-70867728950e",
  "controlToken": "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA"
}
```

| 필드 | 형식 | 설명 |
| --- | --- | --- |
| `tabId` | UUID, 필수 | 녹음을 시작한 최초 탭의 ID |
| `controlToken` | 패딩 없는 Base64URL 43자, 필수 | 녹음 시작 때 보낸 제어 증명 |

## 성공 응답 `200 OK`

```json
{
  "recordingId": 10,
  "status": "ENDED",
  "endedAt": "2026-10-08T10:15:00Z"
}
```

- 종료는 업로드 완료나 STT 접수를 뜻하지 않는다. 이어서 [업로드 URL 발급 API](recording-audio-upload-url.md)를 호출한다.
- 이미 종료된 녹음을 같은 최초 탭 증명으로 다시 종료하면 `200`과 처음 기록한 `endedAt`을 돌려준다.
  응답을 받지 못했다면 같은 요청을 다시 보내도 안전하다.
- 반복 종료에서도 증명을 먼저 확인한다. 다른 탭이 보낸 재요청은 이미 종료된 녹음이어도 `403`이고 종료 결과를 돌려주지 않는다.
- 마지막 유효 신호부터 30초가 지난 녹음은 요청 시각이 아니라 `lastSeenAt + 30초`로 종료한다(연결 만료). 규칙은 [생존 신호 API](recording-heartbeat.md)에 있다.

## 오류

| 상태 | code | 의미 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | `tabId`·`controlToken` 누락 또는 형식 오류 |
| 400 | `INVALID_REQUEST_BODY` | 본문 없음 또는 JSON 형식 오류 |
| 400 | `INVALID_WORKSPACE_ID`·`INVALID_RECORDING_DATA`·`INVALID_PARAMETER` | 잘못된 ID |
| 401 | `UNAUTHENTICATED` | 로그인 필요 |
| 403 | `WORKSPACE_ACCESS_DENIED` | Workspace 멤버가 아님(탈퇴 포함) |
| 403 | `RECORDING_CONTROL_DENIED` | 녹음 시작자가 아니거나, 시작자라도 최초 탭의 탭 ID·제어 증명이 아님 |
| 403 | `FORBIDDEN` | CSRF 검증 실패 |
| 404 | `WORKSPACE_NOT_FOUND`·`RECORDING_NOT_FOUND` | 없거나 다른 Workspace의 녹음 |
| 409 | `RECORDING_ALREADY_DISCARDED` | 탈퇴·삭제로 폐기된 녹음 |

다른 탭에서 받은 `403 RECORDING_CONTROL_DENIED`는 다시 보내도 결과가 바뀌지 않는다.

## FE 전환

- 본문 없는 기존 종료 호출과 호환되는 경로는 두지 않는다. 이 변경이 배포되면 본문 없는 호출은 `400 INVALID_REQUEST_BODY`가 된다.
- 이 변경을 먼저 배포한다(2026-10-09 결정). FE가 종료 호출에 증명을 붙여 배포할 때까지 개발 서버의 종료는 `400`으로 실패한다.
  백엔드 배포는 개발 서버뿐이므로 이 공백은 개발 서버에서 녹음을 시험하는 팀원에게만 영향을 준다.
- FE는 일시정지와 같이 `getRecordingControlProof()`의 값을 종료 요청 본문에 담는다.

## 이번 범위 밖

- heartbeat, 연결 만료와 2시간 상한에 따른 서버 자동 종료(#384·#385)
- 브라우저 마이크 수집 중지와 오디오 보존
- 같은 브라우저의 물리 탭을 서버가 직접 식별하는 일. 서버는 탭 ID와 제어 증명이 일치하는지만 확인한다
