# #749 녹음 제어·업로드 로그 검증 근거

범위는 녹음 제어·업로드와 공통 로거다. 전사 확장은 #750에서 구현하며, 공개 API·도메인·DB·재시도 정책을 바꾸지 않는다. 이 문서는 배포 증거가 아니다.

## 계약과 테스트 연결

| 요구 | 근거 |
| --- | --- |
| 생성·재요청·제어·자동 종료·업로드 구분 | RecordingStart/Control/Upload Service와 lifecycle/upload 로그 테스트 |
| 커밋 뒤 성공, rollback·커밋 미확정 구분 | RecordingLogTest의 JDBC commit/rollback/commit 예외, PostgreSQL lifecycle 로그 통합 테스트 |
| 구조화 필드·레벨·시간·개인정보 제외 | RecordingLogTest의 native KVP/JSON·requestId 캡처·오류 코드·진단 검증 |
| 정상 heartbeat 생략 | RecordingLifecycleLoggingIntegrationTest |

## 컨벤션 적용 장부

원문 00~08 hash gate 통과. 팀 컨벤션 02·03·05·06·07, 승인 Java 규칙과 기존 API 규칙을 적용한다. Java 규칙은 팀 Notion 동기화 완료, API 초안은 동기화 대기이며 이번 변경은 API 계약을 수정하지 않는다.

| 규칙군 | 적용 |
| --- | --- |
| STACK | 기존 Java25·SpringBoot4.1·Gradle·Spotless 유지 |
| LIB | 의존성 추가·수정 없음 |
| LAYER | application 관측, 기존 트랜잭션 애노테이션 유지 |
| MODEL | 엔티티·업무 불변식·DTO 유지 |
| API | 공개 계약 유지, HTTP MDC와 멱등 입력 ID 구분 |
| DB | migration·쿼리 무변경, 실제 트랜잭션 종료 관측 |
| JAVA | 명시 타입·필드·다중 인자 배치·Spotless |
| TEST | GWT·정상·재요청·실패·커밋 경계 검증 |
| AI | 작성과 독립 리뷰 분리, 민감값 제외 검증 |
| DELIVERY | #749 및 후속 #750, 이슈별 브랜치·PR·Governance |

Spring의 기존 TransactionSynchronization을 사용하며 제품 정책을 새 기본값으로 정하지 않는다. ADR672의 관측 구현 범위를 보완하므로 새 ADR은 필요하지 않다. 자동 종료 사실에는 시간을 붙이지 않는다.

전체 컨벤션 감사의 기존 기준선은 1,215건이다. 공통 verify_backend.py --full은 이 감사에서 중단되므로 Gradle 전체 검증을 직접 실행한다. 이 PR 변경 Java의 신규 진단은 없으며 기존 코드의 위반을 이 PR에서 고치지 않는다. 테스트·빌드의 실제 최종 결과와 CI 상태는 PR 본문에서 확인한다.
