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
