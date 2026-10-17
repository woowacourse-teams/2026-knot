# AI 관측 경로 검증 — #775

2026-10-18 KST, 정상 저장·실제 음성 변환 실패·AI 성공 후 저장 롤백의 세 시나리오를
실행했고 모두 기대한 상태와 로그를 확인했다. 운영 AI를 교체한 결과는 아니다.

## 구현과 전달

- 선행 로깅 PR #751 → #752 위에 이 변경을 쌓는다. 이 PR의 base는 `be/feature/#750`이다.
- #774 / PR #777: 중앙 EC2 재현 설정.
- #762 / PR #780: Mac 호스트 자원 수집.
- #779 / PR #781: 선택적 AI 지표 수집 모듈과 대시보드.
- 이 PR은 인증된 `/internal/metrics`, 단계 시간·작업 상태 계측과 직접 검증을 포함한다.
- 운영 AI의 병합·교체·전환, 알림, GPU, AI 전용 baseline과 장기 부하는 수행하지 않았다.

## 실제 구성과 fixture 경계

| 구간 | 검증 방법 |
| --- | --- |
| 업로드 완료 요청 | 실제 MockMvc controller·인증·CSRF·RequestIdFilter |
| DB | 실제 PostgreSQL Testcontainers, 상태·전사·segment·문서 intake 행 확인 |
| AI 연결 | 실제 backend HTTP client → localhost 검증 relay → SSH 명령 → Mac loopback HTTP |
| AI 처리 | 별도 검증 프로세스, 실제 FFmpeg·Whisper·화자 모델 |
| 저장소 | S3 HEAD·서명 URL은 stub, Mac HTTP fixture에서 합성 한국어 WebM 제공 |
| 문서 생성 | intake 저장까지 확인. worker 비활성, 외부 LLM 호출 없음 |
| 수집 | Mac Alloy v1.20.1 → 기존에 설치한 Mac 전용 mTLS → 새 중앙 Prometheus |
| 표시 | Grafana `knot-mac-ai`, `ai_scope=validation`, 호스트는 기존 `knot-mac` |

relay는 SSH 포트 포워딩이나 운영 EC2→Mac 연결을 검증한 것이 아니다. 외부 수신 포트를
열지 않았으며 기존 운영 AI 프로세스와 포트는 유지했다. 다른 앱을 모두 종료하지 않았으므로
호스트 수치를 AI 단독 자원 사용량이나 성능 기준으로 해석하지 않는다.

## 시나리오 결과

| 시나리오 | AI 실행 | 백엔드 결과 대기 | 저장 | 최종 결과 |
| --- | --- | --- | --- | --- |
| 정상 | 4,821ms 성공, model 4,547ms | 6,352ms 성공 | 71ms 성공, commit | 전사·segment·문서 intake 존재 |
| 손상된 WebM | decode 215ms 실패, 전체 269ms | 2,312ms 실패 | 실패 상태 commit | `DECODE_FAILED`, 전사 없음 |
| AI 성공 후 저장 예외 | 5,588ms 성공, model 5,269ms | 8,052ms 성공 | 첫 저장 24ms 실패·rollback, 실패 처리 15ms | `RESULT_STORAGE_FAILED`, 전사·intake 없음 |

세 번째는 실제 저장 트랜잭션 안의 intake spy에서 예외를 발생시켰다. 전체 rollback과
별도 실패 상태 저장을 확인했다. DB 엔진 자체 중단이나 네트워크 장애를 재현한 것은 아니다.
첫 저장 예외 로그는 `INTERNAL_SERVER_ERROR`, 후속 최종 실패 처리는 `RESULT_STORAGE_FAILED`다.
변환 실패의 `persist failed`는 실패 결과의 저장을 뜻하므로 그 로그 하나로 DB 장애를
단정하지 않고 선행 단계·errorCode·최종 상태를 함께 확인한다.

업로드 완료 HTTP는 **세 건 모두 200**이었다. AI 완료 카운터는 성공 2 / 실패 1,
백엔드 최종 업무는 성공 1 / 실패 2다. AI 계산 성공과 DB 저장 성공은 별개의 경계다.
서로 다른 시계의 timestamp를 빼서 구간 지연을 계산하지 않는다. 표의 elapsedMs는
각 프로세스가 직접 측정한 값이며 relay·polling으로 백엔드 대기 시간이 추가된다.

## 실패 한 건을 찾는 순서

1. HTTP requestId로 `recording.transcription.accept / accepted` 로그를 찾는다.
2. 그 로그의 attemptId로 비동기 backend와 AI의 로그를 연결한다.
3. 같은 attemptId의 download → decode → model → result_check → cleanup을 확인한다.
4. backend result와 persist의 최종 outcome, errorCode, DB 상태를 비교한다.
5. 발생 시각의 호스트 자원·수집 성공·표본 신선도·busy·단계 경과 시간을 함께 본다.

이번 세 시도의 attemptId가 양쪽 로그에서 일치했다. 비동기 로그의 requestId는 null이며
수락 로그의 연결 고리를 이용한다. 임의 ID·전사문·URL·토큰을 메트릭 라벨에 넣지 않았다.
증거 파일은 허용한 로그 필드만 별도 비공개 경로에 보관했으며 저장소에 원문을 넣지 않는다.

## 수집·인증·정리 확인

- 무인증·잘못된 Bearer는 401, 유효한 검증 토큰은 200과 Prometheus text 0.0.4를 반환했다.
- 중앙에서 up=1, ready=1, active_jobs 0→1과 유휴 복귀를 확인했다.
- 5초 scrape를 2초 간격으로 60초 동안 30회 조회했다. 서로 다른 표본 30개를 뜻하지 않는다.
- 조회 중 active_jobs 최대 1, 관측된 작업 경과 최대 2.389초였다. 전 구간 최대 시간이 아니다.
- 유휴 중 Alloy를 재시작해도 AI 누적 성공 2 / 실패 1은 유지됐다.
- 검증 종료 후 별도 AI와 relay를 종료하고 Alloy는 검증 전 host-only 설정으로 복원했다.
- 호스트 상시 수집은 유지한다. AI 대시보드의 검증 시간대 기록은 남지만 현재 운영 AI 수집은
  활성화하지 않았다. 신선한 표본이 없으면 대시보드는 정상 0으로 채우지 않는다.

## 실행과 검증 결과

- AI 기본 `./gradlew test spotlessCheck bootJar`: 116건, 실행 114 / opt-in 생략 2,
  실패·오류 0, BUILD SUCCESSFUL 33s.
- backend opt-in `RecordingObservabilityFlowIntegrationTest`: 실행 3 / 생략 0,
  실패·오류 0, BUILD SUCCESSFUL 43s.
- Mac opt-in `ObservabilityRealPipelineHttpTest`: 실행 1 / 생략 0,
  실패·오류 0, BUILD SUCCESSFUL 2m58s.
- `ObservabilityRealPipelineHttpTest`는 `AI_REAL_OBSERVABILITY=true`일 때만 실행한다.
  합성 WebM·로컬 모델 profile·runner·검증 토큰·증거 디렉터리가 필요하다.
  ready.json을 확인한 뒤 backend 시나리오를 실행하고 stop 파일로 종료한다.
- backend는 `KNOT_REAL_AI_OBSERVABILITY=true`, `KNOT_REAL_AI_BASE_URL`,
  `KNOT_REAL_AI_TOKEN`, `KNOT_OBSERVABILITY_EVIDENCE_DIR`가 필요하다.
  토큰은 환경으로 주입하며 명령 기록·문서·PR에 복사하지 않는다.
- 기본 CI는 opt-in 실험을 실행하지 않는다. 실제 모델 시험 결과와 기본 CI 결과를 구분한다.
- 준비 과정의 새 테스트 Spotless와 MANDATORY spy 설정 오류는 수정 후 다시 실행했다.
  운영 장애 사례로 분류하지 않는다.
- 기존 소스 감사의 build.gradle LIB-VERSION 2건과 AI 모듈의 DB-stack 요구 4건은
  선행 baseline과 같으며 이번 범위에서 변경하지 않았다. 전체 감사와 Gradle 성공을 구분한다.
