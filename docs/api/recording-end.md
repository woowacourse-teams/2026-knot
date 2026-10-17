# 녹음 종료 API

[#765 종료 seal](https://github.com/woowacourse-teams/2026-Knot/issues/765)과 [#767 최종화](https://github.com/woowacourse-teams/2026-Knot/issues/767)를 반영한 현재 계약이다.
과거 #530의 최초 탭 전용 종료와 본문 없음 400 규칙은 아래 원격 종료 계약으로 확장했다.

`POST /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/end`

모든 요청은 인증 쿠키·CSRF와 현재 Workspace 소속·녹음 소유자를 확인한다. 종료는 캡처만 끝내며 최종 WAV·전사·문서 생성 완료와 구분한다.

## 정상 PCM flush

최초 탭이 수집을 멈추고 마지막 조각을 flush한 뒤 `tabId`, `controlToken`, `audioSeal`을 보낸다.
예시는 이미 예약한 1번 80,000 frames 뒤에 1,600 frames 무음 tail을 추가한다. 증명은 형식 예시이며 실제 시작 증명을 사용해야 한다.

```json
{
  "tabId": "0f8fad5b-d9cb-469f-a165-70867728950e",
  "controlToken": "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefA",
  "audioSeal": {
    "lastSequence": 2,
    "totalFrameCount": 81600,
    "pendingDescriptors": [{
      "sequence": 2,
      "sampleOffset": 80000,
      "frameCount": 1600,
      "byteLength": 3200,
      "checksumSha256": "WjEigd9L2N+7TUqUrQv0TQG7jPztEga5DiG0ygVozbE="
    }]
  }
}
```

- descriptor 형식은 [조각 예약 API](recording-audio-chunks.md)와 같다. 번호·offset이 연속이어야 하며 이미 예약한 descriptor와 충돌할 수 없다.
- tail 예약·최종 번호/총 frames 검증·ENDED·NORMAL seal을 한 트랜잭션에서 확정한다. 잘못된 seal은 tail 예약까지 rollback한다.
- 음성이 없으면 `lastSequence=0`, `totalFrameCount=0`, `pendingDescriptors=[]`다. 일시정지 시간은 frames에 더하지 않는다.
- 같은 seal을 재전송하면 처음 확정한 시각·범위를 반환한다. 원격·timeout·상한 종료 뒤 NORMAL seal을 덧붙이거나 기존 범위를 바꿀 수 없다.
- heartbeat 만료가 먼저 발생하면 그 논리적 종료와 seal을 저장하고 정상 flush 요청을 거절한다. 요청 시각으로 종료 시한을 늘리지 않는다.

## 원격 종료와 기존 WebM

본문 없이 요청하면 현재 소속의 녹음 소유자가 다른 탭에서도 종료할 수 있다. PCM은 이미 예약한 descriptor까지만 REMOTE seal로 고정하고 `tailUnknown=true`로 표시한다.

본문에 `tabId`·`controlToken`만 보내는 기존 호출도 유지한다. 본문을 보냈다면 두 증명 필드는 필수이며 잘못된 증명을 생략한 요청으로 처리하지 않는다. 잘못된 최초 탭 증명은 403이다.

WebM에는 PCM `audioSeal`을 보낼 수 없다. 기존 최종 WebM 업로드는 종료 뒤 [업로드 URL 발급 API](recording-audio-upload-url.md)로 이어진다.

## 성공 응답 200

```json
{
  "recordingId": 10,
  "status": "ENDED",
  "endedAt": "2026-10-18T10:00:00Z",
  "audioSave": {
    "audioSaveStatus": "DRAINING",
    "expectedLastSequence": 2,
    "totalFrameCount": 81600,
    "tailUnknown": false,
    "acceptUntil": "2026-10-18T10:02:00Z"
  }
}
```

`audioSave`는 PCM에만 포함한다. 반복 종료에서도 `endedAt`·seal·acceptUntil은 바뀌지 않는다.
이미 최종화된 녹음의 반복 종료는 현재 job의 FINALIZING/READY/FAILED/DISCARDED 상태를 반영한다.

## 종료 후 저장 확인과 최종화

`acceptUntil = endedAt + 120초`다. worker가 만료를 늦게 감지해도 논리적 종료 시각을 사용한다.
NORMAL만 마지막 길이를 알며, REMOTE/TIMEOUT/LIMIT은 `tailUnknown=true`다.

seal 이후에는 이미 정한 descriptor의 동일 URL 갱신·저장 확인만 `now < acceptUntil`에서 허용한다.
URL 만료도 acceptUntil을 넘지 않는다. 서버는 HEAD 뒤 commit 직전에 시한·권한·freeze를 다시 확인한다.
이미 VERIFIED인 조각의 동일 예약·완료 재확인은 cutoff 뒤에도 권한을 확인하고 같은 결과를 반환한다.
freeze 이후 새 descriptor나 새로운 VERIFIED를 추가할 수 없다.

NORMAL은 모든 조각이 검증되면 즉시 최종화하고 나머지 종료 종류는 acceptUntil까지 기다린다.
누락 확정에는 acceptUntil 이후 시작한 서버 저장소 재확인이 필요하다. 최종 결과·품질·재시도는
[GET audio-save와 저장 재시도](recording-audio-chunks.md#오류와-재시도)로 조회한다.
내용이 없으면 `AUDIO_NO_CONTENT`로 실패하며 전사·문서를 만들지 않는다.

## 주요 오류

| 상태 | code | 의미 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR`, `INVALID_REQUEST_BODY` | 제공한 본문의 증명·JSON 형식 오류 |
| 400 | `INVALID_AUDIO_SEAL`, `INVALID_AUDIO_CHUNK` | 마지막 번호·frames·descriptor 형식 오류 |
| 401 | `UNAUTHENTICATED` | 인증 필요 |
| 403 | `FORBIDDEN`, `WORKSPACE_ACCESS_DENIED`, `RECORDING_CONTROL_DENIED` | CSRF·현재 소속·소유자·제공한 탭 증명 불일치 |
| 404 | `WORKSPACE_NOT_FOUND`, `RECORDING_NOT_FOUND` | 대상 없음 |
| 409 | `AUDIO_MODE_CONFLICT` | WebM에 PCM seal 제공 |
| 409 | `AUDIO_SEAL_CONFLICT` | 이미 확정한 범위·descriptor와 충돌 |
| 409 | `AUDIO_ACCEPTANCE_CLOSED`, `AUDIO_SAVE_FROZEN` | 저장 확인 시한 또는 최종 범위 확정 |
| 409 | `RECORDING_ALREADY_DISCARDED`, `RECORDING_ALREADY_ENDED` | 폐기 또는 정상 flush 이전 논리적 종료 |
