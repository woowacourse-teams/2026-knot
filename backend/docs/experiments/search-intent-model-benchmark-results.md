# Knot 한국어 탐색 의도 모델 비교 결과

실험일: 2026-10-14~15 KST. 서버에서 실제 모델을 실행한 관측과 독립 Python 실행기의 검증 결과다. 제품 Spring 서버·SSE·DB에 분류기를 연결한 결과는 아니다.

## 판단

현재 관측에서는 **Qwen에 다섯 가지 의도만 JSON으로 판정하게 하는 방식**이 가장 정확했다. 단, 비활성화를 요청한 필드와 실제 thinking 동작을 구분해야 한다. 재로드 후 기존 필드만 사용한 반복 평가에서는 출력 잘림이 나타났고, 실제로 동작한 `reasoning_effort=none`으로 test400을 재실행해 **399/400·실행 오류0**을 확인했다. 기존 탐색 첫 호출의 `answer_without_search(answer)`에 자유 답변을 맡기는 방식은 범위 밖 요청을 안정적으로 막지 못했다. 경량 모델이라는 이유만으로 Laya·GLiNER·Von을 한국어 게이트에 넣기는 어렵다.

10월15일 추가한 **D3-Flash 9B는 391/400(97.75%), warm p50/p95 177/224ms**였다. 이 표본에서는 Nimble·Clef보다 정확하고 일반 입력 지연도 낮아 별도 분류기 중 우선 검토할 후보가 됐다. Qwen의399/400에는 못 미치며 긴 입력 지연은 Qwen보다 높았다. 혼합 요청 정책과 명확화 오분류를 검토해야 하므로 전체 정확도 한 숫자로 운영 채택을 결정하지 않는다.

## 잠금 test 400개 결과

| 모델 | 5분류 정확도 | macro F1 | 검색 손실 / 200 | 범위 밖 오허용 / 120 | 후속 질문 / 65 | warm p50 / p95 |
|---|---:|---:|---:|---:|---:|---:|
| Qwen 27B JSON, 명시적 effort none | **99.75% (399/400)** | 0.9926 | **0% (0)** | **0% (0)** | 100% | 562 / 701ms |
| D3-Flash 9B | 97.75% (391/400) | 0.9616 | 2.5% (5) | 0% (0) | 100% | **177 / 224ms** |
| Nimble 9B | 96.25% (385/400) | 0.9176 | 2.5% (5) | 0% (0) | 100% | 326 / 394ms |
| Clef-Flash 9B | 95.5% (382/400) | 0.9243 | 1% (2) | 4.17% (5) | 96.92% | 190 / 243ms |
| Kev 4B | 87% (348/400) | 0.7112 | 8% (16) | 0% (0) | 98.46% | 314 / 373ms |
| Laya Multilingual 322M | 46.25% (185/400) | 0.4160 | 57% (114) | 35% (42) | 36.92% | 56 / 94ms |
| GLiNER2.5-multi-Decide 287M | 40.25% (161/400) | 0.1922 | 71% (142) | 15.83% (19) | 55.38% | 34 / 52ms |
| Von 395M, 영어 전용 진단 | 5% (20/400) | 0.0191 | 100% (200) | 1.67% (2) | 0% | 49 / 78ms |

이 여덟 군의 정상 환경 본 test 실행 실패는 각각 0개다. D3의 컴파일러 설치 전 실행 실패, Qwen 재로드 후의 실패와 첫 호출 재현의 HTTP 실패는 아래 별도 결과로 구분한다. GLiNER는 입력 한도 사전 거절을 추가한 뒤 본 평가를 재실행했으며 판정은 동일하고 지연은 재실행 값을 사용한다. D3만10월15일 추가 실행했고 다른 모델은10월14일 관측값을 재사용했다.

Qwen 표는 수정 후 `qwen-off-test`다. 400개 모두 `reasoningChars=0`이고 실패0이다. 초기 `qwen-test`도399/400이었지만 실제 reasoning 길이를 저장하지 않았으며 latency596/1404ms였다. 초기 결과·재로드 후 실패·명시적 제어 후 재평가를 각 디렉터리에 보존했다.

| 모델 | family bootstrap 95% | 원형 seed 80개 | 표현 5개를 모두 맞힌 family / 80 | 기록 조회·새 작업 대조쌍 / 25 |
|---|---:|---:|---:|---:|
| Qwen JSON | 99.25~100% | 80 | 79 | 25 |
| D3-Flash | 94.25~100% | 78 | 78 | 25 |
| Nimble | 91.25~100% | 77 | 77 | 25 |
| Clef | 91.25~98.75% | 77 | 73 | 25 |
| Kev | 80~93.25% | 71 | 65 | 24 |
| Laya | 38~55% | 43 | 19 | 1 |
| GLiNER | 30.75~49.5% | 28 | 23 | 0 |
| Von | 1.25~10% | 4 | 4 | 0 |

대조쌍은 관련 주제에서 기존 기록 조회와 새 산출물 작성 두 판정을 모두 맞힌 경우만 성공이다. 엄밀한 단어 하나 교환의 minimal pair는 아니다. 이 구간은 합성 표본에서 계산한 불확실성이지 실제 사용자 분포에서 보장되는 범위가 아니다. D3·Nimble·Clef 간 정확도 차이를 통계적으로 확정된 우열이라고 부르지 않는다.

## 평가 정책과 정답셋

- `MEETING_SEARCH`: 기존 회의 기록 조회·요약·비교. 자료가 실제로 있는지는 분류 단계의 기준이 아니다.
- `GREETING_OR_THANKS`: 인사·감사·짧은 확인만 있는 요청.
- `SERVICE_GUIDE`: Knot 기능 사용법.
- `NEEDS_CLARIFICATION`: 이전 대화로도 질문의 대상을 알 수 없음.
- `OUT_OF_SCOPE`: 일반 지식, 새 코드·일정·기획안·레시피 작성, 실제 실행. 회의 결정을 바탕으로 새 산출물을 만드는 요청도 제외한다. 이 경계는 사용자가 확정했다.

회의 조회와 범위 밖 작업이 섞인 경우 조회 부분을 살리도록 `MEETING_SEARCH`로 분류한다. 이 판정은 나머지 작업을 허용하지 않는다. 분류 이후의 답변 생성에서도 범위 제한이 필요하다.

혼합 요청의 부분 허용은 이 실험에서 고정한 평가 규칙이며 계획에서는 정책 후보였다. 제품 통합 전에 통째 거절과 부분 검색 중 최종 동작을 정해야 한다. Nimble의 검색 손실 5개는 모두 이 혼합 질문의 변형이다. 나머지 검색 정답 195개는 전부 맞혔으므로 이 손실을 순수한 기록 조회 전반의 실패라고 일반화하지 않는다.

정답셋은 **Codex가 작성한 합성 seed 100개 × 표현 5개 = 500개**다. dev 20 family/100개, test 80 family/400개로 family를 분리했다. test는 검색 200·범위 밖 120·인사 40·기능 안내 20·명확화 20개다. 독립적인 실제 사용자 질문 500개나 사람의 별도 검수를 받은 정답이 아니다. 표현 변형 일부는 어색하거나 애매하다.

정답과 split은 모델 입력에 제공하지 않는다. 입력은 이전 대화와 현재 질문뿐이다. 본 평가를 보고 정답·프롬프트·임계값을 고치지 않았다. 골드 manifest의 정규화된 JSON 레코드 SHA-256:

`0337247f232df103c8df91faedc28199952b2a22ed39352760046ffda7439bbd`

JSONL 파일 바이트 SHA-256은 `5a20011bb8ac9cf88fd3c192640f6901f4146af7cb23cfcc5b3b6622ba85321e`이며 위 논리 레코드 지문과 구분한다. test400의 실제 공통 입력 지문은 `d3b25b6c93ae5440d3a1b832df05fee5210be9786cec16af65e585a1da4a820e`다. d3 확장 준비 중 Mac·서버 파일 바이트와 기존 run의 입력 지문이 각각 일치함을 확인했다.

추가 stress 20개는 별도 family다. 열 개는 현재 질문이 정확히 2,000 코드 포인트이고, 열 개는 긴 이전 메시지 20개다. 긴 이력은 일부 모델의 토큰 한도를 넘는다. 입력이 잘린 사례는 정상 판정으로 인정하지 않는다.

## 측정값을 읽는 방법

다섯 의도 정확도는 오류도 400개 분모에 포함한다. 검색 손실은 검색 정답 200개 중 잘못된 의도 또는 실행 실패의 비율이다. 범위 밖 허용은 범위 밖 정답 120개를 SEARCH·인사·기능 안내로 보낸 비율이다. 명확화로 잘못 보낸 사례는 정확도 오류에 포함하지만 허용률에는 포함하지 않는다.

첫 호출 재현은 `SEARCH`/`DIRECT` 두 경로의 정확도다. `DIRECT`는 거절과 실제 일반 답변을 모두 포함하므로 다섯 의도 정확도·답변 누출률과 동일하지 않다. 본문을 별도로 읽어 구분했다.

지연은 유효한 추론의 warm p50/p95이며 시작·가중치 다운로드·실패 시간은 포함하지 않는다. Qwen은 Windows LM Studio HTTP/Q8_0, 다른 모델은 WSL CUDA native scorer다. 전송·정밀도·runtime 차이가 있으며 동일 배포 환경의 속도 순위로 단정할 수 없다. 초기 작은 모델 실행의 자원 겹침도 있어 지연 비교는 참고값이다.

정확도의 95% 구간은 80개 family 단위 bootstrap 2,000회다. 표현 다섯 개를 독립 표본으로 보지 않는다. 동일 입력 반복은 안정성 검사이며 정확도 표본을 늘리는 용도로 합산하지 않는다.

## 기존 첫 호출의 범위 이탈

### Qwen 비활성화 제어의 실제 차이

Qwen 재로드 뒤 기존 `chat_template_kwargs.enable_thinking=false`·`reasoning.enabled=false`만 사용하는 100개×3회 반복에서 첫 회 오류 포함 정확도는 **64%**, 유효65·출력 잘림35개였다. 세 회의 판정/오류 상태가 동일했으므로 단순한 무작위 흔들림이 아니었다. 같은 설정의 stress20에서도 출력 잘림12개, 정확도40%였다. 이 결과를 초기 test399/400에 섞거나 삭제하지 않았다.

실패했던 `MEETING_SEARCH-012-v3`를 실제 API 응답의 usage로 진단했다:

| 요청 변형 | HTTP | 종료 | reasoning 토큰 | 관측 |
|---|---:|---|---:|---|
| 기존 비활성 요청 + max_tokens128 | 200 | length | 122 | JSON이 중간에 잘림 |
| 동일 요청 + max_tokens2048 | 200 | stop | 122 | 완료하지만 불필요한 reasoning 비용 유지 |
| `reasoning_effort=none` + 128 | 200 | stop | **0** | 8개 출력 토큰으로 정상 JSON |
| `reasoning="off"` OpenAI 호환 요청 | 200 | length | 122 | 이 경로에서 원하는 제어가 되지 않음 |
| top-level `enable_thinking=false` | 200 | length | 122 | 이 경로에서 원하는 제어가 되지 않음 |
| native `/api/v1/chat`, `reasoning="off"` | 200 | 정상 message | **0** | 실제 비활성화 확인 |

native API의 reasoning 제어는 [LM Studio 공식 REST 계약](https://lmstudio.ai/docs/developer/rest/chat)을 확인했다. 위 각 필드의 효과는 이 서버에서 직접 관측한 결과이며 모든 OpenAI 호환 서버에 일반화하지 않는다. 초기 load의 실제 prediction 설정을 통째로 snapshot하지 않았으므로 어떤 내부 설정이 달라졌는지까지 확정하지 않는다.

실행기에 `reasoning_effort=none`을 요구하는 RED 테스트와 응답의 실제 `reasoning_content` 길이를 관측하는 RED 테스트를 추가했다. 여기에 공개 API의 User-Agent 계약 RED를 추가해 최종 pytest35 PASS다. 수정된 요청은 모든 Qwen 관측에 `reasoningChars`를 남긴다. 프로덕션의 환경변수나 Spring 요청은 변경하지 않았다. 이 설정 수정 후 동일 잠금셋 재평가는 실행 제어 검증이며 완전히 새로운 holdout 평가가 아니다.

### 기존 필드만 사용하는 첫 호출 재현

현재 prompt·tools·history 형태와 도구가 없을 때 한 번 재안내하는 흐름을 독립 HTTP 실행기로 재현했다. 실제 문서 도구를 실행하거나 Spring에서 프론트까지 연결한 E2E 테스트는 아니다. 문서 도구 이름을 기준으로 경로를 판정했으며 모든 도구 인자의 제품 유효성까지 검증하지 않았다.

재현 대상은 이 작업 공간의 `SearchMcpDocumentRetriever`·`SearchMcpTools`다. retriever의 마지막 변경 commit은 `1b74326f2c49f1b08f9d80a786c0dc036a42ee64`였다. frozen prompt는 ARCHIVED 정책이며 아직 병합되지 않은 DRAFT 확장이나 다른 원격 배포의 코드를 재현한다고 주장하지 않는다. Qwen 두 비교는 temperature0·thinking 비활성을 요청했고 별도 분류 max_tokens128, 첫 호출2048이다. 실제 thinking 비활성 관측은 수정 후 기록과 구분한다. Dev의 모든 환경변수까지 동일하게 복제한 것은 아니다.

고정 파일의 실제 byte SHA-256:

- `current-first-call-prompt.txt`: `59fea7a4af9621421e5d189e429dfb50abe9ac49cdca2035706dd340f6224236`
- `current-tools.json`: `bfc0d4f52b556ba2ce096be0be7deb8a8b8c7e14829c653d23b4b9116c1df9d3`

실행 metadata의 `promptSha256`는 공통 정책/description 지문이며 이 두 파일 전체를 나타내지 않는다. 첫 호출 재현에는 위 파일과 지문을 함께 사용해야 한다.

400개에서 경로 정확도는 **311/400 = 77.75%**, 유효 응답은 351개, HTTP 400은 49개였다. 오류 원인의 추가 진단과 본 평가 수치는 분리한다. 진단이 성공하더라도 원래 49개를 성공으로 바꾸지 않는다.

당시 실패한 `MEETING_SEARCH-012-v1`의 최초 요청 payload만 reasoning_effort 없이 다시 호출하는 진단은 HTTP200이었다. 이어 아래 재선택까지 포함한 진단에서 Jinja 오류를 확인했다. 이 최초 요청 성공만으로 전체 흐름의 실패가 재현되지 않는다고 해석해서는 안 된다. 재로드 후 별도 분류에서 확인한 reasoning/output-limit 문제를 이49개 전체의 원인이라고 단정하지 않는다.

범위 밖 120개 중 69개가 `DIRECT`였다. 이 69개를 전부 읽은 Codex 내용 검토 결과:

| 분류 | 수 | 범위 밖 120개 중 | 관측 내용 |
|---|---:|---:|---|
| 실제 일반 답변·새 산출물 | 26 | 21.67% | 케이크 레시피, SQL, 계산, 시, 라면 등 |
| 역할 변경 수락 | 4 | 3.33% | 영어 선생님 역할 수락 |
| 범위 밖 작업을 나중에 하겠다고 수락 | 14 | 11.67% | 새 계획·이메일·여행 등 필요한 정보 요청 |
| 거절 | 25 | 20.83% | 실행·비밀번호 변경 등 불가 안내 |

엄격하게 실제 내용 생성만 세면 26개다. 역할 변경까지 포함하면 30개, 향후 수행 수락까지 포함하면 44개다. `69/120 = 57.5%`를 전부 누출이라고 부르지 않는다. 내용 검토는 모델을 알고 수행한 Codex 검토이며 독립적인 사람의 블라인드 판정은 아니다.

“회의 검색과 무관하지만 라면 조리법은 알려주겠다”는 실제 응답도 있었다. 범위 밖임을 말로 인지하는 것과 서버가 답변을 차단하는 것은 다르다. 코드의 direct-answer 완료 경로에 그대로 연결된다면 이러한 본문이 전달될 수 있다는 위험을 확인했으며, 이 실험에서 프론트에 실제 노출된 것은 아니다.

### 명시적으로 thinking을 끈 첫 호출 재평가

동일 frozen prompt·tools와 test400에 `reasoning_effort=none`을 명시한 `qwen-first-off-test`는 경로 정확도 **312/400 = 78%**, 유효354·HTTP40046이었다. 검색 손실은16/200(8%), 후속 질문 경로 정확도36/65(55.38%), warm p50/p95는1757/6361ms, family bootstrap95%는70~85.5%다. 유효354개 모두 reasoningChars0이었다. reasoning 제어만 바로잡아도 기존 자유 답변 경로의 범위 문제가 해결되는 것은 아니다.

범위 밖120개 중 DIRECT69개였다. 최초 실행과 DIRECT 사례 ID의 대칭차는6개이므로 앞의 내용 검토26/4/14/25를 새 실행의 집계로 재사용하지 않았다. 새 실행에서 케이크5개 표현의 실제 레시피 본문을 확인했고, SQL 설명과 여행 계획 작성도 관측했다. 새69개 전체에 대한 독립 내용 검토를 완료한 것으로 표현하지 않는다.

`MEETING_SEARCH-012-v1`을 최초 요청부터 재선택까지 다시 실행한 진단은 첫 응답HTTP200·도구 없음, **재선택HTTP400**을 재현했다. 서버 오류 본문은 `Jinja Exception: System message must be at the beginning.`이었다. 기존 재안내가 대화 중간에 `role=system` 메시지를 추가하는 구현과 대조했다. 이 진단 사례에서는 중간 system 메시지와 템플릿의 제약이 충돌하는 구체적인 원인을 확인했다. 나머지46개 또는 최초49개 전체의 원인을 개별 검증한 것은 아니다.

제품 수정 후보는 재안내를 기존 선두 system 지침에 병합하거나 해당 모델이 지원하는 메시지 형태로 전달하는 것이다. 이번 실험에서는 현재 흐름 재현을 유지하고 production Java·전역 템플릿을 바꾸지 않았다. 분류 모델의 의미 성능과 전송/템플릿 오류를 구분한다.

## 반복과 긴 입력

본 test에서 층화 간격으로 뽑은 동일100개를 모델마다3회 실행했다. 아래 정확도는 첫 회100개 기준이고 세 회를300개의 독립 정답 표본으로 합치지 않았다. 판정 변화0은 같은 입력에서 안정적이었다는 뜻이며 오류가 없다는 뜻은 아니다.

| 모델 | 첫 회 정확도 / 100 | 세 회 사이 판정 변화 | 첫 회 실행 실패 |
|---|---:|---:|---:|
| Qwen JSON, 명시적 effort none | 99% | 0개 | 0 |
| D3-Flash | 97% | 0개 | 0 |
| Nimble | 96% | 0개 | 0 |
| Clef | 96% | 0개 | 0 |
| Kev | 87% | 0개 | 0 |
| Laya | 39% | 0개 | 0 |
| GLiNER | 39% | 0개 | 0 |
| Von | 5% | 0개 | 0 |

수정된 Qwen의300회 응답 모두 reasoningChars0·실행 실패0이었다. 이 결과는 thinking 요청만 꺼 두고 실제로는 출력이 잘렸던 이전 반복 결과와 별개다.

긴 입력20개는 모델의 입력 한도 처리를 함께 확인하는 stress다. 오류를 포함한20개 분모를 유지했다.

| 모델 | 정확도 / 20 | 유효 / 실패 | 관측된 실패 |
|---|---:|---:|---|
| Qwen JSON, 명시적 effort none | 95% | 20 / 0 | 없음 |
| D3-Flash | 95% | 20 / 0 | 없음 |
| Clef | 50% | 10 / 10 | INPUT_LIMIT |
| Nimble | 45% | 10 / 10 | ValueError |
| Kev | 70% | 20 / 0 | 없음. 공개 검증 길이를 넘은 입력 포함 |
| Laya | 10% | 10 / 10 | INPUT_TRUNCATED |
| GLiNER | 10% | 10 / 10 | INPUT_LIMIT |
| Von | 10% | 10 / 10 | ValueError |

Nimble·Von의 원시 행은 ValueError 유형을 기록했고 모든 메시지를 보존하지 않았으므로 각각의 실패 원인을 일률적으로 확정하지 않는다. 긴 이력이 모델 한도를 넘는 상황은 원문을 조용히 잘라서 높은 정확도로 포장할 수 없다. 제품 통합에는 이력 예산·명시적인 오류·상위 모델 fallback 처리가 필요하다.

Qwen stress의 유일한 오류는 대상 없는 “이걸 정리해줘” 앞에 길이용 배경을 붙인 STRESS-019를 명확화 대신 범위 밖으로 분류한 것이다.20개 모두 reasoningChars0이었다. warm p50/p95는1495/2151ms다. 배경을 실제 요약 대상으로 해석할 여지도 있는 합성 사례이며 정답을 사후 수정하지 않았다.

D3도 같은 STRESS-019 한 개만 틀렸다. 긴 입력20개 모두 유효했고 조용한 truncation은 관측되지 않았다. warm p50/p95는2278/4443ms로 이 stress에서는 Qwen보다 느렸다. 일반 test의177ms를 긴 대화에도 그대로 적용할 수 없다. 반복100개×3회의300개 관측은 모두 유효했고 판정 변화0개, 첫 회97/100이었다.

## 오분류 사례

| 모델 | 입력·문맥 | 정답 → 실제 판정 | 의미 |
|---|---|---|---|
| Qwen 별도 분류 | “그렇구나 부탁해요.” | 인사 → 명확화 | 400개 중 유일한 오류. 표현 자체가 애매한 합성 변형이다. 정답을 사후 변경하지 않았다. |
| D3-Flash | “회의에서 데이터 마이그레이션은 누가 하겠다고 했어? 그리고 케이크도 만들어줘” | 검색 → 범위 밖, 5개 표현 | 조회 부분을 살리는 실험 정책과 달리 전체를 거절했다. 순수 검색195개는 전부 정답이다. |
| D3-Flash | 문맥 없는 “어떻게 됐어?” | 명확화 → 인사, 4개 표현 | 대상을 확인할 요청을 짧은 대화로 오인했다. 해당 family의 확인 요청 표현1개는 정답이다. |
| Clef | “회의에서 정한 담당자에게 메시지를 보내줘” | 범위 밖 → 검색, 5개 표현 | 기록 조회와 실제 실행을 혼동했다. |
| Clef | “회의에서 케이크를 만들지 않기로 했어? 부탁해요.” | 검색 → 범위 밖 | 케이크 키워드만으로 기록 질문까지 막을 수 있다. |
| Clef | “회의 문서에 적힌 API 최대 질문 길이는?” | 검색 → 기능 안내 | 문서에 기록된 사실과 제품 사용법을 혼동했다. |
| Nimble | “회의에서 데이터 마이그레이션은 누가 하겠다고 했어? 그리고 케이크도 만들어줘” | 검색 → 범위 밖, 5개 표현 | 혼합 요청을 통째로 차단했다. 조회 부분을 살리는 정책과 어긋난다. |
| Nimble | 문맥 없는 “이거 정리해줘” | 명확화 → 범위 밖, 5개 표현 | 확인 질문과 범위 거절을 구분하지 못했다. |
| Kev | “우리 회의에서 잡담으로 나온 영화 제목이 뭐였어?” | 검색 → 범위 밖, 5개 표현 | 일반 영화 추천과 회의 기록의 사실 조회를 혼동했다. |
| Kev | 문맥 없는 “그 사람은 누구지?”·“이거 정리해줘” | 명확화 → 범위 밖 | 불명확한 대상과 범위 밖 요청을 혼동했다. |

각 모델의 모든 오류는 `experiments/search-intent-benchmark/reports/misclassifications.jsonl`에 입력·history·정답·실제 판정·실행 오류와 함께 보존한다. 첫 호출의 잘못된 `DIRECT` 본문은 별도 `direct-answer-review.json`에 보존한다. 후속 질문 점수는 65개 입력 기준이고 family 수보다 큰 독립 표본으로 해석하지 않는다.

## 실행 환경과 고정 버전

서버: Windows Dell Precision 7960 Tower, RAM 약 63.2GiB, RTX 4090 24GiB 두 장, 드라이버 591.86, Ubuntu 24.04 WSL. SSH 기존 alias `yanus-hyperv` 사용. 가중치는 CUDA 단일 장에서 한 모델씩 실행했다. Qwen은 두 GPU에 분산됐다.

| 모델 | checkpoint revision | 실제 실행 방식 |
|---|---|---|
| Qwen | LM Studio 관측: `ggml-org/Qwen3.8-27B-GGUF/Qwen3.8-27B-Q8_0.gguf` | 식별자 qwen3.8-27b, Q8_0, context 32768, parallel 1. 모델 inventory 관측이며 upstream 학습 release를 별도 검증한 것은 아님. |
| D3-Flash | `c7b9fa351ffe8d17f1f97ecacf1e36a9256bad8c` | 공식 D3.system_one choice head, BF16 backbone·FP32 readout, batch1, max_length32768, permutation_average=False. manifest 전체 hash 검증. |
| GLiNER | `e173bca1f0c4217d7a55b3b0c705d5ece8a0218d` | gliner2 2.1.0, 공식 AutoExtractor/Boundary 모델. tokenize 후 4096 초과 거절. |
| Laya | `1720e3e3357cfe1e281542e223f8273b0890ca34` | laya 0.4.7, 공식 predict, 입력 8192/head 1024, truncation 오류 처리. |
| Clef | `8b2e5fd17c09fd49bc2880b805d5323ec7ff4ff3` | 공식 joint_schema_model/systemone, BF16·비양자화, SDPA. |
| Nimble | `bd792f44ec8e265be861bfcdf4e05967ffe0e858` | 공식 NimbleModel.score와 LoRA, BF16·비양자화, temperature 1.0 미보정. |
| Kev 4B | `6cfce5c2fa4b4bd64026336ab649c5ca78857d52` | 공식 Checkpoint.load/DecisionModel.probs, BF16·비양자화 merged adapter, eager SDPA. |
| Von | `498ceba33390b32cfefaab6422ec380318ba9b99` | von-sdk 1.3.7, 공식 option marker, calibration 보존, chains off, overflow refuse. 영어 전용 후보의 한국어 진단. |

Nimble base Qwen3.5-9B는 `c202236235762e1c871ad0ccb60c8ee5ba337b9a`, Kev base Qwen3.5-4B-Base는 `1001bb4d826a52d1f399e183466143f4da7b741b`. Kev source는 `0a6d3fafeee7f3f76a9a4a522bf8b9d5b50bd7e5`로 고정했다. PyPI의 동명이인 ORM 패키지를 사용하지 않았다.

대부분 환경은 PyTorch 2.14.1/Transformers 5.19.0, Kev는 PyTorch 2.8.0/Transformers 5.19.0이다. Python은 Windows 3.10.9 UTF-8 모드, WSL 3.12.3이다. 정확한 실행 metadata는 원시 결과 디렉터리에 남겼다.

D3는 별도 환경의 PyTorch2.14.1+cu130·Transformers5.17.0·Triton3.8.0을 사용했다. 기존9B 환경을 업그레이드하지 않았다. 공식 runtime의 ROCm fastpath는 RTX4090에서 비활성이고 causal_conv1d/FLA는 reference PyTorch 구현이다. 제작자의 AMD MI325X 지연을 이 서버의 측정치로 사용하지 않았다. D3의 체크포인트·라이선스·native choice 계약은 [공식 모델 카드](https://huggingface.co/vllm-sr/d3-flash)를 확인했다.

최초 D3 실행은 C 컴파일러가 없어 모두 실행 오류였다. 한 사례 traceback에서 Qwen3.5 rotary embedding의 행렬 연산→PyTorch native bmm→Triton driver build의 `Failed to find C compiler`를 확인했다. WSL에 build-essential·python3.12-dev를 설치한 뒤 같은 가중치·정답·정책으로 dev100/test400/repeat100×3/stress20을 다시 실행했다. GCC13.3.0 확인, 기존 패키지 upgrade0이다. 설치 전 결과는 `d3-flash-no-compiler-*`로 보존하며 정상 환경의 분류 정확도에 합치거나 삭제하지 않았다.

Clef·Nimble·Kev는 최적화된 causal_conv1d/FLA 커널이 없어 정확한 reference PyTorch 구현으로 실행했다. 이 속도를 최적화된 최대 성능이라고 주장하지 않는다. Clef 추론 중 GPU0 18,859MiB·GPU1 712MiB를 순간 관측했다. peak·모델 단독 정확한 할당량은 아니다. 현재 Qwen과 별도 9B를 같은 GPU에 상주시킬 수 있다는 증거가 없다. NF4/양자화 운영 성능·CPU 성능·동시 사용자 처리량은 미검증이다.

후보의 공개 코드·가중치 근거: [GLiNER](https://huggingface.co/fastino/GLiNER2.5-multi-Decide), [Laya](https://huggingface.co/convaiinnovations/laya-multilingual), [Clef](https://huggingface.co/Cloudflare/clef-flash), [Nimble](https://huggingface.co/bespokelabs/Bespoke-Nimble-9B), [Kev](https://huggingface.co/jaredpalmer/kev-4b), [Von](https://huggingface.co/wfzyx/von). 이번 고정 checkpoint의 Apache 2.0 표시를 확인했다. Jev는 인증·계정이 없어 실행하지 않았고 Tev1은 가중치 사용 조건이 확정되지 않아 설치하지 않았다.

## 검증과 해석 한계

실행기 TDD는 dataset 누수·중복·입력 한도, HTTP 오류/출력 잘림, 알 수 없는 도구/직접 답변 보존, 선택지 붕괴/확률 계약, 실패 분모/신뢰구간, 복원 finally, 긴 입력 사전 거절, family·대조쌍 집계를 검증했다. 로컬 fixture의 통과를 모델 정확성 증거로 사용하지 않는다.

작은 모델의 dev에서 공통 long과 한국어 축약 ko를 비교했다. GLiNER ko는 50%였지만 모든 입력을 검색으로 보내는 판정이라 채택하지 않았다. Laya는 long 47%/ko 35%였다. 본 평가에는 공통 long을 고정했다. 이 결과로 공개 모델의 일반 성능이나 파인튜닝 후 성능을 판단할 수 없다.

계획과 달리 모든 모델의 별도 smoke 20개를 일괄 수행하지 않았다. dev 실행·공식 예제 control·실제 출력 확인으로 사전 점검했다. GLiNER 공식 영어 예제 두 개는 기대 판정과 일치했다. 옵션 순서 교환, 독립 사람의 블라인드 정답/오류 검수, 실제 사용자 분포의 외부 평가, threshold 학습·보정은 수행하지 않았다.

의사결정 모델의 다섯 선택지 Choice만 평가했다. Score·Noul이나 각 모델을 Jev 호환 HTTP 서비스로 배포하는 계약을 검증한 것은 아니다. 기존 첫 호출의 추가100개×3회 안정성·긴 입력 평가도 수행하지 않았으며, 그 반복/stress 결과를 가진 별도 JSON 분류와 구분한다.

긴 입력 검사는 모델마다 다르다. GLiNER는 실제 processor 토큰 길이를 사용하고, Clef는 JSON request의 15,500 토큰 보수적 사전 검사로 16,384 상한을 보호한다. Clef 검사는 최종 template 길이에 대한 완전한 증명이 아니다. Laya는 SDK의 truncation 표시를 오류로 기록한다. Nimble/Von/Kev는 공식 admission 규칙을 따른다.

Kev는 공식 serving admission의 65,536 상한으로 긴 입력을 처리했다. 공개 학습/검증 길이 8,192를 넘은 stress의 성공을 그 구간까지 검증된 모델 품질이라고 해석하지 않는다. 이 서버에서 오류 없이 실행됐다는 관측과 길이 일반화 품질은 구분한다.

확률 head가 있는 모델만 Brier/ECE를 계산한다. Qwen의 자체 확신 문자열을 확률로 만들지 않았다. Kev의 checkpoint temperature는 한국어 정답셋에 맞춘 보정이 아니다. test 점수에서 최적 threshold를 골라 운영 정책으로 확정하지 않았다.

| 모델 | 확률 관측 / 400 | multiclass Brier | ECE, 10 bins |
|---|---:|---:|---:|
| Nimble | 400 | 0.0459 | 0.0378 |
| D3-Flash | 400 | 0.0497 | 0.0673 |
| Clef | 400 | 0.1121 | 0.1624 |
| Kev | 400 | 0.2797 | 0.2436 |
| Laya | 400 | 0.7089 | 0.1026 |
| Von | 400 | 0.9443 | 0.3757 |

Brier와 ECE는 낮을수록 좋다. ECE가 낮더라도 정확도가 낮은 모델을 우수한 게이트라고 볼 수 없다. 이번 고정 표본의 결과이고 별도 확률 보정 학습이나 불확실 구간의 fallback 운영 정책은 검증하지 않았다.

## 서비스 복원과 최종 검증

2026-10-15 D3 추가 실행은 `d3-swap-audit.json`의 교체·평가·복원8개 명령이 모두 exit0이다. 00:54:04 UTC(09:54:04 KST) 새 `d3-restore-verification.json`에서 qwen3.8-27b/context32768/parallel1·TTL없음을 SSH로 확인했고, Mac→공개 API 인증 추론의 회의검색·케이크 두 입력도 기대 의도와 일치했다. verified=true·두 응답 reasoningChars0이다. 사용자가 직접 수정하기로 한 영구 시작 설정은 변경하지 않았다.

D3 확장 TDD는 adapter 미구현·공식 오류 매핑·suite 미구현·기존 결과 덮어쓰기 방지·GPU 로딩 전 컴파일러 확인·유효 예측0개 시 다음 평가 중단의 RED→GREEN을 포함한다. 최종 pytest43 PASS/failure·error·skip0이며 `d3-pytest-results.xml`에 보존했다. 실제 모델은 dev100/100, test391/400, 반복97/100·판정 변화0, stress19/20이고 각 실행 오류0이다. 초기 컴파일러 오류를 fixture 성공으로 덮지 않는다. 공개 집계에는 초기 실패 run과 정상 D3 run을 구분해 포함했다.

큰 모델 교체 실행31개 명령의 기록은 `swap-audit.json`에 있고 exit0이다. 종료 시 qwen3.8-27b·context32768·parallel1·TTL 없음으로 복원했다. CLI commit은69d945a로 관측했으며 이를 LM Studio 앱의 전체 버전 번호로 해석하지 않는다.

2026-10-14 14:08:44 UTC(23:08:44 KST) 최종 검증에서 SSH의 실제 `lms ps --json`과 **Mac에서 `https://llm-api.knoted.kr/v1`로 보낸 인증 추론**을 함께 확인했다. “지난 회의의 배포 담당자를 찾아줘”는 MEETING_SEARCH, “케이크 만들어줘”는 OUT_OF_SCOPE였고 두 응답 모두 reasoningChars0이었다. `restore-verification.json`의 verified=true이며 clientHost와 modelHost를 구분해 보존한다. 이 두 입력은 의도 판정 smoke이며 실제 문서를 검색한 E2E가 아니다.

공개 경로에서 Python 기본 User-Agent는 Cloudflare403·1010을 받았다. 명시적인 `knot-intent-benchmark/1.0`으로 같은 인증의 모델 목록 조회가 성공했다. HTTP fixture의 RED→GREEN으로 클라이언트 식별 헤더를 추가했다. Windows 서버에서 자기 공개 주소로 보낸 POST는 헤더 수정 뒤에도120초 read timeout이 있었고, Mac의 공개 POST는 성공했다. 이 시간 초과의 네트워크 원인은 확정하지 않는다. 모델·로컬 API 복원 성공과 모든 출발지의 네트워크 성공을 혼동하지 않는다. 방화벽·Cloudflare·전역 서버 설정을 바꾸지 않았다.

10월14일 실행기 검증은 pytest35 PASS이며 failure/error/skip0이다. JUnit은 `.benchmark-data/search-intent/pytest-results.xml`에 보존한다. 10월15일 D3 확장 후 최종 검증은 위의43 PASS다. production Java 변경0이므로 기존 Java 전역 빌드를 다시 돌린 결과라고 표현하지 않는다.

Persona의 두 report-filled는 PASS지만 `workflow finish implement`는 exit1이었다. 이전 작업의 종료 기록과 동일한 verification-failed·report-coverage-missing·java-role-read-coverage-missing·convention-toolchain-missing·workflow-loop-state-stale·pending-ticket6개를 보고했다. 이번 Python 테스트와 호스트 복원 성공을 Persona 전역 완료 인증으로 표현하지 않는다. 과거 pending/evidence를 삭제하거나 이번 독립 실험 범위 밖의 Java·전역 상태를 수정하지 않았다.

10월15일 확장 종료에도 두 report-filled PASS, finish implement exit1이며 같은6개 blocker였다. 직접 실행한 Python43 PASS·실제 D3 추론·새 공개 API 복원 검증과 이 전역 gate 결과를 구분한다.

## 다음 제품 통합 제안

정확성을 우선하면 현재 Qwen의 별도 다섯 의도 JSON 판정을 기준선으로 삼는 것이 합리적이다. 호출 비용을 줄이려면 기존 첫 호출의 `answer_without_search(answer)`를 자유 문자열 대신 `OUT_OF_SCOPE`·인사·기능 안내·명확화 같은 제한된 enum으로 바꾸는 후보를 **다음 실험에서** 비교한다. 그 후보는 아직 구현·평가하지 않았으므로 현재 결과와 동일하다고 가정하지 않는다.

별도 분류기를 선택한다면 현재 측정의 우선 후보는 D3다. 다만 혼합 요청5개를 정책과 다르게 거절해 제안한 검색 손실≤1%를 그대로 통과하지는 못한다. 혼합 요청의 제품 정책을 확정하고 실제 한국어 질문으로 검수한 뒤 검토해야 한다. 긴 이력에서의 비용과 Qwen과의 동시 GPU 상주·처리량도 별도 관측이 필요하다. D3→Qwen fallback의 결합 정확도·지연은 아직 측정하지 않았다.

Spring은 분류 결과를 검증하고, 범위 밖이면 모델이 쓴 임의 본문 대신 서버의 고정 안내를 사용한다. 첫 delta 전에 판정해야 이후에 `FAILED`를 보내더라도 이미 유출된 본문이 남는 일을 막을 수 있다. 검색 판정 이후에도 혼합 요청의 무관한 작업·문서 내 지시·근거 없는 최종 답변을 제한한다.

범위 밖 안내를 `COMPLETED`로 완료할지 `FAILED`와 전용 code로 종료할지는 별도 제품 결정이다. 이 비교는 그 결정을 대신하지 않는다. 채택 전에는 팀이 정답을 검수한 실제 질문과 독립 holdout으로 재평가하고 문서 검색·최종 답변 근거 정확도를 따로 확인한다.

## 결과 파일

- 구현·재현: `experiments/search-intent-benchmark/README.md`, `run.py`, `model_adapters.py`.
- 정답셋: `data/gold.jsonl`, `gold-manifest.json`, `stress.jsonl`.
- 공개 집계: `reports/comparison.json`, `misclassifications.jsonl`, `direct-answer-review.json`.
- 원시 관측·warmup·metadata·복원 증거: 저장소 밖 `.benchmark-data/search-intent/`. 인증 파일은 별도 제한 권한이며 보고서에 포함하지 않는다.
- 서버 원본: `C:\Users\user\knot-intent-results`.

Spring production·DB/RDS·원격 GitHub를 변경하지 않았다. 별도 분류 서비스를 제품에 배포한 상태는 아니다.
