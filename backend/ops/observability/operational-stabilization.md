# EC2 로그·서비스·백업 운영 기준

2026-10-25 운영 적용 기준이다. dev/prod는 Spring과 RDS를 사용한다.
Alloy의 legacy·blue·green journal selector는 모두 `_SYSTEMD_UNIT`이며 `slot`을 붙인다.
Prod legacy source 이름 `spring_blue_legacy`는 저장된 journal cursor를 보존하기 위해 유지한다.
이 이름은 내부 component 식별자다. 새 로그의 라벨은 legacy=`slot="legacy"`,
blue=`slot="blue"`, green=`slot="green"`이고 각각 정확한 `unit`도 붙인다.
두 환경의 legacy unit은 점검 당시 inactive지만 enabled이며 배포 코드에 복구 경로가 남아 있어
수집을 제거하지 않았다. 과거 `slot="blue"`로 이미 저장된 legacy 로그는 소급 변경하지 않는다.

## 저장소와 운영 설정

| 항목 | dev | prod |
| --- | --- | --- |
| 호스트·Spring scrape / timeout | 30초 / 10초 | 5초 / 3초 |
| RDS CloudWatch 집계·조회 | 5분 | 1분 |
| RDS exporter scrape | 60초 | 60초 |
| PostgreSQL 세션·최대 연결 수 | 30초 | 30초 |
| 외부 health | `/actuator/health`, 30초 | `/actuator/health`, 10초 |

### `job_name`과 실제 `job` 라벨

Alloy exporter가 target을 만들 때 `job="integrations/<exporter>"`를 먼저 설정한다.
`prometheus.scrape.job_name`은 target에 `job`이 없을 때만 쓰는 기본값이다. 따라서 이전
`job_name="node"`가 중앙에서 변환된 것이 아니라 처음부터 `integrations/unix`가 전송됐다.
저장소의 fallback도 실제 값으로 맞췄으며 기존 중앙 시계열 이름은 바꾸지 않았다.

| 수집 | 현재 설정과 실제 `job` |
| --- | --- |
| 호스트 | `integrations/unix` |
| CloudWatch RDS | `integrations/cloudwatch` |
| PostgreSQL 세션 | `integrations/postgres` |
| Spring | `spring` |

근거: [Alloy v1.20.1 exporter target 생성](https://github.com/grafana/alloy/blob/v1.20.1/internal/component/prometheus/exporter/exporter.go#L124),
[job_name의 기본값 의미](https://github.com/grafana/alloy/blob/v1.20.1/docs/sources/reference/components/prometheus/prometheus.scrape.md).

`agent/config.alloy`가 공통 원본이다. `agent/render.py`는 환경별 값을 확정하고
비공개 수집 origin을 주입한다. 인증서·키와 PostgreSQL DSN은 기존 서버 파일로만 관리한다.
`agent/postgres-metrics.yml`은 원문 SQL·사용자 데이터를 수집하지 않고 세션 상태별 개수와
최대 연결 수만 조회한다.

후보 파일 생성 예시(저장소 루트):

```sh
python3 backend/ops/observability/agent/render.py --environment prod \
  --ingest-url-file /private/path/ingest-origin \
  --output /private/path/candidate.alloy
```

출력 파일은 새 경로여야 한다. renderer의 selector 검사는 Alloy 문법 검사만으로 검출되지
않는 `_SYSTMD_UNIT` 오타와 슬롯 누락도 거절한다. 실제 설치 전 기존 Compose·`.env`·설정의
백업과 해시를 보관하고 배포 잠금 `/run/lock/knot-backend-bluegreen.lock`을 잡는다.
실행 중인 Alloy 안에서 `alloy validate`를 통과시킨 뒤 마운트된 파일의 **inode를 유지해**
내용을 반영하고 `POST http://127.0.0.1:12345/-/reload`, `/-/ready`를 확인한다.
실패하면 같은 방식으로 기존 파일을 복원하고 reload한다. 앱 재시작은 필요하지 않다.

현재 운영 Compose와 저장소 Compose는 PostgreSQL 질의·DSN mount가 같고 `.env`에는
`KNOT_ENV`, `KNOT_INGEST_BASE_URL`을 준비한다. 수집기 재생성 전 `docker compose config --quiet`로
확인한다. 인증서·DSN 파일을 새로 생성하거나 덮어쓰지 않는다.

## 검증

`python3 backend/ops/observability/agent/test_render.py` 6개와 `node --test backend/ops/observability/discord-notifications.test.mjs` 4개로 렌더·알림 보존을 검사한다. 2026-10-25 운영에서 dev/prod blue 완료 로그 각 1건과 8개 scrape=1을 확인했다. green unit 시험 로그 수신은 실제 Java 앱 전환 검증이 아니다.
