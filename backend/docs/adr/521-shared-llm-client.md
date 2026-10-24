# 공통 통신은 global/infrastructure/llm, 문서 프롬프트·해석은 document/infrastructure/llm에 둔다.

## 상태

Proposed

## 관련 Issue

- #521 [BE] 공통 LLM API 연동과 전사 원문 주제 분류 구현

## 한 줄 요약

공통 통신은 global/infrastructure/llm, 문서 프롬프트·해석은 document/infrastructure/llm에 둔다.

## 왜 이 결정이 필요했나

문서 분류·작성에서 공통으로 사용할 단일 LM Studio API 통신이 필요하다.

사용자가 llm 별도 패키지와 global/infrastructure/llm 및 document/infrastructure/llm의 분리를 논의하고 후자를 선택했다.

결정 동인:

- 공통 통신 재사용
- 문서 의미 해석 책임 분리
- 단일 공급자 구현의 단순성

## 검토 근거

최초 결정 snapshot은 복구하지 못했다. 아래의 추가 대안 비교는 2026-10-09 PR #534 리뷰 반영에서 실제로 검토한 내용이며, 과거에 논의했다고 소급하지 않는다.

## 트레이드 오프

- document/infrastructure 내부에 HTTP 통신 유지: 작은 단일 문서 기능에서 global 추출을 피할 수 있는 기본안이다. #522도 같은 인증·timeout·envelope 검증을 사용하므로 중복을 피하려고 공통 통신 분리를 선택했다.
- 독립 llm 도메인 패키지: 사용자 제안. 현재는 별도 도메인 규칙보다 외부 통신 책임이 중심이다.
- global 공통 client + document 전용 프롬프트·해석: 사용자 선택. 문서 규칙을 document에 보존한다.

### HTTP 구현 · 2026-10-09 재검토

- Java 25 HttpClient: 기존 JDK로 단일 POST를 구현하고 Future 대기 기한·interrupt·응답 byte 제한을 직접 제어한다. Spring 변환 계층은 사용하지 않으므로 envelope·상태 처리를 직접 테스트해야 한다.
- Spring RestClient: Spring의 HTTP 변환·상태 처리와 통합하기 쉽다. 전체 본문 중단·응답 크기 제한은 선택한 request factory와 함께 검증해야 한다. 기존 저장소가 RestClient로 통일됐다는 근거는 없다.
- 선택: 이미 검증한 HttpClient 구현을 유지한다. 공급자 SDK·WebFlux 의존성 추가 없이 현재 요구를 충족하며, timeout·중단·본문 제한은 로컬 HTTP 계약 테스트로 검증한다.

## 무엇을 결정했나

공통 통신은 global/infrastructure/llm, 문서 프롬프트·해석은 document/infrastructure/llm에 둔다.

문서 의미를 공통 client에 섞지 않으면서 단일 공급자의 단순한 구현을 유지한다.

## 결과

- application의 `DocumentTopicClassifier.classify(String)` 계약을 infrastructure의 `LlmDocumentTopicClassifier`가 구현한다. 서비스는 구체 adapter를 import하지 않는다. 문서 계층 규칙의 예외를 도입하지 않는다.

- #522가 같은 통신 client를 재사용한다.
- 사용자 공급자 교체 요구를 반영해 `LlmClient.complete` 전략 인터페이스를 둔다. 문서 classifier는 인터페이스에만 의존하고 `LlmConfig`가 첫 `LmStudioClient` 전략을 주입한다.
- 지원하지 않는 공급자 설정은 시작 시 실패한다. 두 번째 공급자의 실제 계약이 정해질 때 전략을 추가하며 Factory나 미구현 공급자 클래스를 먼저 만들지 않는다.
- 상위 서비스가 classify를 DB 트랜잭션 밖에서 호출한다.

## 다시 논의해야 할 조건

- 두 번째 공급자를 실제 지원할 때
- 공통 LLM 비즈니스 도메인 규칙이 생길 때

## 확인

- 예정 경로: `backend/docs/adr/521-shared-llm-client.md`
- 결정 주체: jyt6640(흑곰). 대화에서 공통/문서 infrastructure 분리와 공급자 교체 계약을 선택했고, 2026-10-09 리뷰 수정도 요청했다.
- 팀의 이 ADR 자체에 대한 승인 상태는 별도로 확인하지 않았다. PR의 일반 승인만으로 ADR을 Accepted로 바꾸지 않는다.
- AI 하네스가 Proposed ADR 파일을 생성했다.
- 팀이 PR에서 승인한 뒤 Accepted로 바꾼다.
