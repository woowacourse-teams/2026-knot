# #563 LLM 도구 호출 실제 호스트 검증

- 관측 시각: 2026-10-11 18:45 KST.
- 호스트: `https://llm-api.knoted.kr`.
- 조회/요청/응답 모델 식별자: `qwen3.8-27b`.
- 결과: **native 도구 호출 → 결과 전달 → 최종 답변 PASS**, **최종 답변 스트리밍 PASS**.
- 범위: 실제 LLM 호스트와 통신했다. 도구 실행 결과는 로컬 테스트 실행기가 만든 가상 문서 데이터이며, MCP 서버나 실제 Knot DB를 호출한 검증은 아니다.

## 검증한 요청과 응답

`GET /v1/models`는 HTTP 200으로 `qwen3.8-27b`, `gemma-4-31b-it`와 embedding 모델 두 개를 반환했다. 완료 모델 중 `qwen3.8-27b`를 선택했다. 다른 모델은 이번에 테스트하지 않았다.

모든 추론 요청은 `/v1/chat/completions`를 사용했다. `temperature=0`, `max_tokens=768`, `reasoning=off`, `chat_template_kwargs.enable_thinking=false`로 보냈다. 도구 선택은 `tool_choice=auto`이며 강제 호출로 성공을 만든 결과가 아니다.

| 단계 | 요청 | 실제 관측 | HTTP | 요청 시작부터 응답 전체 수신까지 |
| --- | --- | --- | --- | --- |
| 1 | `tools`에 `read_test_document` 정의, 테스트 문서 317 읽기 요청 | `finish_reason=tool_calls`, 함수 이름·ID·JSON 인자 반환 | 200 | 6.113초 |
| 2 | 앞선 assistant tool_calls와 연결된 `role=tool` 결과 전달 | 결과에만 있던 검증 코드와 담당자를 최종 답변에 포함, `finish_reason=stop` | 200 | 3.678초 |
| 3 | 같은 도구 결과 문맥으로 `stream=true` 요청 | content delta 44개, `finish_reason=stop`, `[DONE]` | 200 | 2.465초 |

시간은 이 세 요청의 단일 관측이며, 운영 지연 목표나 평균 성능은 아니다. 스트리밍 시간은 응답 전체 수신 시간이고 첫 토큰 지연은 측정하지 않았다.

### 도구 정의

```json
{
  "type": "function",
  "function": {
    "name": "read_test_document",
    "description": "테스트 문서를 읽습니다. 문서의 검증 코드와 담당자는 이 도구의 결과에만 있습니다.",
    "parameters": {
      "type": "object",
      "properties": {"documentId": {"type": "integer"}},
      "required": ["documentId"],
      "additionalProperties": false
    }
  }
}
```

사용자 메시지는 “테스트 문서 317을 읽고, 검증 코드와 담당자를 알려줘. 검증 코드는 그대로 적어줘.”였다. 문서의 코드와 담당자는 이 메시지나 system prompt에 넣지 않았다.

### 실제 첫 응답의 도구 호출

```json
{
  "finish_reason": "tool_calls",
  "tool_calls": [{
    "type": "function",
    "id": "GPzVkzfrDRiLmgu53bZFftX35CZK5b9k",
    "function": {
      "name": "read_test_document",
      "arguments": "{\"documentId\":317}"
    }
  }]
}
```

함수 이름, call ID 존재, type, arguments의 documentId를 파싱·검사했다. 응답에 일반 JSON 문자열을 쓴 것을 native 도구 호출로 간주한 결과가 아니다.

### 실행기가 전달한 도구 결과

테스트 실행기가 첫 응답 이후 임의 검증 코드를 생성했다. 아래 코드는 인증 토큰이 아니라 가상 문서의 공개 테스트 값이다.

```json
{
  "role": "tool",
  "tool_call_id": "GPzVkzfrDRiLmgu53bZFftX35CZK5b9k",
  "content": "{\"documentId\":317,\"verificationCode\":\"KNOT-TOOL-8A1E94AF70\",\"owner\":\"테스트담당자 지우\"}"
}
```

앞선 assistant의 tool_calls 메시지도 함께 보냈다. 최종 응답에는 추가 도구 호출이 없었고 다음 내용이 있었다.

```text
테스트 문서 317의 정보입니다:

- **검증 코드:** KNOT-TOOL-8A1E94AF70
- **담당자:** 테스트담당자 지우
```

검증 코드와 담당자 문자열을 실제 응답과 비교했다. 두 번째 요청의 usage는 prompt 425 / completion 45 / total 470 tokens였다. 첫 요청은 prompt 338 / completion 29 / total 367 tokens였다.

별도 세 번째 요청은 두 번째와 같은 도구 결과까지의 문맥에서 `stream=true`를 사용했다. Content-Type은 `text/event-stream`이었다. SSE를 파싱해 content delta를 합친 결과에도 같은 검증 코드와 담당자가 포함됐다. `[DONE]`과 stop 종료도 확인했다. 네트워크 첫 토큰 지연이나 실시간 화면 렌더링은 이번 script에서 측정하지 않았다.

## 통신 중 확인한 차이

Python urllib의 기본 User-Agent로 `/v1/models`를 호출한 첫 시도는 Cloudflare의 `403`, `error code: 1010` 응답을 받았다. 실행기를 식별하는 `User-Agent: Knot-LLM-Tool-Probe/1.0`을 명시한 뒤 같은 HTTPS 호스트와 인증으로 모델 조회 및 세 추론 요청이 성공했다.

이는 클라이언트 헤더 변경 후 성공한 관측이다. Cloudflare 정책의 정확한 내부 원인은 확인하지 않았다. 로컬 LLM 서버의 모델·parser·MCP 설정은 변경하지 않았다. 실제 Java client의 기본 헤더로도 통신되는지는 adapter 연결 테스트에서 확인해야 한다.

## 다음 구현에 적용할 판단

현재 호스트의 `qwen3.8-27b`에 도구 정의를 요청으로 전달하는 방식은 최소 호출 사이클에서 동작한다. 이번 결과에 근거해 native tool_calls adapter 개발을 진행할 수 있다.

다음은 아직 검증하지 않았다.

- 검색·목록·읽기 세 도구 사이의 선택과 여러 차례 반복 탐색.
- 실제 Java/Spring MCP 서버·client와 인증 경계.
- 실제 DB의 ARCHIVED·Workspace 필터, pagination·본문 이어 읽기.
- 긴 문맥, 잘못된 arguments, 도구 실패와 중단·timeout·동시 실행.
- 최종 답변의 전체 사용 근거를 구조화 응답으로 확정하는 단계.
- 실제 회의 문서에서 후보 발견·답변 정확성·상위 3개 카드 관련도.

인증 값은 문서·script·결과 파일에 저장하지 않았다. 실행기는 숨김 입력으로 받은 값을 지정 HTTPS 호스트의 Authorization 헤더에만 사용했다. 제품 코드·설정·브랜치를 변경하지 않았다.

임시 실행기: `/tmp/knot-tools-probe-20261011.py`. 인증을 제외한 요청·응답 기록: `/tmp/knot-tools-probe-20261011.json`. 임시 파일은 OS 정리로 사라질 수 있으므로 주요 관측을 이 문서에 남겼다.
