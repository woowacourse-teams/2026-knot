# LLM 문서 검색 아키텍처 벤치마크와 단계형 검색 후보

## 상태

Proposed

## 관련 Issue

- #271 [BE] LLM 문서 검색 아키텍처 벤치마크 하네스 구현
- #275 [BE] LLM 검색 품질 평가·아키텍처 판정 보고서 구현

## 한 줄 요약

전체 원문 전달은 운영 후보에서 제외하고, 마지막 성공 동기화 스냅샷을 기준으로 DB/키워드 pre-filter → Qwen pgvector RAG → 필요 시 rerank를 MVP 기본 검색 경로로 채택한다.

이번 기록은 MVP 구현 방향을 결정한 것이며, Java credential forwarding과 사람의 품질 평가가 끝나기 전에는 전체 사용자 대상 운영 아키텍처로 확정하지 않는다.

## 왜 이 결정이 필요했나

Knot은 Workspace 소유자가 지정한 Notion 문서를 동기화하고, 팀원이 문서 위치나 날짜를 기억하지 않아도 자연어로 결정사항과 회의록을 찾게 해야 한다. 검색 결과는 답변뿐 아니라 근거 문서와 함께 제공되어야 하며, 첫 답변 표시 시간은 5초 이내를 목표로 한다.

문서를 매번 모두 모델에 전달하면 문서 수가 늘어날수록 컨텍스트 비용과 초과 위험이 커진다. 반대로 검색 단계가 빠르더라도 의미가 다른 문서를 근거로 선택하면 답변 품질을 보장할 수 없다. 따라서 같은 스냅샷과 채팅 모델을 사용해 원본, DB 직접 검색, pgvector RAG, MCP replay를 분리 측정할 필요가 있었다.

## 트레이드 오프

| 선택지 | 장점 | 단점 | 이번 판정 |
| --- | --- | --- | --- |
| 전체 원문 직접 전달 | 검색 구현이 단순하고 원문 누락이 적다. | 문서 규모에 따라 컨텍스트 한도를 넘고 매 요청 비용이 커진다. | 운영 후보에서 제외 |
| PostgreSQL DB/키워드 직접 검색 | 검색 지연이 가장 짧고 구현·운영이 단순하다. | 표현이 다른 질문과 문서의 의미 유사성을 놓칠 수 있다. | pre-filter 및 기준선으로 채택 |
| Qwen 임베딩 + PostgreSQL pgvector RAG | 한국어·의미 기반 후보를 만들고 모델에 필요한 청크만 전달한다. | 임베딩 생성·색인 운영이 필요하고, 검색 품질을 별도 평가해야 한다. | MVP 기본 검색 경로로 채택 |
| MCP replay/live | 도구 경계와 실시간 외부 문서 접근을 검증할 수 있다. | live는 Notion 네트워크·페이지네이션·권한·rate limit에 영향을 받고, replay는 live 지연을 대표하지 않는다. | replay는 통제 비교군, live smoke test 완료·전체 비교 보류 |

## 무엇을 결정했나

- 검색 대상은 Workspace의 마지막 성공 동기화 스냅샷으로 제한한다. 비활성·부분 동기화 데이터와 다른 Workspace의 문서는 답변 컨텍스트에 넣지 않는다.
- Notion access token은 Knot 백엔드에만 보관하고 NIM 요청에는 전달하지 않는다. NIM에는 검색된 문서 내용과 필요한 메타데이터만 전달한다.
- MVP 기본 검색 경로는 `DB/키워드 pre-filter → Qwen3-Embedding-0.6B 임베딩 → PostgreSQL pgvector 후보 검색 → 관련 청크만 채팅 모델에 전달`로 채택한다. 후보가 충분하지 않거나 품질 문제가 확인되면 reranker를 별도 검증한다.
- Raw는 문서가 커질 때 조용히 잘라서 성공으로 계산하지 않는다. 컨텍스트 한도를 넘으면 오류로 기록해 운영 부적합을 드러낸다.
- `mcp-replay`는 동일한 로컬 스냅샷을 읽기 도구 응답처럼 재생하는 통제군으로만 해석한다. 실제 Notion MCP/API의 성능·권한 결론으로 사용하지 않는다.
- 전체 사용자 대상 운영 전환은 Java credential forwarding, 30개 이상 독립 질문, 실제 Notion MCP-live의 반복 측정, 사람이 검증한 답변·관련 문서 품질 라벨을 확보한 뒤 판정한다.

## 결과

통제 스냅샷은 461개 문서와 2,715개 청크로 구성했다. Qwen 임베딩은 LM Studio OpenAI-compatible API에서 1,024차원으로 실행했다.

- 검색 p50은 DB 직접 검색 79.6ms, MCP replay 118.7ms, RAG 200.2ms였다. 검색 단계만 보면 DB가 가장 빨랐다.
- RAG와 DB의 end-to-end TTFT 차이는 현재 10개 질문 표본에서 통계적으로 유의한 승자를 선언할 수 없었다. 세 후보의 첫 표시 시간은 이번 로컬 실행에서 각각 10/10건이 5초 이내였다.
- Raw는 약 1,559,218자의 전체 컨텍스트가 생성 보호선을 초과해 답변 생성을 시도하지 않았다. 이를 성공으로 처리하지 않았다.
- RAG는 일부 질문에서 요구사항 문서의 예시 문장을 실제 결정 이유로 오인했다. 검색 속도와 별개로 문서 유형 판별·rerank·사람 품질 평가가 필요하다.
- `mcp-replay`는 로컬 lexical replay이므로 Notion API 호출 시간, 페이지네이션, 권한 범위, rate limit은 측정되지 않았다.

## 실제 LM Studio/Notion MCP-live smoke test

2026-09-01에 LM Studio `0.4.16` native `/api/v1/chat`에서 `qwen/qwen3.6-27b`와 `mcp/notion` plugin을 사용했다. LM Studio 출력의 `provider_info.plugin_id`와 실제 `notion-search`/`notion-fetch` 호출을 확인했다.

- PostgreSQL 결정 이유 질의는 `01. 기술 스택과 라이브러리 도입` 문서를 찾아 관계형 데이터 안정적 관리와 향후 `pgvector` 확장을 근거로 답변했다.
- `previous_response_id`를 사용한 후속 질문은 앞선 PostgreSQL 문맥을 유지했지만, 추가 검색과 reasoning 때문에 약 190초가 걸렸다.
- 로드맵 날짜와 Redis 예시·공식 결정 부재 질의는 동작했으며, 넓은 코드 컨벤션 질의는 관련도가 낮은 문서를 선택했고 구체화한 질의에서 회복했다.
- 관측된 live 요청의 end-to-end 완료 시간은 약 38~77초였고 후속 질문은 약 190초였다. 5초 목표를 만족한 것으로 판정하지 않는다.
- 이번 smoke test는 LM Studio-managed OAuth 연결만 검증했다. Java가 Workspace별 MCP token을 선택·전달하는 운영 경계, Workspace 격리, 30개 이상 독립 질문 품질 평가는 남아 있다.

상세 원시 결과와 통계는 Git에서 제외한 `.benchmark-data/`에 기록한다.

## 다시 논의해야 할 조건

- 30개 이상 독립 질문에서 RAG의 답변 근거성과 관련 문서 품질이 DB 기준선보다 낮게 확인되는 경우
- 실제 Notion MCP-live에서 API 지연 또는 권한 범위가 5초 목표와 충돌하는 경우
- Java credential forwarding에서 Workspace별 token 선택·폐기·재인증 경계가 검증되지 않는 경우
- 임베딩 모델·차원·pgvector 인덱스 운영 비용이 MVP 규모를 초과하는 경우
- 동기화 스냅샷의 최신성·논리적 삭제·Workspace 격리 정책이 변경되는 경우
- 최종 운영 모델 또는 reranker를 별도 결정할 충분한 품질·비용·지연 자료가 확보되는 경우

## 확인

이 ADR은 통제 벤치마크 결과를 남기기 위해 `Proposed`로 작성한다. 팀이 PR에서 실험 범위와 단계형 검색 후보를 검토·승인한 뒤에만 `Accepted`로 변경한다.
