# 녹음 제어·업로드 로그

녹음 한 건은 `recordingId`, HTTP 요청 하나는 `requestId`, 업로드 한 번은 `uploadId`로 찾는다. 전사 실행의 `attemptId` 연결과 AI 단계 추적은 #750에서 확장한다.

## 기록 형식

기존 콘솔 접두사 뒤의 메시지가 JSON이다. 동일 필드를 SLF4J key-value에도 넣는다. 기본 콘솔 패턴은 key-value를 출력하지 않으므로 JSON 메시지에서 필드를 조회한다. 콘솔 한 줄 전체가 JSON인 것은 아니다.

- 공통: `event`, `outcome`, `requestId`, `workspaceId`, `recordingId`, `uploadId`, `attemptId`
- 선택: `errorCode`, `endReason`, `elapsedMs`, 안전한 `diagnostic`
- 아직 식별자가 없거나 HTTP 밖의 작업이면 해당 필드는 null이다.
- `requestId`는 HTTP MDC 값이다. 시작 API의 멱등성 입력 `RecordingStartCommand.requestId`와 다르다.
- 토큰·쿠키·controlToken·tabId·저장소 키·서명 URL·오디오·전사본·예외 메시지는 기록하지 않는다. 진단에는 예외 종류와 코드 위치만 남긴다.

## 이벤트 의미

| event | 정상 결과 | 의미 |
| --- | --- | --- |
| recording.start | created / replayed | 신규 생성 / 멱등 재요청 |
| recording.pause, recording.resume | changed / replayed | 상태 변경 / 이미 적용됨 |
| recording.end | changed / replayed / ignored | 사용자 종료 / 이미 종료 / 자동 종료가 먼저 적용됨 |
| recording.auto_end | changed | 실제 커밋한 자동 종료, endReason 포함 |
| recording.upload_url | issued / reissued | 업로드 URL 발급 / 같은 업로드에 재발급 |
| recording.upload.prepare | prepared / replayed | HEAD 전 검증 / 이미 완료되어 HEAD 생략 |
| recording.upload.verify | found / missing | HEAD에서 객체 발견 / 미발견 |
| recording.upload.complete | completed / replayed | 메타데이터 검증 및 DB 확정 / 이미 완료 |

관측 구간은 `started`를 즉시 기록하고 결과를 한 번 기록한다. DB 구간의 정상 결과는 트랜잭션 완료 콜백에서 기록한다. 거절은 `rejected`, 예상하지 못한 오류는 `failed`, 커밋 결과를 확정하지 못하면 `unconfirmed`다. 외부 트랜잭션이 나중에 롤백하면 정상 결과를 기록하지 않는다.

`auto_end`는 커밋한 사실만 기록한다. 시작·소요 시간은 측정하지 않는다. 정상 heartbeat와 상태 조회는 별도 업무 로그를 만들지 않는다.

`verify=found` 뒤에도 타입·크기·녹음 상태 검증이나 DB 저장이 실패할 수 있다. 업로드 성공 판단에는 `upload.complete=completed/replayed`까지 확인한다.

## 시간·레벨

`elapsedMs`는 구간 진입부터 끝까지 `System.nanoTime()`으로 잰 경과 시간이다. DB 구간에는 잠금 대기·쿼리·커밋/롤백 완료가 포함된다. 트랜잭션 시작 전 연결 확보나 HTTP 전체 시간은 포함하지 않는다.

정상·멱등 재요청·예상 거절은 INFO, `unconfirmed`는 WARN, `failed`는 ERROR다. 여러 계층에서 같은 실패를 기록할 수 있으므로 ERROR 줄 수를 실패한 녹음 수로 세지 않는다.

## 조회 순서

1. 실패한 HTTP `requestId`의 마지막 결과와 `errorCode`를 찾는다.
2. 그 줄의 `recordingId`로 다른 요청의 제어·업로드를 연결한다.
3. `uploadId`별 prepare → verify → complete를 확인한다.
4. 전사 접수의 `attemptId`로 백엔드와 AI 로그를 연결한다.

Loki에서는 현재 수집기의 실제 label을 선택한 다음 JSON 메시지를 추출한다. 식별자를 Loki label로 만들지 않는다. 예시의 `app` label은 환경에 맞게 바꾼다.

```logql
{app="backend"} | regexp `(?P<payload>\{"event":.*\})` | line_format `{{.payload}}` | json | recordingId="123"
```

`started`만 있으면 아직 실행 중이거나 프로세스 종료·수집 유실일 수 있다. 다음 로그가 없다는 이유만으로 특정 단계의 실패를 확정하지 않는다. 이 변경은 로그 수집기·대시보드·배포를 포함하지 않는다.

## 검증 범위

RecordingLogTest는 JDBC 커밋·롤백·커밋 미확정, JSON/KVP 일치와 민감값 제외를 검증한다. RecordingLifecycleLoggingIntegrationTest는 PostgreSQL에서 재요청·외부 롤백·자동 종료와 거절의 동시 발생·heartbeat 생략을 검증한다. RecordingUploadLoggingTest는 사전 거절·재요청·객체 부재를 구분한다.
