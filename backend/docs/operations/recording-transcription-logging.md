# 전사 실행 로그

백엔드와 AI는 기존 전사 API의 `attemptId`로 연결한다. 같은 실행의 제출·확인·polling은 같은 ID, 사용자 재시도는 새 ID다. `recordingId`는 여러 시도를 묶는다. HTTP 요청의 `requestId`를 AI·Worker에 복사하지 않는다. Worker가 DB에서 고른 작업은 dispatch를 깨운 HTTP 요청의 작업과 다를 수 있다.

## 단계별 경계

| event | 결과 | 측정 범위 |
| --- | --- | --- |
| recording.transcription.accept | accepted / replayed / rejected | 자동 접수 진입→DB 트랜잭션 완료 |
| recording.transcription.retry | accepted / rejected / failed | 재시도 API Service 진입→예약 및 dispatch 반환 |
| recording.transcription.retry_reserve | accepted / rejected / failed | 재시도 예약→DB 트랜잭션 완료 |
| recording.transcription.input | prepared / rejected / failed | 기한 확인·불변 오디오 다운로드 URL 준비 |
| recording.transcription.submit | accepted / rejected / failed / unconfirmed | POST 제출→응답 또는 오류 |
| recording.transcription.confirm | confirmed / unconfirmed | POST 응답 유실 후 같은 ID 조회 |
| recording.transcription.result | completed / failed / unconfirmed | 제출 시작→종료 결과 수신, 네트워크·polling 대기 포함 |
| recording.transcription.persist | completed / failed / ignored | 결과·실패 상태 저장→DB 트랜잭션 완료 |
| recording.transcription.accept (AI) | accepted / replayed / rejected / failed | AI 접수 처리 |
| recording.transcription.execute (AI) | started / completed / failed | AI 실행 시작→정리 후 최종 상태 공개 |
| recording.transcription.download (AI) | started / completed / failed | 파일 다운로드 |
| recording.transcription.decode (AI) | started / completed / failed | ffmpeg 디코딩·오디오 메타데이터 확인 |
| recording.transcription.model (AI) | started / completed / failed | manifest 작성과 통합 runner 실행 대기 |
| recording.transcription.result_check (AI) | started / completed / failed | 생성된 결과 검사 |
| recording.transcription.cleanup (AI) | started / completed / failed | 엔진 작업 디렉터리·프로세스 정리 |

백엔드는 [공통 로그 필드](recording-logging.md)를 사용한다. AI에는 `event`, `outcome`, `attemptId`, `elapsedMs`와 선택적인 `errorCode`, 예외 클래스·원인 클래스·첫 코드 위치가 있다. AI의 모델 구간은 Whisper와 화자 분리 등을 합친 runner이며 Whisper만의 연산 시간은 아니다. AI 시작 로그의 elapsedMs는 시작 직후 경과 시간이다.

## 실패를 해석하는 순서

- `submit=unconfirmed`: 응답을 못 받았다. AI 실행 실패라고 단정하지 않는다. 기존 동작대로 같은 attemptId를 GET으로 확인한다.
- `result=failed`: AI의 FAILED 응답을 받았다. `errorCode`로 AI 내부 실패 단계와 연결한다.
- `result=unconfirmed`: 제한 시간·통신·중단으로 최종 결과를 확인하지 못했다. 기존 정책이 로컬 DB를 FAILED로 바꿀 수 있지만 AI의 실제 실행 결과와 다르다.
- `result=completed` 후 `persist=failed`: AI 결과는 수신했지만 DB 저장이 실패했다. 후속 실패 상태 저장도 별도의 persist 구간이다.
- `persist=ignored`: 이미 끝났거나 사라진 과거 시도의 결과다. 새 전사 실패로 세지 않는다.
- `persist=completed`: 전사본·구간과 문서 생성 **접수**가 커밋됐다. 문서 생성 완료를 뜻하지 않는다.

자동 접수가 BUSY로 거절되어 기존 정책대로 DB FAILED/BUSY가 저장된 경우 `accept=rejected`가 남는다. DB 트랜잭션 자체가 실패하면 커밋 콜백은 정상 결과를 기록하지 않는다.

AI 정리 실패는 cleanup에서 드러난다. 기존 정책대로 연산 성공 결과를 보존할 수 있으므로 cleanup 실패와 execute 완료가 함께 나올 수 있다. 최종 execute 결과는 정리를 마친 뒤 실제 조회 가능한 상태를 공개하면서 기록한다. 정상 상태 조회와 반복 polling에는 매회 업무 로그를 만들지 않는다.

## 레벨과 개인정보

백엔드 failed는 ERROR, unconfirmed는 WARN, 정상·예상 거절은 INFO다. AI의 예상된 TranscriptionException 실패는 WARN, 예상하지 못한 실패는 ERROR, 정상과 접수 거절은 INFO다. 안전한 코드 목록 밖의 원격 오류 문자열은 백엔드 로그에서 UNKNOWN_FAILURE로 바꾼다. 저장 정책은 바꾸지 않는다.

서명 URL·토큰·파일 키·오디오·전사 원문·예외 메시지를 기록하지 않는다. JSON은 기존 콘솔 접두사 뒤의 메시지다. Loki에서는 공통 문서처럼 메시지를 추출한 뒤 두 서버의 attemptId를 검색한다. requestId·recordingId·attemptId를 label로 승격하지 않는다.

기존 `TRANSCRIPTION_ENABLED`가 false이면 전사 접수·AI 실행 자체가 발생하지 않는다. 로그 코드는 이 기능 플래그와 운영 설정을 켜지 않는다.

## 검증과 한계

RecordingTranscriptionLoggingTest는 POST 응답 유실 후 동일 ID 확인, 확정 AI 실패, AI 성공 후 저장 실패, Worker의 HTTP MDC 비상속, polling 로그 수를 검증한다. RecordingTranscriptionStateIntegrationTest는 PostgreSQL 커밋·롤백·재시도·과거 시도를 검증한다. AI의 TranscriptionLoggingTest와 HeadlessTranscriptionEngineTest는 단계 순서·실패 코드·정리 후 상태 공개·민감값 제외를 검증한다.

API·스키마·재시도·슬롯·보관 정책과 의존성을 바꾸지 않는다. 프로세스 강제 종료나 로그 전달 실패를 포함한 exactly-once 기록은 보장하지 않는다. 수집 인프라 배포와 실제 서버 간 운영 검증은 별도다.
