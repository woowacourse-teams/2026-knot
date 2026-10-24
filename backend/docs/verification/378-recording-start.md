# #378 녹음 시작 구현 검증

2026-10-01 `develop` `7a517a41278b7f2ea2fd94eb92ab0947fee4103c`에서 분기한
`be/feature/#378`의 로컬 구현을 검증했다. 작업 경로는 `/Users/lhs/Desktop/knot/.worktrees/issue-378`이다.
이 구현 검증 시점에는 커밋·push·PR 생성 전이며 원격 CI 결과가 없다. 후속 게시와 CI 결과는 실제 PR에서 확인한다.

## 근거와 적용 상태

- [Issue #378](https://github.com/woowacourse-teams/2026-Knot/issues/378)의 멱등 시작·탭 제어 증명·같은 탭 복구 계약을 적용했다.
- BE 위키의 녹음 시작 API·도메인·STT 요구사항은 후속 Issue 계약으로 보완했다. 로컬 내보내기 사본 3개를 갱신했으며 실시간 Notion 반영·팀 승인은 미확인이다.
- `/Users/lhs/Desktop/knot/노션/knot/백엔드`의 00~08 원문 hash gate가 통과했다. 01~08 개별 문서가 기준이며 합본은 참고다.
- 사용자 승인 Java·API 컨벤션 배포본을 적용했다. API별 문서 인터페이스는 기존 저장소 패턴을 따르며, 그 구조에 관한 ADR을 Accepted로 간주하지 않았다.
- Spring 기본값: Controller는 HTTP 변환, Application은 유스케이스와 트랜잭션, Domain은 상태·시간 불변식, Infrastructure는 JPA·해시 구현을 맡는다. `Clock`은 주입한다.
- 복구·만료 ADR은 저장소 생성기로 materialize한 `Proposed` 문서다. 시작 API의 구현이 제품 전체 복구·만료를 완료했다는 뜻은 아니다.

## 완료 조건과 증거

| 조건 | 직접 증거 |
| --- | --- |
| 최초 시작 201, 본인 활성 녹음 전역 하나 | Service·PostgreSQL 동시 시작·Acceptance 테스트 |
| 동일 키 재시도 200, ID·시작 시각 유지 | Service·Controller·Acceptance 및 동일 키 동시 요청 테스트 |
| 같은 키의 Workspace·탭·증명 변경 409 | Service·Acceptance 테스트 |
| ENDED 재시도는 재활성화하지 않음 | Domain·Service·Acceptance 테스트 |
| PAUSED 시간 제외와 종료 시간 고정 | Domain 15개 테스트와 저장 왕복 테스트 |
| 인증·CSRF·소속 검사 및 정보 비노출 | Controller·JWT/CSRF Acceptance·OpenAPI 테스트 |
| 원문 대신 SHA-256 저장, DTO 문자열 마스킹 | Hasher·Controller·Acceptance 테스트 |
| FK·부분 Unique·키 이력·rollback | Repository·Service 통합 및 V14→V15 upgrade 테스트 |
| 입력 오류 400, 반환값 검증 오류 500 | GlobalExceptionHandler·Controller·Acceptance 테스트 |

## 실행 결과

`./gradlew spotlessApply spotlessCheck test integrationTest acceptanceTest bootJar --no-parallel`
이 성공했다. 새 JUnit XML 기준 단위 225개·통합 71개·인수 118개, 총 414개이며 실패·오류·건너뜀은 모두 0이다.
그중 recording 패키지는 단위 32개·통합 12개·인수 14개다.

`verify_backend.py --workspace /Users/lhs/Desktop/knot --project /Users/lhs/Desktop/knot/.worktrees/issue-378/backend`
도 통과했다. 변경 범위 자동 컨벤션 검사 파일 30개에서 위반은 없었다. `git diff --check`도 통과했다.
독립 코드 리뷰에서 차단 결함을 발견하지 못했다.

`verify_backend.py`의 `--full`은 전체 소스 컨벤션 검사에서 다음 기존 위반 5건으로 중단됐다.
전체 Gradle task는 위 명령으로 별도 실행해 성공했으므로 full wrapper 성공으로 보고하지 않는다.

| 기존 파일 | 규칙 |
| --- | --- |
| `auth/infrastructure/github/GithubOAuth2User.java` | `JAVA-FIELD-ONE` |
| `auth/infrastructure/github/GithubOAuth2UserService.java` | `JAVA-FIELD-ONE` |
| `auth/presentation/handler/OAuth2AuthenticationSuccessHandler.java` | `LAYER-PRESENTATION-DEPENDENCY` |
| `src/main/resources/db/migration/V1__create_members.sql` | `DB-FK-DELETE` |
| `auth/presentation/handler/OAuth2AuthenticationSuccessHandlerTest.java` | `LAYER-PRESENTATION-DEPENDENCY` |

## 10개 규칙군 적용 장부

| 규칙군 | 적용 및 확인 |
| --- | --- |
| STACK | 기존 Spring·PostgreSQL·JPA·Flyway·Springdoc·JUnit·Testcontainers 유지. 새 의존성 없음 |
| LIB | 기존 Lombok 생성자와 JDK SHA-256 사용. Application은 해시 Port에 의존 |
| LAYER | 요청/응답과 Command/Result 분리. Workspace→Member 잠금 뒤 저장하는 public 유스케이스에 트랜잭션 |
| MODEL | 팩토리에서 ID·키·시간 검증. RECORDING/PAUSED/ENDED 전이와 종료 불가역성·실제 녹음 구간 합산 |
| API | v1 URI, 201/200/409 의미, UTC Instant, 공통 한글 오류·fieldErrors. 제어값·DB 상세 비노출 |
| DB | V15 신규 테이블, RESTRICT FK, 회원/요청 키 Unique와 활성 회원 부분 Unique, 상태별 시간 Check |
| JAVA | 120자·복수 인자 줄바꿈·JPA 필드 간격 확인. 해시 getter·Entity toString 없음 |
| TEST | GWT 하나의 행위. Domain·Service·Controller·PostgreSQL·인수 범위와 경합·rollback·upgrade 검증 |
| AI | 상태·권한·증명·동시성 독립 리뷰. 입력 원문 로그와 예외 누출 검사. 실제 파일 보존을 주장하지 않음 |
| DELIVERY | #378 범위·브랜치·API 문서·Proposed ADR 연결. 원격 PR/CI는 미실행, 기본 checkout 변경 4개 보존 |

## 예외와 후속 경계

Eclipse formatter가 Request record의 Validation·Schema annotation을 한 줄로 합쳐 120자를 초과했다.
기존 문서 인터페이스 패턴처럼 `RecordingStartRequest`의 annotation/record 선언과 `RecordingStartApi`의
중첩 문서 annotation에만 formatter 제외를 두고 직접 줄바꿈했다. 메서드 본문은 자동 포맷한다.
이 결과에서 Spotless와 변경 범위 컨벤션 검사를 모두 통과했다.

전체 regression에서 기존 Flyway 테스트의 최신 버전 V14 고정 assertion이 실패했다.
V14 기존 계약과 신규 V15 초기 설치·upgrade를 별도 테스트로 나눴으며 기존 migration은 수정하지 않았다.
입력 검증 500 재현과 수정은 Obsidian의 `경로 제약과 요청 본문의 Spring 메서드 검증`에 기록했다.

다음 [#379](https://github.com/woowacourse-teams/2026-Knot/issues/379)는 이 브랜치의 Domain·Repository를
재사용해 현재 활성 조회를 구현할 수 있다. 조회는 저장값·생존 신호·제어권을 바꾸지 않아야 한다.
[PR #411](https://github.com/woowacourse-teams/2026-Knot/pull/411)은 아직 OPEN이며,
병합 시 활성 참여·삭제 Workspace 필터와 미배포 V15 번호 충돌을 함께 조정하고 다시 검증한다.
종료·복구·만료 HTTP, 탈퇴 시 저장 없는 폐기, 오디오 업로드·STT·문서 생성·브라우저 연동은 후속 범위다.
