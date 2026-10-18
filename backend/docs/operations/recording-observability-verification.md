# Dev 녹음 관측 경로 검증

[#829](https://github.com/woowacourse-teams/2026-knot/issues/829)의 실행 절차다. 업로드 접수, AI 계산, DB 커밋, 결과 조회를 각각 확인한다. 운영 장애 주입과 실제 사용자 음성은 사용하지 않는다.

## 실행 전제

- 실제 대상은 Dev→Mac 18766이다. 실행 직전 배포 기록·JAR 해시·전사 활성 설정·대기/실행 작업 수를 다시 확인한다. Git SHA를 확인하지 못하면 아티팩트 해시와 그 한계를 기록한다.
- 전용 검증 계정의 정상 인증과 Workspace 소속을 먼저 확인한다. 만료 쿠키를 우회하거나 DB 인증 상태를 직접 바꾸지 않는다. CSRF·쿠키·제어 증명·서명 URL·음성·전사문은 증거 문서에 넣지 않는다.
- 기존 작업이 실행 중이면 종료시키지 않고 기다린다. 시험의 계정·녹음·프로세스 소유 범위를 분리한다.
- 합성 음성의 길이·포맷·크기·SHA-256과 AI 모델·프로파일·runner 버전을 기록한다. 일반 개발 작업이 섞인 구간은 AI 단독 성능 기준으로 사용하지 않는다.

## 현재 녹음 경로 확인

최신 FE에는 PCM 조각 녹음이 추가됐다. 구형 WebM 시험만 실행하고 현재 화면의 전체 흐름을 검증했다고 표시하지 않는다. 실제 배포 플래그와 시작 응답의 `audioMode`를 기준으로 아래 경로를 선택하고 기록한다.

| 경로 | 확인할 순서 |
| --- | --- |
| `PCM16_CHUNKS_V1` | 시작 → 조각 예약 → 서명된 S3 PUT → 조각 VERIFIED → 정상 seal → 최종 WAV READY → 전사 QUEUED/PROCESSING → DB 저장·조회 |
| `FINAL_WEBM_V1` | 시작·종료 → 최종 WebM 업로드 예약 → S3 PUT → 업로드 완료 → 전사 QUEUED/PROCESSING → DB 저장·조회 |

PCM은 [조각 저장 계약](../../../docs/api/recording-audio-chunks.md)과 [종료 계약](../../../docs/api/recording-end.md)을 따른다. 실제 시작에서 만든 requestId·tabId·controlToken을 유지하고, 조각 checksum과 frame 수를 바꾸어 재시도를 우회하지 않는다. 조회·조각 저장은 heartbeat를 대신하지 않는다.

## 정상 요청의 증거

1. 시험 시작·종료 시각과 각 HTTP 응답의 requestId를 비공개 증거에 보존한다. HTTP 성공만으로 전사 성공을 판단하지 않는다.
2. 시작·종료 로그의 recordingId로 접수 로그를 찾고, attemptId로 비동기 백엔드와 AI 단계를 연결한다. PCM 최종화처럼 비동기 접수에는 HTTP requestId가 없을 수 있다.
3. AI의 download → decode → model → result_check → cleanup 결과와 각 elapsedMs를 확인한다. 다른 서버의 시각 차이를 빼서 구간 소요 시간으로 사용하지 않는다.
4. 백엔드 `result.completed` 뒤 `persist.completed`와 전사·segment·문서 생성 접수 행을 대조한다. 최종 문서 생성이나 LLM 성공을 이 검증에 포함하지 않는다.
5. 정상 조회 API에서 해당 녹음의 전사 성공과 저장 결과를 확인한다. DB 조회는 읽기 전용으로 수행하고 사용자·Workspace 범위를 제한한다.
6. 같은 시간대의 Grafana `ai_scope=service`에서 fresh 표본·ready·active·단계 시간을 대조한다. 짧은 단계가 scrape 간격 사이에 끝나면 로그·카운터로 보완하고 화면에서 관측했다고 쓰지 않는다.

단계 이벤트가 없거나 ID가 연결되지 않으면 관측 공백으로 기록한다. API 응답이나 DB 조회로 확인한 사실을 해당 업무 로그가 존재하는 증거로 대체하지 않는다.

## 격리된 실패 시험

`RecordingObservabilityFlowIntegrationTest`와 `ObservabilityRealPipelineHttpTest`를 별도 AI 프로세스·PostgreSQL Testcontainers에서 실행한다. 실제 모델과 FFmpeg를 사용하더라도 S3·음성 공급·연결 relay가 대역이면 명시한다. 실행 조건은 [기존 검증 문서](../verification/775-ai-observability.md)를 따른다.

- 손상 입력: AI 디코딩 실패 코드, 백엔드 실패 상태 저장, 전사·문서 접수 부재를 확인한다.
- AI 성공 후 저장 예외: `result.completed`와 `persist.failed`, 트랜잭션 롤백, 최신 lease 복구를 위한 `PROCESSING` 유지를 확인한다. 과거 `RESULT_STORAGE_FAILED` 즉시 종료 기대값을 사용하지 않는다.
- 실패 주입은 실제 Dev의 DB·업무 코드를 바꾸거나 운영 AI를 끊는 방식으로 수행하지 않는다.

## 정리와 완료 판정

시험 프로세스와 데이터 목록을 먼저 확인하고 전용 시험 범위만 정리한다. 기존 수집기 label·다른 AI·다른 대화의 checkout을 종료하거나 삭제하지 않는다. 수집기·AI의 인증 상태와 새 중앙 표본을 다시 확인한다.

결과 보고에는 배포 식별, 실제 요청 경로, 입력 조건, 단계별 결과·시간, DB 커밋·조회, 격리 시험 경계, 관측 공백과 미검증 항목을 남긴다. 인증·배포·중앙 연결 등 전제가 해결되지 않았으면 준비 완료와 실행 완료를 구분한다.
