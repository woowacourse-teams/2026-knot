# #722 탐색 지연 수동 실측

문제 상황부터 결론까지의 통합 기록은 [탐색 첫 delta 지연 문제 해결 기록](troubleshooting.md)을 참고한다.

실제 Qwen·Spring HTTP SSE·MCP 문서 도구·Testcontainers PostgreSQL을 실행한다. 기본 `check`와 CI에는 이 소스를 추가하지 않는다. Dev/RDS에 접근하지 않는다. 요청/응답 본문은 로그나 결과에 저장하지 않으며, 각 `LATENCY_RESULT`의 `answerId`로 해당 요청의 phase 로그를 모은다.

Java25·Docker와 인증된 `qwen3.8-27b` 서버가 필요하다. 기본 연결은 SSH 터널 `127.0.0.1:18997 → 모델 서버 127.0.0.1:1234`이다. OpenAI 호환 API 호스트를 직접 쓸 때는 `KNOT_LLM_BASE_URL`에 HTTPS 호스트 주소만 설정한다(예: `https://llm-api.knoted.kr`). 클라이언트가 `/v1/chat/completions`를 붙이며, `/v1` 경로를 포함하면 애플리케이션 URL 검증에 실패한다. 인증 키는 비공개 환경 설정의 `KNOT_LLM_API_KEY`로만 주입하고 키를 명령 인수·소스·실행 기록에 넣지 않는다. 모델 설정과 가중치는 변경하지 않는다.

저장소 `backend/`에서 아래 명령을 한 번씩 실행한다. 각 실행은 순차적으로 3회 워밍업 후 30회 측정한다. `latency.source`는 이 디렉터리의 절대 경로다.

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew --no-daemon \
  -I docs/qa/697-latency/latency.gradle \
  -Dlatency.source="$(cd docs/qa/697-latency && pwd)" \
  -Dlatency.variant=agent -Dlatency.scenario=single_meeting \
  -Dlatency.samples=30 intentLatency

JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew --no-daemon \
  -I docs/qa/697-latency/latency.gradle \
  -Dlatency.source="$(cd docs/qa/697-latency && pwd)" \
  -Dlatency.variant=agent -Dlatency.scenario=cross_meeting \
  -Dlatency.samples=30 intentLatency
```

이 비교를 재현하려면 위 명령을 각각 `-Dlatency.variant=direct-search`로 바꿔 추가 실행한다. 모든 실행은 순차로 하고, 비교할 때 같은 모델·설정·fixture·질문·측정 수를 유지한다. `direct-search`는 QA 소스에만 있는 수동 변형으로 운영 코드의 기본 동작을 바꾸지 않는다.

- `single_meeting`은 DRAFT 문서 하나에 배포 담당자 `흑곰`·날짜 `금요일`을 넣고 `지난 배포 회의에서 배포 담당자는 누구로 정했어?`를 보낸다.
- `cross_meeting`은 6월 10일의 카드 결제 논의와 6월 12일의 계좌 이체 결정·수수료 이유를 각각 DRAFT 문서로 만들고, 변경 내용과 이유를 함께 묻는다.
- 각 `LATENCY_RESULT`에는 시나리오·run index·warmup 여부·answerId·accepted·첫 client delta·전체 종료 시간·예상 사실 확인 결과가 포함된다. 마지막 `LATENCY_SUMMARY`에는 측정 표본의 첫 delta와 전체 종료 p50/p95가 출력된다.
- 시작은 클라이언트의 POST 전송 직전, 첫 delta는 완전한 SSE delta 이벤트를 클라이언트가 수신한 시점이다. CSRF 발급·애플리케이션 부팅·fixture 저장 시간은 제외한다.
- 주기적 작업 선점의 변동을 제거하려고 스케줄러를 끄고 availability 검사만 mock한다. 실제 접수 commit과 accepted 수신 후 제품 worker를 비동기로 한 번 실행한다. 분류기·검색기·생성기·권한·DB·MCP·SSE는 제품 구현이다. 운영 큐 대기·프록시·TLS 지연은 포함하지 않는다.
- delta의 `text`를 JSON으로 파싱해 이어 붙이고 담당자 사실과 COMPLETED를 확인한다. 전송 조각 사이에 이름이 나뉘어도 정상이다.
- `Search latency` 로그는 `queue_wait`, `intent_classification`, `document_retrieval`, `llm_call`·`llm_first_content`·`llm_usage`, `mcp_handshake`·`mcp_call`, `content_persistence`, `sse_first_delta`를 구분한다. 같은 answerId의 `mcp_call` 행 수가 문서 도구 호출 수다. `messageContentChars`는 전송한 대화 본문 문자열 길이일 뿐 토큰 수가 아니며, provider가 숫자 usage를 반환하지 않으면 token 값은 `unavailable`로 남는다.
- `contentPersistedAtToSseSendMs`는 snapshot에서 확인한 대화 `updatedAt`부터 서버 `SseEmitter.send()`가 반환될 때까지의 근사치이며, SSE poll 간격이 포함될 수 있다. 서버 send 반환은 브라우저 수신 확인이 아니므로 실제 첫 delta는 `LATENCY_RESULT.firstDeltaMs`로 본다.

## 2026-10-16 가설 검증 A/B

`qwen3.8-27b` HTTPS API, Testcontainers PostgreSQL, 고정 DRAFT fixture, 로컬 Spring SSE를 사용했다. 각 경로·질문 유형마다 워밍업 3회 후 측정 3회를 실행했고, 측정 12회가 모두 `COMPLETED`, 기대 사실 문자열, 기대 `SearchEvidence.documentId` 검증을 통과했다.

| 질문 유형 | 경로 | 첫 delta p50 / p95 | 전체 완료 p50 / p95 |
| --- | --- | ---: | ---: |
| 단일 회의 | 기존 agent | 8.171 / 8.412초 | 9.667 / 9.835초 |
| 단일 회의 | 수동 direct search | 2.695 / 3.150초 | 4.097 / 4.289초 |
| 회의 간 비교 | 기존 agent | 19.604 / 19.754초 | 22.085 / 22.562초 |
| 회의 간 비교 | 수동 direct search | 2.597 / 2.659초 | 5.463 / 5.551초 |

고정 fixture 기준으로 직접 검색은 첫 delta p50을 단일 회의에서 67.0%, 회의 간 비교에서 86.8% 줄였다. agent의 문서 retrieval은 단일 질문에서 약 5.3–5.5초 동안 탐색 LLM 3회를 호출했고, 회의 비교에서는 약 15.7–16.5초 동안 4회를 호출했다. 이에 비해 MCP 도구 호출은 대체로 수 밀리초에서 수십 밀리초였다. direct search 경로는 기존 classifier와 최종 답변 생성을 유지하면서 질문 원문 검색과 문서 읽기만 수행했고, 두 시나리오의 목표 문서와 핵심 사실을 모두 찾았다.

직접 검색 후 남은 first delta p50은 약 2.6초다. 분류는 대체로 0.8–1.2초였고, `SearchAnswerJsonStream`은 provider가 JSON을 처음 보내도 `answer` 필드에 도달하기 전까지 client delta를 만들지 않는다. `llm_first_content`와 `sse_first_delta` 사이에는 약 1.1초가 관측됐다. 반면 서버 SSE send 자체의 측정값은 대부분 100ms 미만이었다. 다음 최적화 실험은 JSON에서 `answer` 필드를 먼저 내보내도록 요청했을 때 답변·근거 검증을 유지하면서 이 대기가 줄어드는지 확인하는 것이다.

이 실험은 각 조건 3개 표본과 두 개의 고정 질문뿐이다. 표본 p95는 사실상 최댓값이며, 직접 검색이 일반적인 질문·많은 문서·모호한 기간·주제·후속 질문에서도 근거를 보존하는지는 입증하지 않는다. 결과는 탐색 계획 LLM을 모든 질문에 제거할 근거가 아니라, 간단한 질문 빠른 경로를 더 넓은 한국어 정답셋에서 검증할 이유다.

## 2026-10-16 실제 모델 실측

`qwen3.8-27b`를 HTTPS OpenAI 호환 `/v1/chat/completions` endpoint에 연결해 Testcontainers PostgreSQL과 로컬 Spring SSE 경로로 실행했다. 각 시나리오에서 워밍업 3회 뒤 측정 3회를 순차 수행했고, 측정 6회 모두 `COMPLETED` 및 고정 fixture의 예상 사실 확인을 통과했다.

| 시나리오 | 첫 delta p50 / p95 | 전체 완료 p50 / p95 |
| --- | ---: | ---: |
| 단일 회의 | 8.296 / 8.632초 | 9.470 / 9.814초 |
| 회의 간 비교 | 18.547 / 18.882초 | 21.552 / 21.824초 |

단일 회의 phase 로그에서 의도 분류는 819–1,028ms, 문서 탐색은 5,288–5,565ms였고, 탐색 중 순차 LLM 호출이 약 5.2–5.5초를 차지했다. 최종 답변 생성은 첫 provider content까지 907–940ms, 완료까지 3,144–3,174ms였다. 같은 요청의 MCP HTTP 검색·읽기 도구 호출은 대체로 각각 한 자릿수 밀리초여서 병목은 MCP/DB 왕복보다 여러 차례 이어지는 LLM 탐색 호출이다. 회의 간 비교 워밍업의 탐색 phase는 15.7–16.2초로 관측됐다.

지정된 평문 HTTP 주소는 인증 없이 요청했을 때 401을 반환하고 HTTPS로 리다이렉트하지 않았다. 인증 키를 평문으로 보내지 않도록 같은 호스트의 HTTPS 주소로 실제 측정했다. 세 표본의 p95는 개발 fixture에서 관측한 소표본 통계일 뿐 운영 p95나 전반적인 답변 정확도를 보장하지 않는다. `expectedFact` 검사는 각 fixture의 핵심 문자열 포함만 확인한다.

이 측정은 두 개의 고정 질문 흐름을 보는 수동 개발 실험이며 검색 품질 전체나 운영 p95를 보장하지 않는다. `results.csv`의 기존 값은 #697 당시 단일 문서 측정이므로 이번 두 시나리오와 직접 비교 가능한 baseline으로 간주하지 않는다.
