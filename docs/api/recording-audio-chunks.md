# 녹음 중 PCM 오디오 조각 저장 API

[Issue #764](https://github.com/woowacourse-teams/2026-Knot/issues/764)의 조각 저장과 #765 종료 seal·#767 최종화를 반영한 현재 계약이다.
최초 탭이 조각을 예약하고 S3에 직접 PUT한 뒤 서버의 저장 확인을 받는다.
PUT 성공만으로 저장 완료를 표시하지 않고, 완료 API의 `VERIFIED`를 기준으로 삼는다.

## 적용 범위와 인증

[시작 API](recording-start.md)의 `audioMode`를 `PCM16_CHUNKS_V1`로 지정한다.
신규 PCM 시작 설정 `recording.audio-chunks.enabled`의 기본값은 false다.
설정이 꺼져도 기존 PCM 녹음의 동일 시작 재시도와 조각 저장은 계속 허용한다.
모드를 생략하거나 null로 보내면 기존 `FINAL_WEBM_V1`이며 두 업로드 방식을 섞을 수 없다.

앱 API에는 인증 쿠키가 필요하다. 조각 예약·완료 POST에는 `X-XSRF-TOKEN`과 최초 탭의 `tabId`·`controlToken`을 보낸다. 저장 재시도는 CSRF와 소유자를 검사하며 탭 증명을 받지 않는다.
조회는 본인 녹음·현재 Workspace 소속을 확인하며 탭 증명을 요구하지 않는다.
조각 예약·완료·조회는 heartbeat를 갱신하지 않으므로 기존 heartbeat를 계속 보낸다.

아래 UUID는 예시다. `controlToken`의 43자 `A`는 실제 증명을 가린 **형식 검증용 대체값**이다.
요청할 때는 녹음 시작에 사용한 값을 넣는다. 이 대체값을 실제 난수로 사용하지 않는다.
JSON의 형식·길이·checksum은 검증했으며, 실제 인증 세션에서 아래 값을 그대로 호출한 예시는 아니다.

## 1. 조각 예약

`POST /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/audio-chunks`

본문 예시는 값이 0인 PCM 160,000바이트, 즉 5초 무음의 SHA-256을 사용한다.
WAV 헤더·WebM 컨테이너·압축 데이터를 붙이지 않은 PCM16LE·16kHz·mono 바이트를 보낸다.

```json
{
  "tabId": "00000000-0000-4000-8000-000000000001",
  "controlToken": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
  "sequence": 1,
  "sampleOffset": 0,
  "frameCount": 80000,
  "byteLength": 160000,
  "checksumSha256": "uc4WTTDkEBsAn+S+dloHBZPPvdSPiXhT3hWajBd/q+g="
}
```

| 필드 | 규칙 |
| --- | --- |
| `sequence` | 1부터 연속 증가. 예약은 순서대로 보낸다. |
| `sampleOffset` | 앞서 예약한 조각의 `frameCount` 합. 첫 조각은 0이다. |
| `frameCount` | 1~80,000프레임. 한 조각 최대 5초다. |
| `byteLength` | `frameCount × 2`바이트. |
| `checksumSha256` | 보낼 PCM 전체의 SHA-256을 패딩 있는 표준 Base64로 인코딩한 32바이트 해시. URL-safe Base64나 hex를 사용하지 않는다. |

누적 범위는 115,200,000프레임, 즉 2시간 이하다.
예약 후 같은 `sequence`의 offset·크기·checksum을 바꾸지 않는다.
전송·완료 순서는 달라도 된다. 완료된 뒤쪽 조각은 앞쪽 완료를 기다리지 않고 보존한다.

### 예약 응답

새 예약은 `201 Created`, 같은 descriptor의 재요청은 `200 OK`다.
응답 필드는 다음과 같다.

| 필드 | 값 |
| --- | --- |
| `sequence` | 예약 조각 번호 |
| `status` | `RESERVED` 또는 `VERIFIED` |
| `uploadUrl` | RESERVED의 서명 PUT URL. VERIFIED이면 null |
| `expiresAt` | 해당 URL의 만료 시각. VERIFIED이면 null |
| `requiredHeaders` | PUT에 그대로 사용할 헤더. VERIFIED이면 빈 객체 |

RESERVED의 동일 요청은 같은 조각에 대한 URL을 재발급한다.
이미 VERIFIED이면 새 PUT 없이 다음과 같이 반환한다.

```json
{
  "sequence": 1,
  "status": "VERIFIED",
  "uploadUrl": null,
  "expiresAt": null,
  "requiredHeaders": {}
}
```

## 2. S3 PUT

RESERVED 응답의 `uploadUrl`에 봉인한 PCM을 PUT하고 `requiredHeaders`를 그대로 보낸다.
현재 헤더에는 `content-type: application/octet-stream`, `if-none-match: *`,
`x-amz-checksum-sha256`이 포함된다. `Host`·`Content-Length`는 브라우저가 설정하므로 직접 넣지 않는다.
앱 인증 쿠키·제어 증명을 S3에 전달하지 않는다. 서명 URL은 로그에 남기지 않는다.

중복 PUT의 412는 이미 객체가 있다는 뜻이며 DB 완료를 뜻하지 않는다.
같은 조각의 완료 API로 저장을 확인한다. checksum 오류나 응답 유실이 있어도 조각 identity와 PCM 바이트를 바꾸지 않는다.

## 3. 저장 확인

`POST /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/audio-chunks/{sequence}/complete`

```json
{
  "tabId": "00000000-0000-4000-8000-000000000001",
  "controlToken": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
}
```

서버는 HEAD의 크기·Content-Type·SHA-256·비어 있지 않은 version ID를 검증한다.
DB에 VERIFIED와 version을 저장한 뒤 `200 OK`를 반환한다.
이미 VERIFIED이면 HEAD를 다시 호출하지 않고 같은 `confirmedAt`을 반환한다.
연속 저장량은 그 시점의 전체 조각 상태로 다시 계산하므로 재요청 응답에서 증가할 수 있다.

첫 조각만 예약·검증된 경우의 응답 예시다.

```json
{
  "sequence": 1,
  "status": "VERIFIED",
  "verifiedThroughSequence": 1,
  "verifiedThroughFrame": 80000,
  "verifiedAudioMillis": 5000,
  "confirmedAt": "2026-10-18T00:00:00Z"
}
```

`verifiedThroughSequence`·`verifiedThroughFrame`은 첫 조각부터 빈틈없이 확인한 연속 구간의 끝이다.
`verifiedThroughFrame`은 끝 프레임을 제외하며 `verifiedAudioMillis`는 이를 16으로 나눈 정수다.
2번만 VERIFIED이고 1번이 RESERVED이면 세 값은 모두 0이다.

## 4. 저장 상태 조회

`GET /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/audio-save`

아래는 5초 조각 두 개를 예약하고 첫 조각만 VERIFIED인 녹음 중 상태다.

```json
{
  "audioSaveStatus": "CAPTURING",
  "verifiedThroughSequence": 1,
  "verifiedThroughFrame": 80000,
  "verifiedAudioMillis": 5000,
  "verifiedRanges": [{ "start": 0, "endExclusive": 80000 }],
  "missingRanges": [{ "start": 80000, "endExclusive": 160000 }],
  "tailUnknown": false,
  "expectedLastSequence": null,
  "acceptUntil": null,
  "failureCode": null,
  "canRetry": false,
  "jobId": null,
  "quality": null
}
```

두 ranges의 단위는 밀리초나 바이트가 아닌 **프레임**이며 `[start, endExclusive)`다.
인접한 같은 상태 구간은 합쳐 반환한다. `verifiedRanges`는 연속 prefix 뒤쪽의 VERIFIED 구간도 포함한다.
`missingRanges`는 manifest 확정 전에는 예약했지만 VERIFIED가 아닌 구간, 확정 뒤에는 manifest에 고정된 알려진 누락 구간이다. 아직 예약하지 않은 마지막 음성의 길이는 추측하지 않는다.

녹음 중에는 `CAPTURING`, 종료·조각 확인 대기에는 `DRAINING`, 조립·업로드 중에는 `FINALIZING`,
최종 WAV 검증·채택 뒤에는 `READY`, 실패 소진 시에는 `FAILED`, 폐기에는 `DISCARDED`를 반환한다.
seal 이후 `expectedLastSequence`·`acceptUntil`·`tailUnknown`이 고정된다. NORMAL은 마지막 길이를 알고,
REMOTE/TIMEOUT/LIMIT은 `tailUnknown=true`다. `quality`는 manifest 확정 뒤 COMPLETE 또는 PARTIAL이며,
`jobId`는 재시도해도 유지한다. `failureCode`와 `canRetry`로 저장 실패의 수동 재접수 가능 여부를 구분한다.

조회는 heartbeat나 만료 상태를 변경하지 않으므로 그 응답만으로 녹음의 생존 기한이 연장되지 않는다.

## 오류와 재시도

오류는 공통 `code`·한글 `message`를 사용한다. 필드 검증 오류에는 `fieldErrors`가 포함될 수 있다.

| 상태 | 코드 | 조건 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR`, `INVALID_REQUEST_BODY`, `INVALID_PARAMETER` | 필수값·증명 형식·JSON·경로 오류 |
| 400 | `INVALID_WORKSPACE_ID`, `INVALID_RECORDING_DATA` | 양수가 아닌 Workspace ID 또는 변경 요청의 녹음 ID |
| 400 | `INVALID_AUDIO_CHUNK` | 번호·offset·프레임·바이트·checksum 제약 위반 |
| 401 | `UNAUTHENTICATED` | 인증 없음 |
| 403 | `FORBIDDEN`, `WORKSPACE_ACCESS_DENIED`, `RECORDING_CONTROL_DENIED` | CSRF·현재 소속·녹음 소유자·최초 탭 증명 불일치 |
| 404 | `WORKSPACE_NOT_FOUND`, `RECORDING_NOT_FOUND`, `AUDIO_CHUNK_NOT_FOUND` | 대상 또는 조각 예약 없음 |
| 409 | `AUDIO_MODE_CONFLICT` | PCM 녹음이 아닌 대상에 조각 API 사용, 또는 PCM 녹음에 기존 최종 파일 업로드 사용 |
| 409 | `AUDIO_SAVE_RETRY_NOT_ALLOWED` | READY·폐기·영구 실패·보관 기한 경과 등 재접수 불가 |
| 409 | `AUDIO_SEQUENCE_GAP` | 다음 예약 번호보다 큰 번호. 최상위 `expectedNextSequence`로 다음 번호를 제공 |
| 409 | `AUDIO_CHUNK_CONFLICT` | 기존 descriptor 변경 또는 다음 offset 불일치 |
| 409 | `AUDIO_CHUNK_NOT_STORED` | HEAD에서 저장 객체 없음 |
| 409 | `AUDIO_CHUNK_MISMATCH` | 객체 크기·형식·checksum·version 검증 실패 |
| 409 | `RECORDING_ALREADY_ENDED`, `RECORDING_ALREADY_DISCARDED`, `AUDIO_SAVE_FROZEN` | 종료·폐기·저장 범위 확정으로 변경 불가 |
| 500 | `AUDIO_STORAGE_UNAVAILABLE` | 서명·저장소 요청 실패. 저장 성공 여부를 단정하지 않는다. |

응답을 잃으면 같은 descriptor로 예약하거나 같은 sequence로 완료를 다시 요청한다.
오류 시 새 번호를 만들거나 checksum을 바꿔 우회하지 않는다.

`POST /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/audio-save/retry`는 빈 본문으로 요청한다.
현재 소유자·Workspace 소속·CSRF를 검사하고, 새 재접수는 202, 이미 같은 job이 대기·실행 중이면 200과 audio-save 상태를 반환한다.
READY·DISCARDED·무결성 충돌·내용 없음·보관 기한 경과에는 409 `AUDIO_SAVE_RETRY_NOT_ALLOWED`를 반환한다.
기존 manifest·종료 descriptor·PUT 허용 시한은 재시도로 바뀌지 않는다.
`AUDIO_STORAGE_ACCESS_DENIED`는 저장소 접근 거절, `AUDIO_NO_CONTENT`는 검증된 음성 없음으로 재시도할 수 없다.

## 종료·최종화·문서 원본 품질

종료 요청·마지막 descriptor·유예의 정본은 [녹음 종료 API](recording-end.md)다. seal 이후에는 이미 정한 descriptor만
acceptUntil 안에 업로드·확인할 수 있다. 동일 VERIFIED 재확인은 멱등이며, freeze 이후 새 VERIFIED나 descriptor 확장은 불가하다.

NORMAL seal은 전부 VERIFIED이면 즉시 최종화한다. REMOTE/TIMEOUT/LIMIT은 acceptUntil까지 기다린다.
누락을 고정하려면 acceptUntil 이후 시작한 서버 HEAD 확인이 필요하다. 이전 HEAD 404보다 DB에 commit된 ACK를 우선한다.
정확한 객체 version을 순서대로 합치고 알려진 누락 frames만 무음으로 채운다. 손상된 조각을 무음으로 바꾸지 않는다.
최종 WAV 채택과 전사 QUEUED 등록은 같은 DB 트랜잭션이며, 전사·문서 생성은 별도 상태로 진행한다.

녹음 상세·문서 상세의 `sourceAudio`는 다음 형태이며 legacy 원본은 null이다.

```json
{
  "quality": "PARTIAL",
  "missingRanges": [{ "start": 80000, "endExclusive": 160000 }],
  "tailUnknown": false
}
```

품질 경고는 원본 녹음의 고정 manifest에서 읽으며 문서마다 DB에 복제하지 않는다. 오디오 보관이 끝나도 이미 채택한 원본의 누락 경고를 유지한다.

최종화 워커는 `recording.audio-finalization.enabled=false`가 기본값이다. 선행 기능·AI WAV 호환과 배포 환경을 검증한 뒤 별도로 활성화한다.
구현·검증 범위는 [#767 검증 기록](../verification/767-recording-audio-finalization.md)을 따른다.
FE에 먼저 전달한 ZIP은 자동 갱신하지 않았으므로 최신 인계에는 이 문서와 종료 API를 함께 사용한다.
