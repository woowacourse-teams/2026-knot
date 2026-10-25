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

## 서비스와 RDS 백업 감시

`operational_checks.py`를 `/opt/knot-observability-agent/`에 설치하고 동명
`knot-operational-check.service`, `.timer`를 `/etc/systemd/system/`에 설치한다.
`systemd-analyze verify` → `daemon-reload` → 서비스 1회 실행 → `.prom` 성공값 확인 →
Alloy textfile 수신 확인 순서로 검증한 뒤 타이머를 `enable --now`한다.
AWS CLI와 인스턴스 역할의 `rds:DescribeDBInstances` 읽기 권한이 필요하다.

- 매분 nginx·docker·codedeploy-agent 및 backend legacy/blue/green 상태를 조회한다.
  대기 슬롯의 정상 inactive는 실패로 세지 않으며 backend는 최소 한 슬롯이 active여야 한다.
- 5분마다 RDS 보존 일수와 `LatestRestorableTime`을 조회한다. 실패하면 이전 복구 시각을
  보존하면서 조회 성공값을 0으로 기록한다. 오류 원문·자격 증명은 출력하지 않는다.
- `/var/lib/knot-observability/textfile/*.prom`을 원자적으로 갱신하고 기존 host mount로
  Alloy가 읽는다. 새 exporter 컨테이너나 외부 포트는 추가하지 않는다.
- `provisioning/alerting/operational-checks.json`은 dev/prod 각각 서비스 이상(2분 지속),
  점검 표본 3분 이상 누락(3분 지속), RDS 복구 시각 1시간 이상 지연·백업 비활성·조회 실패·
  조회 표본 15분 이상 지연(10분 지속)을 알린다. 기존 알림 24개와 합쳐 30개다.
  1시간은 초기 경보 기준이며 복구 목표나 복원 검증 결과를 뜻하지 않는다.

공식 설정 근거: [Alloy Unix exporter의 textfile collector](https://grafana.com/docs/alloy/latest/reference/components/prometheus/prometheus.exporter.unix/#textfile).

## 퇴역한 prototype 백업

`knot-prototype-backup.service`는 옛 로컬 Docker PostgreSQL의 `knot_prototype` 덤프만
만드는 서비스였다. 현재 대상 컨테이너·볼륨·5432 listener가 없고 운영 앱은 RDS를 사용한다.
따라서 timer를 `disable --now`하고 퇴역 처리 후 failed 상태를 정리했다.
스크립트·unit·덤프 24개는 남겼으며 모든 덤프의 변경 전후 해시가 같다.
최근 덤프는 2026-10-19 03:00 KST 생성본으로 SHA-256 sidecar와 `PGDMP` 헤더를 확인했다.
실제 DB 복원 시험은 하지 않았다.

현재 RDS 자동 백업 보존은 dev 7일·prod 1일이다. 이번 작업은 보존 기간을 변경하지 않았다.
Dev의 별도 prototype 백업은 10/25 03:00 KST 생성본·체크섬과 보존 볼륨이 있어 유지했다.
퇴역 처리한 timer는 prod의 실패한 예약뿐이다.
퇴역 백업 타이머 재활성화는 실제 대상 DB를 복원한 뒤에만 수행한다.
운영 서버의 `retired-backup-schedule-*` 디렉터리에 원래 unit·timer와 퇴역 이유를 보존한다.

### 결정 항목 — prod RDS 보호 설정 (보류)

- 사실: 앞선 점검에서 prod 자동 백업 보존 1일·삭제 보호 꺼짐을 확인했다.
- 위험: 뒤늦게 발견한 데이터 손상은 1일 복구 창을 벗어날 수 있고, DB 삭제에 대한 추가 보호가 없다.
- 권장안(미승인): 보존 7일·삭제 보호 켜기를 검토하고 실제 복원 시험으로 복구 가능성을 확인한다.
- 사용자 지시: 이번에는 RDS 설정 변경과 부하테스트를 보류한다. 비용·운영 영향 확인과 AWS 콘솔/API 접근은 사전 확인 후 별도 작업으로 진행한다.

## 검증과 되돌리기

`python3 backend/ops/observability/agent/test_operational_checks.py`와 `promtool test rules backend/ops/observability/operational-alerts.test.json`으로 실패·정상 standby·조회 오류·표본 누락을 검사한다. 2026-10-25 실제 중앙의 30개 규칙 평가 health=ok를 확인했지만 Discord 장애·복구 메시지 시험과 실제 DB 복원 시험은 하지 않았다.

타이머를 먼저 중지하고 Alloy 설정을 복원한다. Grafana 파일 삭제만으로 규칙은 제거되지 않으므로 신규 6개 UID만 `deleteRules`로 명시해 reload한다. 기존 24개 규칙·수신처·라우팅은 유지한다. AWS 콘솔·API의 추가 조회와 실제 재적용은 사전 확인 후 별도로 수행한다.
