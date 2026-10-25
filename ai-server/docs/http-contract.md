# 전사 작업 HTTP 계약

EC2가 녹음의 presigned GET URL을 보내면 맥미니가 모델을 실행하고 결과를 반환합니다. 기본 주소는 `127.0.0.1:18765`이며 모든 경로에 `Authorization: Bearer <AI_APP_TOKEN>`이 필요합니다.

| 요청 | 응답 |
| --- | --- |
| `GET /health` | 준비되면 200 `{"status":"UP"}`, 준비되지 않으면 503 `{"status":"DOWN"}` |
| `POST /api/v1/transcription-jobs` | 202, `Location` 헤더와 현재 작업 상태 |
| `GET /api/v1/transcription-jobs/{attemptId}` | 200, 현재 작업 상태와 완료 결과. 현재 ID가 아니면 404 `JOB_NOT_FOUND` |

삭제·취소 API는 없습니다. health는 실행 슬롯의 여유를 뜻하지 않으므로 작업 중에도 UP일 수 있습니다.

## 접수 본문

```json
{
  "attemptId": "00000000-0000-4000-8000-000000000001",
  "downloadUrl": "https://example-bucket.s3.ap-northeast-2.amazonaws.com/recordings/example.webm?X-Amz-Signature=EXAMPLE",
  "speakerCount": null
}
```

`attemptId`는 필수 UUID, `downloadUrl`은 필수 문자열입니다. `speakerCount`를 생략하거나 null로 보내면 화자 수를 자동 추정하며, 지정할 때는 양의 정수를 사용합니다. 위 URL은 형식 예시이므로 실제 요청에는 유효한 presigned URL이 필요합니다.

EC2는 업로드가 끝난 변경되지 않는 S3 객체의 URL을 발급해야 합니다. AI 서버는 별도 ETag·versionId를 받거나 객체 불변성을 보장하지 않습니다. HTTPS, 기본 포트 또는 443 포트, 설정에 등록된 정확한 AWS S3 호스트만 허용합니다. 사용자 정보·fragment·리다이렉트는 거부합니다. S3 응답은 `Content-Type: audio/webm` 또는 `audio/wav`와 양의 `Content-Length`가 필요하며 실제 다운로드 크기가 선언된 길이와 같아야 합니다. `audio/wav`는 다운로드 직후 RIFF/WAVE 컨테이너·선언 길이·chunk 경계와 PCM16LE mono 16kHz 형식을 검사합니다. 비어 있거나 2시간을 초과한 WAV, MIME만 WAV로 표시한 다른 파일, 손상되거나 잘린 WAV는 변환 전에 거부합니다. 기존 WebM은 FFmpeg 변환 경로를 유지합니다.

## 상태와 결과

접수와 조회는 같은 `{attemptId,status,failureCode,result}` 구조를 반환합니다.

```json
{
  "attemptId": "00000000-0000-4000-8000-000000000001",
  "status": "SUCCEEDED",
  "failureCode": null,
  "result": {
    "schemaVersion": 1,
    "rawWhisperJson": "{\"transcription\":[{\"text\":\"안녕하세요\"}]}",
    "utterances": [
      {"ord": 0, "speakerLabel": "speaker_0", "startSec": 0, "endSec": 1, "text": "안녕하세요"}
    ]
  }
}
```

상태는 `PROCESSING`, `SUCCEEDED`, `FAILED`입니다. `PROCESSING`에는 파일·프로세스를 정리하는 시간도 포함됩니다. 실패하면 `failureCode`에 원인을 담고, 성공 이외의 `result`는 null입니다. 작업이 FAILED여도 조회 자체의 HTTP 응답은 200입니다.

`rawWhisperJson`은 Whisper 원본 JSON의 UTF-8 문자열입니다. `utterances`는 시작 시간순이며 `ord`가 0부터 연속됩니다. 유효한 발화가 없거나 원본만 있으면 성공으로 처리하지 않습니다.

## 단일 작업과 재시도

동시에 실행하는 작업은 하나이며 대기열은 없습니다. 현재 작업만 메모리에 보관합니다.

| 상황 | 동작 |
| --- | --- |
| 접수 가능한 서버에 현재 ID로 POST | URL·화자 수가 달라도 기존 상태 반환. 다시 실행하지 않음 |
| 실행·정리 중 다른 ID로 POST | 409 `BUSY` |
| 정리 완료 후 새 ID로 POST | 이전 결과를 새 작업으로 교체 |
| 교체된 ID를 GET | 404 `JOB_NOT_FOUND` |
| 서버 종료 중이거나 정리에 실패한 뒤 POST | 503 `NOT_READY` |

EC2는 성공 결과를 저장한 뒤 다음 작업을 제출해야 합니다. 조회만 하고 새 작업을 보내면 이전 결과를 다시 읽을 수 없습니다. 실패한 작업을 재실행하려면 새 `attemptId`를 사용합니다. 과거 ID의 기록은 없으므로 이미 교체된 ID를 POST하면 새 작업으로 실행될 수 있습니다. EC2가 요청 순서를 관리해야 합니다.

재시작하면 상태와 결과가 사라집니다. EC2는 조회의 404를 확인한 뒤 전사를 재요청할 수 있습니다. 재시작을 넘는 요청 중복 제거와 결과 복구는 제공하지 않습니다.

## 제한과 정리

| 항목 | 상한 또는 기본값 |
| --- | --- |
| 다운로드 크기 | 500MiB |
| 디코딩한 오디오 길이 | 7,200초 |
| 결과 파일 크기 | 256MiB |
| 연결·읽기 timeout | 각각 10초·30초, 코드 상수 |
| 전체 다운로드 timeout | 15분, `AI_DOWNLOAD_TIMEOUT` |
| FFmpeg 변환 timeout | 15분, `AI_CONVERSION_TIMEOUT` |
| 모델 실행 timeout | 12시간, `AI_INFERENCE_TIMEOUT` |

다운로드 후 FFmpeg로 앞 7,200초까지 PCM16 mono 16kHz WAV로 변환하고 실제 PCM 길이를 검사합니다. 2시간 미만 입력은 원래 길이를 유지하며, 초과 입력은 앞 2시간만 전사합니다. 업로드한 S3 원본은 변경하지 않습니다. 다운로드·출력 크기 제한과 변환 결과의 PCM 형식·길이 검증은 유지합니다. runner는 제한된 WAV를 음량 정규화한 뒤 Whisper와 화자 분리를 병렬 실행하고 발화를 병합합니다.

Python supervisor는 제한 시간과 부모 JVM 생존 여부를 감시하고 소유한 프로세스 그룹을 정리합니다. 서버 시작 때도 잔존 프로세스를 먼저 회수합니다. 소유권을 확인할 수 없으면 서버 시작 또는 새 작업 접수를 막습니다. 정리 대상은 전용 work directory의 UUID 작업 디렉터리입니다.

## 오류 응답

접수·조회 오류는 HTTP 상태와 코드·정적 한글 메시지로 반환합니다. 검증 오류에는 `fieldErrors`를 포함하며 입력값 원문은 넣지 않습니다.

```json
{"code":"BUSY","message":"다른 전사 작업이 실행 중입니다","fieldErrors":[]}
```

인증 실패는 401 `{"code":"UNAUTHORIZED","message":"앱 인증이 필요합니다"}`를 반환합니다. 토큰·서명 URL·녹음·발화 원문은 로그에 출력하지 않습니다.

### 구간 전사 (내부 계약)

기존 요청에 선택 필드 `window`를 추가한다. 생략하면 전체 파일 전사 동작을 유지한다.

```json
{"window":{"pipelineVersion":"pcm-window-v1","inputManifestSha256":"64자리 소문자 SHA256","inputStartFrame":0,"inputEndFrame":2080000,"decodePolicy":"WITH_VOCABULARY"}}
```

입력은 서버가 조립한 16 kHz mono PCM16 WAV이며 최대 130초(새 음성 2분과 직전 10초)다. frame 범위와 실제 WAV 길이가 같아야 한다.

선택 필드 `decodePolicy`는 구간의 디코딩 횟수를 정한다. 생략하면 `DEFAULT_ONLY`이며 그 밖의 값은 400 `INVALID_REQUEST_BODY`다.

- `WITH_VOCABULARY`: 녹음 중 구간. 기본 디코딩 뒤 고정 용어 프롬프트로 한 번 더 디코딩하고, 앞뒤 단어가 같은 3단어 이하 범위에서 용어만 새로 맞힌 곳만 그 단어로 바꾼다. 숫자·부정 표현·시각이 달라지는 교체와 단어 삽입·삭제는 받지 않는다.
- `DEFAULT_ONLY`: 녹음의 마지막 구간. 종료 후 대기를 줄이려고 기본 디코딩만 쓴다.

두 정책 모두 기본 디코딩에 반복 사고(짧은 token 연속 반복, 긴 구절 반복 고리, 같은 질문 반복)가 있으면 이전 문맥을 이어받지 않는 디코딩(`-mc 0`)을 한 번만 더 실행한다. 재시도 결과에 반복이 없고 단어 수가 기본 결과의 70% 이상일 때만 그 결과를 쓴다.
용어 디코딩과 재시도는 실패하거나 120초 안에 끝나지 않으면 버리고 기본 디코딩 결과로 성공한다. `rawWhisperJson`은 고른 기본 또는 재시도 디코딩의 원문이며 용어 교체는 `tokens`와 `utterances`에만 반영된다. 결과의 `window`에는 `decodePolicy`를 돌려주지 않는다.
같은 attemptId에서 URL 재발급은 허용하지만 구간 메타데이터 또는 화자 수를 바꾸면 409 ATTEMPT_CONFLICT다.
결과의 선택 필드 `window`에는 schemaVersion=1, 요청 메타데이터, modelVersion,
`tokens[{startFrame,endFrame,text,speakerId}]`, `speakers[{speakerId,embedding,voicedFrames}]`가 담긴다.
토큰 시간은 입력 WAV의 시작을 0으로 삼는 frame이다. 실제 단어 시간이 없는 유음 결과는 실패한다.
각 화자 벡터는 실제 음성으로 계산한 512차원 유한수 목록이며 샘플이 부족하면 빈 목록이다.
화자 ID는 해당 구간 내부에서만 유효하다. 텍스트가 있으나 겹치는 화자 구간이 없는 단어는 unknown으로 보존한다.
무음 구간은 빈 토큰·발화로 성공할 수 있다. 전체 결과의 UTF-8 크기는 1 MiB 이하로 제한한다.

구간 실행에는 프로필에 절대 경로 `sherpaLibrary`, `pythonBinary`가 필요하다.
현재 ctypes 구조체는 실제 검증한 sherpa-onnx 1.13.6 ABI만 허용한다.
기존 전체 전사 준비 상태 확인은 이 선택 설정에 영향받지 않는다.
벡터와 원문은 작업 디렉터리의 비공개 파일에만 기록하며 애플리케이션 로그에 출력하지 않는다.

Whisper의 실제 단어 시간이 WAV 끝을 소폭 넘을 수 있으므로 겹치는 시간만 입력 frame 범위로 자른다.
입력 범위와 전혀 겹치지 않는 단어는 제외한다. 구간 중앙 시각으로 단어 시간을 추정하지 않는다.

입력 안에서 길이가 0인 단어는 같은 Whisper segment의 인접한 양의 길이 단어와 묶어 텍스트를 보존한다.
선행 단어가 있으면 그 단어 뒤에 붙이고, segment의 맨 앞에서 연속되면 다음 양의 길이 단어 앞에 붙인다.
묶음의 시작·끝은 원래 단어 시각들의 최솟값·최댓값이며 입력 frame 범위로 자른다.
해당 토큰의 `text`에는 여러 단어가 들어갈 수 있고, 묶인 단어만의 독립 시각을 제공하지 않는다.
양의 길이 단어가 전혀 없는 segment는 `INVALID_RESULT`로 실패한다.
음수·역전·비정상 시각은 계속 거절하며, 다른 segment의 시간을 빌리거나 임의 길이를 만들지 않는다.

## 음성 변환 준비

전체 파일을 모델 실행 슬롯에서 분리하기 위한 내부 API다. 인증은 기존 Bearer 토큰을 적용한다.
다운로드 URL은 기존 S3 호스트·경로 검증을 그대로 적용한다.

- `POST /api/v1/audio-preparations` — `{attemptId,downloadUrl}`, HTTP 202.
- `GET /api/v1/audio-preparations/{attemptId}` — HTTP 200 작업 snapshot.
- `GET /api/v1/audio-preparations/{attemptId}/audio` — 성공한 WAV, HTTP 200 `audio/wav`와 정확한 Content-Length.

```json
{"attemptId":"UUID","status":"SUCCEEDED","failureCode":null,"result":{"schemaVersion":1,"normalizationVersion":"pcm16le-16k-mono-v1","totalFrames":160000,"byteLength":320044,"checksumSha256":"Base64 SHA256"}}
```

상태는 PROCESSING/SUCCEEDED/FAILED다. 성공 전에는 result가 없으며 실패는 정적 failureCode를 반환한다.
오디오는 44바이트 canonical RIFF/WAV 헤더와 16 kHz mono PCM16LE 본문으로 구성한다.
최대 2시간, 230,400,044바이트이며 SHA256은 헤더를 포함한 전체 파일 기준이다.
`normalizationVersion`은 형식 변환 버전이며 음량 정규화를 뜻하지 않는다.

준비 작업은 독립된 단일 worker에서 다운로드·FFmpeg(디코딩 threads=1)만 실행한다.
Whisper/sherpa의 단일 추론 슬롯과 별도로 동작한다. 같은 attempt는 URL 갱신에도 현재 결과를 재생한다.
다른 attempt가 진행 중이거나 현재 오디오를 전송 중이면 BUSY다.
성공·실패 snapshot과 준비 WAV의 캐시 유효 기간은 완료 후 15분이다. 다음 준비 요청을 먼저 수락하면 이전 캐시를 제거한다.
만료된 GET·audio 요청은 JOB_NOT_FOUND를 반환한다. 1분 주기의 정리 작업은 worker와 활성 reader가 없을 때 파일을 제거한다.
이미 시작한 파일 전송은 만료 후에도 종료까지 파일을 고정하며, 마지막 reader가 닫힐 때 만료 파일을 정리한다.
만료된 같은 attempt는 갱신된 downloadUrl로 다시 생성할 수 있다. 정리 실패 시 새 작업 접수를 막는다.

임시 저장소는 `ai.work-directory`의 형제 디렉터리 `<work-directory 이름>-preparations`다.
권한 0700의 루트 아래 UUID 작업 디렉터리를 사용하며 추론 작업 저장소와 섞지 않는다.
재시작 시 기존 supervisor의 프로세스 소유권 검사를 거쳐 이전 준비 작업·부분 파일을 제거한다.
심볼릭 링크나 UUID가 아닌 항목이 발견되면 시작을 중단한다.
