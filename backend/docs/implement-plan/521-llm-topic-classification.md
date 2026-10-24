# #521 공통 LLM API 연동과 전사 원문 주제 분류 구현 계획

- 상태: #521 제품 구현과 실제 합성 API 관찰 완료. #534 리뷰에 따라 계층 의존성과 문서 근거를 보완한다. 품질 관찰의 실패와 한계는 별도로 기록한다.
- 계획·첫 검증: 2026-10-08 KST. 리뷰 반영: 2026-10-09·10 KST.
- 대상: [Issue #521](https://github.com/woowacourse-teams/2026-Knot/issues/521).
- 현재 브랜치: `be/feature/#521`.
- 현재 요청 범위: #534 리뷰 수정·검증·커밋·푸시·답글. 공용 Notion 하네스는 사용자의 지시에 따라 같은 PR에 포함하며, 작성·선별 실험은 #535로 이동한다.

## 1. 범위와 브랜치

### 이번에 완성할 동작

저장된 `Transcript.content` 전체를 전달받아 LM Studio에 한 번 요청하고, 문서화할 주제 목록을 반환한다. 정상 응답의 빈 주제 배열과 호출·응답 실패를 구분한다. 사용자용 HTTP API를 새로 만들지 않는다. 이후 자동 생성 흐름이 호출할 내부 서비스다.

| 작업 | 담당 범위 |
| --- | --- |
| #521 | 공통 API 통신·설정·실패 계약, 문서 전용 분류 프롬프트, 검증·정규화한 주제 목록 반환 |
| #522 | 확정 주제 하나의 `title`·`summary`·Markdown 본문 생성·검증 |
| #523 | 저장 완료 원문 접수, 분류 결과 저장, 주제별 Job 등록, 중복 접수·중단 복구 |
| #524 | Document DRAFT·확인 대상·Job 성공 기록의 원자적 저장 |
| #525 | Job 실행·실패 기록·재시도·RUNNING 복구·녹음 전체 결과 집계 |
| #526 | 실패 입력·원본 오디오의 보존 정리 |

분류 결과로 주제 세 개를 받았다고 이 작업에서 문서 세 개를 저장하지 않는다. #521의 완료 결과는 검증된 주제 목록이다. `NO_CONTENT`도 여기서는 결과의 의미이며 DB에 새 상태를 추가하는 작업이 아니다.

### 계획 시작 시 저장소·브랜치 근거 · 2026-10-08

아래는 구현 전 관측이다. 현재 구현 완료 상태나 최신 PR 목록으로 해석하지 않는다.

- 작업 시작 시 working tree는 깨끗했다. `git fetch origin develop` 후 HEAD는 `abcdc125`, `origin/develop`은 `54b50666`이었다. 현재 브랜치는 develop보다 커밋 2개 앞서고 뒤처진 커밋은 없었다.
- develop과의 제품 코드 차이는 없으며 앞선 커밋에는 `backend/docs/llm-test/`와 `docs/harness/notion-alignment.md` 변경이 포함돼 있다. 이 계획에서 기존 문서를 재편집하지 않는다. 새 checkout·merge는 필요하지 않다.
- 현재 `global/infrastructure/llm`·`document/infrastructure/llm` 및 LLM client는 없다.
- `Transcript`는 `recordingSessionId`·전체 `content`·`createdAt`을 저장한다. 현재 클래스가 `TranscriptionJob` 연결이나 구간 저장을 제공한다고 가정하지 않는다.
- 열린 PR #520은 #499의 원문 조회·`TranscriptSegment`와 V31 migration을 포함한다. #521은 구간 테이블을 읽지 않고 전체 문자열을 입력받으므로 해당 PR을 선행 병합하거나 cherry-pick할 필요가 없다.
- 확인한 열린 PR 목록에는 별도의 공통 LLM 구현 PR이 없었다. 구현 직전 목록과 develop 상태를 다시 확인한다.

### 요구사항과 실험 결과의 구분

기준은 사용자와 정한 LM Studio 연동·패키지 분리, #521·상위 #501의 본문, [현재 V2 MVP 기준](../product/current-v2-mvp.md), [Notion 정합성 워크플로](../../../docs/harness/notion-alignment.md), 실제 코드다. 이번에 운영 Notion 원문을 실시간으로 재수집하거나 팀 승인 상태를 확인한 것은 아니다.

후속 #522·PR #535에서 보관하는 작성·선별 설정 실험의 추가 54회는 **문서 작성 48회와 특정 주제 발언 선별 6회**다. 전체 원문에서 모든 주제를 발견하는 #521의 품질 시험으로 계산하지 않는다. 작성 v7·추론 512·출력 4096의 추천은 #522의 후보이고, 발언 선별도 #521의 주제 분류와 다른 동작이다.

사용자가 지정한 공급자는 LM Studio, 연결 대상은 환경변수 `${LLM_BASE_URL}`, 실험 모델 별칭은 `qwen3.8-27b`다. 이를 구현 후보로 재사용한다. 기존 환경의 토큰은 코드·문서에 복사하지 않고 환경변수로 주입한다. 합성 원문 시험과 실제 사용자 원문 전송 허용 범위는 별개이며 후자는 운영 연결 전에 팀에서 확인한다.

## 2. 구현할 흐름

### 전체 제품 흐름에서의 위치

```mermaid
flowchart TD
    A["사용자: 녹음 종료·오디오 업로드"] --> B["음성 담당: STT 처리·Transcript.content 저장"]
    B --> C["#523: 저장 완료 원문 접수"]
    C --> D["#521: 전체 원문으로 주제 분류 요청 1회"]
    D --> E{"호출·응답 검증 결과"}
    E -->|"유효한 주제가 있음"| F["#523: 확정 주제 목록 저장·주제별 Job 등록"]
    E -->|"정상 topics: []"| G["#523: 내용 없음 결과 저장"]
    E -->|"호출 실패·잘못된 응답"| H["#523·#525: 분류 실패 기록·재시도 흐름 연결"]
    F --> I["#522·#525: 주제별 문서 작성·실행"]
    I --> J{"개별 주제 결과"}
    J -->|"성공"| K["#524: 문서·확인 대상·Job 성공 기록 저장"]
    J -->|"실패"| L["#525: 해당 Job 실패 기록"]
    K --> M["사용자: 성공한 문서 즉시 조회"]
    L --> N["실패한 Job만 재시도·성공 문서 유지"]
    classDef current fill:#dbeafe,stroke:#2563eb,stroke-width:2px,color:#111827;
    class D,E current;
```

위의 STT·저장·Job 실행은 연결 예정 흐름이다. #521만 구현해도 실제 녹음 종료부터 문서 생성까지 자동으로 이어지는 것은 아니다. 최초 생성에는 원문 검토·확인 완료를 요구하지 않는다.

### #521 내부 호출 순서

```mermaid
sequenceDiagram
    participant Caller as 후속 #523 호출자
    participant Service as DocumentTopicClassificationService
    participant Classifier as LlmDocumentTopicClassifier (application 계약 구현)
    participant Prompt as DocumentTopicPrompt
    participant Client as LlmClient (LM Studio 전략)
    participant LM as LM Studio API

    Caller->>Service: classify(전체 원문)
    Service->>Service: null·공백 입력 검증
    alt 잘못된 입력
        Service-->>Caller: 입력 오류 · 외부 호출 없음
    else 유효한 입력
        Service->>Classifier: classify(전체 원문)
        Classifier->>Prompt: createRequest(전체 원문)
        Prompt-->>Classifier: 메시지·JSON Schema·분류 옵션
        Classifier->>Client: complete(request)
        Client->>LM: POST /v1/chat/completions · 1회
        LM-->>Client: HTTP 응답 또는 통신 실패
        Client->>Client: 상태·종료 사유·assistant content 검증
        alt 공급자 실패 또는 미완료 응답
            Client-->>Classifier: 구분된 호출 오류
            Classifier-->>Service: 오류 전파
            Service-->>Caller: 실패 · 빈 배열로 바꾸지 않음
        else 정상 완료 응답
            Client-->>Classifier: assistant content 문자열
            Classifier->>Classifier: topics JSON 해석·타입·이름 검증
            alt 주제 응답 계약 위반
                Classifier-->>Service: 분류 응답 오류
                Service-->>Caller: 실패 · 빈 배열로 바꾸지 않음
            else 정상 topics 배열
                Classifier->>Classifier: 공백 정리·중복 제거·순서 유지
                Classifier-->>Service: 검증된 주제 목록
                Service-->>Caller: Result · 빈 목록이면 isNoContent=true
            end
        end
    end
```

서버에서 내부적으로 호출하므로 새 로그인·멤버십·CSRF 검사를 이 서비스에 넣지 않는다. 원문 소유 범위와 저장 완료 여부는 #523의 입력 조회·접수에서 확인하고, 기존 사용자 재시도 API의 권한은 유지한다. 프론트가 임의 원문·주제를 보내서 생성시키는 endpoint도 추가하지 않는다.

### 유즈케이스와 결과

| 유즈케이스 | 입력·상황 | #521 결과 | 후속 처리 |
| --- | --- | --- | --- |
| 복수 주제 발견 | 알림 표현·발송 채널·검색 조건을 논의한 전체 원문 | 주제 문자열 3개 | #523이 각각의 Job을 등록 |
| 짧지만 유효한 논의 | 한두 문장으로 보관 기간을 합의하거나 대안을 논의 | 해당 주제 반환 | 녹음 길이·글자 수 때문에 제외하지 않음 |
| 결정 없는 논의 | 이메일·앱 내 알림 장단점을 비교했지만 결론 없음 | 알림 채널 주제 반환 | 결론이 없다는 이유로 내용 없음 처리하지 않음 |
| 정상적인 내용 없음 | 인사·마이크 점검 등 문서화할 내용이 없음 | 정상 `topics: []`, 결과의 `isNoContent()`가 true | #523에서 정상 종료 결과를 기록 |
| 같은 이름 반복 | `" 검색 기능 "`, `"검색  기능"`, `"검색 기능"` | `"검색 기능"` 한 개 | 후속 작업에 확정 문자열을 전달 |
| 잘못된 모델 응답 | topics 누락·null·문자열·빈 항목·깨진 JSON | 분류 응답 실패 | 빈 주제 목록으로 바꾸지 않음 |
| 공급자 실패 | 인증 오류·429·5xx·timeout | 구분된 호출 실패 | 클라이언트가 다시 호출하거나 시도 수를 변경하지 않음 |
| 입력 한도 초과 | 전체 요청이 로드 컨텍스트에 들어가지 않음 | 입력 한도 실패 | 원문을 자르거나 내용 없음으로 바꾸지 않음 |

빈 입력 자체는 `INVALID_TOPIC_CLASSIFICATION_INPUT` 후보 오류로 거절한다. 저장 입력 누락을 정상 내용 없음으로 숨기지 않는다. 정상적인 내용 없음은 **유효한 입력에 대한 정상 모델 응답**으로 판정한다.

### 실제 요청 형태의 초안

환경변수에서 읽은 토큰을 `Authorization: Bearer <환경변수 값>`으로 전달한다. base URL에는 `/v1`을 포함하지 않고 client가 `/v1/chat/completions` 경로를 조립한다.

```json
{
  "model": "qwen3.8-27b",
  "messages": [
    {"role": "system", "content": "문서용 주제 분류 시스템 프롬프트 전체"},
    {"role": "user", "content": "{\"transcriptText\":\"저장된 전체 원문\"}"}
  ],
  "temperature": 0.2,
  "top_p": 0.9,
  "top_k": 20,
  "repeat_penalty": 1,
  "reasoning": "off",
  "chat_template_kwargs": {"enable_thinking": false},
  "max_tokens": 1024,
  "stream": false,
  "response_format": {
    "type": "json_schema",
    "json_schema": {
      "name": "document_topics",
      "strict": true,
      "schema": {
        "type": "object",
        "properties": {
          "topics": {
            "type": "array",
            "items": {"type": "string", "minLength": 1}
          }
        },
        "required": ["topics"],
        "additionalProperties": false
      }
    }
  }
}
```

user content는 문자열 연결로 JSON을 만들지 않고 Jackson으로 직렬화한다. 원문은 messages의 system 역할로 승격하지 않는다. JSON Schema가 전달돼도 서버에서 응답을 다시 검증한다. 정상 HTTP 응답의 `choices[0].message.content`는 JSON **문자열**이므로 HTTP envelope와 주제 JSON을 두 번 해석한다. 이 형식은 [LM Studio 공식 Structured Output 문서](https://lmstudio.ai/docs/developer/openai-compat/structured-output)를 확인했다.

설정은 분류용 **초기 후보**다. 1024와 추론 끔을 주제 분류의 최적값으로 검증한 것은 아니다. 복수 주제가 출력 한도에 걸리면 `finish_reason=length`를 실패로 반환하고, 먼저 합성 사례로 출력 한도를 재조정한다. 원문 일부나 앞의 몇 개 주제만 성공으로 반환하지 않는다.

초기 작성 실험은 stream=true였다. #521의 실제 합성 분류 실험 10회는 stream=false로 수행했고 정상 완료와 입력 초과 거절을 관찰했다. 이 결과로 실제 장시간 회의·동시 부하의 안정성까지 입증한 것은 아니다.

### 제품 분류 프롬프트의 기준

전체 시스템 프롬프트의 단일 기준은 [제품 리소스](../../src/main/resources/llm/document/topic-classification-system.txt)다. 계획서에 이전 초안을 복제하지 않는다. 담당자·기한 제외, 수치 비교 관계 보존, 결정·보류·미결정 상태를 임의로 이름 붙이지 않는 규칙을 포함한다.

[실제 분류 관찰](../llm-test/521-topic-classification-2026-10-08.md)에 입력·실제 출력·통과 기준·실패 판정을 기록했다. 복수 논의의 ‘발송 채널 결정’ 이름은 명명 규칙 실패다. 반복 장문은 프롬프트 예시와 겹치는 동일 입력 회귀이며 미지 입력 품질 근거로 계산하지 않는다. 전역 주제 사전은 이번 범위에 넣지 않는다.

## 3. 나올 코드와 메서드 초안

### 기존 코드에서 확인한 책임

| 기존 클래스·위치 | 현재 책임 | 이번 사용 방식 |
| --- | --- | --- |
| `recording/domain/Transcript` | 전사 전체 문자열 보관 | 이후 호출자가 `content`를 읽어 전달 |
| `document/application/DocumentGenerationInputQuery` | 재시도 입력의 Workspace 범위 조회 | #521에서 직접 호출하지 않음 |
| `document/infrastructure/DocumentGenerationInputJpaRepository` | `PESSIMISTIC_WRITE`로 입력 조회 | LLM 호출 중 잠금을 유지하는 용도로 재사용하지 않음 |
| `document/application/DocumentGenerationJobRetryService` | 본인 재시도 권한·상태·횟수를 한 트랜잭션으로 저장 | 실행기와 연결할 때 사용. 이번에는 수정하지 않음 |
| `document/domain/Document`·V27 | 읽기 전용 문서, 같은 녹음·주제 UNIQUE | 후속 결과 저장이 사용 |
| `global/exception/ProjectException`·`ErrorCode` | 프로젝트의 타입 있는 예외 계약 | 공통 호출 실패도 기존 계층에 맞춤 |
| `global/config` 설정 클래스 | Spring Bean·환경 설정 조립 | 신규 연결 설정의 위치 기준 |

### 제안 클래스

아래 경로는 `backend/src/main/java/com/knot/backend/` 기준이다. 공급자 교체 요구에 따라 `LlmClient.complete`를 전략 인터페이스로 둔다. 문서 classifier는 이 인터페이스만 의존하며 첫 전략은 `LmStudioClient`다. 별도 port 패키지·Factory·미구현 공급자 클래스는 추가하지 않는다.

| 위치·클래스 | 책임 | 메서드·입출력 후보 |
| --- | --- | --- |
| `global/config/LlmProperties` | base URL·token·model·연결/전체 응답 timeout·응답 크기·enabled 설정 | 설정 binding과 enabled일 때 필수값 검증 |
| `global/config/LlmConfig` | HTTP client와 공통 호출 Bean 조립 | `llmHttpClient()`, `lmStudioClient(...)` |
| `global/infrastructure/llm/LlmClient` | 공급자가 바뀌어도 유지할 호출 계약 | `complete(LlmCompletionRequest): String` |
| `global/infrastructure/llm/LmStudioClient` | 인증·JSON 전송·HTTP/envelope 검증·실패 분류 | `complete(LlmCompletionRequest): String` |
| 같은 패키지의 `LlmCompletionRequest`·`LlmMessage`·`LlmGenerationOptions` | 공통 호출에 필요한 불변 전달값 | 메시지·JSON Schema·샘플링·추론·생성 한도. 타입별 별도 파일 |
| `global/exception/LlmException`·`LlmErrorCode` | 공급자 실패의 타입 있는 계약 | `ProjectException` 기반. 원문·인증값이 없는 code/message |
| `document/application/DocumentTopicClassificationService` | 주제 분류 유즈케이스 진입점 | `classify(String transcriptContent): DocumentTopicClassificationResult` |
| `document/application/dto/result/DocumentTopicClassificationResult` | 검증된 불변 주제 목록 전달 | `topics()`, `isNoContent()`. 빈 목록에서만 내용 없음 판정 |
| `document/application/DocumentTopicClassifier` | 서비스가 사용하는 분류 계약 | `classify(String): List<DocumentTopic>` |
| `document/infrastructure/llm/LlmDocumentTopicClassifier` | 문서용 요청·응답 해석·주제 정규화 | `classify(String): List<DocumentTopic>` |
| `document/infrastructure/llm/DocumentTopicPrompt` | 분류 prompt·schema·분류 설정 조립 | `createRequest(String): LlmCompletionRequest` |

주제 분류 Service는 application의 `DocumentTopicClassifier` 인터페이스를 호출한다. infrastructure의 `LlmDocumentTopicClassifier`가 그 계약을 구현하고 Spring이 주입한다. HTTP나 Jackson 타입은 infrastructure 내부에 둔다. 공통 client는 Document·Transcript·Job·주제의 의미를 알지 않는다. #522의 작성 adapter도 같은 `complete(...)`를 사용하면서 다른 prompt·schema·options를 넘길 수 있다.

프롬프트와 schema는 classpath의 `llm/document/topic-classification-system.txt`, `llm/document/topic-classification-schema.json` 후보로 둔다. 런타임이 `backend/docs/llm-test/`나 LM Studio UI 프리셋을 읽지 않게 한다. 설정과 프롬프트의 변경은 서버가 관리하며 사용자 옵션 API를 추가하지 않는다.

### 메서드별 판단과 부작용

- `DocumentTopicClassificationService.classify`: 입력이 null·공백이면 호출 전 거절한다. 원문 길이로 정상 논의를 제외하지 않는다. classifier 결과를 불변 Result로 반환한다. DB 조회·저장·트랜잭션이 없다.
- `DocumentTopicPrompt.createRequest`: 시스템 지시와 원문 데이터를 분리하고 전체 원문을 직렬화한다. 분류 Schema와 분류 옵션을 조립한다. 네트워크·DB 부작용이 없다.
- `LmStudioClient.complete`: HTTP 요청 한 번만 수행한다. 정상 완료한 assistant content만 반환한다. topics를 파싱하지 않는다. redirect를 자동으로 따라 다른 호스트에 token을 전달하지 않는다.
- `LlmDocumentTopicClassifier.classify`: 공통 client의 문자열을 JSON으로 해석하고 주제 계약을 검증·정규화한다. 유효하지 않은 항목 하나라도 있으면 전체 응답을 실패 처리한다.
- `DocumentTopicClassificationResult.isNoContent`: 이미 검증된 목록이 비어 있는지만 판정한다. 호출 실패를 처리하거나 예외를 잡는 메서드가 아니다.

검증 private 메서드는 `validateTranscriptContent`, `validateCompletionStatus`, `validateFinishReason`, `validateAssistantContent`, `validateTopicsArray`, `validateTopicName`, `DocumentTopic.of`, `removeDuplicateTopics`처럼 규칙 단위로 나눈다. 모든 비교를 무조건 메서드로 만들지는 않는다. 삼항 연산자 금지·첫 선언 유형에 따른 공백·필드 공백 규칙은 기존 AGENTS를 따른다.

### 클래스 의존 관계

다음은 구현 예정 의존 관계다. Service에는 HTTP·JSON 처리를 넣지 않고, client에는 주제 분류 규칙을 넣지 않는다.

```mermaid
flowchart LR
    Caller["#523 내부 호출자"] --> Service["DocumentTopicClassificationService"]
    Service --> Classifier["DocumentTopicClassifier"]
    Service --> Result["DocumentTopicClassificationResult"]
    Adapter["LlmDocumentTopicClassifier"] -.->|implements| Classifier
    Adapter --> Prompt["DocumentTopicPrompt"]
    Adapter --> Strategy["LlmClient"]
    Strategy --> Client["LmStudioClient"]
    Prompt --> Request["LlmCompletionRequest·Message·Options"]
    Prompt --> Resources["분류 system prompt·JSON Schema"]
    Client --> LM["LM Studio API"]
    Config["LlmConfig·LlmProperties"] --> Client
```

### LmStudioClient: 한 번 요청하고 정상 완료 문자열 반환

의존성은 공통 `HttpClient`, Jackson `ObjectMapper`, 연결 설정이다. 유일한 업무 진입점은 `complete`이며 네트워크 대기·오류 변환은 이 클래스 안에서 끝낸다.

| 접근·메서드 후보 | 입력 → 반환 | 판단·부작용 |
| --- | --- | --- |
| public `complete(LlmCompletionRequest request)` | 공통 요청 → assistant content `String` | 요청 구성 → 한 번 전송 → 대기 → 상태 검증 → 정상 결과 추출. 외부 HTTP 부작용이 있는 메서드 |
| private `createRequestBody(LlmCompletionRequest request)` | 공통 요청 → `ObjectNode` | 모델·messages·response_format·샘플링 옵션을 조립. 인증값은 body에 넣지 않음 |
| private `addThinkingSettings(ObjectNode body, LlmGenerationOptions options)` | 요청 body·옵션 → void | 추론 끔/켬과 선택 예산을 명시적 if로 구성. 분류·작성의 기본값은 정하지 않음 |
| private `buildHttpRequest(ObjectNode body)` | 요청 body → `HttpRequest` | 고정 경로·POST·JSON·Bearer 구성. 환경변수 token은 헤더에만 사용 |
| private `awaitResponse(HttpRequest request)` | HTTP 요청 → `HttpResponse<String>` | sendAsync 1회 후 전체 응답 기한까지 대기. timeout·interrupt·통신 실패를 공통 오류로 변환 |
| private `validateCompletionStatus(HttpResponse<String> response)` | HTTP 응답 → void | 401/403·429·확인된 입력 초과·기타 4xx·5xx를 구분. 오류 body를 정상 완료 JSON으로 해석하지 않음 |
| private `readAssistantContent(String responseBody)` | HTTP 응답 body → assistant content `String` | envelope를 해석하고 choices·message·content의 실제 타입과 정상 종료를 검증 |
| private `validateFinishReason(JsonNode choice)` | choice → void | stop만 정상 완료로 인정. length는 출력 한도 실패, 그 외 예상 밖 종료도 실패 |
| private `validateAssistantContent(JsonNode message)` | message → void | 실제 문자열·공백 아님 확인. 추론 필드나 tool_calls를 결과로 대신 쓰지 않음 |

HTTP envelope의 예시는 다음과 같다. client가 반환하는 것은 `choices` 객체가 아니라 `content` 안의 문자열이다.

```json
{
  "choices": [
    {
      "finish_reason": "stop",
      "message": {"content": "{\"topics\":[\"검색 기능\"]}"}
    }
  ]
}
```

`complete`는 `{"topics":["검색 기능"]}`이라는 문자열을 반환한다. 이 문자열을 주제 목록으로 해석하는 것은 아래 classifier다. #522에서는 같은 문자열 자리에 title·summary·content JSON이 들어올 수 있다.

### 공통 요청 DTO: 전송에 필요한 값만 전달

DTO마다 별도 파일을 만든다. 모델·token·base URL은 서버 연결 설정이므로 호출자가 넣지 않는다. messages·schema·생성 옵션은 호출 목적마다 달라져 요청 DTO에 둔다.

| 타입 후보 | 필드 초안 | 의미 |
| --- | --- | --- |
| `LlmMessage` | `String role`, `String content` | system 지시와 user 데이터를 별개 메시지로 전달 |
| `LlmGenerationOptions` | `double temperature`, `double topP`, `int topK`, `double repeatPenalty`, `boolean thinkingEnabled`, `Integer thinkingBudgetTokens`, `int maxTokens` | 공급자 호출에 필요한 생성 옵션. 선택 예산은 추론 켬에서만 사용 |
| `LlmCompletionRequest` | `List<LlmMessage> messages`, `String schemaName`, `JsonNode outputSchema`, `LlmGenerationOptions options` | messages·Schema와 해당 호출의 옵션 묶음 |

JsonNode는 infrastructure 내부 전달값이다. Application Result에 포함하지 않는다. messages는 방어적으로 복사하고, request body를 조립할 때 schema 원본을 수정하지 않는다. DTO의 `toString`으로 원문을 로깅하지 않는다. 생성 범위·메시지·schema의 잘못된 입력에는 타입 있는 요청 오류를 사용한다.

### DocumentTopicPrompt: 원문을 분류 요청으로 구성

system prompt·schema resource와 분류용 옵션을 가진다. classpath resource는 매 요청마다 다시 읽지 않고 초기화 때 읽고 검증한다. 초기화 실패도 정상 내용 없음으로 대체하지 않는다.

| 접근·메서드 후보 | 입력 → 반환 | 판단·부작용 |
| --- | --- | --- |
| public `createRequest(String transcriptContent)` | 전체 원문 → `LlmCompletionRequest` | system/user 메시지·document_topics schema·분류 옵션을 묶음. 네트워크 없음 |
| private `createMessages(String transcriptContent)` | 전체 원문 → `List<LlmMessage>` | 고정 system 메시지와 직렬화한 user 데이터 메시지 생성 |
| private `serializeTranscript(String transcriptContent)` | 전체 원문 → user 메시지용 JSON `String` | transcriptText에 원문을 정확히 보존. 따옴표·줄바꿈을 Jackson으로 escape |

첫 옵션은 T=0.2·top_p=0.9·top_k=20·repeat=1·추론 끔·max_tokens=1024다. classpath schema와 옵션이 실제 HTTP body에 전달되는지는 client 계약 테스트에서도 확인한다.

### LlmDocumentTopicClassifier: topics 계약 해석·정규화

의존성은 `DocumentTopicPrompt`, `LlmClient`, `ObjectMapper`다. HTTP 상태를 다시 처리하거나 공급자를 재호출하지 않는다. `LlmConfig`가 `provider=lm-studio` 전략을 조립하며 지원하지 않는 값은 시작 시 거절한다. 다른 공급자의 실제 계약이 정해지면 전략 구현과 설정 선택을 추가하고 문서 classifier는 유지한다.

| 접근·메서드 후보 | 입력 → 반환 | 판단·부작용 |
| --- | --- | --- |
| public `classify(String transcriptContent)` | 전체 원문 → 불변 `List<DocumentTopic>` | prompt 구성 → client 호출 → 주제 JSON 해석. client를 한 번 호출 |
| private `parseTopics(String content)` | assistant content → `List<DocumentTopic>` | JSON 객체·topics 배열·추가 필드 정책 확인 후 항목 검증과 정규화 수행 |
| private `validateTopicsArray(JsonNode root)` | 해석한 JSON → void | 객체와 필수 topics 배열 확인. null·누락·잘못된 타입 거절 |
| private `validateTopicName(JsonNode topic)` | 배열 항목 → void | 실제 문자열인지 확인. 숫자·boolean·null의 강제 문자열 변환 금지 |
| private `createTopic(String topic): DocumentTopic` | 주제 문자열 → 검증된 값 객체 | `DocumentTopic.of`가 NFC·공백 정규화와 빈 값 검증을 담당한다. 도메인 오류를 분류 응답 오류로 변환 |
| private `removeDuplicateTopics(List<DocumentTopic> topics)` | 정리된 주제 목록 → 불변 `List<DocumentTopic>` | 정규화 문자열의 정확한 중복만 제거하고 최초 순서 유지 |

`[" 검색 기능 ", "검색  기능", "알림 채널"]`은 `["검색 기능", "알림 채널"]`로 정리한다. `["검색 기능", " "]`은 전체 응답 실패다. 공백 항목만 버리고 성공 처리하지 않는다. 유효한 `[]`는 그대로 성공 반환한다.

### DocumentTopicClassificationService와 Result: 내부 유즈케이스 제공

Service에는 application의 `DocumentTopicClassifier` 인터페이스 하나를 주입한다. 서비스 단위 테스트도 이 인터페이스를 mock한다. Repository와 LLM 연결 설정은 주입하지 않는다.

| 접근·메서드 후보 | 입력 → 반환 | 판단·부작용 |
| --- | --- | --- |
| public `classify(String transcriptContent)` | 전체 원문 → `DocumentTopicClassificationResult` | 입력 검증 → classifier 호출 → Result 생성. 실패를 catch해서 빈 결과로 바꾸지 않음 |
| private `validateTranscriptContent(String transcriptContent)` | 원문 → void | null·공백뿐인 입력 거절. 짧은 유효 논의는 허용 |
| Result `isNoContent()` | 없음 → boolean | 이미 검증된 topics 목록이 비어 있는지 판정 |

Service의 핵심은 다음 정도로 유지하는 제안이다. 이 예시는 제품 코드 구현이 아니다.

```java
public DocumentTopicClassificationResult classify(String transcriptContent) {
    validateTranscriptContent(transcriptContent);
    List<DocumentTopic> topics = classifier.classify(transcriptContent);
    return new DocumentTopicClassificationResult(topics);
}
```

Result는 `List<DocumentTopic> topics`를 가진 record 후보이며 생성 시 `List.copyOf`로 방어 복사한다. 별도의 status 필드에 NO_CONTENT를 함께 저장하지 않아 상태와 목록이 어긋나는 경우를 만들지 않는다. 저장할 상태는 #523이 `isNoContent()`를 보고 결정한다.

### 상위 서비스가 분류 서비스를 사용하는 관계

#523의 자동 접수 서비스가 저장 원문을 확보한 뒤 `DocumentTopicClassificationService.classify(input.content())`를 호출한다. 의존 방향은 상위 서비스 → 분류 서비스 → 문서 classifier → 공통 client다. 분류 서비스는 상위 서비스를 다시 호출하지 않는다. 이번 #521에서는 이 호출 가능한 진입점까지 구현하며 #523 서비스와 Job 저장은 추가하지 않는다.

```mermaid
sequenceDiagram
    participant Intake as 자동 접수 서비스 (#523)
    participant Store as 입력·결과 저장 서비스 (#523)
    participant Classification as DocumentTopicClassificationService
    participant LLM as LM Studio
    Intake->>Store: 짧은 트랜잭션: 입력·실행권 확보
    Store-->>Intake: 저장 완료된 전체 원문
    Note over Intake,LLM: DB 트랜잭션 종료 후 외부 호출
    Intake->>Classification: classify(input.content())
    Classification->>LLM: 주제 분류 요청 한 번
    LLM-->>Classification: topics JSON
    Classification-->>Intake: 불변 Result
    alt result.isNoContent()
        Intake->>Store: 짧은 트랜잭션: 내용 없음 기록
    else 주제 존재
        Intake->>Store: 짧은 트랜잭션: 확정 주제·주제별 Job 저장
    end
```

상위 조정 메서드 전체에는 `@Transactional`을 붙이지 않는다. 잠금 획득과 결과 저장은 별도 Bean의 짧은 public 트랜잭션 메서드로 나눠 프록시 경계를 유지한다. 실행권 만료·중단 복구·참조 보호는 #523·#525의 저장 계약에서 다룬다. 분류 실패는 예외로 전달하며 내용 없음으로 저장하지 않는다.

### LlmProperties·LlmConfig: 연결값 검증과 Bean 조립

`LlmProperties`는 `enabled`, `baseUrl`, `apiToken`, `model`, `connectTimeout`, `requestTimeout`, `maxResponseBytes`를 가진 설정 class 후보다. 설정 검증은 사용 여부와 입력 의미별로 나눈다. 검증 진입점은 `validateEnabledConfiguration()`이고, 의미별 검증 메서드는 private으로 둔다. Config의 두 메서드는 Bean 조립 지점이다.

| 메서드 후보 | 책임 |
| --- | --- |
| `validateEnabledConfiguration()` | enabled=true인 경우에만 필수 연결 설정 검증 |
| `validateBaseUrl()` | 서버가 관리하는 URL의 scheme·host·경로 계약 검증. 로컬 HTTP server 테스트와 배포 HTTPS 설정을 구분 |
| `validateCredentials()` | token·model의 필수값 확인. 오류 메시지에 실제 값을 넣지 않음 |
| `validateTimeouts()` | 연결·전체 응답 기한의 양수 및 일관된 범위 확인 |
| `validateResponseLimit()` | 응답 byte 한도가 양수인지 검증 |
| Config `llmHttpClient()` | 연결 timeout·redirect 정책을 가진 공통 HttpClient Bean 생성 |
| Config `lmStudioClient(...)` | client에 HttpClient·ObjectMapper·설정을 주입 |

분류 options 조립은 `DocumentTopicPrompt`가 담당한다. LlmProperties에 문서 주제명·본문 템플릿·Job 재시도 횟수를 넣지 않는다.

### HTTP 구현 선택 제안

첫 client는 Java 25의 `HttpClient`와 기존 Jackson 3(`tools.jackson`)을 사용한다. 외부 provider SDK·Spring AI·WebFlux 의존성을 추가하지 않는다. 작은 POST 계약과 전체 응답 대기 시간 제어가 이번 요구다.

`sendAsync`의 응답 Future를 **전체 본문을 받는 BodyHandler**와 함께 만들고 지정 기한까지 기다린다. public `complete`는 최종 문자열을 반환하는 동기 메서드다. 이 내부 API 사용은 이벤트·백그라운드 Job 실행을 추가한다는 뜻이 아니다. timeout·호출 스레드 interrupt에서는 원래 Future 취소를 시도하고, interrupt 플래그를 복원하며 타입 있는 실패를 반환한다. 취소가 공급자의 생성 중단까지 보장한다고 가정하지 않는다.

본문 크기도 설정된 한도 안에서만 받는다. Java 25의 `BodyHandlers.limiting`과 `ofString`은 [JDK 공식 API](https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpResponse.BodyHandlers.html)에서 확인했다. 기본 제안은 connect 3초·전체 응답 120초·본문 256KiB이며, 실제 분류 SLA나 측정한 최적값은 아니다. 느린 헤더·멈춘 본문을 로컬 HTTP server로 검증한다.

Spring `RestClient`도 가능하다. Spring의 HTTP 변환·상태 처리와 통합하기 쉽지만 이번에는 추가 변환 계층보다 작은 단일 공급자 client를 택하는 제안이다. 선택은 구현 ADR에 이유를 기록한다. 기존 코드가 RestClient로 통일돼 있다고 주장하지 않는다.

## 4. 저장 기반과 주의할 점

### DB와 트랜잭션

**#521에는 DB 변경과 migration이 없다.** 저장 완료한 원문을 입력 문자열로 받으며 주제 목록을 메모리의 불변 Result로 반환한다. 분류 결과 영속화·Job 상태·중복 방지·NO_CONTENT 저장은 #523 이후다.

현재 입력 조회는 비관적 쓰기 잠금을 사용한다. 따라서 `findForUpdate()`로 잠금을 얻은 트랜잭션 안에서 장시간 LLM을 호출하지 않는다. 이후 실행기는 짧은 트랜잭션에서 입력과 실행 상태를 확보하고 종료한 뒤 외부 호출, 다시 짧은 트랜잭션에서 결과를 기록해야 한다. 이 계획에서 재시도 Service의 트랜잭션을 변경하지 않는다.

현재 V27·V28·V30과 열린 #520의 V31을 확인했다. 새 migration 번호를 예약하지 않는다. 분류 중 반복 호출을 이 서비스만으로 멱등 처리한다고 주장하지 않는다. 같은 원문을 다시 호출하면 외부 요청이 다시 발생하며, 반복 접수·분류 결과 재사용은 #523의 저장 계약이 맡는다.

### 정규화 계약

1. 응답은 객체이며 `topics`가 실제 JSON 배열이어야 한다. 문자열·숫자·null을 문자열 목록으로 강제 변환하지 않는다.
2. 각 항목은 실제 문자열이어야 한다. 앞뒤 공백을 제거하고, Unicode NFC 정규화와 연속 공백의 단일 공백 변환을 적용한다. 공백 판정은 Unicode 공백도 포함해 테스트한다.
3. 정리 후 빈 주제가 있으면 실패다. 잘못된 항목을 조용히 제거해 `[]`로 만들지 않는다.
4. 정규화한 문자열이 정확히 같을 때만 중복을 제거한다. 최초 등장 순서를 유지한다.
5. `검색 도입`과 `검색 기능 개발`처럼 의미만 유사한 항목은 코드가 임의 병합하지 않는다. 주제 통합은 분류 프롬프트의 판단이며 QA에서 평가한다.
6. 후속 Job과 Document는 반환한 확정 문자열을 그대로 사용한다. 재실행 때 주제를 다시 분류해 UNIQUE 제약만으로 중복이 막힌다고 가정하지 않는다.

### 실패 계약 후보

| 상황 | 내부 실패 후보 | 처리 |
| --- | --- | --- |
| 공급자 401·403 | `LLM_AUTHENTICATION_FAILED` | 서버 연결 설정 실패. 사용자 로그인 401로 치환하지 않음 |
| 공급자 429 | `LLM_RATE_LIMITED` | 호출 실패로 반환. 숨겨진 재호출 없음 |
| 5xx·연결 실패 | `LLM_UNAVAILABLE` | 공급자 장애로 반환 |
| 전체 응답 기한 초과 | `LLM_TIMEOUT` | Future 취소 시도. 부분 결과 사용 금지 |
| interrupt | `LLM_CALL_INTERRUPTED` | interrupt 복원·취소 시도·실패 반환 |
| 확인된 context 초과 응답 | `LLM_INPUT_LIMIT_EXCEEDED` | 자르지 않고 실패 |
| 그 외 4xx·모델/옵션 거절 | `LLM_REQUEST_REJECTED` | 모든 400을 context 초과로 간주하지 않음 |
| finish_reason=length | `LLM_OUTPUT_LIMIT_EXCEEDED` | JSON이 우연히 파싱돼도 미완료 응답은 거절 |
| envelope 오류·빈 content·비정상 종료 | `LLM_INVALID_RESPONSE` | 유효한 완료 결과로 반환하지 않음 |
| 본문 byte 한도 초과·전송 실패 | `LLM_UNAVAILABLE` | 제한된 body handler의 실패로 응답 수신을 중단. 부분 JSON을 사용하지 않음 |
| topics 누락·타입·빈 이름·JSON 오류 | `INVALID_TOPIC_CLASSIFICATION_RESPONSE` | 문서 도메인의 응답 계약 실패 |

공급자 실패 코드는 내부 생성 흐름용이며 새 FE 오류 API 계약을 정하는 작업이 아니다. 공통 실패는 `global/exception`, 문서 전용 실패는 기존 `document/domain/DocumentErrorCode`·`DocumentException`에 둔다. 예상 가능한 실패를 `IllegalStateException`이나 빈 결과로 표현하지 않는다. 전역 handler·공통 응답 포맷은 바꾸지 않는다.

로그에는 모델명·HTTP 상태·내부 실패 code·시간·프롬프트 버전처럼 필요한 운영 정보만 기록한다. 인증 헤더·전체 요청/응답·원문·생성 본문·추론 내용·주제 목록을 남기지 않는다. provider 오류 본문이나 이를 포함한 예외 message/stack을 통째로 로깅하지 않는다.

### 입력 크기와 설정

컨텍스트는 전체 입력과 생성 출력의 합이다. 32768 로드·분류 max_tokens 1024라는 후보에서 31744는 입력의 이론상 잔여 토큰 수일 뿐, 원문 글자 수나 보장된 입력 상한이 아니다. system prompt와 chat template도 입력에 포함된다.

지원이 확인되지 않은 `/tokenize` endpoint나 임의 글자 수/토큰 비율을 구현 사실로 쓰지 않는다. 첫 구현은 원문을 그대로 한 요청에 보내고, 실제 공급자의 명확한 초과 오류를 분류한다. **운영 활성화 전에** 과대 합성 입력으로 서버가 거절하는지·자동으로 원문을 버리는지 확인한다. 서버가 자동 절단한다면 이를 끄고 동일 시험을 통과시킨다. 거절을 보장하지 못하면 정확한 tokenizer/지원 API 기반 사전 검사 등 별도 방안을 확정하기 전까지 운영 연결을 완료로 보지 않는다.

| 설정 묶음 | 초기 후보·주입 방법 | 검증 상태 |
| --- | --- | --- |
| 연결 | `LLM_BASE_URL`, `LLM_API_TOKEN`, `LLM_MODEL` | 실제 실험 URL·모델 재사용. token은 환경변수만 |
| 활성화 | `LLM_ENABLED=false` 기본, 설정된 배포에서 true | 현재 다른 도메인·테스트에 비밀값 주입을 강제하지 않는 제안 |
| timeout·본문 한도 | connect 3초·전체 응답 120초·256KiB | 기술 초기값. 실제 합성 요청 완료 관찰. timeout 최적값·동시 부하 미검증 |
| 분류 | T=0.2, top_p=0.9, top_k=20, repeat=1, 추론 끔, max_tokens=1024 | 실제 합성 관찰과 명명 실패 기록. 미지 원문 품질 검증 필요 |
| 로드 컨텍스트 | 서버 32768 유지 | 실험에서 관찰. API 호출로 GPU·로드 설정 변경 안 함 |
| 문서 작성 | 추론 512·max_tokens 4096·v7 | #522 후보. 이번 분류값에 섞지 않음 |

enabled=false에서는 관련 Bean을 만들지 않고 정상 내용 없음 대체 client를 주입하지 않는다. enabled=true이면 필수 URL·token·model·양수 timeout·설정 범위를 검증한다. #523의 소비자가 생길 때 활성화 조건도 함께 연결한다. 설정 getter/record의 `toString`으로 token을 노출하지 않는다.

### ADR와 남은 확인의 처리 시점

#521의 Proposed ADR은 최초 생성 당시 원래 snapshot을 복구하지 못했다. 과거 대안 논의를 추정해 보충하지 않는다. 2026-10-09 리뷰 반영 시 실제로 검토한 document 내부 통신 유지, global 공통 통신 분리, HttpClient·RestClient 선택의 현재 근거를 [ADR](../adr/521-shared-llm-client.md)에 기록했다. 사용자 결정과 팀 승인을 구분하며 상태는 Proposed로 유지한다.

실제 사용자 원문 전송 범위는 운영 연결 전 확인한다. 분류 품질·stream=false/proxy 동작·초과 입력 거절은 구현 초기에 합성 원문으로 관찰할 기술 항목이다. 공급자·패키지 분리는 사용자가 이미 정한 방향이므로 같은 결정을 다시 질문하지 않는다.

## 5. TDD와 검증 순서

### 동작별 테스트

| 테스트 위치·방식 | 검증 동작 | 기대 결과 |
| --- | --- | --- |
| `document/application/DocumentTopicClassificationServiceTest` / Mockito | 정상 전체 원문, null·공백 입력, 공급자 실패 | 원문 그대로 한 번 위임·Result 반환. 잘못된 입력은 호출 없음. 실패를 빈 결과로 바꾸지 않음 |
| `document/infrastructure/llm/DocumentTopicPromptTest` / 단위 | prompt·원문 분리, 따옴표·줄바꿈·원문 내 지시, 잘못된 입력 | 전체 문자열이 정확히 직렬화됨. system 역할 승격·부분 절단 없음 |
| `LlmDocumentTopicClassifierTest` / Mockito+실제 Jackson | 복수 주제·빈 배열·공백/중복·Unicode, 누락·null·타입·빈 항목·JSON 오류 | 정상 목록/내용 없음 구분. 순서 유지. 오류는 분류 실패 |
| `global/config/LlmPropertiesTest`·`LlmConfigTest` / binding·작은 Context | enabled=false, 정상 enabled=true, 필수값 누락·잘못된 범위 | 비활성 상태에 가짜 결과 없음. 활성 설정 오류는 시작 시 검출 |
| `global/infrastructure/llm/LmStudioClientTest` / JDK 로컬 HTTP server | 정확한 URL·Bearer·JSON·schema·분류 옵션, HTTP200 정상 | 공급자 envelope를 해석하고 content만 반환. 요청 횟수 1 |
| 같은 HTTP 테스트 | 401·403·429·400·5xx·손상된 envelope | 오류 code 구분. 재요청 없음. token/본문 비노출 |
| 같은 HTTP 테스트 | 헤더 이후 본문 중단·전체 기한·interrupt·본문 크기 초과 | 대기 종료·실패 반환. 취소 시도. partial JSON 사용 금지 |
| 같은 HTTP 테스트 | finish_reason=length·content=null·예상 밖 종료·중복 key | JSON 형태가 있어도 완료 결과로 반환하지 않음 |
| `DocumentTopicClassificationIntegrationTest` / 로컬 HTTP | Service부터 HTTP·JSON 해석·정규화 전체 연결 | 정상 Result와 공급자 오류 전파 |
| 합성 입력 실제 LM Studio 검증 / 수동 실행 | 복수 주제·짧은 논의·결정 없는 논의·인사만·원문 내 지시·장문 말미의 안건 | 분류 의미와 실제 API/proxy 완료 관찰. 기대 주제의 누락·병합·분할 평가 |
| 합성 과대 입력 실제 LM Studio 검증 / 수동 실행 | context 초과 | 원문 자동 절단 없이 실패. 명확한 오류를 분류 |

HTTP 테스트의 JDK server는 test support에 별도 클래스로 둔다. 신규 모의 서버 라이브러리는 기본으로 추가하지 않는다. 테스트용 timeout은 짧게 설정하되 단순 정확한 millisecond 일치 대신 합리적인 종료 범위와 요청 횟수를 확인한다.

Mockito는 위임·결과 계약을 확인하고 실제 HTTP 기한이나 인증 전송을 증명하지 않는다. 로컬 HTTP server는 실제 네트워크 계약을 확인하지만 실제 모델 의미 품질을 증명하지 않는다. #521에 repository/새 endpoint가 없으므로 신규 PostgreSQL·MVC·Security 테스트를 억지로 추가하지 않는다. 기존 CI의 테스트는 유지한다.

실제 모델 품질 검증은 비공개 원문 대신 합성 fixture를 쓰며 default test/CI에서 외부 API를 호출하지 않는다. 기대 주제는 사전에 기록하고 출력 확인 후 정답을 바꿔 통과시키지 않는다. 한 주제를 두 이름으로 쪼갰는지·관련 제안이 주제명 범위에서 누락되는지도 평가한다. 결과는 `backend/docs/llm-test/`의 Markdown에 실패 사례까지 기록한다.

### 메서드·책임 단위 TDD 순서

1. **공통 요청 계약·정상 HTTP 호출**: 최소 요청 DTO·오류 계약을 선언한 뒤 로컬 server의 정확한 URL/인증/JSON 및 envelope 검증 테스트 → `LmStudioClient.complete` 정상 동작. 이 단계에서는 연결값을 테스트에서 직접 주입한다.
2. **공급자 오류 구분**: 인증·429·5xx·400·미완료 출력·깨진 envelope 테스트 → 상태·종료·본문 검증 메서드. 테스트가 요청 한 번만 관찰하는지 확인.
3. **대기 기한·중단**: 응답 지연·중간 본문 정지·interrupt·크기 초과 테스트 → 전체 대기 한도와 취소 처리. happy path도 함께 유지.
4. **분류 요청 구성**: 전체 원문·system/user 분리·Schema·추론 끔 옵션의 실패 테스트 → Prompt와 resource 검증.
5. **정상 복수 주제와 내용 없음 구분**: `classify_success_multipleTopics`·정상 빈 배열·누락/타입/공백 항목 실패 테스트 → classifier의 JSON 해석·검증. client는 Mockito로 대체한다.
6. **주제 정규화**: 공백·Unicode·중복·순서 테스트 → `DocumentTopic.of`·`removeDuplicateTopics`. 의미 유사 주제 임의 병합 금지.
7. **유즈케이스 연결**: 정상 위임·공백 입력 거절·공급자 실패 전파 테스트 → Service와 불변 Result. 실패를 내용 없음으로 바꾸지 않음.
8. **설정 조립**: 활성/비활성·필수값·범위 실패 테스트 → properties·Bean·환경변수 연결. 기본 테스트에 실제 token 불필요.
9. **실제 분류 확인·문서**: 합성 사례의 실제 API 관찰 → 분류 설정·prompt 수정 시 같은 fixture 재측정. 요청/결과/판정·제한을 Markdown으로 기록.

각 단계는 실패하는 테스트를 먼저 확인하고 최소 구현 후 통과시킨다. 테스트 선언에 필요한 계약 타입을 먼저 추가해 컴파일을 깨뜨리지 않는다. 커밋을 따로 요청받으면 동작별 `test` → `feat` 경계를 사용한다. `refactor`는 중복이나 읽기 어려움이 실제로 생긴 경우에만 테스트가 통과한 뒤 진행한다. config·transport·normalization·문서는 독립 경계로 둔다. 이 계획은 commit/push 권한이 아니다.

### 구현 때 사용할 검증 명령

현재 `build.gradle`과 Backend CI에 `test`·`integrationTest`·`acceptanceTest`·`spotlessCheck`·`bootJar`가 존재한다. 작은 HTTP client 테스트는 test에, 전체 classify 흐름은 integration 태그에 둔다.

```bash
# 단위 단계: 해당 이름의 테스트가 구현된 뒤 실행
./gradlew test --tests '*DocumentTopicClassificationServiceTest' --tests '*LlmDocumentTopicClassifierTest' --tests '*DocumentTopicPromptTest'
./gradlew test --tests '*LlmPropertiesTest' --tests '*LlmConfigTest'

# 실제 로컬 HTTP 계약·timeout 검증
./gradlew test --tests '*LmStudioClientTest'
./gradlew integrationTest --tests '*DocumentTopicClassificationIntegrationTest'

# 완성된 변경의 공통 영향·CI 기준 확인
./gradlew spotlessCheck test integrationTest acceptanceTest bootJar
```

2026-10-08 최초 구현 검증에서는 집중 단위 73개·integrationTest 269개·acceptanceTest 373개·spotlessCheck·bootJar·Governance 8개가 통과했다. 당시 전체 test 689개 중 기존 JWT 날짜 fixture 실패 1개가 있었다. 이 결과는 당시 기록이며 현재 브랜치의 실패 상태를 뜻하지 않는다.

2026-10-09 리뷰 수정에서는 추론 예산 필드 회귀 테스트의 실패를 먼저 확인하고 `thinking_budget_tokens`로 고쳤다. `spotlessCheck`, 분류 서비스·adapter·prompt·설정·HTTP 계약 단위 57개와 분류 HTTP 통합 2개가 통과했다. 모의 서버에서 필드 전달을 확인한 결과이며 실제 호스트의 예산 강제 동작을 새로 검증한 것은 아니다.

## 6. 완료 기준과 다음 작업

#521은 다음 조건을 만족하면 완료다.

- 공통 client가 prompt·schema·옵션을 받아 지정 공급자에 한 번 요청하고, 정상 완료 content 또는 구분된 실패를 반환한다.
- 전체 원문에서 복수 주제를 받고 정규화·중복 제거 계약을 지킨다. 정상 빈 배열과 모든 실패가 구분된다.
- 인증값·원문·추론·생성 본문을 로그나 저장소에 남기지 않는다. 자동 테스트는 실제 공급자를 호출하지 않는다.
- timeout·본문 중단·출력 잘림·공급자 오류는 로컬 HTTP surface에서 검증한다.
- 합성 원문에서 복수 주제·짧은/결정 없는 논의·내용 없음의 실제 분류 결과와 stream=false·초과 입력 거절을 관찰하고 Markdown 증거를 남긴다. 모의 HTTP 성공을 의미 품질 성공으로 계산하지 않는다.
- 계획·Proposed ADR·구현/리뷰 report와 실제 코드가 같은 범위를 설명하고 필요한 검증을 통과한다.

후속은 #522가 공통 client에 작성 v7·작성 옵션을 연결하고, #523이 저장 원문 접수와 주제 목록·Job 등록을 연결하는 순서다. 장문 발언 선별은 #522의 기존 전체 원문 요청 계약을 변경할 수 있는 별도 최적화 후보다. 이번 #521에 끼워 넣거나 이미 합의된 작성 계약으로 표현하지 않는다.

실제 합성 completion 10회 중 개선 prompt의 정상 5회는 추론 0·stop으로 완료했고 초과 입력 1회는 거절됐다. [현재 주제 분류 실제 관찰](../llm-test/521-topic-classification-2026-10-08.md)에 표본과 한계를 기록했다. 사용자는 이후 commit/push/Draft PR 게시를 요청했다. Issue 본문이나 다른 도메인 코드는 변경하지 않는다.

## 2026-10-10 공백·주제 계약 리뷰 반영

- `DocumentText.isBlank`는 null·빈 문자열·Java 공백·Unicode 구분자(NBSP 포함)를 공백으로 판정한다. 문서 생성·저장·원문 조회·재시도 입력에 같은 기준을 사용한다.
- `DocumentTopic.of`에서 NFC·공백 정규화와 빈 주제 검증을 수행한다. 분류 adapter는 문자열을 이 값 객체로 변환하고 정확한 값 중복을 제거한다. 도메인 생성 실패는 분류 응답 오류로 변환한다.
- application 분류 계약과 Result는 `List<DocumentTopic>`을 전달한다. `topicNames()`는 문자열 경계 변환이며, 후속 Job 저장은 기존 TEXT 컬럼을 유지한다. DB CHECK는 우회 저장 방어이므로 제거하지 않는다.
- 이번 수정은 로컬 단위·HTTP 통합 검증이다. 운영 LLM 품질을 새로 검증했다고 해석하지 않는다.
