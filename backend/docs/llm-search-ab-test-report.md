# Knot LLM 검색 방식 비교 결과

## 결론

현재 통제 실험에서는 **Raw를 운영 후보에서 제외**하고, 검색 지연만 보면 `DB 직접 검색`이 가장 짧으며, `RAG`는 Qwen 임베딩 비용을 추가하는 대신 의미 검색 후보를 만든다. `MCP replay`는 실제 Notion MCP가 아니라 로컬 lexical replay이므로 MCP-live의 결론으로 사용하지 않는다. 2026-09-01에는 LM Studio에서 실제 Notion MCP를 호출하는 smoke test까지 완료했지만, Java가 Workspace별 credential을 전달하는 경로와 전체 품질·지연 비교는 아직 보류한다.

- 검색 관측: `400`개 기록, 오류 제외 `400`개
- 답변 생성 관측: 같은 결과 파일의 model latency 기록을 별도 집계
- 채팅 모델: `qwen/qwen3.6-27b`
- 임베딩 모델: `text-embedding-qwen3-embedding-0.6b:2`
- LM Studio 컨텍스트: `32768` tokens, API max_tokens: `4096`, 동시성: `1`
- temperature: `0`, Qwen 질의 instruction: 영어 한 문장 사용
- 실제 MCP smoke test: LM Studio `mcp/notion` + Notion OAuth, `notion-search`/`notion-fetch`

## 실행 식별자와 재현성 경계

이 문서의 기존 전략 비교표와 `최신 RAG 품질 게이트` 수치는 PR #309의 현재 HEAD에서 생성된 결과가 아니다. 기존 PASS 결과는 PR #308의 리뷰 반영 전 실행 commit `1e56e02`에서 생성된 역사적 참고치이며, PR #309는 당시 그 검색 구현을 포함하지 않는다.

- 기존 결과 실행 commit: PR #308 `1e56e02` (리뷰 반영 전)
- 기존 결과 snapshot manifest SHA-256: `7bc3e082e1b55174aacf7d77f6fe2669ae0ef1d9cf989dd857890f06916e75c`
- PR #308 리뷰 반영 후 검색 commit: `929d7f8`
- PR #309와 #308은 별도 PR이다. 아래의 기존 결과는 #308 검색 구현과 해당 snapshot을 함께 사용해야 재현할 수 있다.
- 기존 실행 조건: `top_k=3`, `chunk_size=1200`, `chunk_overlap=180`, retrieval 10회 반복, e2e는 별도 1회 실행

따라서 기존 결과의 `160/160 PASS`는 현재 코드의 acceptance 결과로 사용하지 않는다. #308 수정 후 동일 snapshot과 조건으로 재실행한 결과는 아래에 별도로 기록한다.

## 최신 RAG 품질 게이트 (이전 #308 실행 기록)

초기 검색 오류를 보정한 뒤 PR #308 리뷰 반영 전 코드에서 동일한 Notion export snapshot을 평가한 기록이다. benchmark 코드는 Java `src/main`에 포함하지 않고 테스트 하네스 디렉터리에만 둔다.

- 코퍼스: 461개 문서, heading-aware metadata 보존 청크 1,788개, 기본 `1200자 / overlap 180자`
- 검색 구조: vector·PostgreSQL lexical 후보를 각각 수집 → 출처 단위 union/rerank → 출처별 근거 passage를 최대 4개까지 결합
- retrieval-only: 13개 case, 16개 turn을 10회 반복한 160개 관측
- retrieval gate: **160/160 PASS (이전 실행)** — 기대 page ID, 중복 출처, 최대 3개 문서, MongoDB 무응답, 광범위 질문 명확화를 검사
- 검색 지연: `search_ms` p50 **343.0ms**, p95 **968.1ms**; embedding p50 **157.3ms**, DB 단계 p50 **185.9ms**
- Qwen e2e: 16개 turn 1회 생성, 무응답·명확화 2개는 결정형 응답으로 처리
- answer policy gate: **16/16 PASS (이전 실행)** — 질문 유형별 필수 사실·후속 맥락·충돌·무응답 형식을 검사
- e2e TTFT: p50 **4,469.9ms**, p95 **9,137.5ms**, 5초 이내 **9/14**. 따라서 검색 자체는 1초 안쪽이지만 Qwen 27B 생성 지연 때문에 전체 5초 목표는 아직 보장하지 않는다.

이 gate는 gold set의 출처·정책·필수 표현을 자동 검사한 결과이며, 다른 Workspace로 일반화하려면 30개 이상 독립 질문과 사람이 원문을 대조한 의미 정확성 라벨이 추가로 필요하다.

## 30개 이상 독립 workload와 사람 검수 게이트

현재 구현된 독립 workload는 W-001부터 W-031까지 31개 case, 33개 turn이다. 기존 G-series 대화형 골드셋과 분리하여 [docs/llm-search-benchmark-independent-30.json](llm-search-benchmark-independent-30.json)에 질문 유형·기대 page ID·원문 대조용 핵심 사실을 기록했다. JSON workload는 기존 benchmark runner의 입력으로 사용할 수 있다.

사람 검수 템플릿은 [docs/llm-search-benchmark-human-review-template.jsonl](llm-search-benchmark-human-review-template.jsonl)에 있으며 33개 행이 모두 pending이다. evaluator는 각 답변에 대해 답변 정확성·source 관련성·정책 준수 라벨과 결과 `result_fingerprint`를 모두 요구하고, 누락·중복·pending·불일치 지문 라벨이 있으면 human_gate=PASS를 내지 않는다.

따라서 현재 상태는 독립 질문 준비 완료, 사람 품질 라벨 미평가다. 이 문서의 기존 수치는 과거 실행 기록이며 31개 workload의 실제 NIM e2e 품질 결과가 아니다. 아래 조건이 채워지기 전에는 최종 아키텍처 승자나 5초 목표 달성을 주장하지 않는다.

- 동일한 모델·프롬프트·옵션으로 31개 case를 실행한 NIM e2e 결과
- 동일한 실행 ID로 기록한 cold/warm 조건과 MCP-live access 지연
- 사람 검수 33개 turn의 terminal pass/fail 라벨
- Java가 Workspace credential을 선택하고 Notion token을 NIM에 넘기지 않는 운영 경계 검증

## #308 수정 후 동일 조건 retrieval-only 재실행

평가셋 정답 단어를 검색 경로에서 제거한 PR #308 `929d7f8`을 기준으로, 위 manifest와 기존 `1788`개·`1024`차원 pgvector corpus를 사용해 다시 실행했다. 이 실행은 답변 생성이 아닌 검색 게이트만 측정했으며, 결과 파일은 저장소에 커밋하지 않았다.

```bash
uv run --python 3.14 tools/llm-benchmark/run_four_way_benchmark.py \
  --snapshot-dir /Users/yongtae/Desktop/knot/.benchmark-data/notion-export \
  --strategy rag \
  --case G-001,G-002,G-003,G-004,G-005,G-006,G-007,G-008,G-009,G-010,G-011,G-012,G-013 \
  --top-k 3 \
  --repeats 10 \
  --retrieval-only \
  --skip-index \
  --output /tmp/knot-rag-quality-fixed-929d7f8.jsonl
```

평가 명령은 PR #309의 evaluator로 실행했다.

```bash
uv run --python 3.14 tools/llm-benchmark/evaluate_rag_quality.py \
  --results /tmp/knot-rag-quality-fixed-929d7f8.jsonl \
  --gold-set backend/docs/llm-search-benchmark-gold-set.md \
  --strategy rag \
  --repeats 10
```

관측 결과는 `results=160 expected=160`, `retrieval_gate=FAIL (140/160)`, `answer_gate=PARTIAL (20/20)`이었다. G-009 10회와 G-013 후속 turn 10회의 source 기대값이 충족되지 않아 검색 품질은 아직 PASS가 아니다. 이는 gold-set 고유어를 주입하지 않은 상태의 실제 회귀 결과이며, holdout 일반화와 기존 정답셋 품질을 모두 만족시키려면 문서 유형·질문 의도·일반 relevance 기준을 추가로 조정하고 다시 평가해야 한다.

## 전략별 결과 (이전 #308 실행 기록)

| 전략 | 검색 기록 | 생성 성공 | 생성 오류 | 검색 p50 | 검색 p95 | 임베딩 p50 | DB/구성 p50 | 5초 내 TTFT | 정답 source hit@5 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `raw` | 100 | 0 | 10 | 0.6ms | 1.5ms | 0.0ms | 0.6ms | 0/0 | 90/90 |
| `rag` | 100 | 10 | 0 | 200.2ms | 271.5ms | 132.4ms | 73.4ms | 10/10 | 60/90 |
| `db` | 100 | 10 | 0 | 79.6ms | 119.2ms | 0.0ms | 79.6ms | 10/10 | 40/90 |
| `mcp-replay` | 100 | 10 | 0 | 118.7ms | 129.7ms | 0.0ms | 118.7ms | 10/10 | 50/90 |

`source hit@5`는 골드셋에 기록된 page ID가 관련 문서 후보에 포함됐는지만 나타내며, 답변의 의미 정확성을 보장하지 않는다.

## 검색 단계 paired 비교

| A | B | metric | pairs | median Δ (B−A) | 95% bootstrap CI | B faster | permutation p |
| --- | --- | --- | ---: | ---: | --- | ---: | ---: |
| `rag` | `db` | `search_ms` | 100 | -108.5ms | [-134.2, -96.0]ms | 100/100 | 0.00010 |
| `rag` | `mcp-replay` | `search_ms` | 100 | -78.5ms | [-99.6, -63.0]ms | 100/100 | 0.00010 |
| `db` | `mcp-replay` | `search_ms` | 100 | 39.1ms | [31.7, 44.3]ms | 4/100 | 0.00010 |

Δ가 음수면 B가 더 빠르다. p-value는 효과 크기나 품질을 보정하지 않으며, 독립 질문 수가 적은 현재 결과로 일반화 승자를 선언하지 않는다.

## 답변 첫 표시(end-to-end TTFT) paired 비교

| A | B | metric | pairs | median Δ (B−A) | 95% bootstrap CI | B faster | permutation p |
| --- | --- | --- | ---: | ---: | --- | ---: | ---: |
| `rag` | `db` | `ttft_ms` | 10 | -85.7ms | [-151.1, 7.7]ms | 7/10 | 0.17368 |
| `rag` | `mcp-replay` | `ttft_ms` | 10 | 692.6ms | [548.1, 770.7]ms | 0/10 | 0.00170 |
| `db` | `mcp-replay` | `ttft_ms` | 10 | 747.7ms | [651.4, 832.3]ms | 0/10 | 0.00280 |

Δ가 음수면 B가 더 빠르다. p-value는 효과 크기나 품질을 보정하지 않으며, 독립 질문 수가 적은 현재 결과로 일반화 승자를 선언하지 않는다.

## 답변 포함 end-to-end paired 비교

| A | B | metric | pairs | median Δ (B−A) | 95% bootstrap CI | B faster | permutation p |
| --- | --- | --- | ---: | ---: | --- | ---: | ---: |
| `rag` | `db` | `total_ms` | 10 | -1847.3ms | [-6346.2, 1595.1]ms | 7/10 | 0.19108 |
| `rag` | `mcp-replay` | `total_ms` | 10 | -1428.9ms | [-6748.3, 1442.3]ms | 7/10 | 0.29337 |
| `db` | `mcp-replay` | `total_ms` | 10 | 753.6ms | [202.8, 2039.9]ms | 2/10 | 0.52205 |

Δ가 음수면 B가 더 빠르다. p-value는 효과 크기나 품질을 보정하지 않으며, 독립 질문 수가 적은 현재 결과로 일반화 승자를 선언하지 않는다.

## 실제 LM Studio/Notion MCP-live smoke test

2026-09-01에 LM Studio `0.4.16`의 native `/api/v1/chat`으로 `qwen/qwen3.6-27b`를 실행했다. `mcp.json`에 등록한 Notion 서버를 `mcp/notion` plugin으로 활성화했고, LM Studio 출력의 `provider_info.plugin_id`가 `mcp/notion`인지 확인했다. 따라서 아래 결과는 Codex Notion connector나 로컬 MCP replay가 아니라 LM Studio가 실제 Notion MCP의 `notion-search`와 `notion-fetch`를 호출한 결과다.

토큰 값은 기록하지 않았다. 이번 검증은 LM Studio가 관리하는 Notion OAuth 연결을 사용했으며, Java가 Workspace별 MCP access token을 주입하는 운영 경로의 검증은 아니다.

| 질의 | 실제 도구 호출 | 결과 | 관측 |
| --- | --- | --- | --- |
| PostgreSQL을 왜 사용하기로 했는가 | `notion-search` → `notion-fetch` | 통과 | 기술 스택 문서에서 관계형 데이터 안정적 관리와 향후 `pgvector` 확장을 근거로 답변 |
| `그렇게 결정한 이유는 뭐야?` | `notion-search` → `notion-fetch` → `notion-search` | 문맥·근거 통과, 속도 실패 | `189,976.6ms`, model TTFT `4.441s`, reasoning output `2,565` tokens |
| 로드맵 회의는 언제 했는가 | `notion-search` → `notion-fetch` × 2 | 내용 통과, 속도 실패 | `38,310.4ms`, model TTFT `3.081s` |
| Redis 세션 저장소 결정이 최종인가 | `notion-search` × 5, `notion-fetch` × 3 | 예시 문단과 공식 결정 부재를 구분 | `76,742.0ms`, model TTFT `1.403s` |
| 넓은 코드 컨벤션 질문 | `notion-search` → `notion-fetch` × 3 | 실패 | `60,782.1ms`, 백엔드 문서를 가져와 폴더·파일명 질문에 답하지 못함 |
| 구체적인 폴더·파일명 규칙 질문 | `notion-search` → `notion-fetch` × 2 | 통과 | `41,921.1ms`, model TTFT `2.719s`, `camelCase`/`PascalCase` 규칙 확인 |

첫 PostgreSQL 질의의 최종 답변은 다음 근거와 일치했다.

> 문서·워크스페이스·사용자·동기화 상태와 같은 관계형 데이터를 안정적으로 관리하기 위해 PostgreSQL을 사용하고, 향후 `pgvector`를 활용한 문서 임베딩 및 벡터 검색으로 확장할 수 있다는 점을 고려했다.

관련 문서는 `01. 기술 스택과 라이브러리 도입`이다. 후속 질문은 `previous_response_id`로 앞선 PostgreSQL 문맥을 유지했지만, 추가 검색과 기본 reasoning 때문에 5초 목표를 크게 초과했다. 넓은 코드 컨벤션 질의가 실패하고 검색어를 구체화한 뒤 통과한 결과는 MCP tool 자체뿐 아니라 질의 재작성·검색 결과 제한·문서 유형 판별이 필요하다는 것을 보여준다.

이 smoke test는 연결·도구 호출·기본 근거 생성이 가능한지를 확인한 결과다. 5초 목표와 전체 Workspace 일반화의 통계적 판정으로 사용하지 않으며, Java credential forwarding, Workspace 격리, 30개 이상 독립 질문의 사람 품질 라벨을 추가로 검증해야 한다.

## Raw의 컨텍스트 한계
원본 전략은 461개 문서, 약 1,559,218자를 매번 구성했다. 현재 생성 상한 120,000자 보호선을 넘기므로 10개 질문 모두 답변 생성을 시도하지 않고 `context exceeds generation limit`으로 기록했다. 이를 잘라서 성공으로 계산하지 않았다.

## 해석과 한계

- 검색 지연 100회 반복은 동일한 10개 질문을 10회 반복한 결과다. paired bootstrap과 순열 p-value는 제공하지만, 독립 질문이 10개뿐이므로 다른 Workspace나 질문 분포로 일반화할 수 없다.
- 답변 생성은 전략별 10개 질문을 1회씩 실행한 최소 10 paired observations다. 생성 모델의 변동과 질문별 컨텍스트 차이를 충분히 추정하는 표본이 아니므로 유의한 승자를 선언하지 않는다.
- 5초 목표는 end-to-end TTFT 기준이어야 한다. 검색만 빠른 DB/RAG가 곧 사용자 답변 성공을 뜻하지 않으며, 관련 문서·답변 의미·충돌·무응답은 사람이 원문과 대조해야 한다.
- 이번 `mcp-replay`는 snapshot을 로컬 함수로 재생했다. 실제 Notion MCP/API의 네트워크·페이지네이션·권한·rate limit은 측정하지 않았다.

## 초기 실행에서 확인된 품질 이슈 (개선 전)

- RAG는 G-001/G-003에서 요구사항 문서의 예시 문장인 ‘팀원들의 PostgreSQL 사용 경험’을 실제 선정 이유처럼 답했다. 기술 스택 원문이 말하는 관계형 데이터 관리와 pgvector 확장 이유와 구분하지 못했으므로, 검색 속도와 별개로 source 유형/예시 판별 또는 reranker가 필요하다.
- G-004의 Redis 결정 이유와 G-006의 문서 위치는 상위 5개 후보에 안정적으로 들어오지 않았다. 현재 Qwen 임베딩을 한국어에 쓸 수 있다는 사실만으로 검색 품질이 충분하다고 결론내릴 수 없다.
- G-009는 DB 규칙과 Java 규칙을 함께 보여야 하는데 일부 전략은 한 규칙만 제공하거나 문서 충돌로 잘못 분류했다.

위 문제는 이전 #308 실행에서 다음처럼 보정했다.

- 기술 식별자 exact-match와 문서 권위 점수를 반영해 PostgreSQL 선정 이유와 Redis 세션 근거를 분리했다.
- Markdown heading과 문서 날짜 메타데이터를 청크에 보존하고, 동일 출처의 근거 passage를 함께 전달해 날짜·요약·미결 사항 누락을 줄였다.
- 후속 질문은 이전 turn을 검색어와 답변 체크리스트에 반영하고, MongoDB 부재 질문과 너무 넓은 질문은 모델 호출 전에 결정형으로 종료한다.

## 다음 판정

MVP 구현 후보는 `마지막 성공 동기화 스냅샷 → PostgreSQL lexical/vector 후보 union → Qwen pgvector RAG → 필요 시 rerank`의 단계형 구조로 검증했다. 다만 #308 리뷰 반영 후 동일 조건 재실행은 `140/160`으로 검색 gate를 통과하지 못했으므로, 기존 `160/160` 결과를 최종 품질 근거로 사용하지 않는다. 실제 Notion MCP 연결은 가능하다는 것을 확인했지만, 현재 live smoke test는 end-to-end 5초 목표를 만족하지 못했고 LM Studio-managed OAuth만 검증했다. 전체 사용자 대상 운영 전환 전에는 일반화된 retrieval 규칙 보완, Java credential forwarding, 30개 이상 독립 질문, 사람이 검증한 answer/source 품질 라벨을 추가해야 한다.

## 2026-09-02 #274 MCP live adapter 구현 기록

현재 #274 브랜치에는 Streamable HTTP MCP handshake, JSON/SSE 응답 파싱, Notion search/fetch pagination, Workspace·snapshot·page allowlist, read-only tool-call validation, retry/rate-limit/error 계측과 retrieval-only/live runner가 포함되어 있다. 검색 access 시간과 모델 생성 시간을 분리해 기록하므로 MCP 네트워크 비용을 기존 RAG 결과와 혼동하지 않는다.

이 섹션은 구현 및 계약 테스트 결과이며 새로운 live 성능 수치가 아니다. 실제 Notion OAuth token을 사용한 전체 측정, Java credential forwarding, Workspace 격리의 운영 검증은 아직 수행하지 않았고 token은 결과 파일에 기록하지 않는다.
