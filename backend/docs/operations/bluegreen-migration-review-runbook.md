# Blue-Green 마이그레이션 검토 승인 운영 절차

기존 SQL의 수정·삭제를 허용하는 절차가 아니다. 추가 SQL을 검토한 운영자가 정확한 두
JAR 조합에 대해서만 발급하는 한시 검토 기록이다. 애플리케이션 배포 아티팩트에는 승인 파일을 넣지 않는다.

## 검토 순서

1. 실제 활성 슬롯의 JAR와 배포 후보 JAR를 확보하고 각각 `sha256sum`을 기록한다.
2. SQL 파일 목록뿐 아니라 내용을 비교한다. 기존 SQL의 내용 변경·삭제·과거 버전 추가는
   이 방식으로 배포할 수 없다. DB의 현재 Flyway 이력과도 대조한다.
3. 구버전의 조회·쓰기·기본값·제약 호환성과 데이터 보정 영향을 확인한다. SQL fixture만으로
   Worker 메시지/작업 형식이나 모든 Java 기능이 검증됐다고 판단하지 않는다.
4. 실제 실행 프로세스의 기능 플래그·큐·실행 작업과 신규 바이너리의 기본값을 확인한다.
5. Prod는 `rolling`으로 구·신 버전 공존이 가능할 때만 진행한다. Dev Worker 형식이 달라지면
   점검 시간을 승인받고 `serial-workers`를 선택한다. 알 수 없는 호환성은 차단 상태로 둔다.

## 검토 기록 형식

기본 디렉터리는 `/var/lib/knot-backend-deploy/blue-green/migration-reviews`이다.
root 소유 디렉터리 0700, 파일 0600으로 운영한다. 파일과 부모 디렉터리를 다른 사용자가
쓸 수 없어야 하며 symlink는 허용하지 않는다. 파일명은 다음 형태다.

```text
<dev|prod>-<active JAR SHA256>-<candidate JAR SHA256>.json
```

다음 예시의 해시·환경·기한·근거는 실제 검토 결과로 채운다. `expires_at_epoch`는 Unix 초
정수이며 현재보다 미래, 최대 24시간 이내여야 한다. 이번 작업은 15분을 사용했다.

```json
{
  "schema": "knot.migration-review.v1",
  "environment": "dev",
  "active_jar_sha256": "실제 활성 JAR의 SHA256",
  "candidate_jar_sha256": "검토한 후보 JAR의 SHA256",
  "expires_at_epoch": 0,
  "backward_compatible": true,
  "runtime_preconditions_verified": true,
  "execution_mode": "serial-workers",
  "evidence": "SQL/구버전 쓰기/Worker 작업 형식/기능 플래그 검토 근거와 기록 위치"
}
```

Prod의 `execution_mode`는 `rolling`만 허용한다. 해시가 바뀌면 같은 SQL이라도 기존 추가 SQL
승인은 재사용되지 않는다. SQL이 완전히 같으면 기존처럼 승인 없이 `rolling`을 사용한다.
이 검사는 DB 전체 내용이나 모든 실행 조건을 자동으로 입증하지 않는다.

승인 기록을 준비한 뒤, 배포 hook과 같은 인자로 사전 검사를 실행할 수 있다.

```bash
sudo python3 scripts/verify_migration_compatibility.py \
  --active /opt/knot-backend/slots/green/app.jar \
  --candidate /검토한/배포아카이브/app.jar \
  --environment dev \
  --reviews /var/lib/knot-backend-deploy/blue-green/migration-reviews
```

활성 슬롯 경로는 실제 Nginx/서비스 상태에 맞게 바꾼다. 결과는 stdout의 `rolling` 또는
`serial-workers`, 실패는 비정상 종료 코드와 stderr 사유로 구분한다. 이 문서의 예시만으로
승인 파일을 자동 생성하지 않는다.

## 실패와 복구

- 승인 실패: 후보 기동 전에 중단한다. 기한·해시·환경이 달라졌으면 검토부터 다시 한다.
- Dev 순차 교체: health 외 요청을 잠시 503으로 막고 drain → 기존 Worker 종료 → 후보
  기동/지표 검사 → route 전환 → 점검 해제 순서다.
- Dev 후보 실패: 후보를 먼저 종료하고 기존 Worker와 Nginx를 복구한다. 후보 종료를
  확인할 수 없으면 기존 Worker를 동시에 시작하지 않고 `route-uncertain`으로 후속 배포를 막는다.
- Prod: 기존 TLS health·지표·중앙 로그 수신 검사 후에만 이전 슬롯을 drain/stop한다.
- 앱 롤백은 이미 적용된 DB 확장을 되돌리지 않는다. Flyway SQL 삭제·repair·clean으로
  검사나 이력을 맞추지 않는다.

## 코드와 관측 근거

- 결정: [ADR #989](../adr/989-bluegreen-migration-review.md) (Proposed)
- 실제 배포: [2026-10-25 운영 기록](bluegreen-migration-review-20261025.md)
- 공통 CLI: `python3 scripts/tests/test_migration_compatibility.py`
- 배포 회귀: `python3 scripts/tests/test_dev_bluegreen_deploy.py`
- 구버전 SQL 쓰기: develop 기준 V69가 있는 checkout에서
  `python3 scripts/tests/check_postgres_migration_compatibility.py` 실행. Docker의 격리 임시
  PostgreSQL 18 + pgvector 컨테이너를 사용하며 실제 Dev/Prod DB를 수정하지 않는다.

SQL은 같지만 Worker 형식만 바뀌는 변경은 이 SQL 비교만으로 감지할 수 없다. 그런 변경은
별도 런타임 호환성·점검 배포 절차를 먼저 검토한다.
