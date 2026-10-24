# #761 Workspace 로그 검증 근거

사용자는 recordingId 단독으로 탈퇴 폐기 원인과 요청을 찾고, Workspace 조회도 성공·실패·시간을 남기도록 확인했다. 사용자 확인으로 인터뷰 완료, 독립 계약 검토 Pass다. 기존 관측 경계를 확장하는 작업이며 새 제품 정책이나 ADR은 추가하지 않는다.

| 완료 조건 | 검증 근거 |
| --- | --- |
| 생성·조회·마지막 선택의 결과와 실패 단계 | WorkspaceServiceLoggingTest, WorkspaceCreationLoggingIntegrationTest |
| 실제 트랜잭션 commit·rollback·UNKNOWN와 시간 | WorkspaceLogTest의 JDBC transaction manager, 실제 PostgreSQL 로그 통합 테스트 |
| 녹음 ID에서 탈퇴 요청·이유 연결 | WorkspaceDepartureLoggingAcceptanceTest의 실제 HTTP 3종 경로 |
| 문서 실패·둘째 녹음 실패·재요청·대상 보존 | WorkspaceDepartureLoggingIntegrationTest와 기존 탈퇴·승계 테스트 |
| JSON/KVP·안전한 진단·요청 ID 캡처·관측 장애 격리 | WorkspaceLogTest |

## 출처와 컨벤션

00~08 원문 hash gate와 사용자 승인 Java/API 규칙을 적용한다. Java는 팀 Notion 동기화 완료, API 초안은 동기화 대기이며 이번 변경은 공개 API를 수정하지 않는다. Notion 원문 캐시는 현재 없어서 최신 원문 상태를 주장하지 않는다. 저장소 MVP 기준·기존 ADR·실제 구현/테스트와 사용자 합의를 근거로 제품 동작을 유지한다. BE 위키의 마지막 조회 결정은 확정, 사용자/Workspace 데이터 격리 자료는 과거 Notion 연동 초안으로 구분했다.

| 규칙군 | 적용 |
| --- | --- |
| STACK | 기존 Java25·SpringBoot4.1·Gradle·Spotless |
| LIB | 의존성·버전·빌드 설정 무변경 |
| LAYER | Application 관측, 기존 트랜잭션과 도메인 의존 유지 |
| MODEL | 도메인·DTO·정책 무변경 |
| API | HTTP 상태·응답·오류·권한과 기존 재요청 동작 유지 |
| DB | migration·쿼리 조건 무변경, 기존 원자적 처리 유지 |
| JAVA | 명시 타입·필드·다중 인자 배치와 formatter |
| TEST | GWT, 실제 PostgreSQL·HTTP와 실패·롤백 검증 |
| AI | 계약 도전·작성·독립 diff 리뷰 분리, 민감값 제외 |
| DELIVERY | #761, be/feature/#761, 실제 diff·검증과 Governance |

Spring은 기존 TransactionSynchronization을 사용하며 제품 정책을 새 기본값으로 채우지 않는다. 로그 전달·보존은 운영 검증과 분리한다. 전체 정적 감사는 기준선과 현재 모두 1,221건이며 신규 진단은 0건이다. 공통 wrapper가 기존 감사에서 중단되어 Gradle 검증을 직접 실행한다. 최종 실행 결과는 PR 본문에 기록한다.

## 전체 실행과 분기 규칙 보완

2026-10-17 KST, `./gradlew spotlessCheck test integrationTest acceptanceTest bootJar`가 4분 57초에 성공했다. XML 기준 단위 1,564·PostgreSQL 통합 689·인수 570건의 실패·오류·생략은 0건이다. 신규 로그 계약 검증은 단위 20·통합 10·실제 HTTP 인수 3건이다. Governance 단위 10건, 독립 계약·최종 구현 리뷰, diff 공백 검사를 통과했다.

전체 회귀에서 ID 없는 기존 녹음 fixture의 언박싱 예외를 발견해 nullable ID를 허용하고 업무 실행·폐기 롤백 검증을 추가했다. 기본 checkout은 기존 명명·패키지 차이를 보존해 반영했으며 관련 단위 39·통합 10·인수 3건과 Spotless·bootJar가 통과했다. 이는 로컬 검증이며 실제 운영 배포·수집·보존 검증은 아니다.

PR 리뷰에서 자동 감사가 탐지하지 못한 삼항·else·중첩 조건문을 확인해 규칙별 조기 반환 메서드와 명시적 CSV 기대값으로 정리했다. 기존 33개 로그 계약과 결과 분류는 유지하며, 관련 단위 39·통합 10·인수 3건과 독립 동등성 검토를 다시 통과했다. 분기 정리 후 전체 명령의 재실행과 새 HEAD의 CI 결과는 PR 본문에서 확인한다.

Scope 첫 필드 앞 공백 제안은 적용하지 않았다. CONTRIBUTING 앞부분과 후반이 충돌하며, 사용자 승인 Java 규칙(`docs/07-conventions/backend-java-code-conventions-notion-draft.md` 2절)의 첫 필드 앞 빈 줄 금지를 우선한다. 실제 컨벤션 감사도 같은 규칙을 사용한다.


## 2026-10-18 최신 develop 통합 검증

PR HEAD `f853013d`에 `develop ca190aeb`를 로컬에서 통합했다. 두 서비스의 충돌을 해소했고 커밋·푸시·PR 병합은 수행하지 않았다.

- `WorkspaceService.create`는 develop의 `long` 반환을 유지하면서 생성·OWNER 저장과 커밋 경계 로그를 보존한다.
- 탈퇴는 기존 녹음 폐기 → 멤버십 flush → `audioCleanup.afterDeparture` → 문서 보관 순서를 유지한다. 기존 오디오 정리 호출을 `schedule_audio_cleanup` 단계로 기록하며, `prepared`는 정리 상태 예약의 반환을 뜻한다. S3 객체 삭제 완료를 뜻하지 않는다.
- 원문 00~08 hash gate, 관련 업무 로그·탈퇴·생성 흐름과 사용자 승인 Java/API 컨벤션을 다시 확인했다. 위 10개 규칙군의 적용 범위는 그대로다. 공개 API·권한·잠금·DB·정리 정책과 의존성을 바꾸지 않았다.
- 좁은 검증은 단위 51·PostgreSQL 통합 25·HTTP 인수 3건, Governance 단위 10건과 실제 PR 형식 검사를 통과했다.
- 전체 자동 감사는 develop과 통합 소스가 각각 1,247건이며, 상대 경로·규칙·행·메시지를 대조한 신규 진단은 0건이다. 충돌을 해소한 두 서비스의 진단도 0건이다. 변경 범위 감사는 develop 통합 변경까지 포함한 기존 진단 177건으로 실패했다. `verify_backend.py --full`이 전체 감사에서 중단되어 Gradle 검증을 직접 실행했다. 전체 감사 통과로 보고하지 않는다.

- 전체 `./gradlew spotlessCheck test integrationTest acceptanceTest bootJar`는 7분 17초에 성공했다. XML 기준 단위 1,771·통합 802·인수 589건에서 실패·오류는 0건이다. 통합 중 실제 AI 3건과 실제 S3 1건은 별도 opt-in으로 생략됐고, 단위·인수의 생략은 0건이다.
- 로컬 통합 검증과 원격 PR 상태는 구분한다. 새 통합 커밋의 원격 CI·팀 재검토, 실제 운영 배포·수집·보존·S3 정리 실행은 이번 검증 범위에 포함하지 않았다.
