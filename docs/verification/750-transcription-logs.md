# #750 백엔드·AI 전사 로그 검증 근거

#749의 공통 로거에 의존한다. AI 모듈은 기존 attemptId API를 유지하며 로그만 추가한다. 결과 수신과 DB 커밋, 응답 유실과 확정 실패를 구분한다.

## 계약과 테스트 연결

| 요구 | 근거 |
| --- | --- |
| 제출 응답 유실·같은 ID 조회·재제출 없음 | RecordingTranscriptionLoggingTest + 기존 ServiceTest |
| AI 성공과 DB 실패 구분, Worker MDC 비상속 | RecordingTranscriptionLoggingTest |
| 접수·재시도·저장·과거 시도·실제 rollback | RecordingTranscriptionStateIntegrationTest |
| AI 단계 순서·실패 위치·정리 후 결과 공개 | TranscriptionLoggingTest·HeadlessTranscriptionEngineTest |
| 안전한 필드·오류 코드·민감값 제외·polling 생략 | 위 로그 캡처 테스트의 KVP/JSON 검증 |

## 컨벤션 적용 장부

| 규칙군 | 적용 |
| --- | --- |
| STACK | 기존 Java25·SpringBoot4.1·Gradle·Spotless 유지 |
| LIB | 의존성·버전 무변경 |
| LAYER | 기존 접수·Worker·DB·AI 엔진 경계에 관측 추가 |
| MODEL | 기존 상태·슬롯·cleanup·오류·재시도 정책 보존 |
| API | requestId 전파 없이 기존 attemptId 사용 |
| DB | 스키마·쿼리 무변경, 커밋·rollback·과거 시도 검증 |
| JAVA | 명시 타입·다중 인자 배치·Spotless |
| TEST | GWT와 비동기 latch, 기존 회귀 assertion 보존 |
| AI | 작성·독립 리뷰 분리, 원문·URL·예외 메시지 제외 |
| DELIVERY | #750, 기반 #749를 먼저 병합하는 의존 PR |

00~08 원문 hash gate와 승인 Java·API 규칙의 상태는 #749 검증 문서를 따른다. API·DB 정책 변경과 새로운 Spring 기본값 적용은 없으며 새 ADR이 필요하지 않다. 모델 시간은 Whisper 단독 연산이 아니라 manifest 작성과 통합 runner 대기를 포함한다.

전체 컨벤션 감사 기준선 1,215건은 기존 코드다. 새 Map<String, String> 필드 한 개를 JAVA-FIELD-ONE이 복수 선언으로 오탐한다. 해당 선언은 필드 한 개임을 수동 확인했다. 감사기를 바꾸거나 검사를 생략하지 않고 이 차이를 공개하며, wrapper의 감사 중단 뒤 Gradle 전체 검증을 직접 실행한다. 최종 테스트·CI 결과는 PR 본문을 따른다.
