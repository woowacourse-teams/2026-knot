# 선택한 회의 문서 전문 기반 답변 검증

[#1091](https://github.com/woowacourse-teams/2026-knot/issues/1091)용 검증 경로에 Spring AI `OpenAiChatModel`을 연결했다. OpenAI 호환 공급자와 설정에 등록된 워크스페이스에서만 사용할 수 있다. 기본 경로는 기존 HTTP 생성기를 사용한다. [Spring AI 2.0.2 OpenAI Chat 레퍼런스](https://github.com/spring-projects/spring-ai/blob/v2.0.2/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/chat/openai-chat.adoc)를 참고했다.

- `knot.search.meeting-pipeline.enabled=true`, `knot.search.answer.spring-ai.enabled=true`, `knot.search.answer.spring-ai.workspace-ids`가 필요하다. 일반 대화와 근거 없는 요청은 기존 생성 경로로 처리한다.
- 예산에 들어가는 문서는 전문 단위로 전송하고 포함하지 못한 문서가 있으면 확인 범위 지시를 추가한다. 전문을 하나도 전달할 수 없으면 기존 확인 불가 안내를 사용한다.
- 응답 스키마, 모델 스트림 종료, 응답량, 권한 만료를 확인한다. 본문과 제공된 근거 ID의 구조 검증 후 기존 원자적 완료 경로에서 저장한다.
- [#1096](https://github.com/woowacourse-teams/2026-knot/issues/1096)의 결과 없음 응답은 상태가 `UNKNOWN` 또는 `NOT_APPLICABLE`이고 인용이 비어 있으면 완료한다. `DECIDED` 또는 인용이 붙은 조합은 거부한다.

로컬에서 Spring AI의 실제 HTTP 요청을 루프백 대역으로 받아 스키마 전달, 길이 제한 종료, 내용 전 권한 취소를 재현했다. #1096의 오류 조합은 실제 Spring SSE에서 본문·완료 이벤트와 인용 0건으로 검증했다. dev 실공급자의 조건·상태 보존, 주장별 인용 의미 검증, 생성 중 선택 문서 변경과 재검증, 공급자 차이 검증은 남아 있다.
