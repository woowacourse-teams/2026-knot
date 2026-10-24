# 개발 DB 전사 마이그레이션 충돌 복구

2026-10-14 개발 서버의 별도 전사 대기열 JAR가 저장소와 다른 V49·V50을 적용했다.
최신 JAR로 실행 경로를 바꾸면 Flyway 검증에서 시작이 실패한다.
이 절차는 확인된 개발 DB의 일회성 복구에만 사용한다. 애플리케이션 시작 시 자동 repair하지 않는다.

## 확인된 이력

| 버전 | 개발 DB에 적용된 원본 | 체크섬 | 저장소 원본 체크섬 |
| --- | --- | --- | --- |
| V49 | `V49__create_recording_transcriptions.sql`, finished_at 포함 | 261213821 | -272133924 |
| V50 | `V50__queue_recording_transcriptions.sql`, queued_at·QUEUED 추가 | 1488856651 | -835008331 |

기존 원본은 테스트 fixture에 보존한다. 운영 마이그레이션의 V49·V50은 수정하지 않는다.

## 적용 순서

1. 개발 백엔드를 중지하고 대상 DB 백업 및 `flyway_schema_history` 전체 원본을 보관한다.
2. 이 PR의 V51까지 포함된 동일 소스와 애플리케이션이 사용하는 Flyway 12.4.0으로 `info`·`validate`를 실행한다.
   V49·V50의 체크섬과 V50 설명 차이, V51 미적용 표시 외에 실패·누락·다른 버전 충돌이 있으면 이 절차를 적용하지 않는다.
3. 대상이 개발 DB이며 V49·V50이 위 이력과 일치하는지 아래 조회로 확인한다.
4. 같은 마이그레이션 위치에서 `repair`를 한 번 실행하고 이어 `migrate`, `validate`를 실행한다.
5. 최신 JAR로 백엔드를 시작하고 health, 기존 데이터, 탐색 SSE의 완료를 확인한다.

```sql
SELECT version, description, script, checksum, success
FROM flyway_schema_history
WHERE version IN ('49', '50', '51')
ORDER BY installed_rank;

SELECT status, count(*)
FROM recording_transcriptions
GROUP BY status;
```

Flyway CLI의 `FLYWAY_URL`, `FLYWAY_USER`, `FLYWAY_PASSWORD`는 기존 개발 DB 접속 정보를
환경변수로 주입한다. 비밀번호를 명령 인자나 저장소 파일에 기록하지 않는다.
저장소 루트에서 아래 명령을 실행한다. CLI는 별도 설치가 필요하며 Spring Boot 실행 옵션이 아니다.

```bash
flyway -locations="filesystem:$(pwd)/backend/src/main/resources/db/migration" info
flyway -locations="filesystem:$(pwd)/backend/src/main/resources/db/migration" validate
# 확인된 V49·V50 충돌만 있을 때 실행한다.
flyway -locations="filesystem:$(pwd)/backend/src/main/resources/db/migration" repair
flyway -locations="filesystem:$(pwd)/backend/src/main/resources/db/migration" migrate
flyway -locations="filesystem:$(pwd)/backend/src/main/resources/db/migration" validate
```

`repair`는 이미 적용된 이력의 체크섬·설명·타입 등을 정리하며 SQL을 다시 실행하지 않는다.
따라서 `repair`만으로 복구 완료로 판단하지 않는다.
공식 동작은 [Flyway repair 문서](https://documentation.red-gate.com/fd/repair-277578892.html)를 따른다.

## V51의 역할과 데이터 보존

- 기존 개발 DB에 없는 `storage_version_id`와 완료 상태 제약조건을 추가한다.
- 기존 대기열 DB에서는 현재 코드가 종료 시각을 쓰지 않아도 전사 완료·실패·재시도가 가능하도록 제약조건을 조정한다.
- 전사 행과 `finished_at`, `queued_at`, 기존 인덱스 및 QUEUED 상태를 보존한다.
- 사용자·워크스페이스·녹음·문서·탐색 데이터는 삭제하거나 갱신하지 않는다.
- 저장소 V50을 이미 적용한 DB와 빈 DB는 repair 없이 정규 V51을 적용한다.
- 기존 QUEUED를 읽을 수 있도록 enum 값을 유지한다. 대기열 소비 기능을 새로 구현하거나 기존 대기 작업을 자동 실행하는 변경은 아니다.

운영 DB에 이 일회성 repair를 적용하지 않는다. repair가 바꾼 원래 이력은 사전에 보관한 백업으로 추적한다.
V51 적용 후 구형 별도 JAR를 다시 실행하지 않는다. 그 JAR의 체크섬은 복구된 이력과 맞지 않는다.

## 검증

Testcontainers PostgreSQL 18에서 실제 Flyway repair와 migrate를 실행한다.
기존 대기열 DB, 현재 저장소 V50 DB, 빈 DB의 적용과 데이터 보존, 완료·재시도 쓰기,
repair 단독의 스키마 미반영, 알려지지 않은 체크섬의 검증 차단을 확인한다.
운영 DB에 테스트를 실행하지 않는다.
