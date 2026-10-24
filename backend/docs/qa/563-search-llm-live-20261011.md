# #563 실제 LLM 서버 연결 테스트

- 확인: 2026-10-11 12:39 KST.
- 브랜치: `be/feature/#563`, 부모 #565 HEAD `6932506261df8c88276be3b4f66bded58b582361` 위 미커밋 구현.
- 사용자 요청: 현재 코드로 `https://llm-api.knoted.kr` 테스트.
- 서버 모델: 인증된 `GET /v1/models` HTTP 200에서 `qwen3.8-27b` 확인.
- 인증은 사용자 제공 토큰을 실행 프로세스 메모리·환경 변수로만 전달했다. 토큰을 설정 파일·문서·명령 인자에 저장하지 않았다.

## 실제 관측

| 경로 | 결과 | 관측값 |
| --- | --- | --- |
| 인증 없는 모델 목록 | HTTP 401 | LM Studio Bearer 토큰 필요 |
| 인증된 모델 목록 | HTTP 200 | 생성 모델 qwen3.8-27b 및 embedding 모델 2개 |
| 서버 직접 stream=true 호출 | HTTP 200, text/event-stream | 3,622ms, SSE data frame 20개, 본문 delta 18개, finish_reason=stop, [DONE] |
| 현재 LmStudioClient.complete() | 성공 | 2,560ms, stream=false, strict JSON schema의 answer를 파싱 |
| 현재 탐색 POST + 실제 모델 stream | HTTP 200, accepted 첫 이벤트 | accepted 167ms, 총 2,823ms, 실제 모델 delta 18개 |
| 탐색 worker 부분 저장 | 성공 | delta callback 18번마다 즉시 DB 본문을 읽어 누적 내용 일치 확인 |
| 종료 저장·SSE·조회 | 성공 | COMPLETED, evidence·completed 이벤트 뒤 EOF, Document 근거 1개 저장, GET messages 본문/상태 일치 |

시간은 단일 호출의 관측값이다. 반복 부하·p95·동시 생성·운영 성능 기준을 의미하지 않는다.

## 입력과 출력

실제 서버에는 아래 합성 문서만 보냈다. 사용자 녹음·운영 Document·Transcript는 읽거나 전송하지 않았다.

```text
문서: 출시일은 10월 20일이다. 담당자는 민지이다.
질문: 출시일과 담당자는 누구야?
```

completion과 streaming에서 모두 다음 답변을 받았다.

```text
출시일은 10월 20일이고 담당자는 민지이다.
```

## 실행한 코드와 격리 범위

기존 production `LmStudioClient`, 탐색 Controller·접수 Service·Worker·부분 저장 Service·SSE transport·메시지 GET을 사용했다. 로컬 RANDOM_PORT Spring 서버와 Testcontainers PostgreSQL을 실행했다. 운영 DB에 데이터를 저장하거나 서비스를 배포하지 않았다.

현재 production 검색/생성 adapter는 미연결이므로 임시 진단 테스트에서 다음을 연결했다.

- `SearchGenerationAvailability`는 테스트에서 허용했다. 실제 기본 enabled=false 설정은 바꾸지 않았다.
- `SearchDocumentRetriever`는 합성 문서 한 개와 excludedCount=0을 반환하도록 고정했다. 검색 전략·색인 준비·제외 수 정책을 선택한 구현이 아니다.
- `SearchAnswerGenerator`의 테스트 호출은 실제 서버에 `stream=true` 요청을 보냈다. 공급자의 SSE delta.content를 그대로 callback에 전달했으며 완성된 답변을 인위적으로 분할하지 않았다.
- 사용 근거 ID는 해당 단일 테스트 Document ID를 반환하도록 고정했다. production에서 모델이 사용 문서를 보고하는 계약이나 실제 검색 정확성 검증은 아니다.
- 기본 reasoning=off, temperature=0, max_tokens=256. 추론 필드를 답변 callback에 전달하지 않았고 finish_reason=stop·[DONE]을 검사했다.

임시 진단 코드는 Git에서 제외한 `backend/build/manual-llm-probe/`에 있다. 기존 build.gradle·production adapter·설정·migration은 이번 진단에서 수정하지 않았다. `init.gradle`을 명시한 실행에만 테스트 소스를 추가하며 일반 CI 테스트에는 넣지 않는다.

```sh
./gradlew --init-script build/manual-llm-probe/init.gradle \
  acceptanceTest --tests '*SearchQuestionLiveProbeTest' --console=plain
```

실행 프로세스에 `KNOT_LIVE_LLM_TOKEN`을 별도로 제공해야 한다. 실제 토큰을 명령 인자에 넣지 않는다.

결과는 BUILD SUCCESSFUL in 21s. JUnit XML: tests=2, failures=0, errors=0, skipped=0. 기존 1,943개 전체 회귀 검사와 별개의 실제 호스트 진단 두 건이다.

## 남은 구현

이번 결과로 실제 서버의 현재 인증·completion·SSE와 기존 탐색 저장/전송 흐름의 연결 가능성을 확인했다. #563 전체 완료로 판정하지 않는다.

실제 검색 방식, 문서 준비 완료·제외 수 기준, production 스트리밍 adapter의 frame/timeout/취소/출력 한도/실패 분류, 모델 사용 근거 ID 검증과 답변 품질 평가가 남았다. FE 화면·운영 프록시·서버 재시작·동시 부하도 이번 실서버 진단에서 관측하지 않았다. 부모 V37 migration 충돌은 이전 상태를 유지한다.
