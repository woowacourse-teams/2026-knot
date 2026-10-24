# #620 서버 오류 로그의 원인 정보와 수준 보완 구현 계획

## 1. 범위와 브랜치

상태: 갱신한 계획의 구현과 집중 검증 완료. 최종 전체 검증·Persona 종료 결과는 §7에 기록한다.
확인 날짜: 2026-10-12.
대상: [Issue #620](https://github.com/woowacourse-teams/2026-Knot/issues/620).
담당자: jyt6640. 라벨: 흑곰, BE, BugFix.
조사 기준 HEAD: `92a83fadc3bb2457429c04130ca6d16021d4a64c`.
구현 브랜치: `be/bugfix/#620`. 사용자의 구현·테스트 승인으로 조사 HEAD에서 분기해 #619 코드와 사용자 문서·QA 커밋을 유지했다.

공통 처리기·탐색 SSE·OAuth·문서/탐색/오디오 삭제 워커·관련 스케줄러의 원인 로그와 수준을 보완한다.
HTTP 상태 분류는 [#619](https://github.com/woowacourse-teams/2026-Knot/issues/619)의
[PR #622](https://github.com/woowacourse-teams/2026-knot/pull/622)에 구현돼 있으나 조사 시점에는 OPEN·미병합이다.
현재 checkout에는 404·405·415 처리가 있다. develop에 병합된 구현으로 간주하지 않는다.
기본 구현 순서는 #622 병합 후 최신 develop에서 #620 브랜치를 만드는 것이다.
병합 전에 구현한다면 #619 의존성을 별도로 표시하고, 사용자 문서·QA 커밋을 재작성하거나 제거하지 않는다.
외부 응답과 인증·권한·재시도·취소·보존 정책은 이번 로그 개선의 변경 대상이 아니다.

| 근거 | 확인 시점·revision | 적용 사실 | 판정 |
| --- | --- | --- | --- |
| 사용자 로그 점검·Issue 분리 지시 | 2026-10-12 | 공통 예외와 별도 로그 Issue, 두 Issue 게시 | 확정 |
| production Java catch 전수 점검 | 2026-10-12, HEAD `92a83fad`, 513개 파일·52개 파일의 catch 112곳, 명시적 로그 호출 17곳·11개 파일 | 모든 catch 본문과 관련 변환·호출 경계를 읽고 §3.1에 112행으로 기록 | 정적 구현 관측. 비동기·MCP의 실제 최종 기록은 구현 시 검증, 프레임워크 전체 로그·운영 실행을 보증하지 않음 |
| [GlobalExceptionHandler](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/global/exception/GlobalExceptionHandler.java:24)·[탐색 처리기](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/global/exception/SearchQuestionExceptionHandler.java:49) | 현재 checkout | 일반 예외·프로젝트 5xx·committed 분기 원인 로그 없음 | 구현 관측 |
| [OAuth 기존 비밀값 테스트](/Users/yongtae/Desktop/knot/backend/src/test/java/com/knot/backend/auth/presentation/handler/OAuth2AuthenticationSuccessHandlerTest.java:351) | 현재 checkout | private-refresh-secret이 로그에 없어야 함 | 기존 계약, Throwable 원문 일괄 출력 금지 근거 |
| [공통 계약 #155](https://github.com/woowacourse-teams/2026-Knot/issues/155) | 2026-10-12 조회 | 공통 오류 응답 내부 정보 비노출 | 기존 계약 |
| [신 API 명세](https://app.notion.com/p/API-3deb4351752280e2ad98f2b5c5670fb2) | revision 2026-09-30T06:23:00Z, 캡처 2026-10-07T13:07:32.841Z | 오류 envelope·인증·CSRF 관련 캐시 확인 | unsupported 블록 존재, 최신 원문 미검증 |
| [운영 관측 문서](../operations/sprint-1-observability.md) | 2026-10-12 읽음 | journal → Alloy → Loki, 요청 민감정보 제외 | 과거 문서, 실제 호스트 미검증 |
| [Issue #620](https://github.com/woowacourse-teams/2026-knot/issues/620) | 2026-10-12 원격 재조회, OPEN·jyt6640 | 안전한 원인·작업 문맥·수준·최종 경계 단일 기록 및 이중 실패 검증 | 사용자 확정 작업 범위, 팀 승인 여부와 구분 |
| origin/develop·[PR #622](https://github.com/woowacourse-teams/2026-knot/pull/622) | 원격 develop SHA `9fc1219b1914d72b452cb941fc6eabbc17efaec4`, PR OPEN | 공통 처리기 공유, #619 분류 코드가 선행 기반 | 원격 상태 관측, 미병합 |
| LmStudioClient·LlmException / SearchAgentHttp·SearchToolException / S3RecordingAudioStorage·RecordingException | 현재 HEAD의 catch·생성자 읽음 | 실제 원인을 버린 뒤 worker에 전달하는 경로 존재 | 구현 관측, 원인 보존을 위한 최소 adapter 변경 필요 |
| [OAuth API](https://app.notion.com/p/oauth2-authorization-github-4bfb43517522822ea25c81a034b85b8d) | body/properties revision `2026-10-08T05:07:00Z`, capture `2026-10-08T09:35:01.261Z`, complete | GET·구현 YES 속성, 승인 취소와 state 실패의 구분·Cookie 비노출 | 캐시 관측. 현재 callback의 단일 실패 redirect와 차이 있음 |
| [첫 질문 API](https://app.notion.com/p/api-v1-workspaces-workspaceId-search-conversations-3ebb43517522808a9871f288fbc275b6) | body/properties revision `2026-10-11T09:59:00Z`, capture `2026-10-11T10:26:37.897Z`, complete | POST·구현 YES 속성, 접수 전 HTTP 오류·접수 후 SSE, 연결 종료만으로 생성 취소하지 않음 | 후보 계약. 본문의 미구현 표시와 속성이 충돌하므로 실제 코드를 기준으로 구현 여부 확인 |
| [재연결 API](https://app.notion.com/p/api-v1-workspaces-workspaceId-search-conversations-conversationId-answers-answerId-events-3ebb4351752280e886cdc57d0fdf292c) | body/properties revision `2026-10-11T13:55:00Z`, capture `2026-10-11T14:35:31.422Z`, complete | GET·구현 YES 속성, snapshot·종료 상태·접근 거절 계약 | 후보 계약. 실제 재연결 Controller·Stream과 대조, 원문 최신성·팀 승인은 미확인 |

[현재 V2 기준](../product/current-v2-mvp.md)과
[Notion 근거 지침](../notion-context.md)을 읽고 적용 범위를 확인했다.
고위험 shared·security 계약은 pass, 자료 충분으로 인터뷰 생략이다.
기존 게시 계약의 ADR 불필요 판단을 유지한다. 로그 인프라·보존 아키텍처를 새로 선택하지 않는다.
Notion은 관련 API 본문과 method·구현·도메인 속성을 선별해 읽었다. 전체 캐시를 적용하지 않는다.
OAuth 취소 redirect 차이와 Search의 본문/속성 구현 표시 차이는 이 로그 작업에서 응답 정책으로 변경하지 않는다.
이번 범위에 필요한 미결정 제품 정책은 없다. 새 로그 규칙과 출력 상한은 아래 기술 제안이다.

이번 전수 조사는 `backend/src/main/java`의 Java catch를 대상으로 한다. 주석·문자열을 제외한
catch 목록과 본문을 추출하고 각 항목의 상위 전파, 소비, cause 보존, 로그 책임을 대조했다.
원인 없는 새 예외로 변환하는 catch 39곳, 최종 기록·수준 보완 20곳, 분류/종료 경계 검증·보완 11곳,
기존 전파/정상 제어 유지 42곳으로 모두 분류했다. 서로 겹치지 않는 대표 조치 기준이며 합계는 112다.
39곳은 입력 검증도 포함한 원인 소실 건수이지 서버 장애 39종이나 로그 추가 39곳을 뜻하지 않는다.
문서 worker의 enum 변환처럼 새 예외 없이 원인을 소비하는 4곳은 최종 기록 보완에 포함한다.
HTTP 예외 handler와 OAuth failure callback처럼 catch가 없는 최종 경계도 기존 계획 범위에 유지한다.

## 2. 구현할 흐름

```text
HTTP 내부 실패 → 최종 공통 처리 경계 → 안전한 원인 로그 1건 + 기존 500 응답
SSE 시작 전 실패 → global 위임 → 같은 기록
SSE 시작 후 실패 → 탐색 경계 기록 → 기존 상태·빈 body·비동기 종료
비동기 작업 실패 → 원인 보존 → 최종 worker/scheduler 경계 기록 + 기존 상태 처리
OAuth 내부 실패 → 안전한 원인 기록 + 기존 인증 실패 처리
정상 취소·접근 철회 → 기존 종료/정리, 장애 WARN·ERROR로 기록하지 않음
```

정상 HTTP 요청과 정상 작업은 기존 결과를 반환한다. 예를 들어 DB 호출이 실패하면 클라이언트에는
기존 `INTERNAL_SERVER_ERROR`만 보이고, 서버에는 예외 종류와 발생 위치가 남는다.

```text
ERROR event=document-generation-failed workspaceId=1 jobId=42 stage=GENERATION attempt=2
diagnostic=DocumentException -> IllegalStateException at SomeService.execute(SomeService.java:80)
```

위 출력은 제안 형식을 설명하는 예시이며 실제 발생 로그가 아니다.
DB 처리에는 새 저장 작업을 추가하지 않는다. worker의 실패 상태 저장·재시도는 기존 메서드가 수행한다.

예외 타입·원인 체인·스택 위치로 원인을 진단하며 원문 메시지·SQL·외부 응답·credential을 추가 로그에 넣지 않는다.
작업 ID·단계·attempt는 해당 작업에서 이미 갖는 안전한 식별자만 사용한다.
복구 가능한 알려진 실패는 WARN, 예상 밖 내부 장애는 ERROR, 정상 요약은 기존 INFO로 유지한다.
용량 제한·인터럽트 등을 정상 제어 흐름으로 처리하는 기존 경계는 장애로 확대하지 않는다.

| 상황 | 로그 수준 제안 | 응답·업무 상태 |
| --- | --- | --- |
| 예상 밖 RuntimeException, 내부 설정·저장·실패 상태 저장 장애 | ERROR | 기존 오류 응답·실패/전파 처리 유지 |
| 알려진 외부 timeout·일시적 공급자 실패·제한으로 재시도 가능한 작업 | WARN | 기존 실패 분류·재시도 유지 |
| 인증 거절·state 불일치 | WARN | 기존 인증 실패 처리 유지 |
| 명시적 취소·접근 철회·정상 interrupt·executor 용량 부족 | DEBUG 또는 기존 무로그 | 기존 정리·interrupt flag·실행 슬롯 반환 유지 |
| 정상 자동 종료 건수 요약 | 기존 INFO | 기존 성공 결과 유지 |
| 정상 입력·인증 거절로 확인된 validation·인증/권한·경로/메서드/형식의 일반 4xx | 공통 처리기 장애 ERROR 없음 | #619 및 기존 응답 유지 |

HTTP 500이라는 이유만으로 모든 예외를 ERROR로 판단하지 않는다.
예를 들어 Search의 `ANSWER_GENERATION_UNAVAILABLE`에는 용량 부족과 예상 밖 실행 오류가 함께 들어온다.
cause와 실제 발생 경계를 이용해 용량 부족은 정상 제어, 예상 밖 RuntimeException은 ERROR로 구분한다.
LLM_CALL_INTERRUPTED도 기존 업무 실패 분류는 유지하면서 장애 로그 수준만 낮춘다.
반대로 4xx로 포장된 예외에도 내부 원인이 있을 수 있다. JwtProvider의 INVALID_JWT/UNAUTHENTICATED,
RecordingSessionRepositoryAdapter의 미분류 DB 제약 실패, MCP의 INVALID_TOOL_ARGUMENTS를
응답 코드만으로 정상 거절이라고 판단하지 않는다. 해당 변환 위치와 보존된 cause로 내부 실패를 구분해
최종 경계에서 ERROR를 기록한다. 이번 작업에서는 기존 HTTP status·공개 code를 바꾸지 않는다.
알려진 토큰 서명/만료, cursor 파싱, named unique 충돌에는 ERROR를 추가하지 않는다.

## 3. 나올 코드와 메서드 초안

아래 경계와 동작은 현재 코드에서 확인했다. 변경과 메서드 이름은 구현 제안이다.
경로는 backend/src/main/java/com/knot/backend 아래에 있다.

| 기존 클래스·메서드 | 현재 관측 | 제안 책임 |
| --- | --- | --- |
| global/exception/GlobalExceptionHandler.handleUnexpectedException·handleProjectException | 일반·프로젝트 내부 오류 무로그 | 예상 밖·프로젝트 내부 오류 및 내부 원인이 4xx로 포장된 실패에 안전한 진단, 정상 4xx 무 ERROR·기존 응답·Retry-After 유지 |
| global/exception/SearchQuestionExceptionHandler.handleUnexpectedException·handleProjectException | committed에서 global 우회 | 시작 전은 global 한 번, 시작 후는 여기서 한 번 기록하고 기존 종료 |
| auth/presentation/handler/OAuth2AuthenticationSuccessHandler·FailureHandler | 내부 실패 원인 type/code만 기록, 인증 거절과 내부 장애 구분 부족 | 기존 비밀값 보호를 유지하며 내부 오류 진단·수준 구분 |
| document/application/DocumentGenerationWorker.fail | job/stage/attempt/cause enum만 전달, 원래 Throwable 소실 | 실패 입력에 원인 보존, 작업 실패와 실패 상태 저장 실패를 각각 기록 |
| search/application/SearchAnswerWorker | RuntimeException type만 WARN, 정상 CancellationException도 포함 | 정상 취소를 분리하고 실제 실패의 원인 기록 |
| recording/application/RecordingAudioDeletionWorker | state.fail 후 원인 없는 WARN | state.fail 자체가 실패해도 최초 저장소 원인과 상태 저장 실패가 진단되게 기록 |
| document/infrastructure/DocumentGenerationScheduler·RetentionCleanupScheduler, search/infrastructure/SearchAnswerScheduler | 실행·복구·정리 실패 ID만 WARN | 안전한 작업 문맥과 원인 추가, 재시도 정책 유지 |
| search/presentation/stream/SearchAnswerStream | SearchException 변환에서 cause 소실 | 기존 SearchException(code, cause) 생성자로 원인 보존, 중간 중복 로그 금지 |
| search/infrastructure/SearchTransportCancellation | RuntimeException 후 종료만 수행 | 예상 접근 철회와 내부 오류를 구분하고 내부 오류 진단 |
| global/exception/LlmException·global/infrastructure/llm/LmStudioClient | LlmException에 cause 생성자 없음, timeout·interrupt·ExecutionException·JSON 변환에서 원인 소실 | cause 생성자 추가, 실제 잡은 예외만 전달. 상태 코드로 만든 예외에 가짜 cause를 만들지 않음 |
| search/domain/SearchToolException·search/infrastructure/SearchAgentHttp | 도구 예외에 cause 생성자 없음, HTTP 읽기·대기 오류 원인 소실 | 기존 code 유지, cause 생성자와 catch 변환만 보완, adapter 로그 추가 없음 |
| recording/domain/RecordingException·recording/infrastructure/storage/S3RecordingAudioStorage | SdkException을 AUDIO_STORAGE_UNAVAILABLE로 바꾸며 원인 소실 | cause 생성자와 변환 보완. 삭제 key·bucket·외부 응답 원문은 출력하지 않음 |
| auth/presentation/handler/JwtLogoutHandler·recording/application/RecordingConnectionExpiryService | 기존 두 로그는 Throwable 전체를 출력 | 기존 목적·상태·다음 후보 처리 유지, 원문 출력을 안전한 진단으로 교체 |
| document/domain/DocumentException 및 §3.1의 원인 소실 39개 변환 | DocumentException에도 cause 생성자가 없고 입력·생성 결과·파싱·설정 변환에서 원인 소실 | 기존 code 생성자 유지, cause 생성자 추가. Document/Llm/Recording/SearchTool 및 기존 SearchException 생성자로 39곳의 실제 원인 보존 |
| auth/infrastructure/jwt/JwtAuthenticationFilter·JwtProvider / auth/application/AuthSessionRefreshService | 정상 거절과 내부 오류가 같은 code로 포장되거나 filter/replay 분기에서 소비될 수 있음 | 최종 경계가 내부 cause 판별. provider 중간 로그 금지, replay에서 소비하는 내부 실패만 해당 service 책임 |
| search/infrastructure/mcp/SearchMcpAuthenticationFilter·SearchMcpTools·SearchToolTokenCodec | filter·MCP 결과는 global을 통하지 않는 분기, broad Runtime/IllegalArgument 변환 존재 | 입력 거절/접근 철회와 내부 오류를 구분, cause 보존, 실제 MCP 요청 테스트로 filter/tools 최종 기록 확인 |
| search/presentation/stream/SearchStreamReservation.run | 별도 executor에서 ExecutionException을 cause 없이 재던짐 | cause 보존과 안전한 실행 경계 기록 후 기존 전파·finally 유지. HTTP advice 도달 가정 금지 |
| document/infrastructure/llm의 prompt·parser, global/config의 duration 검증, recording/infrastructure의 hash·무결성 변환, search/infrastructure의 응답 parser | catch 변환 원인 소실, 일부는 bean 생성 시 실행 | cause 추가, 시작 실패와 runtime 실패의 최종 책임 구분. resource/모델 본문·SQL·설정값 출력 없음 |

새 코드 후보는 `global/logging/SafeExceptionDiagnostics` 한 개다. 현재 존재하지 않는 제안이다.
`public static String describe(Throwable exception)`는 예외를 입력받아 타입·cause/suppressed 관계·스택 위치를
안전한 문자열로 반환한다. 로그를 직접 남기거나 원래 예외를 변경하거나 DB에 저장하지 않는다.
원문 `message`, `localizedMessage`, `toString`을 읽어 출력하지 않고, log.error의 마지막 인자에 원본 Throwable을 넣지 않는다.
identity 기준 방문 집합으로 순환을 멈추고, 예외 노드 16개·노드당 스택 4개·전체 4,096자 제한을 초안으로 둔다.
한도에 도달하면 생략 표시를 남긴다. 입력 null·빈 스택도 안전하게 처리한다.
기존 로거와 SLF4J/Logback을 사용하며 새 로그 프레임워크나 의존성을 추가하지 않는다.
예외 원문을 정규식으로 일부 가리는 방식만으로 credential 보호를 보장하지 않는다.

중요 메서드 변경 초안:

- GlobalExceptionHandler.handleUnexpectedException / handleProjectException: 예외 종류·ErrorCategory·알려진 제어 흐름을 판단해 최종 로그를 남긴 뒤 기존 ResponseEntity를 반환한다. 일반 4xx는 장애로 기록하지 않는다.
- SearchQuestionExceptionHandler의 같은 두 메서드: 응답 시작 전에는 global에만 위임한다. 시작 후에는 이 경계에서 기록하고 기존 status·빈 body·async complete를 유지한다. 응답을 만들기 위해 global을 재호출하지 않는다.
- OAuth 성공/실패 handler: private logFailure 후보로 정상 승인 취소·인증 거절·외부 실패·내부 장애를 구분한다. 외부 OAuth error code는 알려진 값만 분류에 사용하며, 임의 description·error URI·전체 요청은 출력하지 않는다.
- DocumentGenerationWorker.fail(execution, failureCause, exception): 기존 execution·실패 enum에 원래 Throwable을 추가한다. 최초 작업 실패를 먼저 기록하고 failAttempt의 별도 실패도 기록한다. 기존 삼키는/전파하는 동작은 유지한다.
- SearchAnswerWorker.execute(answerId): 정상 claim 없음은 무로그, 정상 취소는 DEBUG/무로그로 분리한다. 기존 executions.fail 호출은 유지해 이미 종료된 실행에서의 no-op도 검증한다. 실패 저장에서 새로 발생한 예외는 scheduler의 최종 경계에 맡긴다.
- RecordingAudioDeletionWorker.execute(taskId): 원래 삭제 실패를 state.fail 이전에 기록한다. state.fail 실패는 기존처럼 상위로 전달하고 RetentionCleanupScheduler.execute가 기록한다. 성공 시 state.succeed 호출은 그대로다.
- 각 scheduler의 dispatch/recover/execute 경계: 해당 경계의 phase와 이미 가진 ID·attempt를 진단에 붙인다. 실제 종료하지 않은 작업을 완료 처리하거나 executor 용량 부족을 장애로 바꾸지 않는다.
- SearchAnswerStream.reserve/open: 기존 SearchException(code, cause)로 변환하고 정리·permit 반환을 유지한다. 중간 로깅은 하지 않는다. openRetry·reconnect의 기존 연결 처리도 회귀 검증한다.
- SearchTransportCancellation.check: SEARCH_ACCESS_DENIED는 정상 철회로 구분하고, DB 등 예상 밖 실패는 안전하게 한 번 기록한 뒤 registration.close를 유지한다. 감시 등록 실패는 기존처럼 정리 후 재던져 호출 경계에 맡긴다.
- JwtAuthenticationFilter.doFilterInternal: 정상 토큰 거절은 기존 익명 처리, 내부 cause를 가진 실패는 filter에서 1회 기록 후 같은 흐름을 유지한다. JwtProvider.validate/decodeAndValidate의 중간 로그는 추가하지 않는다. Global/OAuth/refresh도 감춰진 내부 원인을 같은 기준으로 구분한다.
- AuthSessionRefreshService.validateCurrentTokenOrHandleConcurrentReplay: 정상 replay의 세션 폐기·true 결과를 유지한다. 내부 실패를 잡은 뒤 true로 소비하는 분기에서만 service가 기록하고, 재던지는 분기는 global에 맡긴다.
- SearchToolTokenCodec.decode: 기존 INVALID_TOOL_ARGUMENTS를 유지하며 cause를 전달한다. 정상 Base64/JSON 입력 거절과 내부 오류를 구분할 수 있게 테스트한다. 내부 SearchException의 그대로 재던짐도 유지한다.
- SearchMcpAuthenticationFilter.doFilterInternal / SearchMcpTools.call: 내부 오류가 소비되면 해당 경계가 기록한다. 예상 밖 재던짐은 안전한 기록 후 기존 전파를 유지하되 실제 transport에서 앱 로그 중복을 검증한다. tools의 인자 변환을 private 메서드로 좁혀 입력 오류와 서비스/직렬화의 IllegalArgumentException을 구분하며 기존 MCP 결과를 유지한다.
- SearchStreamReservation.run: ExecutionException의 cause를 보존하고 안전한 실행 경계 로그 후 기존 재던짐을 유지한다. ready.run이 직접 던지는 RuntimeException도 이 executor 경계에서 기록 누락이 없도록 검증한다. connection.completeWithError에 전달된 오류를 여기서 다시 기록하지 않는다.
- SearchAgentHttp.closeInput: timeout 중 close 실패를 worker가 항상 기록한다고 가정하지 않는다. 독립 정리 실패에는 안전한 DEBUG를 남기고, 정상 취소는 무로그를 유지한다. 실패 저장/재시도·timeout 동작은 바꾸지 않는다.
- JwtLogoutHandler.writeInternalError: 500 응답 작성 중 별도 IOException의 기록 책임을 logout 경계에 둔다. 원래 logout 오류와 별개 이벤트로 분류하고 기존 UncheckedIOException 전파는 유지한다.

SearchAnswerWorkResult에는 숫자 attempt가 없고 실행 UUID token이 있다.
로그에는 workspaceId·conversationId·answerId 및 경계의 고정 phase만 사용하고 token·질문·생성 내용은 제외한다.
Document·오디오 삭제의 attempt는 기존 execution에 있는 값을 사용한다. 로그를 위해 새 DB 조회나 필드를 추가하지 않는다.

RecordingConnectionExpiryScheduler의 양수 건수 INFO는 현재 목적에 맞아 그대로 둔다.
SearchAnswerReconnectConnection·SearchTransportRegistration의 연결 종료 IOException 무로그는 이유가 있는 정리 경로이므로
catch마다 ERROR를 일괄 추가하지 않는다.

### 3.1. catch 112곳 전수 판정표

아래 위치는 조사 HEAD의 `backend/src/main/java/com/knot/backend/` 기준이다.
ID는 경로·행 순으로 부여했고 multi-catch도 catch 블록 한 개로 셌다.
현재 동작과 구현 제안을 함께 적었으며, 조치 완료·테스트 통과를 뜻하지 않는다.
분류는 대표 조치 기준으로 하나씩 부여한다. 원인 보존 항목에도 별도 최종 로그가 필요할 수 있으므로 행의 책임 설명을 함께 적용한다.

| 분류 | 개수 | 의미 |
| --- | --- | --- |
| C: 원인 보존 | 39 | 새 예외 변환 시 cause 소실. 기존 공개 code 유지하며 cause 추가 |
| L: 기록 보완 | 20 | 내부 실패를 소비하는 경계의 누락·불충분한 원인·수준·원문 출력 보완 |
| V: 경계 보완 | 11 | 입력/내부 오류 구분, filter/MCP/async/응답 쓰기·정리의 최종 기록 검증 및 보완 |
| U: 유지 | 42 | 원인 있는 상위 전파 또는 정상 제어. 중간 로그를 추가하지 않는 이유 명시 |
| 합계 | 112 | 미분류 0 |

#### auth

| ID | 위치 | 분류 | 현재 동작 → #620 조치·최종 책임 |
| --- | --- | --- | --- |
| 001 | [auth/application/AuthSessionRefreshService.java:81](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/auth/application/AuthSessionRefreshService.java:81) | V | 토큰 검증 AuthException을 재던지거나 소비된 토큰 재사용이면 true로 종료. 재던질 때 cause 유지; 내부 원인이 replay 분기에서 소비되면 여기서 1회 기록, 정상 replay는 무로그. |
| 002 | [auth/infrastructure/AuthSessionRepositoryImpl.java:21](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/auth/infrastructure/AuthSessionRepositoryImpl.java:21) | U | DB save 실패를 AUTHENTICATION_INTERNAL_ERROR(cause)로 전파. 원인 보존됨; 최종 global/OAuth/logout 경계가 기록, repository 로그 추가 없음. |
| 003 | [auth/infrastructure/AuthSessionRepositoryImpl.java:33](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/auth/infrastructure/AuthSessionRepositoryImpl.java:33) | U | refresh hash 조회 DB 실패를 cause와 전파. 최종 global/OAuth/logout 책임, hash 출력 없음. |
| 004 | [auth/infrastructure/AuthSessionRepositoryImpl.java:45](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/auth/infrastructure/AuthSessionRepositoryImpl.java:45) | U | refresh hash 잠금 조회 실패 cause 보존. 최종 인증 처리 경계 기록, 중간 로그 없음. |
| 005 | [auth/infrastructure/AuthSessionRepositoryImpl.java:57](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/auth/infrastructure/AuthSessionRepositoryImpl.java:57) | U | 세션 ID 잠금 조회 실패 cause 보존. 최종 인증 처리 경계 기록, 중간 로그 없음. |
| 006 | [auth/infrastructure/ConsumedRefreshTokenRepositoryImpl.java:21](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/auth/infrastructure/ConsumedRefreshTokenRepositoryImpl.java:21) | U | 소비 토큰 저장 실패 cause 보존. global 최종 기록, token/hash 제외. |
| 007 | [auth/infrastructure/ConsumedRefreshTokenRepositoryImpl.java:33](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/auth/infrastructure/ConsumedRefreshTokenRepositoryImpl.java:33) | U | 소비 토큰 조회 실패 cause 보존. global 또는 해당 소비 경계 기록, 중간 로그 없음. |
| 008 | [auth/infrastructure/OAuthIdentityRepositoryImpl.java:36](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/auth/infrastructure/OAuthIdentityRepositoryImpl.java:36) | U | 알려진 unique 충돌은 NICKNAME_SETUP_ALREADY_COMPLETED(cause), 그 외는 원본 재던짐. 정상 충돌 무 ERROR; 나머지는 global/OAuth에서 기록. |
| 009 | [auth/infrastructure/github/GithubOAuth2UserService.java:46](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/auth/infrastructure/github/GithubOAuth2UserService.java:46) | U | OAuth2AuthenticationException 그대로 재던짐. OAuthFailureHandler가 최종 분류·기록. |
| 010 | [auth/infrastructure/github/GithubOAuth2UserService.java:48](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/auth/infrastructure/github/GithubOAuth2UserService.java:48) | U | 사용자 정보 조회 RuntimeException을 OAuth 예외(cause)로 전파. cause 보존됨; OAuthFailureHandler가 내부/공급자 실패 분류. |
| 011 | [auth/infrastructure/github/GithubOAuth2UserService.java:65](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/auth/infrastructure/github/GithubOAuth2UserService.java:65) | U | 유효하지 않은 OAuth 사용자 AuthException을 OAuth 예외(cause)로 전파. cause 보존됨; OAuthFailureHandler 기록. |
| 012 | [auth/infrastructure/github/GithubUserAttributes.java:32](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/auth/infrastructure/github/GithubUserAttributes.java:32) | U | GitHub 속성 cast 오류를 INVALID_OAUTH_USER(cause)로 전파. 원인 보존됨; OAuth 최종 경계. |
| 013 | [auth/infrastructure/jwt/JwtAuthenticationFilter.java:48](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/auth/infrastructure/jwt/JwtAuthenticationFilter.java:48) | L | AuthException을 전부 소비하며 무로그. 정상 토큰 거절과 내부 cause 구분; 내부 실패만 filter에서 안전하게 기록, 기존 인증 흐름 유지. |
| 014 | [auth/infrastructure/jwt/JwtProvider.java:118](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/auth/infrastructure/jwt/JwtProvider.java:118) | V | refresh 검증 AuthException을 UNAUTHENTICATED(cause)로 포장. 원인 보존되나 내부 오류도 401 분류에 숨을 수 있음; global/refresh 소비 경계가 cause까지 판별. |
| 015 | [auth/infrastructure/jwt/JwtProvider.java:201](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/auth/infrastructure/jwt/JwtProvider.java:201) | U | principal 데이터 오류는 INVALID_JWT(cause), 나머지는 원본 전파. 정상 토큰 검증 무 ERROR; 최종 인증 경계 분류. |
| 016 | [auth/infrastructure/jwt/JwtProvider.java:233](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/auth/infrastructure/jwt/JwtProvider.java:233) | U | nickname 토큰 값 변환 오류를 INVALID_JWT(cause)로 전파. 원인 보존됨; 정상 입력 실패 로그 확대 없음. |
| 017 | [auth/infrastructure/jwt/JwtProvider.java:308](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/auth/infrastructure/jwt/JwtProvider.java:308) | U | decode의 AuthException 그대로 전파. 중간 로그 없음; 최종 filter/global/OAuth 경계. |
| 018 | [auth/infrastructure/jwt/JwtProvider.java:310](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/auth/infrastructure/jwt/JwtProvider.java:310) | V | decode의 모든 RuntimeException을 INVALID_JWT(cause)로 변환. 정상 서명·만료 거절과 예상 밖 내부 실패를 최종 경계에서 구분; 기존 응답 코드 유지. |
| 019 | [auth/infrastructure/jwt/JwtProvider.java:329](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/auth/infrastructure/jwt/JwtProvider.java:329) | U | 토큰 subject 숫자 변환 실패 INVALID_JWT(cause). 원인 보존됨; 정상 입력 오류 무 ERROR. |
| 020 | [auth/infrastructure/jwt/JwtProvider.java:400](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/auth/infrastructure/jwt/JwtProvider.java:400) | U | hash 알고리즘 실패 AUTHENTICATION_INTERNAL_ERROR(cause). HTTP/인증 최종 경계가 ERROR, token/hash 비노출. |
| 021 | [auth/presentation/handler/JwtLogoutHandler.java:40](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/auth/presentation/handler/JwtLogoutHandler.java:40) | L | logout 실패를 ERROR+원본 Throwable로 기록하고 500 작성. 원문 출력 제거, 안전한 진단 1회; 쿠키·failure attribute 유지. |
| 022 | [auth/presentation/handler/JwtLogoutHandler.java:79](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/auth/presentation/handler/JwtLogoutHandler.java:79) | V | 500 응답 쓰기 IOException을 UncheckedIOException(cause)로 전파. 공통 advice 도달 가정 금지; 별도 쓰기 실패를 logout 경계에서 안전하게 기록 후 기존 전파, 연결 종료는 분류. |
| 023 | [auth/presentation/handler/OAuth2AuthenticationSuccessHandler.java:81](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/auth/presentation/handler/OAuth2AuthenticationSuccessHandler.java:81) | L | OAuth 성공 처리 AuthException을 code만 WARN 후 실패 redirect. 내부 cause·수준 보완; 예상 인증 거절과 구분. |
| 024 | [auth/presentation/handler/OAuth2AuthenticationSuccessHandler.java:90](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/auth/presentation/handler/OAuth2AuthenticationSuccessHandler.java:90) | L | OAuth 성공 처리 RuntimeException을 type만 ERROR 후 실패 redirect. cause/suppressed·스택 위치 보완, 원문 비노출. |
| 025 | [auth/presentation/handler/OAuth2AuthenticationSuccessHandler.java:131](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/auth/presentation/handler/OAuth2AuthenticationSuccessHandler.java:131) | U | principal cast 오류 OAUTH_AUTHENTICATION_FAILED(cause). 원인 보존됨; OAuthSuccessHandler가 기록. |

#### document

| ID | 위치 | 분류 | 현재 동작 → #620 조치·최종 책임 |
| --- | --- | --- | --- |
| 026 | [document/application/DocumentGenerationResultService.java:119](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/document/application/DocumentGenerationResultService.java:119) | C | 문서 결과 createDraft 실패를 INVALID_DOCUMENT_GENERATION_RESPONSE로 바꾸며 cause 소실. DocumentException cause 생성자 사용; worker에서 최종 기록. |
| 027 | [document/application/DocumentGenerationWorker.java:48](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/document/application/DocumentGenerationWorker.java:48) | L | LLM 실패를 enum으로만 fail에 전달. 원래 Throwable도 전달, 정상 interrupt/외부 실패/내부 장애 수준 분리. |
| 028 | [document/application/DocumentGenerationWorker.java:53](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/document/application/DocumentGenerationWorker.java:53) | L | DocumentException을 enum으로만 fail에 전달. 원래 Throwable 추가; worker가 원인과 작업 문맥 기록. |
| 029 | [document/application/DocumentGenerationWorker.java:58](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/document/application/DocumentGenerationWorker.java:58) | L | DB 실패를 STORAGE enum으로만 fail에 전달. DB 원인 보존해 안전한 ERROR, SQL 원문 제외. |
| 030 | [document/application/DocumentGenerationWorker.java:63](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/document/application/DocumentGenerationWorker.java:63) | L | 예상 밖 RuntimeException을 INTERNAL enum으로만 fail에 전달. 원인 추가, worker ERROR. |
| 031 | [document/application/DocumentGenerationWorker.java:144](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/document/application/DocumentGenerationWorker.java:144) | L | 실패 상태 저장 RuntimeException을 ID·attempt만 WARN하고 소비. 최초 오류와 별도 원인을 안전하게 기록; 기존 소비 유지. |
| 032 | [document/domain/DocumentConfirmationCursor.java:95](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/document/domain/DocumentConfirmationCursor.java:95) | C | 확인 cursor 파싱 실패를 INVALID_PARAMETER로 바꾸며 cause 소실. cause 추가, 정상 400 무 ERROR·cursor 비노출. |
| 033 | [document/domain/DocumentCursor.java:108](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/document/domain/DocumentCursor.java:108) | C | 문서 cursor 숫자/시간 파싱 원인 소실. cause 추가, 정상 입력 오류 무 ERROR. |
| 034 | [document/domain/DocumentGenerationBatch.java:355](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/document/domain/DocumentGenerationBatch.java:355) | C | batch 시간 범위 DateTimeException을 INVALID_DOCUMENT_DATA로 변환하며 원인 소실. cause 추가, 기존 검증·400 유지. |
| 035 | [document/domain/DocumentGenerationInput.java:30](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/document/domain/DocumentGenerationInput.java:30) | C | 생성 입력 DocumentException을 INTERNAL 범주의 새 오류로 바꾸며 원인 소실. cause 추가; worker/global 최종 기록. |
| 036 | [document/domain/DocumentGenerationJob.java:327](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/document/domain/DocumentGenerationJob.java:327) | C | job 시간 검증 DateTimeException 원인 소실. cause 추가, 기존 검증 결과 유지·도메인 로그 없음. |
| 037 | [document/domain/DocumentGenerationJob.java:370](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/document/domain/DocumentGenerationJob.java:370) | C | 재시도 가능 시각 DateTimeException을 RETRY_NOT_ALLOWED로 변환하며 원인 소실. cause 추가, 기존 재시도 정책·응답 유지. |
| 038 | [document/domain/DocumentGenerationJobCursor.java:88](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/document/domain/DocumentGenerationJobCursor.java:88) | C | 생성 job cursor 파싱 원인 소실. cause 추가, 정상 400 무 ERROR. |
| 039 | [document/domain/DocumentGenerationJobCursor.java:125](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/document/domain/DocumentGenerationJobCursor.java:125) | C | 생성 job cursor 시각 범위 원인 소실. cause 추가, 기존 검증·400 유지. |
| 040 | [document/infrastructure/DocumentGenerationScheduler.java:55](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/document/infrastructure/DocumentGenerationScheduler.java:55) | U | executor TaskRejectedException이면 현재 poll 종료. 정상 용량 제어 무 ERROR, 작업 상태·다음 poll 유지. |
| 041 | [document/infrastructure/DocumentGenerationScheduler.java:73](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/document/infrastructure/DocumentGenerationScheduler.java:73) | L | lease 복구 실패 ID·attempt만 WARN 후 다음 후보. cause·phase·수준 보완, 후보 격리 유지. |
| 042 | [document/infrastructure/DocumentGenerationScheduler.java:89](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/document/infrastructure/DocumentGenerationScheduler.java:89) | L | worker 전파 실패 ID·attempt만 WARN하고 소비. claim/상태 저장의 별도 실패를 안전하게 기록; worker 소비 오류 중복 없음. |
| 043 | [document/infrastructure/RetentionCleanupScheduler.java:68](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/document/infrastructure/RetentionCleanupScheduler.java:68) | L | 문서 보존 정리 실패 batchId만 WARN 후 다음 후보. cause·phase·기존 ID 추가, 내부 장애 ERROR. |
| 044 | [document/infrastructure/RetentionCleanupScheduler.java:82](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/document/infrastructure/RetentionCleanupScheduler.java:82) | L | 오디오 보존 예약 실패 uploadId만 WARN 후 다음 후보. 안전한 cause·phase·수준 추가. |
| 045 | [document/infrastructure/RetentionCleanupScheduler.java:99](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/document/infrastructure/RetentionCleanupScheduler.java:99) | U | 삭제 executor 용량 부족이면 현재 poll 종료. 정상 제어 무 ERROR, 삭제 상태 유지. |
| 046 | [document/infrastructure/RetentionCleanupScheduler.java:108](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/document/infrastructure/RetentionCleanupScheduler.java:108) | L | 오디오 worker 전파 실패 taskId만 WARN. 원인·phase·수준 추가; 최초 삭제 실패와 별도 상태 저장 실패의 책임 분리. |
| 047 | [document/infrastructure/llm/DocumentGenerationPrompt.java:105](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/document/infrastructure/llm/DocumentGenerationPrompt.java:105) | C | 생성 schema JSON 파싱 오류를 LLM_INVALID_CONFIGURATION로 바꾸며 cause 소실. cause 추가; 시작 시 Boot/실행 시 worker 책임 구분, 새 중복 로그 없음. |
| 048 | [document/infrastructure/llm/DocumentGenerationPrompt.java:120](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/document/infrastructure/llm/DocumentGenerationPrompt.java:120) | C | 생성 prompt resource IOException 원인 소실. cause 추가, 시작/실행 경계 책임 구분. |
| 049 | [document/infrastructure/llm/DocumentTopicPrompt.java:86](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/document/infrastructure/llm/DocumentTopicPrompt.java:86) | C | 분류 prompt resource IO/JSON 오류 원인 소실. DocumentException cause 추가. readTree가 catch 밖인 점도 기록, 시작 실패를 런타임 worker 기록으로 오인하지 않음. |
| 050 | [document/infrastructure/llm/LlmDocumentGenerator.java:81](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/document/infrastructure/llm/LlmDocumentGenerator.java:81) | C | 생성 응답 JacksonException 원인 소실. invalidResponse(cause) 경로 추가; worker 최종 기록, 모델 본문 제외. |
| 051 | [document/infrastructure/llm/LlmDocumentTopicClassifier.java:66](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/document/infrastructure/llm/LlmDocumentTopicClassifier.java:66) | C | 분류 응답 JacksonException 원인 소실. invalidResponse(cause) 추가; worker 최종 기록. |
| 052 | [document/infrastructure/llm/LlmDocumentTopicClassifier.java:100](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/document/infrastructure/llm/LlmDocumentTopicClassifier.java:100) | C | 모델 topic의 도메인 검증 오류를 INVALID_TOPIC_CLASSIFICATION_RESPONSE로 변환하며 원인 소실. cause 추가, worker 최종 기록. |

#### global

| ID | 위치 | 분류 | 현재 동작 → #620 조치·최종 책임 |
| --- | --- | --- | --- |
| 053 | [global/config/DocumentGenerationWorkerProperties.java:65](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/global/config/DocumentGenerationWorkerProperties.java:65) | C | duration overflow를 worker 설정 오류로 변환하며 cause 소실. DocumentException cause 추가, 시작 실패 원인 검증. |
| 054 | [global/config/LlmProperties.java:146](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/global/config/LlmProperties.java:146) | C | duration overflow를 LLM 설정 오류로 변환하며 cause 소실. invalidConfiguration(cause) 추가, 시작 실패 원인 검증. |
| 055 | [global/infrastructure/llm/LmStudioClient.java:183](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/global/infrastructure/llm/LmStudioClient.java:183) | C | LLM future timeout 원인 소실. LlmException cause 추가, cancel 유지; 최종 worker WARN. |
| 056 | [global/infrastructure/llm/LmStudioClient.java:186](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/global/infrastructure/llm/LmStudioClient.java:186) | C | LLM interrupt 원인 소실. cause 추가, future cancel·interrupt flag 유지; 정상 종료 ERROR 없음. |
| 057 | [global/infrastructure/llm/LmStudioClient.java:191](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/global/infrastructure/llm/LmStudioClient.java:191) | C | future ExecutionException에서 timeout/unavailable 양쪽 모두 cause 소실. 두 분기에 원래 예외 보존; 실제 원인으로 최종 수준 결정. |
| 058 | [global/infrastructure/llm/LmStudioClient.java:231](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/global/infrastructure/llm/LmStudioClient.java:231) | U | 오류 본문의 context-limit 판별 JSON 실패는 false fallback. 정상 판별 경로 유지; 원문 출력·추가 장애 로그 없음. |
| 059 | [global/infrastructure/llm/LmStudioClient.java:256](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/global/infrastructure/llm/LmStudioClient.java:256) | C | LLM 본문 JacksonException을 INVALID_RESPONSE로 변환하며 원인 소실. cause 추가, worker 최종 기록. |

#### recording

| ID | 위치 | 분류 | 현재 동작 → #620 조치·최종 책임 |
| --- | --- | --- | --- |
| 060 | [recording/application/RecordingAudioDeletionWorker.java:28](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/recording/application/RecordingAudioDeletionWorker.java:28) | L | 삭제 실패에서 state.fail 뒤 원인 없는 WARN. 최초 원인을 먼저 기록; state.fail 실패는 scheduler가 별도 기록, 기존 전파 유지. |
| 061 | [recording/application/RecordingConnectionExpiryService.java:39](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/recording/application/RecordingConnectionExpiryService.java:39) | L | 녹음 만료 처리 실패 원본 Throwable WARN 후 false. 안전한 원인·수준으로 교체, 다음 후보·성공 카운트 유지. |
| 062 | [recording/infrastructure/RecordingSessionRepositoryAdapter.java:26](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/recording/infrastructure/RecordingSessionRepositoryAdapter.java:26) | C | 무결성 실패를 translateIntegrityViolation에서 cause 없는 RecordingException으로 변환. cause 추가; 알려진 unique 409 무 ERROR, 미분류 DB 내부 실패는 응답 유지하며 global ERROR. |
| 063 | [recording/infrastructure/Sha256RecordingControlTokenHasher.java:23](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/recording/infrastructure/Sha256RecordingControlTokenHasher.java:23) | C | SHA-256 알고리즘 실패를 새 RecordingException으로 변환하며 원인 소실. cause 추가, 최종 global ERROR. |
| 064 | [recording/infrastructure/storage/S3RecordingAudioStorage.java:98](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/recording/infrastructure/storage/S3RecordingAudioStorage.java:98) | C | S3 metadata 404는 missing 정상 흐름; 다른 S3Exception 원인 소실. 404 유지, 나머지 cause 추가·최종 global/worker 기록. |
| 065 | [recording/infrastructure/storage/S3RecordingAudioStorage.java:103](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/recording/infrastructure/storage/S3RecordingAudioStorage.java:103) | C | S3 metadata SdkException 원인 소실. cause 추가, key/bucket 제외·최종 경계 기록. |
| 066 | [recording/infrastructure/storage/S3RecordingAudioStorage.java:134](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/recording/infrastructure/storage/S3RecordingAudioStorage.java:134) | C | S3 삭제 SdkException 원인 소실. cause 추가, audio worker가 기록. |
| 067 | [recording/infrastructure/storage/S3RecordingAudioStorage.java:208](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/recording/infrastructure/storage/S3RecordingAudioStorage.java:208) | U | S3 delete 404는 멱등 성공, 그 외 원본 재던짐. 그대로 유지, 비404는 상위 adapter/worker 책임. |

#### search

| ID | 위치 | 분류 | 현재 동작 → #620 조치·최종 책임 |
| --- | --- | --- | --- |
| 068 | [search/application/SearchAnswerWorker.java:39](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/application/SearchAnswerWorker.java:39) | L | 탐색 생성 RuntimeException을 type만 WARN 후 fail. 정상 취소 구분·원인 기록; fail에서 생긴 별도 오류는 scheduler 책임. |
| 069 | [search/domain/SearchConversationCursor.java:86](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/domain/SearchConversationCursor.java:86) | C | 탐색 cursor 파싱 오류를 INVALID_PARAMETER로 변환하며 원인 소실. 기존 SearchException cause 생성자 사용, 정상 400 무 ERROR. |
| 070 | [search/infrastructure/SearchAgentHttp.java:82](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/infrastructure/SearchAgentHttp.java:82) | C | JSON HTTP reader IOException 원인 소실. SearchToolException cause 추가, worker 최종 기록. |
| 071 | [search/infrastructure/SearchAgentHttp.java:167](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/infrastructure/SearchAgentHttp.java:167) | C | SSE HTTP reader IOException 원인 소실. cause 추가, 정상 취소 여부와 실제 외부 장애를 구분. |
| 072 | [search/infrastructure/SearchAgentHttp.java:230](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/infrastructure/SearchAgentHttp.java:230) | C | transport interrupt 원인 소실. cause 추가, interrupt flag 유지; 정상 종료 ERROR 없음. |
| 073 | [search/infrastructure/SearchAgentHttp.java:234](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/infrastructure/SearchAgentHttp.java:234) | C | transport timeout 원인 소실. cause 추가, pending cancel 유지; 알려진 외부 timeout WARN. |
| 074 | [search/infrastructure/SearchAgentHttp.java:237](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/infrastructure/SearchAgentHttp.java:237) | C | ExecutionException/CancellationException을 동일 code로 바꾸며 원인 소실. cause 추가, 감시 장애의 후속 취소와 실제 전송 실패 중복 구분. |
| 075 | [search/infrastructure/SearchAgentHttp.java:239](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/infrastructure/SearchAgentHttp.java:239) | C | transport 자원 IO 오류 원인 소실. cause 추가, 최종 worker 기록. |
| 076 | [search/infrastructure/SearchAgentHttp.java:258](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/infrastructure/SearchAgentHttp.java:258) | V | timeout의 closeInput IOException을 소비. 주석만으로 worker 기록 확정 불가; 독립 정리 실패는 안전한 DEBUG, 정상 취소는 무로그, 읽기 실패와 중복 ERROR 금지. |
| 077 | [search/infrastructure/SearchAnswerJsonStream.java:80](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/infrastructure/SearchAnswerJsonStream.java:80) | U | 분할 JSON의 아직 불완전한 필드를 기다리는 정상 경로. 무로그 유지, complete 시 전체 응답 검증. |
| 078 | [search/infrastructure/SearchAnswerJsonStream.java:143](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/infrastructure/SearchAnswerJsonStream.java:143) | C | JSON 문자열 decoding 오류 INVALID_MODEL_ANSWER로 변환하며 원인 소실. cause 추가, worker 최종 기록·모델 본문 제외. |
| 079 | [search/infrastructure/SearchAnswerPrompt.java:20](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/infrastructure/SearchAnswerPrompt.java:20) | U | 시스템 prompt resource IO를 UncheckedIOException(cause)로 전파. 원인 보존됨; startup 책임, 중간 로그 없음. |
| 080 | [search/infrastructure/SearchAnswerResponse.java:101](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/infrastructure/SearchAnswerResponse.java:101) | C | 완성된 탐색 응답 JSON 파싱 원인 소실. invalid(cause) 추가, worker 최종 기록. |
| 081 | [search/infrastructure/SearchAnswerScheduler.java:51](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/infrastructure/SearchAnswerScheduler.java:51) | U | 탐색 executor 용량 부족 poll 종료. 정상 제어 무 ERROR, 작업 상태 유지. |
| 082 | [search/infrastructure/SearchAnswerScheduler.java:65](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/infrastructure/SearchAnswerScheduler.java:65) | L | 탐색 lease 복구 RuntimeException을 answerId만 WARN. cause·phase·수준 추가, 다음 후보 유지. |
| 083 | [search/infrastructure/SearchAnswerScheduler.java:77](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/infrastructure/SearchAnswerScheduler.java:77) | L | 탐색 worker 전파 RuntimeException answerId만 WARN. claim/실패 저장 원인 기록, worker 소비 오류 중복 없음. |
| 084 | [search/infrastructure/SearchTransportCancellation.java:67](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/infrastructure/SearchTransportCancellation.java:67) | U | 감시 등록 RuntimeException 정리 후 원본 전파. cause 유지; 호출 worker/global 최종 책임, 감시 등록 중간 로그 없음. |
| 085 | [search/infrastructure/SearchTransportCancellation.java:79](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/infrastructure/SearchTransportCancellation.java:79) | L | 주기적 접근 검증 RuntimeException을 모두 소비하고 close. 정상 접근 철회는 무로그, DB 등 내부 오류는 감시 경계 ERROR 1회. |
| 086 | [search/infrastructure/SearchTransportRegistration.java:71](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/infrastructure/SearchTransportRegistration.java:71) | U | 취소 후 본문 close IOException 소비. 확정된 DB 상태를 바꾸지 않는 정리 경로, 무로그 유지. |
| 087 | [search/infrastructure/mcp/SearchMcpAuthenticationFilter.java:30](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/infrastructure/mcp/SearchMcpAuthenticationFilter.java:30) | V | MCP authenticate SearchToolException을 401로 소비. 정상 토큰 거절 무 ERROR; 내부 cause는 filter 최종 기록, 기존 401 유지. catch 밖 내부 예외도 안전한 기록 후 기존 전파. |
| 088 | [search/infrastructure/mcp/SearchMcpAuthenticationFilter.java:36](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/infrastructure/mcp/SearchMcpAuthenticationFilter.java:36) | V | MCP access SearchToolException을 403으로 소비. 정상 접근 철회 무 ERROR; 내부 cause는 filter 기록. DB RuntimeException은 원본 유지하며 filter 책임 검증. |
| 089 | [search/infrastructure/mcp/SearchMcpTools.java:51](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/infrastructure/mcp/SearchMcpTools.java:51) | V | MCP SearchToolException을 isError 결과로 소비. 알려진 입력/권한/변경/미조회는 무 ERROR, 알 수 없는 내부 cause는 tools 경계 기록·응답 유지. |
| 090 | [search/infrastructure/mcp/SearchMcpTools.java:56](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/infrastructure/mcp/SearchMcpTools.java:56) | V | MCP dispatch 전체 IllegalArgumentException을 INVALID_TOOL_ARGUMENTS로 소비. 인자 변환만 정상 거절로 좁혀 판별, 내부 서비스/직렬화 오류는 안전한 ERROR 후 기존 결과 유지. |
| 091 | [search/infrastructure/mcp/SearchToolTokenCodec.java:91](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/infrastructure/mcp/SearchToolTokenCodec.java:91) | U | codec SearchException 그대로 전파. 원인 보존됨; MCP filter/tools/worker 최종 경계 책임. |
| 092 | [search/infrastructure/mcp/SearchToolTokenCodec.java:93](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/infrastructure/mcp/SearchToolTokenCodec.java:93) | C | codec 모든 RuntimeException을 cause 없는 INVALID_TOOL_ARGUMENTS로 변환. cause 추가, 정상 Base64/JSON 입력과 내부 mapper 오류 구분; credential 비노출. |
| 093 | [search/infrastructure/mcp/SearchToolTokenCodec.java:137](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/infrastructure/mcp/SearchToolTokenCodec.java:137) | U | codec digest 알고리즘 오류 INVALID_SEARCH_CONFIGURATION(cause)로 전파. 원인 보존됨, MCP/worker 최종 기록. |
| 094 | [search/infrastructure/mcp/SearchToolTokenCodec.java:159](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/infrastructure/mcp/SearchToolTokenCodec.java:159) | U | codec signing 알고리즘 오류 INVALID_SEARCH_CONFIGURATION(cause)로 전파. 원인 보존됨, MCP/worker 최종 기록. |
| 095 | [search/presentation/SearchQuestionController.java:60](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/presentation/SearchQuestionController.java:60) | U | 첫 질문 RuntimeException은 예약 정리 후 원본 전파. SearchQuestionExceptionHandler/global 최종 기록, Controller 로그 없음. |
| 096 | [search/presentation/SearchQuestionController.java:90](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/presentation/SearchQuestionController.java:90) | U | 이어 질문 RuntimeException은 예약 정리 후 원본 전파. 같은 최종 책임, 중간 로그 없음. |
| 097 | [search/presentation/SearchQuestionController.java:118](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/presentation/SearchQuestionController.java:118) | U | 재시도 RuntimeException은 예약 정리 후 원본 전파. 같은 최종 책임, 중간 로그 없음. |
| 098 | [search/presentation/stream/SearchAnswerReconnectConnection.java:63](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/presentation/stream/SearchAnswerReconnectConnection.java:63) | U | 재연결 thread interrupt flag 복원·emitter.complete. 정상 종료 무 ERROR 유지. |
| 099 | [search/presentation/stream/SearchAnswerReconnectConnection.java:67](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/presentation/stream/SearchAnswerReconnectConnection.java:67) | U | 재연결 Servlet 쓰기 IOException은 app 예약만 finally 해제. 실제 disconnect/기타 IO 종료를 MVC 테스트로 확인, 단순 IOException 전체를 장애/정상으로 단정하지 않음. |
| 100 | [search/presentation/stream/SearchAnswerReconnectConnection.java:69](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/presentation/stream/SearchAnswerReconnectConnection.java:69) | V | 재연결 RuntimeException을 completeWithError에 전달. cause 유지되나 MVC 최종 기록 미검증; async dispatch에서 1회 기록 확인, 필요 시 이 경계가 소유. |
| 101 | [search/presentation/stream/SearchAnswerSseConnection.java:77](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/presentation/stream/SearchAnswerSseConnection.java:77) | U | 초기 SSE thread interrupt flag 복원·emitter.complete. 정상 종료 무 ERROR 유지. |
| 102 | [search/presentation/stream/SearchAnswerSseConnection.java:81](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/presentation/stream/SearchAnswerSseConnection.java:81) | V | 초기 SSE IO/RuntimeException을 completeWithError에 전달. MVC 최종 기록·disconnect 분류·중복 검증, cause 전달 유지. |
| 103 | [search/presentation/stream/SearchAnswerStream.java:41](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/presentation/stream/SearchAnswerStream.java:41) | C | stream 제출 RuntimeException을 cause 없는 ANSWER_GENERATION_UNAVAILABLE로 변환. cause 추가, 용량 부족은 정상·내부 장애는 global 기록. |
| 104 | [search/presentation/stream/SearchAnswerStream.java:111](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/presentation/stream/SearchAnswerStream.java:111) | C | stream open IO/RuntimeException을 completeWithError와 cause 없는 SearchException 양쪽으로 전달. cause 추가, controller/advice와 async 경로 합쳐 1회만 기록. |
| 105 | [search/presentation/stream/SearchAnswerStream.java:146](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/presentation/stream/SearchAnswerStream.java:146) | U | reconnect open RuntimeException 정리 후 원본 전파. 원인 유지, HTTP 최종 경계 기록. |
| 106 | [search/presentation/stream/SearchStreamReservation.java:41](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/presentation/stream/SearchStreamReservation.java:41) | U | reservation 대기 interrupt flag 복원 후 finally 정리. 정상 종료 무 ERROR 유지. |
| 107 | [search/presentation/stream/SearchStreamReservation.java:44](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/presentation/stream/SearchStreamReservation.java:44) | U | reservation future 취소 소비 후 finally 정리. 정상 종료 무로그 유지. |
| 108 | [search/presentation/stream/SearchStreamReservation.java:45](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/search/presentation/stream/SearchStreamReservation.java:45) | C | reservation 대기 ExecutionException cause 소실 후 별도 executor로 재던짐. cause 추가와 안전한 최종 로그 후 기존 전파·permit 1회 반환; ready.run 외부 RuntimeException도 실행 경계 검증. |

#### workspace

| ID | 위치 | 분류 | 현재 동작 → #620 조치·최종 책임 |
| --- | --- | --- | --- |
| 109 | [workspace/application/WorkspaceInvitationService.java:124](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/workspace/application/WorkspaceInvitationService.java:124) | U | 초대 secret collision은 마지막 예외를 보관해 3회 후 그대로 재던짐. cause 유지; 재시도 중 무로그, 소진 시 global 1회. |
| 110 | [workspace/infrastructure/WorkspaceInvitationRepositoryAdapter.java:28](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/workspace/infrastructure/WorkspaceInvitationRepositoryAdapter.java:28) | U | 초대 unique 충돌은 원인 있는 collision, 그 외 원본 재던짐. cause 유지, retry/global 책임·credential 제외. |
| 111 | [workspace/infrastructure/security/HmacWorkspaceInvitationSecretProtector.java:38](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/workspace/infrastructure/security/HmacWorkspaceInvitationSecretProtector.java:38) | U | HMAC 알고리즘 실패 WorkspaceException(cause)로 전파. 원인 보존됨; global/startup 최종 경계. |
| 112 | [workspace/infrastructure/security/HmacWorkspaceInvitationSecretProtector.java:57](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/workspace/infrastructure/security/HmacWorkspaceInvitationSecretProtector.java:57) | U | 보안 key Base64 오류 WorkspaceException(cause)로 전파. 원인 보존됨; startup 경계, key 원문 로그 추가 없음. |

### 3.2. catch 밖 최종 경계 확인

GlobalExceptionHandler·SearchQuestionExceptionHandler의 @ExceptionHandler와 OAuthFailureHandler는 catch 112개에 포함되지 않는다.
기존 계획대로 이 경계들의 로그·응답·기록 횟수를 검증한다.

| 추가 경계 | 현재 관측 | #620 조치·검증 |
| --- | --- | --- |
| DocumentGenerationScheduler.dispatchQueued / recoverExpired | ready/expired 후보 조회가 항목별 try 밖 | 조회 phase로 안전한 오류 기록, 현재 실행 종료·다음 주기 유지 |
| RetentionCleanupScheduler.cleanupExpiredData / dispatchAudioDeletion | 문서·오디오 보존 후보 및 삭제 후보 조회가 try 밖 | 세 조회의 순서·중단·phase 기록 검증, 후보 상태 변경 없음 |
| SearchAnswerScheduler.dispatchQueued / recoverExpired | queued/expired 후보 조회가 try 밖 | 조회 오류 기록·다음 주기 유지 |
| RecordingConnectionExpiryScheduler / ExpiryService.expireDue | 만료 후보 조회는 항목별 try 밖 | scheduler가 조회 실패 기록, 기존 건수 INFO·다음 주기 유지 |
| prompt/config bean 생성 | 일부 cause 소실 catch는 시작 시 실행. DocumentTopicPrompt의 readTree는 readResource catch 밖 | 원인 보존, context 시작 실패 cause 검증; runtime advice/worker에 도달한다고 주장하지 않음 |
| MCP filter·tools / SSE completeWithError / reservation executor | global advice 호출을 코드만으로 확정할 수 없음 | 기존 응답/전파와 실제 transport/async/executor의 앱 최종 로그 1회를 함께 검증 |

위 확인은 코드의 기록 책임에 대한 계획이다. 프레임워크 자체 로그·실제 운영 호스트까지 전수 감사했다는 의미는 아니다.

## 4. 저장 기반과 주의할 점

DB·Entity·Flyway·로그 저장 테이블 변경은 없다.
실패 상태 저장·재시도·취소 등의 기존 비즈니스 부작용은 유지한다.
로그를 먼저 남겨야 하는 이중 실패 경계는 원래 상태 변경의 의미와 예외 전파를 바꾸지 않도록 검증한다.

같은 예외가 adapter·worker·scheduler를 통과하면 소비하거나 최종 처리하는 경계를 기록 책임으로 정한다.
adapter는 cause 보존이 우선이며 재던지면서 동일 장애를 중복 기록하지 않는다.
별도로 발생한 실패 상태 저장 실패는 최초 작업 실패와 다른 이벤트로 진단할 수 있어야 한다.
문서 worker가 실패 저장 오류를 소비하면 worker가 두 번째 원인을 기록한다.
탐색·오디오 worker에서 실패 저장 오류가 상위로 전파되면 scheduler가 두 번째 원인을 기록한다.
같은 두 번째 예외를 worker에서 기록하고 다시 scheduler에서 기록하지 않는다.
단위 테스트뿐 아니라 worker와 scheduler를 연결한 Mockito 테스트로 전체 기록 횟수를 확인한다.
Transport 감시 오류로 전송이 취소된 경우에도 최초 감시 오류와 뒤따르는 정상 취소를 구분해 중복 장애 로그를 막는다.
SSE committed 응답에 JSON이나 500 status를 다시 쓰지 않는다.
원문 메시지·cause·suppressed의 비밀값과 요청 body·Cookie·Authorization·query를 새 로그에 출력하지 않는다.
이번 계획은 애플리케이션 코드 대상이며 실제 호스트 수집·Loki 보존·외부 라이브러리 전체 로그 감사는 수행하지 않았다.
SSE의 emitter.completeWithError가 실제 MVC 예외 처리로 이어지는 경로는 direct handler 호출만으로 단정하지 않는다.
MockMvc async dispatch와 기존 stream 테스트로 기록 책임을 확인한다. 일반 IOException만으로 정상 사용자 취소라고 분류하지 않는다.
기술적으로 확인할 항목은 이 비동기 기록 경계와 로그 중복이며, 새로운 제품 결정 질문은 필요하지 않다.

전수 catch 점검이 모든 throw·프레임워크 내부 예외 처리·운영 수집을 증명하지는 않는다.
추가로 읽은 scheduler의 후보 조회는 기존 try 밖에 있고, bean 생성의 prompt/config 실패도 HTTP advice를
통하지 않는다. 후보 조회 실패는 각 scheduled 메서드의 고정 phase로 안전하게 기록하고 해당 실행을
종료하는 경계를 추가한다. 다음 주기 실행·후보 상태·항목별 격리는 유지하며 framework의 중복 앱 로그를
유발하는 재던짐을 피한다. Retention의 두 후보 조회는 각각 분리해 기존 중단 순서를 검증한다.
RecordingConnectionExpiryScheduler도 후보 조회 실패를 scheduler에서 기록하고 다음 주기를 유지한다.
startup 실패는 원인 보존과 ApplicationContext 실패 원인 테스트로 확인하며 Boot 실패 로그를 새로
중복 출력하지 않는다. 외부 프레임워크 로그의 메시지 제거·수집 인프라 교체는 이번 범위가 아니다.
MCP SDK가 내부 예외를 JSON-RPC 오류로 변환하는 실제 응답·기록 경계는 transport 테스트로 확인한다.
기존 raw framework 로그를 앱 로그 단위 테스트만으로 안전하다고 주장하지 않는다.

## 5. TDD와 검증 순서

아래 테스트는 구현 예정이다. 이번 기획에서 로그 개선 코드를 구현하거나 신규 테스트를 실행하지 않았다.

| 테스트 위치·방식 | 성공·실패·경계 | 기대 결과 |
| --- | --- | --- |
| 제안 안전한 진단 도구 단위 테스트 | null·cause·순환·suppressed·민감 메시지 | 진단 타입·스택 위치 포함, 민감 표식 없음, 종료·출력 한도 |
| 제안 LlmException·SearchToolException·RecordingException 테스트 및 기존 LmStudioClientTest·S3RecordingAudioDeletionTest, 제안 SearchAgentHttp 테스트 | 기존 생성자·timeout·interrupt·네트워크/JSON/S3 실패 | 기존 code·interrupt flag·자원 정리 유지, getCause의 원래 예외 보존. 원문은 로그에 없음 |
| 기존 GlobalExceptionHandlerTest + ListAppender | 일반 실패·프로젝트 5xx·4xx·정상 호출 | 기존 응답, 내부 실패 ERROR 1건, 4xx 장애 ERROR 없음 |
| 기존 SearchQuestionExceptionHandlerTest | 시작 전·후 5xx와 4xx | 로그 누락·중복 없음, 기존 committed 상태·본문·종료 |
| 기존 OAuth 성공·실패 HandlerTest + OutputCapture | 정상 인증·거절·공급자/내부 실패 | 인증 처리 유지, 수준 구분, 기존 credential 비노출 테스트 유지 |
| 제안 각 Worker 단위 테스트, 기존 각 SchedulerTest | 정상 실행·복구 가능한 실패·내부 실패·정상 취소 | ID·단계·attempt·안전한 원인 및 수준, 상태·재시도 유지 |
| 오디오 삭제·문서 Worker 실패 테스트 | 최초 작업 실패 후 실패 상태 저장도 실패 | 두 원인이 각각 남고 예외 전파·상태 정책 유지 |
| 기존 SearchAnswerStreamTest·SearchTransportCancellationTest | 정상 스트림·executor/시작 실패·접근 철회·예상 밖 실패 | 변환 cause 보존, 정상 취소 장애 로그 없음, 내부 실패 진단 |
| 기존 JwtLogoutHandlerTest·RecordingConnectionExpiryServiceTest | 성공·내부 실패와 비밀값을 포함한 cause/suppressed | 기존 쿠키·500·후보 격리 유지, 원문 Throwable 출력 없음 |
| #619 MVC/Security 테스트와 기존 SSE stream 테스트·제안 async dispatch 사례 | 정상 요청·404/405/415·인증/CSRF 선거절·실제 비동기 오류 | 기존 status·code·JSON·Allow/Accept 헤더·SSE 종료 유지, 앱 최종 오류 로그 한 번 |
| 기존 cursor·DocumentGenerationInput·LlmDocumentGenerator/TopicClassifier·ResultIntegration·prompt/properties 테스트 | §3.1의 정상 파싱·검증 실패·resource/overflow·결과 변환 | 기존 code 유지, 원래 getCause 보존, 정상 입력 ERROR 없음. startup와 runtime 구분 |
| 기존 JwtAuthenticationFilterTest·JwtProviderTest·AuthSessionRefreshServiceTest 및 MVC/Security | 정상 인증·서명/만료 거절·내부 오류를 INVALID_JWT/UNAUTHENTICATED로 포장·replay 소비 | 기존 인증·응답·세션 상태, 내부 오류만 최종 기록 1회. token/cookie 비노출 |
| 제안 SearchToolTokenCodecTest·SearchMcpAuthenticationFilterTest·SearchMcpToolsTest, 기존 SearchAgentConfigurationIntegrationTest 활용 및 실제 /internal/mcp transport 테스트 | 정상 tool·입력/권한 거절·mapper/DB/서비스 내부 실패·응답 직렬화 실패 | 기존 401/403/MCP 결과·오류 전파 유지, 내부 실패 앱 로그 1회, credential/arguments/content 비노출 |
| 기존 SearchStreamReservationTest와 SSE 연결 테스트, 실제 executor + latch | attach/run/close 성공·interrupt·cancel·ExecutionException·ready.run 실패·실제 async dispatch | cause·최종 앱 로그 1회·permit 1회 반환·전파 유지. 정상 종료 ERROR 없음 |
| 기존 recording repository PostgreSQL 테스트·HasherTest 및 제안 cause 단위 테스트 | 알려진 named unique 충돌·미분류 무결성 오류·hash 실패 | 기존 409/400/code 유지, 원인 보존, 미분류 DB 내부 실패만 안전한 ERROR |
| scheduler별 기존 테스트·기존 RecordingConnectionExpirySchedulerTest 및 제안 SearchAgentHttp 정리 테스트 | 후보 조회 DB 실패·다음 주기·독립 close 실패·주 오류와 close 동시 실패 | query phase·원인, 상태 무변경·격리/주기 유지, 정리 DEBUG·중복 ERROR 없음 |

1. 안전한 원인 출력의 민감 표식·순환 실패 테스트 → 필요한 최소 공통 진단 구현.
2. Document/Llm/Recording/SearchTool의 cause 생성자와 정상 cursor/시간/입력 실패 테스트 → 기존 code 생성자 유지, §3.1의 검증 변환 원인 보존. 정상 입력 로그 확대 없음.
3. LLM·검색 HTTP·S3·hash·무결성·prompt/config·결과 parser의 cause 소실 실패 테스트 → 변환 위치별 최소 보완. 39곳의 모든 분기를 대조하고 원문 비노출 확인.
4. 일반 서버 오류와 프로젝트 5xx·내부 원인이 4xx로 포장된 사례의 로그 실패 테스트 → global 최종 경계 기록. 정상 4xx·용량 부족·응답 회귀 확인.
5. JWT 정상 거절/내부 장애와 refresh replay 소비 테스트 → 최종 filter/service/global 책임 분리. 응답·세션 계약 유지.
6. MCP 정상 tool·입력/권한 거절·내부 mapper/DB/서비스 실패 테스트 → codec 원인 보존과 filter/tools 최종 분류. 실제 transport에서 기록 횟수·기존 응답 확인.
7. SSE 시작 전/후와 reservation 실행 실패 테스트 → committed 분기·cause 보존·실행 경계 보완. 실제 executor와 async dispatch, permit 1회 반환 검증.
8. OAuth 정상 성공·승인 취소·인증 거절·공급자/내부 실패 테스트 → 안전한 진단과 수준 분류. 기존 credential 비노출 테스트 유지.
9. 문서 worker의 원인·정상 interrupt·이중 실패 테스트 → fail의 Throwable 전달과 두 원인 기록.
10. 탐색 worker의 정상 취소·실제 실패·이중 실패 테스트 → 로그 수준 보완, 기존 fail의 종료된 실행 no-op 유지.
11. 오디오 worker의 삭제 실패·상태 저장 동시 실패 테스트 → 최초 원인을 state.fail 전에 기록.
12. scheduler별 정상 제출·용량 제한·후보 조회/실행/복구 실패·다음 후보/다음 주기 테스트 → 진단 문맥 보완, worker 연결로 중복 기록 확인. 조회 catch 밖 경계도 포함.
13. SearchTransportCancellation 정상 철회/내부 실패·후속 취소와 SearchAgentHttp close 테스트 → 최종 경계 분류·독립 정리 DEBUG, 실제 전송 실패 중복 없음.
14. 기존 두 Throwable 로그와 logout 응답 쓰기 실패의 민감값 테스트 → 안전한 출력으로 교체, 별도 쓰기 실패 기록·전파 유지.
15. §3.1의 112개 항목에 구현 조치와 대응 테스트/유지 근거를 대조. 새 catch가 생기면 목록을 갱신하며 정상 제어를 장애로 기록하거나 소비한 내부 원인을 빠뜨리지 않는다.
16. 집중 검증 후 공유 동작의 기존 Backend CI gate 수행. 문서는 별도 작업 단위로 갱신.

각 동작마다 실패 테스트 → 최소 구현으로 진행한다. fixture는 중첩 class 없이 별도 파일을 사용한다.
ListAppender는 테스트 전후 연결·해제하며 level·logger·횟수·원인 타입·식별자·민감값을 검증한다.
원문·cause·suppressed 각각에 다른 비밀 표식을 넣고 formattedMessage와 실제 캡처 출력에 모두 없는지 확인한다.
ThrowableProxy/로그 인자에 원본 예외가 들어가지 않는지도 검증해 Logback의 원문 스택 출력 우회를 막는다.
기존 Worker 전용 단위 테스트는 세 클래스에 대해 확인되지 않아 DocumentGenerationWorkerTest·SearchAnswerWorkerTest·RecordingAudioDeletionWorkerTest를 제안한다.
Mockito는 분기와 상태 호출을 검증하며 실제 DB lock·트랜잭션이나 운영 로그 수집을 증명하지 않는다.
기존 PostgreSQL 통합 검증은 상태·재시도 회귀를 확인하는 용도다. 이번 작업에 새 스키마 통합 테스트는 필요하지 않다.
112개 catch마다 구현을 그대로 따라 쓰는 신규 테스트를 만들지 않는다. 기존 공개 동작 테스트를 확장하고
로그 소유 경계, cause 변환, 정상 제어 흐름의 의미 있는 분기를 검증한다. §3.1 항목별로 그 검증을 연결한다.
새 이름으로 제안한 MCP/codec 테스트는 아직 존재하지 않는 파일이며 실행 결과도 없다.

구현 시 확인된 명령:

```sh
cd /Users/yongtae/Desktop/knot/backend
npx ph workflow implement
./gradlew test --tests 'com.knot.backend.global.logging.*' --tests 'com.knot.backend.global.exception.*' --tests 'com.knot.backend.global.infrastructure.llm.*' --tests 'com.knot.backend.global.config.*'
./gradlew test --tests 'com.knot.backend.auth.*' --tests 'com.knot.backend.document.*' --tests 'com.knot.backend.search.*' --tests 'com.knot.backend.recording.*' --tests 'com.knot.backend.workspace.*'
./gradlew spotlessCheck test integrationTest acceptanceTest bootJar --console=plain
npx ph workflow finish implement
```

build.gradle과 Backend CI에서 명령·tag 분리를 확인했다.
integration/acceptance에는 Docker·Testcontainers가 필요하다.
Persona 구현·리뷰 보고서는 실제 구현 단계에서 작성한다.
#619에서 Persona 종료 인증의 기존 전역 상태 문제가 관측됐다. #620 구현에서는 workflow rail을 실행하며 과거 상태를 초기화하지 않는다.
커밋 요청 시 각 최종 처리 경계·cause 보존·문서 단위로 분리하고 test → feat 순서를 검토한다.

## 6. 완료 기준과 다음 작업

내부 장애 원인 타입·cause·스택 위치·작업 문맥이 안전하게 기록되고 정상 취소와 4xx를 장애로 기록하지 않아야 한다.
기존 HTTP·SSE·OAuth 응답과 실패 상태·재시도·취소 처리가 유지되고 집중 테스트와 CI gate가 통과하면 구현 완료다.
실제 호스트 관측은 별도 운영 검증이며 합성 테스트 결과로 대체하지 않는다.
§3.1 전체 항목을 구현·검증과 대조해 원인 소실 39곳의 cause 보존, 기록 보완 20곳,
경계 11곳의 책임, 유지 42곳의 이유를 설명할 수 있어야 한다.

앞선 계획 갱신 요청의 완료 범위는 #620 원격 본문·현재 구현·관련 원문·열린 PR을 재대조하고,
112개 catch 전수 조사와 이 계획의 갱신을 마치는 것이다.
그 계획 갱신 단계에서는 제품 코드·테스트·브랜치·커밋·push·Issue/PR 원격 수정을 수행하지 않았다. 후속 구현·검증 결과는 아래에 별도로 기록한다.
아래 게시 검증은 앞선 두 Issue 게시 작업의 기록이며 이번 계획 갱신에서 새 게시를 수행한 결과가 아니다.
게시 검증: status=pass, action=publish_issue, requested_action=publish_issue,
remote_write_authorized=true, contract_id=e7b7da1a3b0d27d2.
원격 본문·assignee jyt6640·흑곰·BE·BugFix 라벨을 조회했다.
선행 연결 작업: [#619 구현 계획](619-http-error-classification.md).

## 7. 구현과 검증 기록 — 2026-10-12

`be/bugfix/#620`에서 주 세션으로 구현했다. 조사 기준 112개 catch의 항목 번호와 원문 위치는 §3.1의 역사적 기준이며, 아래 결과를 현재 구현과 대조했다.

| 판정 | 대조 결과 |
| --- | --- |
| C 39곳 | 39곳 모두 원인을 생성자·변환 helper에 전달한다. 녹음 repository의 제약 분류도 원본 DataIntegrityViolationException을 전달한다. |
| L 20곳 | 최종 처리 경계의 누락·원인 없는 로그를 안전한 진단으로 보완했다. 로그아웃·녹음 회수의 원본 Throwable 출력도 교체했다. |
| V 11곳 | JWT/refresh의 감춰진 원인, MCP filter/tools, SSE async dispatch, 예약 executor·timeout cleanup의 기록 책임과 정상 종료를 검증했다. |
| U 42곳 | 공백을 정규화한 catch 본문이 현재 소스에 모두 유지됨을 대조했다. 재던짐·취소·S3 404·정리·collision retry의 기존 책임을 유지한다. |
| 새 catch 12곳 | 후보 조회 7개 경계(조회는 8곳), MCP filter 2곳·tools 2곳·예약 실행 1곳을 추가했다. 현재 production catch는 124곳이다. |

SafeExceptionDiagnostics는 최대 16개 예외·각 4개 프레임·4096글자로 타입, cause/suppressed 관계와 위치만 만든다. 순환·긴 체인·null을 검증했고 원문 메시지·SQL·외부 본문·token·storage key를 추가 로그에 넣지 않는다. 계획의 순수 진단 책임을 유지하기 위해 공통 수준 분류와 SLF4J 호출은 ServerErrorLogger로 분리했다. 새 의존성은 없다.

ServerErrorLogger는 알려진 외부 LLM·MCP·S3 실패를 WARN, 내부 오류를 ERROR, 취소·interrupt·접근 철회·용량 부족을 DEBUG/기존 무로그로 처리한다. 일반 HTTP 4xx는 무로그를 유지하고, 실패한 백그라운드 작업의 알려진 업무 오류는 WARN으로 남긴다. 내부 cause/suppressed가 있으면 4xx 포장이나 정상 제어 코드로 숨기지 않는다. DB/provider의 Broken pipe는 Servlet 쓰기 종료로 오분류하지 않는다.

검증은 ListAppender의 level·건수·logger·ThrowableProxy와 진단 문자열을 확인했다. 문서/탐색/오디오 작업의 최초 실패와 상태 저장 실패, 다음 후보·다음 poll, 녹음 INFO 요약을 확인했다. 실제 Spring MVC 초기/재연결 Controller·SseEmitter·executor·async dispatch에서 committed 200과 기존 본문, 앱 로그 1건을 확인했다. 실제 MCP SDK·WebMvcStatelessServerTransport 라우터에서 소비 오류와 재던짐 오류의 기존 결과 형식 및 앱 로그 1건을 확인했다.

예외 원인 보존으로 시작 실패의 최하위 원인이 실제 IOException이 되어 LlmConfigTest를 설정 오류 코드와 IO 원인 동시 검증으로 바꿨다. LmStudio의 실제 timeout·S3 삭제의 SDK 실패·4종 커서 변환·녹음 제약 분류에서도 원인을 검증했다. 전체 인수 검증에서 발견한 기존 AuthRefreshAcceptanceTest의 랜덤 알파벳 부족 실패는 UUID를 고정 12개 알파벳으로 변환하도록 테스트 준비 코드만 보완했다.

최종 제품 gate: PH_BEARSHELL_TIMEOUT_MS=1200000 npx ph bearshell --shell './gradlew spotlessCheck test integrationTest acceptanceTest bootJar --console=plain' PASS, BUILD SUCCESSFUL 4m3s. XML 단위1314·통합608·인수537=2459, 실패/오류/skip0. git diff --check PASS. 앞선 마지막 일괄 검증도3m25s PASS였으며 refresh 로그 helper 분리를 포함한 위 실행으로 재확인했다.
Persona 종료 인증: 두 `report-filled`는 성공했다. `workflow finish implement`는 exit 1이며 `verification-failed`, `report-coverage-missing`, `java-role-read-coverage-missing`, `convention-toolchain-missing`, `workflow-loop-state-stale`, `pending-ticket`을 보고했다. 실제 Gradle 전체 검증 2,459개와 Spotless·bootJar 성공을 이 전역 인증 성공으로 해석하지 않는다. 기존 이력·pending은 보존했다.

검증 범위는 로컬 코드·실제 라이브러리·PostgreSQL/Testcontainers·테스트 요청이다. 운영 host/proxy·로그 수집 도구·외부 모델의 실제 동작을 관측한 결과는 아니다. 설치된 MCP SDK 2.0.0의 DefaultMcpStatelessServerHandler는 재던진 예외 메시지를 기존 JSON-RPC error에 사용한다. 이번에는 그 응답 정책을 변경하지 않았으며 SDK 전체의 로그/오류 메시지 비노출을 보증하지 않는다.

## 8. PR #626 CI의 SSE 테스트 실행 순서 보완 — 2026-10-12

[실패한 Backend CI](https://github.com/woowacourse-teams/2026-knot/actions/runs/38250139213/job/114814001096)는 단위 1,314개 중 SearchSseLoggingMvcTest의 재연결 사례 1개가 isCommitted 검사에서 실패했다. MockMvc 요청 반환과 재연결 worker의 첫 snapshot 전송 사이에는 완료 순서가 보장되지 않았고, 테스트가 먼저 검사하면 아직 응답이 시작되지 않았다.

단일 executor의 선행 작업을 latch로 막아 worker가 늦게 시작되는 조건에서 기존 검사의 실패를 같은 위치로 재현했다. 수정 후에는 worker를 해제하고, 실제 snapshot 전송 다음의 두 번째 snapshot 조회에 도달했음을 latch로 확인한 뒤 committed 상태와 첫 이벤트 본문을 검사한다. 예외 주입은 해당 확인 이후에 해제하며, 기존 상태·본문 유지·ERROR 1건·민감값 비노출 검사를 유지했다. production과 CI workflow는 변경하지 않았다.

집중 테스트 2개 PASS 후 test task를 --rerun으로 10회 실행해 20개 사례가 모두 통과했다. 최종 ./gradlew test spotlessCheck bootJar --console=plain도 성공했으며, 전체 단위 1,314개 실패·오류·skip 0개다. bootJar는 production 무변경으로 UP-TO-DATE였다. 기존 통합·인수 통과 기록과 구분하며 이번 push의 원격 CI 결과는 별도 확인한다.
