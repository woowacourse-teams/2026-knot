# Java LLM·pgvector 연동 가이드

상태: MVP 연동 구현 및 컨테이너 검증 완료, 실제 NIM 운영 전 검증 중

관련 이슈: [#311](https://github.com/woowacourse-teams/2026-Knot/issues/311)

## 기본 경로

Java 백엔드는 Notion credential이나 MCP token을 LLM에 넘기지 않는다. Workspace 소유자가 연결한 Notion 문서는 Import worker가 마지막 성공 publication으로 만든 스냅샷에 저장되고, 색인 완료 후에만 공개된다.

```text
Notion Import
  → imported_pages staging
  → Markdown chunking
  → Qwen embedding
  → PostgreSQL pgvector 색인
  → 성공 시 publication pointer 교체

채팅 질문
  → 현재 Workspace의 published import run 조회
  → keyword + pgvector 후보 검색
  → 출처가 다른 최대 3개 청크 선별
  → 근거 system prompt + 현재 세션 history를 LLM에 전달
  → assistant 저장 + search_reference 저장 + SSE complete
```

새 Import가 진행 중이거나 색인에 실패하면 기존 성공 publication을 유지한다. 최초 성공 publication이 없으면 채팅은 LLM을 호출하지 않고 `CHAT_DOCUMENTS_NOT_READY` 오류를 반환한다.

## 실행 설정

기본값은 외부 호출이 없는 `fake` 모드다. LM Studio 또는 NVIDIA NIM을 사용할 때만 `openai-compatible`을 활성화한다.

| 환경 변수 | 예시 | 용도 |
| --- | --- | --- |
| `LLM_PROVIDER` | `openai-compatible` | 실제 OpenAI 호환 endpoint 사용 |
| `LLM_BASE_URI` | `http://<lm-studio-host>:1234/v1` | 채팅·임베딩 endpoint의 공통 base URI |
| `LLM_API_KEY` | `<secret>` | Authorization header에만 사용 |
| `LLM_MODEL` | `qwen/qwen3.6-27b` | 채팅 모델 |
| `LLM_EMBEDDING_MODEL` | `text-embedding-qwen3-embedding-0.6b:2` | 임베딩 모델 |
| `LLM_EMBEDDING_DIMENSIONS` | `1024` | V13 pgvector 차원 계약 |
| `LLM_SEARCH_EMBEDDING_BATCH_SIZE` | `64` | Import 색인 시 한 번에 임베딩을 요청할 청크 수 |
| `LLM_SEARCH_MINIMUM_RELEVANCE_SCORE` | `0.35` | 검색 후보를 근거로 채택하기 위한 최소 정규화 점수 |
| `LLM_MAX_TOKENS` | `1024` | 채팅 생성 상한 |
| `LLM_TEMPERATURE` | `0.2` | 채팅 생성 온도 |
| `LLM_REQUEST_TIMEOUT` | `PT30S` | 채팅·임베딩 HTTP timeout |

LM Studio는 `/v1/chat/completions`와 `/v1/embeddings`를 OpenAI-compatible API로 제공해야 한다. 임베딩 모델의 실제 응답 차원은 `1024`여야 한다. API key는 환경 변수나 secret manager로만 주입하고 저장소·프롬프트·로그·SSE에 기록하지 않는다.

NVIDIA NIM을 사용할 때는 같은 adapter에 다음처럼 base URI와 key만 바꾼다.

```text
LLM_PROVIDER=openai-compatible
LLM_BASE_URI=https://integrate.api.nvidia.com/v1
LLM_API_KEY=<NIM secret>
LLM_MODEL=<NIM chat model>
LLM_EMBEDDING_MODEL=<NIM embedding model>
```

`LLM_SEARCH_EMBEDDING_BATCH_SIZE`는 임베딩 provider의 요청 크기·timeout에 맞춰 조정한다. 모든
배치가 성공하기 전에는 새 import snapshot을 공개하지 않는다. `LLM_SEARCH_MINIMUM_RELEVANCE_SCORE`
미만인 vector·keyword 후보는 답변 근거에서 제외하며, 남은 후보가 없으면 LLM을 호출하지 않고
문서 없음 응답을 반환한다.

## PostgreSQL

V13이 `vector` extension과 `search_document_chunks`, `search_references`를 만든다. 개발·테스트 PostgreSQL은 pgvector 이미지를 사용한다.

```bash
docker compose up -d postgres
./gradlew test
./gradlew integrationTest
./gradlew acceptanceTest
```

운영 DB가 이미 존재하면 배포 전에 `CREATE EXTENSION vector` 권한과 HNSW index 생성을 확인한다. 임베딩 차원을 바꾸는 경우에는 기존 V13 테이블과 모델 호환성을 별도 migration/ADR로 결정해야 한다.

## Spring AI를 이번 단계에 추가하지 않은 이유

Spring AI 2.0.x는 현재 프로젝트의 Spring Boot 4.1.x와 호환되는 선택지이고, `ChatClient.stream()`도 제공한다. 다만 현재 Knot 채팅은 MVC `SseEmitter`와 pull형 `LlmStream`을 사용하며, Import publication과 custom `search_document_chunks` schema를 이미 직접 통제한다. 이 단계에서 Spring AI를 넣으면 reactive stack과 Spring AI vector store abstraction을 추가로 맞춰야 하므로, 직접 JDK HTTP adapter와 JDBC 검색을 유지했다.

향후 다음 조건이 생기면 Spring AI를 다시 검토한다.

- 채팅 transport를 WebFlux/Reactive streaming으로 전환한다.
- 채팅·임베딩 provider를 여러 개 라우팅해야 한다.
- MCP tool calling과 prompt/tool 관측을 공통 abstraction으로 운영해야 한다.

참고: [Spring AI Getting Started](https://docs.spring.io/spring-ai/reference/getting-started.html), [ChatClient streaming](https://docs.spring.io/spring-ai/reference/api/chatclient.html), [OpenAI-compatible configuration](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/chat/openai-chat.html), [PgVector](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html)

## 검증 범위

- `./gradlew test`: chunking, hybrid ranking, no-result/broad question, embedding response, grounding prompt, Import 색인 실패 경계
- `./gradlew integrationTest`: pgvector vector/keyword query, Workspace 격리, unpublished run 차단, 전체 기존 integration 회귀
- `./gradlew acceptanceTest`: 기존 Notion Import/Page Tree acceptance 회귀
- 기존 #308 실행 기록(역사적): 30 tests passed, 10회 retrieval 구조/source gate 160/160, retrieval p50 343.0ms·p95 968.1ms

벤치마크 결과의 답변 의미·source 직접 관련성은 사람이 원문과 대조해야 한다. Java adapter와 실제 NIM을 연결한 뒤에는 동일 gold set으로 end-to-end TTFT와 answer/source 품질을 다시 측정한다.
