# AI 작업 지표

인증된 `GET /internal/metrics`는 기존 loopback 서버에서 Prometheus text 0.0.4를 반환한다.
기존 Bearer 필터를 유지하며 새 의존성과 업무 API·슬롯·재시도 정책을 추가하지 않는다.
이 문서의 구현·검증은 Issue #775와 선행 로깅 PR #752를 기준으로 한다.

## 상태와 시간

| 지표 | 의미 |
| --- | --- |
| `knot_ai_ready` | 기존 `accepting && engine.isReady()`. BUSY여도 1일 수 있다. |
| `knot_ai_active_jobs` | worker가 있으면 1. cleanup이 끝날 때까지 유지한다. |
| `knot_ai_job_elapsed_seconds` | 새 작업을 수락해 worker를 시작하는 시점부터 경과한 시간. 유휴 상태는 0이다. |
| `knot_ai_stage_active{stage}` | 현재 실행 중인 단계만 1이다. |
| `knot_ai_stage_elapsed_seconds{stage}` | 현재 단계의 경과 시간이며 다른 단계는 0이다. |
| `knot_ai_jobs_completed_total{outcome}` | cleanup 뒤 결과를 공개할 때 최종 결과를 한 번 집계한다. |
| `knot_ai_stage_duration_seconds_sum/count{stage,outcome}` | 끝난 단계의 누적 시간과 횟수. 실패 단계도 별도 집계한다. |

단계는 `download`, `decode`, `model`, `result_check`, `cleanup`으로 고정하고 결과는
`completed`, `failed`로 제한한다. model은 Whisper·화자 분리 통합 runner 시간이다.
진행률 백분율이나 Whisper 단독 연산 시간이 아니다.

시간은 단조 시계인 `System.nanoTime()`의 차이를 초로 변환한다. 프로세스 재시작 시 누적
카운터가 초기화되며, 수집기를 다시 읽거나 재시작해도 AI 프로세스 상태를 초기화하지 않는다.
같은 ID 재접수와 BUSY·NOT_READY 거절은 완료 횟수에 포함하지 않는다.
worker 시작 실패인 EXECUTOR_REJECTED는 실패로 한 번 집계한다.

cleanup 실패는 readiness를 닫되 성공한 전사 결과를 실패로 바꾸지 않는 기존 정책을 유지한다.
따라서 `jobs_completed_total{outcome="completed"}`와 cleanup 실패가 함께 존재할 수 있다.
결과 공개·busy 해제·최종 집계는 같은 service 잠금에서 처리하며 단계 실행 중에는 관측 잠금을
잡지 않는다. 잠금 방향은 service → metrics뿐이며 metrics가 service를 호출하지 않는다.

## 로그와 경계

기존 `TranscriptionLog.stage`의 event·outcome·attemptId·elapsedMs·예외 계약을 유지한다.
attemptId는 업무 로그에서 연결하고 지표에는 request/attempt/user ID, URL, 전사문,
오디오·토큰·모델 경로를 넣지 않는다. 지표 조회가 업무 작업을 시작하거나 재실행하지 않는다.

단계와 상태 지표는 AI 프로세스 내부 상태이며 실제 수집 성공과는 다르다. Alloy의 `up`과
마지막 수신 시각을 함께 확인하고, 지표 없음은 정상 0으로 채우지 않는다.
실제 모델 테스트는 기존 opt-in `RealPipelineHttpTest`를 사용하되 성공 로그를 확인하려면
root WARN 설정과 별도로 `TranscriptionLog`의 INFO 수준을 허용해야 한다.
