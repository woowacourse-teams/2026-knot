# Mac AI 전사 지표 수집과 화면

AI 앱의 인증된 localhost 지표를 기존 Mac Alloy가 5초마다 수집한다. 이 문서는 수집 모듈의
옵트인 설치와 복구를 다룬다. 앱 구현은 상위 [Issue #775](https://github.com/woowacourse-teams/2026-knot/issues/775),
수집·화면 연결은 [Issue #779](https://github.com/woowacourse-teams/2026-knot/issues/779) 범위다.
[Mac 호스트 수집](mac-host-metrics.md)과 [중앙 EC2 운영](mac-monitoring-ec2.md)이 먼저 준비되어야 한다.

## 관측 대상과 의미

기본 앱 주소는 `127.0.0.1:18765`이며 `/internal/metrics`의 GET 요청에 Bearer 인증을 사용한다.
검증은 기존 앱을 교체하지 않고 별도 `127.0.0.1:19765` 프로세스에서 수행한다. 포트를 외부에
공개하거나 중앙 Prometheus에서 Mac으로 직접 접속하지 않는다.

- `job=mac-ai`, `instance=knot-ai-macmini`, `ai_scope=validation|service`로 대상을 구분한다.
  `project=knot`, `env=shared`, `host_role=ai`는 기존 remote_write의 external_labels를 유지한다.
- 모듈 기본 scope는 `service`다. 검증 설치에서는 주소와 scope를 모두 명시한다.
  Grafana의 관측 대상 기본값은 `validation`이며 서비스 운영 상태로 해석하지 않는다.
- `up`은 지표 수집 성공이다. 앱 준비 상태와 작업 진행 여부는 서로 다른 지표다.
  준비 상태가 1이어도 이미 작업 중일 수 있다.
- 호스트 자원은 기존 `Mac AI 서버 자원` 대시보드에서 같은 시각을 확인한다. 호스트 전체의
  CPU·메모리를 AI 프로세스나 모델만의 사용량으로 해석하지 않는다.

| 지표 | 의미 |
| --- | --- |
| `knot_ai_ready` | 기존 accepting 및 엔진 준비 상태 |
| `knot_ai_active_jobs` | 작업 진행 중 1, 정리 완료 후 0 |
| `knot_ai_job_elapsed_seconds` | 현재 작업 경과 시간, 유휴 시 실제 0 |
| `knot_ai_stage_active{stage}` | 고정 5단계 각각의 활성 여부 |
| `knot_ai_stage_elapsed_seconds{stage}` | 활성 단계의 경과 시간, 비활성 단계는 실제 0 |
| `knot_ai_jobs_completed_total{outcome}` | 프로세스 시작 이후 완료·실패 작업 수 |
| `knot_ai_stage_duration_seconds_sum{stage,outcome}` | 완료 단계 시간 합계 |
| `knot_ai_stage_duration_seconds_count{stage,outcome}` | 완료 단계 횟수 |

단계는 `download`, `decode`, `model`, `result_check`, `cleanup`이다. `model`은 통합 runner
호출 구간이며 순수 모델 추론 시간으로 해석하지 않는다. 결과는 `completed|failed`다. 중복 ID
재생과 BUSY·NOT_READY 거절은 완료 횟수에 포함하지 않는다. 전사 성공 후 정리만 실패하면
작업은 completed이고 cleanup 단계는 failed일 수 있다. 프로세스를 재시작하면 누적값이 초기화된다.
요청 ID·토큰·입력 및 전사 본문을 지표 라벨이나 annotation에 넣지 않는다.

## 설치 전 확인

설치·교체는 Mac Alloy와 AI 앱을 실행하는 같은 사용자로 한다. 기존 LaunchAgent와 mTLS
수신이 정상인지 먼저 확인한다. Alloy는 공식 `v1.20.1`을 사용한다. 아래 환경변수는 비공개
운영 설정에서 주입하고 값이나 환경 전체를 출력하지 않는다.

- `KNOT_ALLOY_BINARY`: 현재 LaunchAgent가 사용하는 Alloy 바이너리
- `KNOT_MAC_CONFIG_DIR`: `~/Library/Application Support/Knot/observability`
- `KNOT_INGEST_BASE_URL`: 현재 중앙 HTTPS 주소
- `KNOT_AI_METRICS_TOKEN_FILE`: AI 앱과 Alloy가 읽는 전용 토큰 파일의 절대 경로

토큰은 앱의 인증 설정과 일치해야 한다. 기존 비공개 토큰을 파일로 준비하고 값은 터미널 인자·
셸 이력·저장소에 넣지 않는다. 파일 소유자는 AI 실행 계정, 권한은 `0600`, 부모 디렉터리는
`0700`으로 둔다. root로 준비했다면 실행 계정 소유로 넘긴다. `root:0600` 상태의 파일은 일반
사용자의 Alloy가 읽을 수 없다. 인증서 키나 Grafana 비밀번호를 지표 토큰으로 재사용하지 않는다.

```sh
test -r "$KNOT_AI_METRICS_TOKEN_FILE"
test "$(stat -f '%Su' "$KNOT_AI_METRICS_TOKEN_FILE")" = "$(id -un)"
test "$(stat -f '%Lp' "$KNOT_AI_METRICS_TOKEN_FILE")" = 600
launchctl print "gui/$(id -u)/kr.knot.mac-metrics"
```

## 옵트인 설정 설치

다음 명령은 저장소의 `backend` 디렉터리에서 실행한다. 기존 `config.alloy`에 모듈을 추가하는
절차이며 host 수집과 remote_write 블록을 지우거나 복제하지 않는다. 작업 전 비공개 백업을
만들고 같은 디렉터리에서 후보 파일을 준비한다. WAL과 TLS 파일은 수정하지 않는다.

```sh
umask 077
export KNOT_MAC_CONFIG_DIR="$HOME/Library/Application Support/Knot/observability"
backup_dir="$(mktemp -d "$KNOT_MAC_CONFIG_DIR/ai-backup.XXXXXX")"
cp -p "$KNOT_MAC_CONFIG_DIR/config.alloy" "$backup_dir/config.alloy"
if test -f "$KNOT_MAC_CONFIG_DIR/ai.alloy"; then
  cp -p "$KNOT_MAC_CONFIG_DIR/ai.alloy" "$backup_dir/ai.alloy"
fi
cp ops/observability/mac/ai/config.alloy "$KNOT_MAC_CONFIG_DIR/ai.alloy.candidate"
chmod 600 "$KNOT_MAC_CONFIG_DIR/ai.alloy.candidate"
cp -p "$KNOT_MAC_CONFIG_DIR/config.alloy" "$KNOT_MAC_CONFIG_DIR/config.alloy.candidate"
```

후보 host 설정 끝에 아래 블록을 한 번만 추가한다. 토큰 경로는 비공개 파일에 실제 절대 경로로
설정한다. `KNOT_AI_METRICS_TOKEN_FILE`을 환경변수로 참조하면 현재 셸과 LaunchAgent의
EnvironmentVariables 양쪽에 같은 값이 필요하다. 기존 plist가 그 변수를 전달한다고 가정하지
않는다. 새 환경변수 없이 비공개 설정에 절대 경로를 쓰는 방법을 아래 예제로 사용한다.

```alloy
import.file "knot_ai" {
  filename = sys.env("KNOT_MAC_CONFIG_DIR") + "/ai.alloy.candidate"
}
knot_ai.metrics "ai" {
  address    = "127.0.0.1:19765"
  scope      = "validation"
  token_file = "/PRIVATE/AI/metrics-token"
  forward_to = [prometheus.remote_write.central.receiver]
}
```

`/PRIVATE/AI/metrics-token`을 실제 비공개 토큰 경로로 바꾼 뒤 현재 중앙 주소 환경변수를
유지한 셸에서 검증한다. 후보 검사 실패 시 실제 설정을 교체하거나 수집기를 재시작하지 않는다.

```sh
"$KNOT_ALLOY_BINARY" validate "$KNOT_MAC_CONFIG_DIR/config.alloy.candidate"
```

검증을 통과하면 후보 host 설정의 import 파일명을 `ai.alloy.candidate`에서 `ai.alloy`로 바꾼다.
모듈을 먼저 같은 파일시스템 내 원자 교체하고 최종 host 후보를 다시 검사한 뒤 교체한다.
이 순서는 기존 host 수집이 모듈을 아직 참조하지 않는 최초 옵트인 설치에 해당한다.

```sh
mv "$KNOT_MAC_CONFIG_DIR/ai.alloy.candidate" "$KNOT_MAC_CONFIG_DIR/ai.alloy"
if "$KNOT_ALLOY_BINARY" validate "$KNOT_MAC_CONFIG_DIR/config.alloy.candidate"; then
  chmod 600 "$KNOT_MAC_CONFIG_DIR/config.alloy.candidate"
  mv "$KNOT_MAC_CONFIG_DIR/config.alloy.candidate" "$KNOT_MAC_CONFIG_DIR/config.alloy"
  launchctl kickstart -k "gui/$(id -u)/kr.knot.mac-metrics"
  launchctl print "gui/$(id -u)/kr.knot.mac-metrics"
fi
```

서비스 수집을 추가할 때는 검증 인스턴스를 덮어쓰지 않고 별도 인스턴스 이름을 사용한다.
같은 모듈을 `knot_ai.metrics "service"`로 호출하고 `address="127.0.0.1:18765"`,
`scope="service"`와 그 앱의 토큰 파일을 지정한다. 같은 scope·instance 조합의 수집기를 중복
실행하지 않는다. 기존 모듈 업데이트는 활성 import가 새 모듈을 바로 읽을 수 있으므로 원본
모듈도 백업하고 candidate import로 검사한 후 교체한다. 실패 시 아래 복구 절차를 사용한다.

## 중앙 수신과 Grafana 확인

기존 Mac provider가 `provisioning/mac-dashboards`를 읽는 중앙에서 `knot-mac-ai.json`만
추가한다. 기존 host JSON·provider·알림 설정은 유지한다. 실제 설치 경로는
[중앙 runbook](mac-monitoring-ec2.md)을 따른다. 이 파일만 추가하는 데 중앙 앱 재배포나
새로운 포트가 필요하지 않다. 파일 provider의 갱신 주기 이후 `Mac AI 전사 진행` 화면을 연다.

SSH 터널로 접속한 Grafana에서 관측 대상이 `validation`인지 확인하고 다음을 대조한다.

1. `up{job="mac-ai",ai_scope="validation"}`의 최신 표본이 5초 간격으로 두 번 이상 갱신된다.
2. 기존 `up{job="mac-host"}`도 계속 갱신되고 project/env/instance가 의도한 값이다.
3. 준비·작업 진행·작업 경과와 단계 변화가 검증 앱의 알려진 상태와 일치한다.
4. 완료 결과와 단계 sum/count가 알려진 한 건의 완료·실패 결과와 일치한다.
5. 토큰 오류나 검증 프로세스 중단 때 수집 성공이 0이 되며 앱 상태는 데이터 없음으로 바뀐다.
   중앙 수신 자체가 끊기면 마지막 수집 후 경과 시간이 증가한다.

앱 상태와 완료 누적값은 `up=1`이며 수집 표본이 20초 미만인 때만 표시한다. 수집 성공도
20초 이상 오래되면 데이터 없음으로 표시한다. 상태 stat은 현재 시각의 instant query를
사용하므로 과거 범위의 마지막 정상값을 현재 상태로 되살리지 않는다. 기간을 과거로 이동하면
그 조회 시각의 상태를 보는 것이므로 화면의 조회 종료 시각도 확인한다. 데이터 없음과 아직
완료 표본이 없는 평균을 0으로 채우지 않는다. 이는 화면의 표시 규칙이며 운영 알림은 아니다.

이 PR의 로컬 설정·PromQL 검증은 실제 Mac 수신이나 정상·통제 실패 실험의 완료 증거가 아니다.
실제 실험 결과는 상위 Issue #775에서 별도로 남긴다. 장기 단절·NoData 알림·GPU 관측·AI만
실행한 자원 기준선은 이 절차의 검증 범위에 포함하지 않는다.

## 복구와 제거

설치 전 백업 경로를 지정한 뒤 기존 설정을 같은 파일시스템 내에서 복원하고 검증한다.
원래 모듈이 없었다면 host import를 되돌린 다음 새 모듈 파일을 비공개 백업에 보존한다.
토큰·WAL·TLS·중앙 시계열은 삭제하지 않는다.

```sh
cp -p "$backup_dir/config.alloy" "$KNOT_MAC_CONFIG_DIR/config.alloy.restore"
if test -f "$backup_dir/ai.alloy"; then
  cp -p "$backup_dir/ai.alloy" "$KNOT_MAC_CONFIG_DIR/ai.alloy.restore"
  mv "$KNOT_MAC_CONFIG_DIR/ai.alloy.restore" "$KNOT_MAC_CONFIG_DIR/ai.alloy"
fi
if "$KNOT_ALLOY_BINARY" validate "$KNOT_MAC_CONFIG_DIR/config.alloy.restore"; then
  mv "$KNOT_MAC_CONFIG_DIR/config.alloy.restore" "$KNOT_MAC_CONFIG_DIR/config.alloy"
  launchctl kickstart -k "gui/$(id -u)/kr.knot.mac-metrics"
fi
```

복구 후 host의 최신 표본을 확인한다. AI 패널을 제거할 때는 중앙의 해당 JSON만 백업 위치로
옮기고 provider 정책을 확인한다. 기존 provider의 `disableDeletion=true`이면 파일 제거만으로
화면이 삭제되지 않으므로 Grafana 관리 절차에서 해당 UID `knot-mac-ai`만 처리한다.

## 저장소 검증

설정 계약과 실제 PromQL 평가를 함께 검사한다. Docker의 기존 pinned Prometheus 이미지로
전체 쿼리 문법과 준비·진행 독립, scope 격리, 실패·오래된 표본, 실제 유휴 0, 완료 전 평균의
데이터 없음, 단계 집계 라벨 보존을 확인한다. 검증 컨테이너는 포트를 열지 않고 네트워크를 끈다.

```sh
python3 -m unittest discover -s ops/observability/mac/ai -p 'test_*.py' -v
```

## 공식 근거

- [Alloy v1.20.1 declare](https://github.com/grafana/alloy/blob/v1.20.1/docs/sources/reference/config-blocks/declare.md)
- [모듈 argument와 optional 기본값](https://github.com/grafana/alloy/blob/v1.20.1/docs/sources/reference/config-blocks/argument.md)
- [import.file](https://github.com/grafana/alloy/blob/v1.20.1/docs/sources/reference/config-blocks/import.file.md)
- [인증 파일과 scrape 설정](https://github.com/grafana/alloy/blob/v1.20.1/docs/sources/reference/components/prometheus/prometheus.scrape.md)
