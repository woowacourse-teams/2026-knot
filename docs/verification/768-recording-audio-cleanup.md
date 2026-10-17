# #768 녹음 오디오 폐기와 보관 정리 검증

## 구현 범위

- PCM 시작 트랜잭션에 FK 없는 독립 tombstone을 기록한다. 부모 물리 삭제 후에도 서버 소유 recording prefix를 추적한다.
- RAW는 검증된 최종 WAV의 DB 채택 후에만 정리한다. 폐기·소속 상실·내용 없음·보관 만료는 ALL로 승격한다. 기존 retention scheduler·executor·deletion worker를 공유한다.
- ALL에서 stream·미완료 finalization job과 업로드 접근을 닫는다. `revoked_at`은 영구 접근 철회, `deleted_at`은 실제 객체 삭제 이력이다. 탈퇴 후 재가입이 retry·외부 POST 준비·늦은 AI 결과 채택을 재허용하지 않는다. READY의 품질 이력은 유지한다.
- 실제 S3 version과 delete marker를 삭제하고 empty 결과 뒤에도 tombstone을 재확인한다. 진행 중 PUT의 완료를 증명하지 못하므로 성공한 tombstone도 제거하지 않는다.
- 재시작 임시 WAV 정리는 현재 사용자·0600·NOFOLLOW·PID/시작 시각으로 이전 프로세스 소유가 증명되는 파일만 처리한다.

운영 정책·설정·조회 SQL은 [정리 운영 문서](../operations/recording-audio-cleanup.md)에 있다.

## 완료 조건 연결

| 조건 | 구현·검증 근거 |
| --- | --- |
| R5-A1 채택 전 원본 보존 | CleanupIntegrationTest의 FAILED→DORMANT, READY→RAW. R3 채택 rollback 검증과 함께 확인 |
| R5-A2 폐기 경합·재활성화 차단 | `departureCommitRejectsFinalizerWaitingOnWorkspaceLock`: 탈퇴 TX가 Workspace 잠금을 보유하고 ALL을 먼저 예약한다. 다른 TX의 WAV 채택이 `pg_blocking_pids`로 실제 대기함을 확인한 뒤 탈퇴를 커밋하면 lease 거절·upload/전사 미생성을 검증한다. scope 승격/stale ACK, 탈퇴→재가입→prepare/retry/늦은 AI 결과 거절도 검사한다 |
| R5-A3 versions·늦은 PUT | S3 prefix unit의 모든 version/marker, empty 이후 새 version 재발견, PG 반복 sweep |
| R5-A4 보관 경계 | 정상 완료+30일과 실패 endedAt+30일의 직전/정확한 시각 PG 검사 |
| R5-A5 오류·재시작 | list/delete 403·5xx 실패 전파, DB 실패/lease/재선점·동시 claim 검사 |
| R5-A6 부모 삭제·격리 | 부모 행 삭제 후 tombstone 생존 PG, 다른 recording prefix 거절 S3 unit |
| R5-A7 retry 차단·기록 보존 | revoked upload 공통 guard와 rejoin PG, READY/PARTIAL·deleted_at 보존 검사 |

## 실행 증거

- 실제 PostgreSQL `RecordingAudioCleanupIntegrationTest`: 추가 잠금 경합 회귀를 포함한 15개 통과, 실패 0개. `RecordingAudioSaveRetryAcceptanceTest`의 보관 만료 HTTP 경계 회귀도 7개 통과했다. 추가 회귀 뒤 집중 검증과 `spotlessCheck bootJar`를 통과했으며, 아래 전체 회귀 수는 추가 회귀 이전 기준이다.
- cleanup/upload 모델·S3 prefix·임시 WAV·retention scheduler·deletion worker의 집중 unit 검증 통과.
- R3 최종 `8d0f1698` 통합 후 `./gradlew spotlessApply test integrationTest acceptanceTest bootJar` 통과: unit 1,699개, PostgreSQL integration 764개(기존 실제 S3 opt-in 1개 제외), HTTP acceptance 581개. 실패 0개. 실행된 3,043개 통과, opt-in 제외 1개.
- 마지막 `./gradlew spotlessCheck bootJar` 통과. Governance Python unittest 10개 통과. `git diff --check` 통과.
- 이후 명시적 경계 회귀를 보강하고 정리 PostgreSQL 15건·audio-save HTTP 인수 7건, SpotlessCheck·bootJar를 다시 통과했다. `pg_blocking_pids`로 ALL 정리 트랜잭션을 기다리는 최종 채택을 확인했고, 정리 커밋 뒤 채택 거절·업로드/전사 미생성을 검증했다. HTTP는 종료+30일 직전 1µs의 GET canRetry=true/POST 202와 정확한 시각의 canRetry=false/POST 409를 검증했다.
- 원문 hash gate 통과. 전체 컨벤션 감사는 1,270개 진단으로 미통과다. #768의 실제 규칙 위반은 남지 않았고, 아래 SQL 상수/인스턴스 그룹 구분 오진 1개는 명시적으로 남겼다. 나머지는 기준 브랜치/기존 코드 범위이며 전체 감사 통과를 주장하지 않는다.
- 이 작업에서 실제 사용자 S3 객체를 삭제하지 않았다. 실제 버킷 테스트 prefix의 T18과 FE/배포 전체 검증은 별도 증거가 필요하다.

## 컨벤션 검사

원문 hash gate를 유지한다. SQL text block 상수 `POLICY_SQL`과 인스턴스 필드 `EntityManager` 사이의 한 줄은 상수/인스턴스 그룹 구분을 위한 규칙이다. 자동 검사기는 이 위치를 같은 필드 그룹의 빈 줄로 진단한다(`RecordingAudioCleanupRepositoryAdapter`, `JAVA-FIELD-BLANK`). 이 진단을 숨기거나 scanner를 변경하지 않았다. 저장소 전체의 기존 진단과 최종 전체 감사 결과는 PR 검증에 별도로 기재한다.
