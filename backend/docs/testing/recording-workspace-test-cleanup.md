# 녹음·워크스페이스 테스트 정리

2026-11-11. 제품 동작과 CI 필수 검증을 유지하면서 중복 테스트를 합치고 PostgreSQL 준비 비용을 줄인다.

## 삭제 기준과 남는 검증

같은 업무 흐름·기술 경계에서 검증이 겹치는 경우만 삭제했다. 서로 다른 권한, 트랜잭션,
동시성, 입력 검증, 외부 실패 조건은 테스트 수를 줄이기 위해 합치지 않았다.

| 삭제한 테스트 | 남는 검증과 보완 |
| --- | --- |
| `RecordingStartControllerTest`의 `start_failure_sameRequestChangedInput`, `start_failure_activeRecordingAlreadyExists`, `start_failure_workspaceNotFound`, `start_failure_workspaceAccessDenied` | `RecordingStartAcceptanceTest`가 실제 서비스·DB·보안 필터를 통과해 같은 409·404·403과 오류 코드를 검증한다. Controller의 입력 파싱·검증과 성공 응답 테스트는 유지한다. |
| `RecordingPauseControllerTest.pause_failure_controlDenied` | `RecordingPauseAcceptanceTest`의 다른 탭 제어 거절에 오류 메시지 assertion을 옮겼다. |
| `RecordingResumeControllerTest.resume_failure_controlDenied` | `RecordingResumeAcceptanceTest`의 다른 탭 제어 거절에 오류 메시지 assertion을 옮겼다. |
| `RecordingPauseIntegrationTest.pause_success_endAfterPauseKeepsElapsedTime` | `RecordingEndIntegrationTest.end_success_pausedRecordingKeepsAccumulatedTime`이 실제 시작→일시정지→종료를 수행한다. 5초 이상 누적, 일시정지 시각 보존, 종료 시각·사유·DB 상태를 함께 확인한다. |
| `WorkspaceControllerTest`의 4개 테스트 | `WorkspaceAcceptanceTest`와 `WorkspaceLeaveAcceptanceTest`가 실제 생성·탈퇴 및 오류 응답을 검증한다. 두 오류 메시지 assertion을 옮겼다. mock 서비스 호출 확인만 별도로 반복하지 않는다. |
| `WorkspaceServiceIntegrationTest.create_failure_membershipSaveRollsBackWorkspace` | `WorkspaceCreationLoggingIntegrationTest.membershipFailureRollsBackWorkspaceAndSuccessLog`에 구체적인 DB 예외와 멤버십 0건 assertion을 합쳤다. Workspace rollback과 잘못된 성공 로그 부재도 계속 검증한다. |

삭제·통합한 테스트는 12개다. 별도로 DB 실행 기반의 격리·생명주기를 검증하는 통합 테스트 1개를 추가했다.

## DB 실행 구조

녹음·워크스페이스의 67개 Spring 테스트 클래스에서 `TestcontainersConfiguration` 대신
`@IsolatedPostgresTest`를 사용한다. 기존 `SharedPostgresTestSupport`의 JVM 단위 PostgreSQL
프로세스를 재사용하고, `IsolatedPostgresInitializer`가 **새 Spring 컨텍스트마다 새 DB**를 만든다.

- 같은 캐시 컨텍스트에서는 같은 DB를 쓰며 기존 테스트별 cleanup/rollback을 유지한다.
- 서로 다른 컨텍스트의 DB는 분리한다. 스케줄러나 비동기 작업이 다른 컨텍스트의 데이터를 읽지 않는다.
- `@DirtiesContext`로 새 컨텍스트를 만들면 새 DB를 받는다. 기존 컨텍스트를 닫아도 공유 컨테이너는 종료하지 않는다.
- JPA 슬라이스는 `spring.test.database.replace=NONE`으로 실제 PostgreSQL을 사용한다. Flyway는 각 DB에 그대로 적용된다.
- 컨테이너는 Spring Bean으로 등록하지 않는다. 종료 책임은 기존 Testcontainers/Ryuk 생명주기에 둔다.
- 컨텍스트 캐시 상한, worker 수, 병렬 실행, CI job 분리와 필수 gate는 변경하지 않았다.

`IsolatedPostgresInitializerIntegrationTest`는 한 DB에 만든 테이블이 다른 컨텍스트 DB에
없는지, 한 컨텍스트를 닫아도 나머지 DB에 연결되는지, 재생성 시 다른 URL을 받는지 확인한다.

Migration 이력 전용 테스트의 독립 컨테이너와 실제 S3·AI opt-in 검증은 유지한다.
파일명이나 `@DirtiesContext`, `Thread.sleep`의 존재만으로 테스트를 삭제하지 않았다.

## 실행과 비교

저장소 `backend/`에서 실행한다. Docker와 Java 25가 필요하다.

```sh
./gradlew test integrationTest acceptanceTest --rerun-tasks --console=plain
./gradlew spotlessCheck bootJar
```

`--rerun-tasks`는 기존 성공 결과를 재사용하지 않고 테스트를 실행하기 위한 옵션이다.
전체 명령의 wall time과 JUnit XML의 클래스별 시간 합계를 구분한다. 초기 daemon·컴파일·이미지
준비 비용과 호스트 상태 때문에 단일 실행의 시간 차이를 CI 성능 보장으로 해석하지 않는다.

### 로컬 측정 결과

아래 전후 비교의 기준은 `75d9f161`이다. PR 준비 시 최신 `develop`의 `6b50beac`에 변경을
이식했다. 그 사이 제품 기능과 테스트가 추가됐고 통합 테스트가 두 JVM으로 병렬화됐으므로,
이 표의 테스트 수와 시간은 PR 기준의 전후 비교로 사용하지 않는다.

| 항목 | 변경 전 | 변경 후 |
| --- | ---: | ---: |
| 전체 명령 wall time | 401.67초 | 356.89초 |
| 단위 테스트 수 / XML 시간 합계 | 2,123 / 13.21초 | 2,113 / 12.56초 |
| 통합 테스트 수 / XML 시간 합계 | 981 / 240.24초 | 980 / 216.34초 |
| 인수 테스트 수 / XML 시간 합계 | 647 / 107.08초 | 647 / 105.57초 |
| 녹음 통합 / 인수 XML 시간 합계 | 58.98 / 19.32초 | 40.74 / 17.88초 |
| 워크스페이스 통합 / 인수 XML 시간 합계 | 18.90 / 13.17초 | 15.49 / 11.03초 |

전체 명령은 약 11.1%, 두 도메인의 통합·인수 XML 시간 합계는 약 22.9% 감소했다.
변경 전후 모두 실패는 0건이고 단위 2건·통합 4건의 기존 skip을 유지했다.
실제 실행된 변경 후 테스트는 3,734건이고 skip을 포함한 보고서 합계는 3,740건이다.

같은 로컬 환경에서 순서대로 각 1회 실행한 비교다. 변경 전에는 Gradle daemon 시작도
포함되므로 전체 차이 전부를 코드 변경의 효과로 단정하지 않는다. CI 실측·반복 측정과
전체 40% 단축 목표는 미달성이다. 나머지 도메인의 컨텍스트 기동·Flyway·데이터 준비 비용은 남아 있다.

원본 실행 로그와 XML은 로컬 `/tmp/knot-test-baseline.log`, `/tmp/knot-test-after.log`,
`/tmp/knot-test-baseline-results/`, `/tmp/knot-test-after-results/`에 보존했다.
임시 경로이므로 영구 CI 증거로 취급하지 않는다.

### 남은 테스트의 결함 탐지 확인

제품 소스에 아래 결함을 임시 주입하고 해당 테스트만 실행했다. 두 테스트가 각각 의도한
assertion에서 실패했으며 제품 소스는 원문으로 복구했다.

| 임시 결함 | 남은 테스트에서 관측한 실패 |
| --- | --- |
| `WorkspaceService.create`의 `@Transactional` 제거 | `WorkspaceCreationLoggingIntegrationTest.membershipFailureRollsBackWorkspaceAndSuccessLog`: Workspace 0건을 기대했으나 1건 잔존 |
| `RECORDING_CONTROL_DENIED`를 FORBIDDEN 대신 INVALID_INPUT으로 분류 | `RecordingPauseAcceptanceTest.pause_failure_otherTab`: HTTP 403을 기대했으나 400 반환 |

이는 두 대표 결함에 대한 확인이며 전체 mutation coverage를 측정한 것은 아니다.
복구 후 같은 두 테스트와 `spotlessCheck`, `bootJar`가 모두 통과했다. `git diff --check`도
통과했고 `src/main`에는 최종 변경이 없다. 문서·탐색 도메인 정리는 후속 범위다.

### 이슈 #1119 작업의 최신 develop 재검증

이슈 #1119 게시 준비 시 `6b50beac` 위로 테스트 변경만 이식했다. 기존 통합 테스트
`maxParallelForks=2` 설정을 유지하고 아래 명령을 새 작업 디렉터리에서 실행했다.

```sh
./gradlew spotlessCheck test integrationTest acceptanceTest bootJar --console=plain
```

단위 2,360건(skip 6), 통합 1,080건(skip 10), 인수 716건(skip 0), 실패 0건이었다.
실행된 4,140건과 포맷·JAR 빌드가 통과했고 Governance 검증기 테스트 24건도 통과했다.
이 실행은 최신 기준에서의 호환성 검증이며 앞의 성능 비교에 섞지 않는다.
