# #750 백엔드·AI 전사 로그 검증 근거

#749의 공통 로거에 의존한다. AI 모듈은 기존 attemptId API를 유지하며 로그만 추가한다. 결과 수신과 DB 커밋, 응답 유실과 확정 실패를 구분한다.

## 계약과 테스트 연결

| 요구 | 근거 |
| --- | --- |
| 제출 응답 유실·같은 ID 조회·확인된 NOT_FOUND 외 중복 제출 방지 | RecordingTranscriptionLoggingTest + 기존 ServiceTest |
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
| DB | 스키마·저장 정책 유지, 자동 접수의 workspaceId 조회 추가, 커밋·rollback·과거 시도 검증 |
| JAVA | 명시 타입·다중 인자 배치·Spotless |
| TEST | GWT와 비동기 latch, 기존 회귀 assertion 보존 |
| AI | 작성·독립 리뷰 분리, 원문·URL·예외 메시지 제외 |
| DELIVERY | #750, 기반 #749를 먼저 병합하는 의존 PR |

00~08 원문 hash gate와 승인 Java·API 규칙의 상태는 #749 검증 문서를 따른다. API·DB 정책 변경과 새로운 Spring 기본값 적용은 없으며 새 ADR이 필요하지 않다. 모델 시간은 Whisper 단독 연산이 아니라 manifest 작성과 통합 runner 대기를 포함한다.

2026-10-18 최신 develop과 #749 통합 뒤 전체 컨벤션 감사는 1,233건이다. 그중 1,232건은 기존 기준선이다. 새 Map<String, String> 필드 한 개를 JAVA-FIELD-ONE이 복수 선언으로 오탐한다. 해당 선언은 필드 한 개임을 수동 확인했다. 감사기를 바꾸거나 검사를 생략하지 않고 이 차이를 공개하며, wrapper의 감사 중단 뒤 Gradle 전체 검증을 직접 실행한다. 최종 테스트·CI 결과는 PR 본문을 따른다.

최신 서비스의 대기열·lease/owner fencing·응답 유실 복구·성공 snapshot 재채택과 PCM 화자 설정 전달을 보존했다. 이전 BUSY 즉시 실패와 저장 실패 즉시 terminal 전환을 되살리지 않았다. 로그 테스트도 QUEUED 상태와 lease 기반 실행 문맥에 맞추었고, AI 성공 로그는 DB 저장 전에 확정한다. 최초 통합 시험은 Docker 소켓이 없어 시작하지 못했으며 Docker 복구 후 다시 실행했다.

2026-10-18 17:00 KST 검증: backend의 `spotlessCheck test integrationTest acceptanceTest bootJar --continue`가 6분 9초에 통과했다. 단위 1,741건, 통합 787건, 인수 586건에서 실패·오류는 0건이며 통합의 실제 S3 opt-in 1건은 제외됐다. ai-server의 `spotlessCheck test bootJar`도 통과했고 124건 중 실패·오류 0건, 실제 모델 HTTP opt-in 1건은 제외됐다. 저장소 governance 10건도 통과했다. 이는 로컬 회귀 검증이며 운영 AI 배포와 실제 모델을 이용한 전체 흐름 검증은 별도다.
