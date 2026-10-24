# Content Import는 heartbeat로 실행권을 확인하며 새 snapshot을 완성한 뒤 성공 시에만 원자적으로 공개한다.

## 상태

Proposed

## 관련 Issue

- #284 [BE] Notion Import 작업자와 원자적 Snapshot 공개 구현

## 한 줄 요약

Content Import 코어는 `RUNNING` heartbeat로 살아 있는 작업자를 보호하고, 새 snapshot을 완성한 뒤 성공 시에만 원자적으로 공개한다. Notion 수집과 운영 설정은 Infrastructure 어댑터에 둔다.

## 왜 이 결정이 필요했나

수동 재가져오기 중 실패하거나 빈 결과가 발생해도 Workspace의 기존 Tree와 AI 검색을 유지해야 한다.

OWNER가 새 Import를 실행하는 중 Notion API 장애, 권한 변화, 빈 결과가 발생하면 기존 Tree와 AI 검색이 빈 데이터로 바뀔 수 있다.

결정 동인:

- 기존 정상 데이터 보호
- 실패 복구
- 정상 장기 수집의 잘못된 회수 방지
- 과거 출처 재현
- 단순한 full snapshot 구현

## 트레이드 오프

- 대안: `PENDING`과 `started_at`의 나이만으로 활성 Run을 실패 처리한다
- 채택: `PENDING`은 보존하고 `RUNNING` heartbeat가 만료된 Run만 회수한다

## 무엇을 결정했나

Content Import는 새 snapshot을 완성한 뒤 성공 시에만 원자적으로 공개하고 이전 Run Row는 publication에서 제외해 영구 보존한다.

Domain·Application은 `ContentImportRun`, `ImportedPage`, `ContentSourceCollector` 같은 공급자 중립 계약을 사용한다. Notion API 호출, 수집기 조립, metric과 worker 운영 설정은 `workspace.infrastructure.notion`에 남긴다. MVP에는 Provider registry를 추가하지 않고, 실행 중인 Run의 Connection에서 Provider를 읽어 자격 증명 복호화에 사용한다.

외부 API와 DB 작업의 실패 경계를 분리하면서 불완전한 결과가 사용자 조회에 노출되지 않게 한다.

대기 시간이 길다는 이유로 정상 backlog를 폐기하지 않도록 `PENDING`은 stale 복구 대상에서 제외한다. V12에서 `last_heartbeat_at`을 추가하고 기존 활성 Run은 migration 시각으로 backfill해 롤링 배포 중인 작업자에게 한 번의 만료 유예를 준다. 컬럼의 DB 기본값은 구버전 writer가 만든 `PENDING`도 이후 `RUNNING`으로 전이할 수 있게 한다. 새 코드는 `PENDING`에 null을 명시하고 선점할 때 첫 heartbeat를 기록한다. 외부 수집 동안 poll scheduler와 분리된 전용 lease가 짧은 별도 transaction으로 `RUNNING` Row만 갱신한다. 복구 작업은 DB 현재 시각을 기준으로 heartbeat와 `started_at`이 모두 만료된 `RUNNING`만 `FOR UPDATE SKIP LOCKED`로 회수한다. 따라서 오래 대기해 heartbeat 기본값이 낡은 구버전 `PENDING`도 선점 시각부터 한 번의 timeout을 보장받는다.

V12의 CHECK는 `RUNNING`에 heartbeat가 반드시 있다는 방향만 강제한다. 새 코드는 terminal 전이에서 heartbeat를 비우지만, 배포 중인 구버전 worker가 heartbeat 컬럼을 모른 채 `PENDING`을 선점하거나 `COMPLETED` 또는 `FAILED`로 전이해도 막지 않는다. `PENDING`이나 terminal Row에 남은 legacy heartbeat는 상태 조건과 partial index에서 제외된다.

회수와 늦게 돌아온 작업자가 경합하면 Run 상태가 fencing token 역할을 한다. heartbeat 갱신, Page staging, publication은 모두 같은 Run ID가 여전히 `RUNNING`인지 확인하므로 회수된 작업자는 Page를 추가하거나 pointer를 바꿀 수 없다. Run ID를 재사용하지 않으므로 MVP에서는 별도 worker ID나 lease token을 저장하지 않는다.

## 결과

- 실패 중에는 이전 성공 데이터가 계속 보인다
- 이전 Run 데이터를 보존해 저장 공간이 증가한다
- publication 전환 transaction을 짧게 유지할 수 있다
- 장시간 수집 중에도 DB 장기 transaction 없이 실행권을 유지한다
- heartbeat 갱신용 짧은 DB 부하와 전용 scheduler 수명 관리가 추가된다

## 다시 논의해야 할 조건

- 증분 동기화를 도입할 때
- 이전 Run 보존 비용이 운영 한계를 넘을 때
- checkpoint 재개가 필요해질 때
- 하나의 Run을 다른 worker에게 재할당하거나 Run ID를 재사용할 때

## 확인

- 예정 경로: `backend/docs/adr/284-notion-import-snapshot-publication.md`
- 결정 주체: Knot 팀
- AI 하네스가 Proposed ADR 파일을 생성했다.
- 팀이 PR에서 승인한 뒤 Accepted로 바꾼다.
