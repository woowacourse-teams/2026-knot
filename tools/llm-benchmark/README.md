# Knot LLM 검색 벤치마크

Notion `Markdown & CSV` 내보내기를 같은 스냅샷으로 고정한 뒤, Raw context·Qwen pgvector RAG·PostgreSQL 직접 검색·MCP replay를 동일한 로컬 채팅 모델로 비교하는 하네스다. LM Studio에서 실제 Notion MCP를 호출하는 smoke test는 별도 절차로 검증한다.

현재 `rag`는 Qwen3-Embedding 0.6B GGUF를 LM Studio의 OpenAI-compatible `/v1/embeddings`로 호출하고 PostgreSQL/pgvector에 저장한다. benchmark 하네스는 제품 런타임에 포함하지 않고 이 디렉터리에서만 실행한다. `db`는 같은 저장소의 텍스트 인덱스만 사용하고, `mcp-replay`는 로컬 lexical 검색 결과를 읽기 도구 응답처럼 전달하는 통제군이다. 실제 Notion 네트워크·권한·페이지네이션을 측정하는 `mcp-live`는 smoke test와 전체 비교를 분리한다.

MVP 제품 방향은 `PostgreSQL 키워드 pre-filter → pgvector RAG → 필요한 청크만 채팅 모델에 전달`하는 하이브리드 RAG다. 기본 Markdown 청크는 heading 경계를 우선하고 `1200자 / overlap 180자`로 생성한다. 질의마다 vector·lexical 후보를 각각 최대 50개 모은 뒤 출처별로 합치고, 질문 유형·문서 권위·정확한 기술 식별자·대화 맥락을 반영해 재정렬한다. 근거가 없는 기술명은 무응답으로, 범위가 넓은 질문은 명확화로 종료한다. 기능 계약은 [`docs/llm-search-feature-spec.md`](../../backend/docs/llm-search-feature-spec.md), 비교 결과는 [`docs/llm-search-ab-test-report.md`](../../backend/docs/llm-search-ab-test-report.md)에서 확인한다.

## 1. 내보내기 준비

사용자가 제공한 ZIP처럼 바깥 ZIP 안에 Notion 분할 ZIP이 있고 파일명에 한글이 포함된 경우 macOS에서는 `unzip`보다 `ditto`가 안전하다.

```bash
cd /Users/yongtae/Desktop/knot
export EXPORT_ZIP="/path/to/notion-export.zip"
export EXPORT_TMP="$(mktemp -d /tmp/knot-notion-export.XXXXXX)"

ditto -x -k "$EXPORT_ZIP" "$EXPORT_TMP/outer"
export INNER_ZIP="$(find "$EXPORT_TMP/outer" -type f -name 'ExportBlock-*.zip' -print -quit)"
ditto -x -k "$INNER_ZIP" "$EXPORT_TMP/inner"
export SNAPSHOT_DIR="$(find "$EXPORT_TMP/inner" -type d -name knot -print -quit)"
```

이 ZIP에는 Markdown·CSV 외에 이미지·PDF·키 파일도 포함될 수 있다. 하네스는 텍스트 확장자만 읽고, `Key`, `비밀번호`, `credential`, `token`, `pem` 등 민감 경로, PEM 개인키 본문, 일반적인 `password:`·`api_key=` 같은 credential assignment 문서도 NIM 컨텍스트에서 제외한다. 이 규칙은 완전한 비밀정보 탐지기가 아니므로 실제 실행 전 별도 secret scan도 수행한다. 원본 내보내기와 결과 파일은 `/.benchmark-data/` 아래에 두고 Git에 추가하지 않는다.

## 2. 로컬 검증

먼저 NIM을 호출하지 않는 dry-run으로 문서 수, 검색 결과, 컨텍스트 크기를 확인한다.

```bash
uv run --python 3.14 tools/llm-benchmark/run_benchmark.py \
  --snapshot-dir "$SNAPSHOT_DIR" \
  --strategy all \
  --case G-001,G-003,G-004,G-005,G-006,G-007,G-008,G-009,G-010,G-011,G-012,G-013 \
  --top-k 5 \
  --dry-run
```

전체 질문 목록만 확인하려면 다음을 실행한다.

```bash
uv run --python 3.14 tools/llm-benchmark/run_benchmark.py --list-cases
```

결과는 기본적으로 `.benchmark-data/results.jsonl`에 저장한다. 각 행에는 전략, case/turn, source path, 검색 준비 시간, 컨텍스트 글자 수, NIM model TTFT/완료 시간, end-to-end TTFT/완료 시간이 기록된다. dry-run에서는 `model_*`, `ttft_ms`, `total_ms`가 비어 있다.

Raw 전략은 현재 스냅샷 전체를 보내므로 문서가 많으면 모델 컨텍스트 한도를 넘을 수 있다. 문서를 조용히 잘라서 성공으로 처리하지 않으며, 먼저 RAG와 MCP replay를 실행하고 Raw는 컨텍스트 한도 기준선으로 확인한다.

## 3. LM Studio / NIM 실행 설정

NVIDIA API 키를 코드나 파일에 넣지 말고 셸 환경변수로만 주입한다. 로컬 LM Studio를 쓸 때는 API 키 자리에 더미 값을 넣고, 실제 키를 저장소에 남기지 않는다. Qwen 모델 카드는 질의 쪽 instruction을 권장하므로 한국어 질문이어도 instruction 문장은 영어로 둔다.

```bash
export NIM_API_KEY="local-lm-studio"
export NIM_BASE_URL="http://100.110.23.115:1234/v1"
export NIM_MODEL="qwen/qwen3.6-27b"
export NIM_EMBEDDING_MODEL="text-embedding-qwen3-embedding-0.6b:2"
export NIM_EMBEDDING_QUERY_INSTRUCTION="Given a Korean team workspace question, retrieve relevant document passages that answer the question"
export NIM_TEMPERATURE="0"
export NIM_MAX_TOKENS="4096"
export NIM_REASONING_EFFORT="none"
export NIM_READ_TIMEOUT="120"
export PGVECTOR_DATABASE_URL="postgresql://knot_benchmark:knot_benchmark@localhost:55432/knot_benchmark"
```

LM Studio 설정은 컨텍스트 32768, GPU offload 64, Max Concurrent 1로 고정한다. `reasoning_effort=none`은 내부 추론 토큰 때문에 답변이 잘리지 않게 하기 위한 통제 설정이며, 모델 품질 자체를 비교하는 실험이 아니다.

### 실제 LM Studio/Notion MCP smoke test

LM Studio 앱에서 Notion MCP를 OAuth로 연결한 뒤 native `/api/v1/chat`에 `mcp/notion` plugin을 지정한다. 허용 도구는 초기 검증에서 `notion-search`와 `notion-fetch`로 제한한다. LM Studio API token과 Notion MCP OAuth token은 서로 다른 credential이며, 어느 값도 저장소·프롬프트·벤치마크 결과에 기록하지 않는다.

이 방식은 LM Studio가 관리하는 단일 OAuth 연결을 확인하는 용도다. Workspace별 인증을 제품에 넣을 때는 Java가 서버 측에서 해당 Workspace credential을 선택하고, 모델에는 token을 전달하지 않은 채 MCP 결과만 넘기는 별도 경계를 검증해야 한다. 실제 smoke test 결과와 실패 사례는 [`docs/llm-search-ab-test-report.md`](../../backend/docs/llm-search-ab-test-report.md)에 기록한다.

## 4. pgvector 색인과 네 가지 비교

```bash
cd /path/to/2026-Knot
docker compose -f tools/llm-benchmark/compose.pgvector.yml up -d

uv run --python 3.14 tools/llm-benchmark/run_four_way_benchmark.py \
  --repeats 10 \
  --retrieval-only \
  --output .benchmark-data/four-way-retrieval-10x.jsonl

uv run --python 3.14 tools/llm-benchmark/evaluate_rag_quality.py \
  --results .benchmark-data/rag-quality-retrieval-final-10x.jsonl
```

`--retrieval-only` 실행은 모델을 호출하지 않고 네 전략의 검색·컨텍스트 구성 시간을 10개 질문 × 10회씩 기록한다. 실제 답변 생성은 다음처럼 실행한다.

four-way 결과의 각 행에는 `metadata`가 함께 기록된다. `metadata.run_id`는 한 실행을 묶고, `phase`는 `control`/`live`, `condition`은 `cold`/`warm`, `snapshot_id`는 정규화된 내보내기 지문, `model`은 채팅 모델, `prompt_sha256`은 system prompt 지문, `generation_options`는 비밀값을 제외한 생성·검색 옵션, `observed_at`은 실행 시각이다. `--run-id`로 재현 가능한 이름을 지정할 수 있으며, `--condition`을 생략하면 `--warmup 0`은 `cold`, 그 외에는 `warm`으로 기록한다. 기본 independent JSON workload를 지정하면 W-001부터 W-031까지를 자동으로 모두 선택한다.

품질 평가기는 gold set의 모든 case/turn/repeat가 생성됐는지, 출처 page ID가 기대값과 일치하는지, 출처가 3개를 넘지 않는지, 무응답·명확화 케이스가 빈 출처로 종료되는지를 검사한다. retrieval-only 결과에서는 답변 의미 품질을 `NOT_EVALUATED`로 표시하므로, 생성 결과는 `--require-answer`로 별도 검사하고 최종 의미 왜곡 여부는 사람이 원문과 대조한다.

최종 비교에 사용할 결과는 실행 메타데이터까지 검사한다.

~~~bash
uv run --python 3.14 tools/llm-benchmark/evaluate_rag_quality.py \
  --gold-set backend/docs/llm-search-benchmark-independent-30.json \
  --results .benchmark-data/independent-rag-e2e.jsonl \
  --strategy rag \
  --repeats 1 \
  --require-answer \
  --require-run-metadata
~~~

`--require-run-metadata`를 켜면 모든 결과 행이 하나의 run ID·phase·cold/warm 조건·snapshot·모델·system prompt·생성 옵션을 공유해야 한다. 서로 다른 실행 결과를 합쳐 통계적으로 보이게 만드는 실수를 차단한다.

그룹별 e2e 결과를 합치지 않고도 다음처럼 `--results`를 반복해 한 번에 검사할 수 있다.

```bash
uv run --python 3.14 tools/llm-benchmark/evaluate_rag_quality.py \
  --results .benchmark-data/rag-quality-e2e-final-core-guided.jsonl \
  --results .benchmark-data/rag-quality-e2e-final-policy-guided.jsonl \
  --results .benchmark-data/rag-quality-e2e-g007-guided-final.jsonl \
  --results .benchmark-data/rag-quality-e2e-g013-guided-final.jsonl \
  --repeats 1 \
  --require-answer
```

```bash
uv run --python 3.14 tools/llm-benchmark/run_four_way_benchmark.py \
  --skip-index \
  --repeats 1 \
  --warmup 1 \
  --output .benchmark-data/four-way-e2e.jsonl
```

Raw는 전체 원문을 조용히 자르지 않는다. 컨텍스트가 설정한 보호선을 넘으면 오류로 기록하고, RAG·DB·MCP replay만 같은 채팅 모델에 검색 결과를 전달한다. 이 결과는 Raw가 현재 문서 규모에서 운영 방식이 될 수 없는지도 보여준다.

분석 보고서는 검색 100회 관측과 생성 결과를 합쳐 작성한다.

```bash
uv run --python 3.14 tools/llm-benchmark/analyze_access_benchmark.py \
  --results .benchmark-data/four-way-retrieval-10x.jsonl \
  --e2e-results .benchmark-data/four-way-e2e.jsonl \
  --output backend/docs/llm-search-ab-test-report.md
```

분석기는 검색 p50/p95, Qwen 임베딩 시간, DB 단계, end-to-end TTFT/total, 5초 내 첫 표시 비율, source hit@5, paired bootstrap CI와 sign-flip permutation p-value를 계산한다. 독립 workload 결과를 분석할 때는 반드시 같은 입력 gold set을 `--gold-set backend/docs/llm-search-benchmark-independent-30.json`으로 전달한다. 반복 수가 늘어도 독립 질문 수가 늘지는 않으므로, 일반화 판정 전 30개 이상 독립 질문과 사람이 확인한 품질 라벨이 필요하다.

생성 결과에 기록된 실행 metadata는 보고서의 `control`/`live` 별도 섹션에 run ID·condition·snapshot·model·prompt/options 지문으로 표시된다. 실제 값과 비밀값은 보고서에 복사하지 않으며, 최종 품질 평가는 `evaluate_rag_quality.py --require-run-metadata`로 동일 실행 경계를 다시 검사한다.

NVIDIA hosted endpoint를 비교해야 할 때만 아래처럼 주소와 실제 Build 모델을 바꾼다.

```bash
export NIM_API_KEY="nvapi-..."
export NIM_BASE_URL="https://integrate.api.nvidia.com/v1"
export NIM_MODEL="<build.nvidia.com에서 선택한 모델명>"

uv run --python 3.14 tools/llm-benchmark/run_benchmark.py \
  --snapshot-dir "$SNAPSHOT_DIR" \
  --strategy rag \
  --case G-001,G-003,G-004,G-005,G-006,G-007,G-008,G-009,G-010,G-011,G-012,G-013 \
  --top-k 5 \
  --repeats 3 \
  --output .benchmark-data/rag-results.jsonl
```

전략별 모델·프롬프트·생성 옵션은 동일하게 유지해야 한다. API endpoint는 [NVIDIA NIM Chat Completions API](https://docs.api.nvidia.com/nim/reference/create_chat_completion_v1_chat_completions_post)에 맞춘 OpenAI 호환 스트리밍 요청이다.

## 5. 기존 Raw/RAG A/B 실행

Raw와 RAG만 비교할 때는 순서를 균형 무작위화하는 전용 runner를 사용한다.

```bash
uv run --python 3.14 tools/llm-benchmark/run_ab_benchmark.py \
  --snapshot-dir "$SNAPSHOT_DIR" \
  --repeats 10 \
  --output .benchmark-data/ab-results.jsonl

uv run --python 3.14 tools/llm-benchmark/analyze_benchmark.py \
  --results .benchmark-data/ab-results.jsonl \
  --output backend/docs/llm-search-ab-test-report.md
```

기본 workload는 10개 single-turn case를 10회 반복해 100 paired trials를 만든다. 다만 서로 다른 case는 10개뿐이므로 일반화 판정이 필요하면 `--case`로 최소 30개 이상의 독립 질문 workload를 추가한다. 분석기는 유효 paired 관측 100개와 독립 case 30개를 모두 충족하기 전에는 통계적 승리 판정을 내리지 않는다. `--plan-only`로 NIM 호출 없이 순서 균형만 확인할 수 있다.

## 6. 해석 순서

속도만 보고 선택하지 않는다.

1. 다른 Workspace 문서나 민감 정보가 컨텍스트에 들어가지 않았는지 확인한다.
2. 답변이 골드셋 원문과 일치하고, 충돌·무응답·명확화 정책을 지키는지 사람이 평가한다.
3. 그 조건을 통과한 전략만 p50/p95 end-to-end TTFT와 5초 이내 비율을 비교한다.
4. 이후 실제 Notion API/MCP의 권한, 페이지네이션, rate limit, 동기화 실패를 별도로 측정한다.

정답표는 [`docs/llm-search-benchmark-gold-set.md`](../../backend/docs/llm-search-benchmark-gold-set.md), 전체 도입·동기화 정책은 [`docs/llm-search-benchmark-plan.md`](../../backend/docs/llm-search-benchmark-plan.md)에 있다.

## 7. 실제 Notion MCP live benchmark

run_mcp_live_benchmark.py는 읽기 전용 Streamable HTTP MCP transport와 Notion adapter를 사용해 검색·fetch·페이지네이션·재시도·rate limit 계측을 수행한다. MCP access token은 환경변수로만 주입하고 결과 JSONL·프롬프트·로그에 기록하지 않는다. NOTION_MCP_ALLOWED_PAGE_IDS는 연결된 대상 범위를 명시하는 필수 allowlist다.

```bash
export NOTION_MCP_ACCESS_TOKEN="..."
export NOTION_MCP_WORKSPACE_ID="connected-workspace-id"
export NOTION_MCP_ALLOWED_PAGE_IDS="page-id-1,page-id-2"
export NOTION_MCP_ACTIVE_SNAPSHOT_ID="optional-active-snapshot"

uv run tools/llm-benchmark/run_mcp_live_benchmark.py \
  --gold-set backend/docs/llm-search-benchmark-gold-set.md \
  --retrieval-only \
  --case G-001,G-003,G-005 \
  --top-k 3 \
  --output .benchmark-data/mcp-live-retrieval.jsonl
```

retrieval-only는 NIM을 호출하지 않고 MCP access만 측정한다. 답변까지 비교할 때는 NIM_API_KEY, NIM_BASE_URL, NIM_MODEL을 별도로 주입하고 retrieval-only를 생략한다. 결과에는 MCP HTTP 요청 수, fetch page 수, retry 수, 429 rate-limit 수, access/search 시간, 모델 TTFT·완료 시간, E2E TTFT·완료 시간이 분리되어 기록된다. 검색 시간에 모델 생성 시간을 더하지 않도록 runner에서 MCP access 측정 시점을 생성 전에 고정한다.

실제 Notion MCP가 읽는 범위는 OAuth 연결에 공유된 페이지와 하위 페이지에 한정된다. Java 운영 경로에서는 token을 NIM으로 전달하지 않고, Workspace credential을 서버에서 선택한 뒤 이 adapter에만 주입한다. LM Studio가 관리하는 OAuth smoke test와 Java credential forwarding 검증은 별도 결과로 기록한다.

모델이 반환한 read-only MCP 호출 묶음은 `mcp_tool_loop.py`의 `execute_nim_tool_calls` 경계에서 전부 먼저 검증한 뒤 모델이 반환한 순서대로 실행한다. 지원하지 않는 도구나 잘못된 인자가 하나라도 있으면 adapter 실행을 시작하지 않는다. 이 유틸리티는 호출 계약 테스트용이며, 실제 운영의 Workspace credential 선택과 NIM tool-calling continuation은 Java adapter 연결 단계에서 별도로 구현·검증한다.

## 8. 30개 이상 독립 질문과 사람 검수

기존 Markdown 골드셋은 대화형 핵심 시나리오를 보존하고, 독립 표본의 일반화 검증은 [docs/llm-search-benchmark-independent-30.json](../../backend/docs/llm-search-benchmark-independent-30.json)으로 분리한다. 이 manifest는 31개 case와 33개 turn을 포함하며, 각 항목에 질문 유형·기대 page ID·사람이 확인할 핵심 사실을 기록한다. 후속 대화는 `expected_source_ids_by_turn`으로 turn별 근거를 구분한다. expected_source_ids는 원문 검토용 기준이며, no_answer와 broad 응답에서 사용자에게 source를 노출하라는 뜻이 아니다.

통제된 검색 실행은 동일한 Qwen·스냅샷·옵션으로 수행한다. 31개 case 전체를 명시해야 하며, 반복 횟수는 독립 case 수와 별개로 기록한다.

~~~bash
uv run --python 3.14 tools/llm-benchmark/run_four_way_benchmark.py \
  --gold-set backend/docs/llm-search-benchmark-independent-30.json \
  --case W-001,W-002,W-003,W-004,W-005,W-006,W-007,W-008,W-009,W-010,W-011,W-012,W-013,W-014,W-015,W-016,W-017,W-018,W-019,W-020,W-021,W-022,W-023,W-024,W-025,W-026,W-027,W-028,W-029,W-030,W-031 \
  --strategy rag \
  --repeats 10 \
  --retrieval-only \
  --output .benchmark-data/independent-rag-retrieval-10x.jsonl

uv run --python 3.14 tools/llm-benchmark/evaluate_rag_quality.py \
  --gold-set backend/docs/llm-search-benchmark-independent-30.json \
  --results .benchmark-data/independent-rag-retrieval-10x.jsonl \
  --strategy rag \
  --repeats 10
~~~

답변 의미와 source 관련성은 [docs/llm-search-benchmark-human-review-template.jsonl](../../backend/docs/llm-search-benchmark-human-review-template.jsonl)을 복사해 실제 생성 결과를 보며 채운다. 결과 JSONL에는 각 관측의 `result_fingerprint`가 포함되며, terminal 라벨은 해당 지문을 함께 기록해야 한다. 템플릿은 33개 행을 모두 pending으로 시작한다. 각 행의 answer_correct, sources_relevant, policy_compliant를 모두 확인한 뒤에만 decision을 pass 또는 fail로 바꾼다.

~~~bash
cp backend/docs/llm-search-benchmark-human-review-template.jsonl .benchmark-data/independent-rag-human-review.jsonl

uv run --python 3.14 tools/llm-benchmark/evaluate_rag_quality.py \
  --gold-set backend/docs/llm-search-benchmark-independent-30.json \
  --results .benchmark-data/independent-rag-e2e.jsonl \
  --strategy rag \
  --repeats 1 \
  --human-labels .benchmark-data/independent-rag-human-review.jsonl \
  --human-repeat 1 \
  --require-human-review
~~~

human_gate=PASS가 되기 전에는 5초 TTFT가 좋아도 품질 승자나 최종 아키텍처로 판정하지 않는다. 모델 답변·Notion 원문·access token은 이 저장소에 추가하지 않는다.
