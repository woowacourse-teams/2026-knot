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

## 배포 후 대상 슬롯 로그 확인

prod 배포 코드 기준 `c986a6eb` 위의 후속 변경은 `scripts/bluegreen_deploy.sh`에 두 검사를 연결한다.
후보 시작 전 mTLS 조회 경로를 검사하고, Nginx·metrics 전환 후 **기존 슬롯 drain/종료 전에**
새 HTTP 요청의 완료 로그를 확인한다. 기존 packaging이 `backend/scripts`의 verifier도 번들에 넣는다.
prod hook은 별도 Issue #967의 main 대상 변경이다. 해당 PR이 병합되고 새 artifact가 만들어지기 전에는 자동 검사가 포함되지 않는다. 이 브랜치의 수신 경로·helper만으로 prod hook이 바뀌지는 않는다.

검사 도구 `scripts/verify_deployment_logs.py`는 새 연결로 loopback Nginx의 health를 요청하고
HTTP 200·최상위 `status=UP`·정상 `X-Request-ID`를 확인한다. prod Origin 인증서는 번들의
Cloudflare Origin RSA root로 검증한다. 이어 기존 Alloy mTLS 인증서로 중앙의 제한된
`/<env>/log-receipt`를 조회한다. env·slot·unit·요청 ID·`HTTP dispatch finished`가 일치해야 성공한다.

중앙의 `nginx/log-receipt-locations.conf`는 기존 ingest server에 include한다. 기존 trace·Windows
쓰기 경로 등 현장 확장을 덮어쓰지 않고 include만 추가한다. 이 경로는 환경별 client CN,
GET, blue/green 및 32자리 hex ID만 허용하고 고정된 5분 count 쿼리만 실행한다.
임의 LogQL·원문 로그·다른 환경 조회를 허용하지 않는다. 새 HTTP 요청 ID로 과거 로그와 구분한다.

EC2의 `/etc/knot-observability-agent/deployment-log-check.json`에는 `environment`와 비공개
`ingest_base_url`만 둔다(0600). 키·인증서는 기존 `/opt/knot-observability-agent/tls`를 사용한다.
실행 결과에는 요청 ID·건수·상태만 나오며 curl 오류 원문·응답 본문·개인키는 출력하지 않는다.

### 다음 prod 배포 전·후 실행 순서

1. 배포 artifact의 `scripts/bluegreen_deploy.sh`에 `verify_deployment_logs --preflight`와
   전환 후 `verify_deployment_logs` 호출이 있고, `scripts/verify_deployment_logs.py`가 포함됐는지 확인한다.
   호출 순서는 전환/health → 수신 확인 → 기존 슬롯 drain/종료다. 없는 artifact에는 자동 검증이 없다.
2. prod 호스트에서 아래 preflight를 실행한다. 현재 blue라면 다음 후보는 green이지만 매번 현재
   Nginx upstream과 candidate를 확인한다. 이것은 후보 Java 앱을 시작하지 않는다.
3. 새 hook이 들어간 배포는 자동으로 HTTP 요청과 중앙 수신을 검사한다. 30초 내 확인되지 않으면
   기존 transaction rollback으로 이전 앱·Nginx/metrics 경로를 복구한다. 조회 장애도 성공으로 넘기지 않는다.
4. 종료 후 활성 슬롯을 넣어 수신 검사를 한 번 더 실행하고, 배포 ID·slot·요청 ID·결과를 보관한다.

prod 서버에서 실행(현재 설치된 검사 도구):

```sh
python3 /opt/knot-observability-agent/verify_deployment_logs.py \
  --environment prod --slot green --preflight

# 전환 완료 후 실제 활성 슬롯으로 실행한다. Nginx를 거친 새 HTTP 요청을 직접 생성한다.
python3 /opt/knot-observability-agent/verify_deployment_logs.py \
  --environment prod --slot green \
  --origin-ca /etc/knot-observability-agent/deploy-origin-ca.crt
```

종료 코드 0은 확인 성공, 2는 정상 조회했지만 완료 로그 미수신, 3은 조회/전송/응답 오류,
4는 설정·health 본문·요청 ID 오류다(HTTP 전송/상태 오류는 3). preflight 성공은 실제 후보 슬롯의 수신 성공이 아니다.

새 hook이 포함되지 않은 배포는 위 명령을 **전환 직후 수동 실행**하고 확인 전 완료로 판정하지
않는다. 기존 hook은 이전 슬롯을 자동 종료하므로 수동 검사는 종료 전 보존을 보장하지 못한다.
실패 시 후속 배포를 중단하고 앱 health·경로·Alloy·중앙 조회를 분리 진단한다. 실제 앱/경로
실패라면 기존 절차로 직전 정상 revision을 복구한다. AWS 배포/API 실행은 별도 사전 확인 후 수행한다.

중앙 서버에서 이미 발급받은 ID 또는 안전한 unit 시험 표식을 직접 대조할 때는 아래 도구도 쓸 수 있다.

```sh
python3 verify-log-receipt.py --environment prod --slot green \
  --marker "$REQUEST_ID" --since "$PROBE_STARTED_AT" --timeout 30
```

다른 슬롯/unit의 최근 로그나 예전 요청이 있어도 이 검사는 통과하지 않는다. API 트래픽이 없는
경우 기존 `app request logs missing` 알림은 감지하지 않으므로 이 검사를 대신할 수 없다.
실패 시 앱 health 성공만으로 관측성 검증 완료로 판정하지 않는다.

이번 실제 검증은 서비스 중인 blue HTTP 요청 ID와 비활성 green unit의 고유 journal 표식을
각각 Loki의 해당 slot에서 찾는 방식이었다. green 검증은 배포 잠금 아래 런타임 drop-in으로
로그 한 줄만 출력한 뒤 즉시 원복했으며 Java 앱·DB 작업·트래픽 전환은 수행하지 않았다.
후속 검사에서는 새 도구로 dev/prod 각각 Nginx를 거친 blue 요청의 완료 로그 1건을 확인했다.
다른 환경(403)·추가 쿼리/잘못된 slot(400)·POST(405)·임의 Loki 경로(404)를 실제로 차단했다.
prod 전환·역전환·수신 실패 rollback은 fixture 테스트이며 실제 green 트래픽 전환은 다음 배포 검증이다.

## 검증과 되돌리기

- `python3 -m unittest discover -s backend/ops/observability/agent -p 'test_*.py'`
- `node --test backend/ops/observability/{environment-dashboards,discord-notifications,private-configuration}.test.mjs`
- `python3 backend/ops/observability/deployment.test.py`
- `promtool test rules backend/ops/observability/operational-alerts.test.json`
- `python3 backend/scripts/tests/test_verify_deployment_logs.py`
- prod 계열 작업 트리: `python3 backend/scripts/tests/test_dev_bluegreen_deploy.py`

운영에서 두 환경의 Alloy validate/reload, Prometheus 설정 검사, 표본 수신과 실제 알림 평가를
별도로 확인한다. 수신기의 Discord 장애·복구 메시지 발송 시험은 이번에 하지 않았다.
새 감시를 되돌릴 때는 타이머를 먼저 중지하고 Alloy를 백업으로 복원한다. Grafana 파일을
삭제하는 것만으로 규칙이 삭제되지 않으므로 새 규칙 6개의 UID만 `deleteRules`로 명시해
reload한다. 기존 24개 규칙·수신처·라우팅은 유지한다.

현재 운영 화면에는 별도 요청 추적·사용자 지정 패널이 있다. 이번에는 그 화면을 재배포하지
않았다. 저장소 기본 생성기는 dev/prod 각 7개와 현재 Spring·RDS 의미를 제공한다. 기본
산출물만으로 운영 폴더를 덮어쓰지 않도록 설치기는 기존 dashboard 누락 시 중단한다.
