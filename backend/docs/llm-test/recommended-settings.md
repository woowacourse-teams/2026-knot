# 현재 추천 설정 · 2026-10-08

실제 API 추가 54회 비교 후 고른 후보다. 운영 적용이나 전역 최적값을 의미하지 않는다. [전체 설정 비교와 실제 출력](settings-comparison-2026-10-08.md)을 근거로 한다.

## 일반 문서 작성 기본 후보

| 설정 | 값 |
| --- | --- |
| 모델 | qwen3.8-27b |
| 프롬프트 | [작성 v7](topic-writer-v7.md) |
| 컨텍스트 | 32768 유지 |
| temperature | 0.2 |
| top_p | 0.9 |
| top_k | 20 |
| repeat_penalty | 1.0 |
| 추론 | 켜기 |
| thinking_budget_tokens | 512 |
| max_tokens | 4096 |
| response_format | JSON Schema |
| stream | true |

짧은 사례와 반복 13회에서 12회 통과, 22.82~34.01초, 평균 26.94초였다. 실패 1회는 '통계 기능 공개 조건'이라는 주제에 공유 제안도 포함하도록 평가한 사례다. 범위를 명시한 새 주제명에서는 2회 모두 통과했다. 같은 9개 사례의 초기 비교에서는 추론 끔 7/9, 512는 8/9였다.

```json
{
  "model": "qwen3.8-27b",
  "temperature": 0.2,
  "top_p": 0.9,
  "top_k": 20,
  "repeat_penalty": 1,
  "reasoning_effort": "low",
  "chat_template_kwargs": {"enable_thinking": true},
  "thinking_budget_tokens": 512,
  "max_tokens": 4096,
  "stream": true,
  "stream_options": {"include_usage": true}
}
```

위 값에 매 요청의 시스템·사용자 messages와 title/summary/content JSON Schema를 추가한다. low는 독립된 강도로 검증된 값이 아니라 현재 모델에서 추론을 켜는 데 사용한 요청값이다.

## 장문에서 확인한 빠른 경로

1. [선별 v3](topic-evidence-selector-v3.md)로 포함할 원문 ID와 다른 안건의 제외 ID를 받는다. 추론 끔, max_tokens=1024.
2. ID로 원문 행을 다시 읽어 [작성 v7](topic-writer-v7.md)에 전달한다. 추론 끔, max_tokens=4096.
3. 두 단계 모두 temperature=0.2, top_p=0.9, top_k=20, repeat_penalty=1.0, JSON Schema를 사용한다.

같은 39,982자 장문 주제에서 전체 경로 3회 통과했고 37.80초 / 29.26초 / 31.31초였다. 선별된 원문에 추론 512를 켠 작성은 59.39초에 통과했다. 반복·워밍 영향은 분리하지 않았다. 다른 장문·여러 주제·전체 사용자 대기 시간은 검증하지 않았다. 따라서 빠른 경로는 제한된 검증을 거친 후보로 둔다.

## 유지할 서버 설정과 적용 범위

현재 관찰한 로드 설정은 context_length=32768, parallel=1, eval_batch_size=2048, physical_batch_size=512, Flash Attention과 GPU KV cache offload 켜짐이다. 이 값들의 대안을 비교한 성능 결과는 아니다.

입력과 생성 결과를 합쳐 컨텍스트 안에 들어가야 한다. 초과 원문을 중간에서 잘라 문서를 완성했다고 처리하지 않는다. 전체 출력 제한은 추론과 최종 답의 합이며, 512는 최종 문서 한도가 아니다.

UI 프리셋을 API가 자동으로 선택한다고 가정하지 않고 요청에 프롬프트·설정을 명시한다. 제품 코드와 UI 설정은 변경하지 않았다. 브랜치는 be/docs/#521이며 커밋·푸시는 하지 않았다.
