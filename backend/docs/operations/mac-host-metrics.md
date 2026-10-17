# Mac AI 호스트 자원 수집

Mac의 native Alloy가 CPU·메모리·swap·디스크 지표를 30초마다 수집한다. 사용자 LaunchAgent의
설치·갱신과 지표 해석은 이 문서를 따른다. 현재 수신 대상은 별도 EC2이며, 방화벽·중앙 앱·
Grafana SSH 터널·재부팅 절차는 [Mac 관측 EC2 운영](mac-monitoring-ec2.md)에 있다.
실제 설치·수신·화면·재부팅 검증 범위는 [Issue #762 검증 결과](../verification/762-mac-host-metrics.md)를 따른다.

## 수집과 해석

`mac/config.alloy` → mTLS `/mac/api/v1/write` → Prometheus → `provisioning/mac-dashboards/knot-mac.json`.
호스트 식별은 `project=knot`, `env=shared`, `host_role=ai`, `job=mac-host`,
`instance=knot-ai-macmini`다. Mac 한 대를 Dev·Prod별로 복제하지 않는다. 호스트 이름·개인
파일·프로세스 목록은 수집하지 않으며 외장 볼륨과 보조 시스템 볼륨은 파일시스템 수집에서 제외한다.

- CPU는 전체 코어의 최근 2분 평균 사용률이다. 높은 CPU만으로 장애를 판단하지 않는다.
  처음 연결한 직후에는 두 번 이상 수집될 때까지 CPU 비율이 비어 있을 수 있다.
- 메모리는 Darwin의 활성·고정·압축 값을 각각 표시한다. 합계를 Activity Monitor의 메모리
  사용량이나 메모리 압력과 동일시하지 않는다. JVM heap이나 모델 단독 사용량도 아니다.
- swap은 `node_memory_swap_used_bytes`, 디스크는 `/System/Volumes/Data`의 가용 공간을
  표시한다. 실제 노출 이름·단위·mountpoint를 대상 Mac에서 확인한다. 시각이 다른 원시
  표본과 Grafana의 변동값이 정확히 같아야 하는 것은 아니다.
- `up=1`은 Mac 로컬 scrape 성공이다. 중앙 수신은 마지막 표본 경과 시간으로 함께 확인한다.
  데이터 없음과 미지원 값을 `0`으로 채우지 않는다.
- 전사 전·중·후 측정 시 AI만 실행하고 Grafana annotation에 측정 구간을 표시한다. 다른
  개인 작업이 섞인 구간은 분리하며 녹음 내용이나 개인 작업 내용을 annotation에 쓰지 않는다.

## Mac 신규 설치

1. 실제 Mac의 macOS·칩·RAM·실행 계정과 기존 수집기 여부를 확인한다.
2. 공식 macOS Alloy `v1.20.1` 바이너리와 체크섬을 확인한다. Linux의 `/proc`·systemd
   설정을 복사하지 않는다.
3. Mac의 비공개 경로에서 `client.key`와 CSR을 생성한다. CSR만 중앙으로 보내
   `CN=knot-mac-alloy`·clientAuth 인증서를 서명하고 공개 `client.crt`·`ca.crt`만 반환한다.
   CA 개인키는 중앙에, 클라이언트 개인키는 Mac에 둔다. Dev·Prod 인증서를 재사용하지 않는다.
4. 중앙 Nginx의 mTLS·CN·POST 제한과 Mac 출발 주소 허용을 준비한다. 서버 인증서의 SAN이
   Mac에 설정할 HTTPS 호스트와 일치해야 한다. 주소·인증서·키·비밀번호를 저장소나 Vault에 넣지 않는다.
5. Mac 로그인 사용자로 아래 dry-run을 실행하고, 통과하면 같은 명령에 `--apply`를 추가한다.
   아래 환경변수는 비공개 운영 설정에서 주입하며 sudo로 설치하지 않는다.

```sh
python3 ops/observability/mac/install.py \
  --alloy "$KNOT_ALLOY_BINARY" \
  --ingest-base-url "$KNOT_INGEST_BASE_URL" \
  --tls-directory "$KNOT_MAC_TLS_DIR"
```

기본 명령은 주소·인증서·키 일치와 CN, Alloy 설정만 검증하며 설치·전송하지 않는다.
설치 경로는 `~/Library/Application Support/Knot/observability`, LaunchAgent는
`~/Library/LaunchAgents/kr.knot.mac-metrics.plist`다. 기존 설치가 있으면 덮어쓰지 않고 중단한다.
설정·인증서·로그는 사용자만 읽도록 설치한다. 관리 포트는 `127.0.0.1:12346`만 사용한다.
로그는 `logs/alloy.log`와 `.log.1`에 각각 약 2MiB씩 보존한다.

이 LaunchAgent는 사용자 로그인 세션에서 실행한다. 로그아웃·재부팅 후 로그인 전의 상시
수집은 보장하지 않는다. 무로그인 수집은 별도 LaunchDaemon 운영 범위이며, 이번 설치는
Mac의 전원·휴면 정책을 바꾸지 않는다.

## 실제 수신 확인

Mac 원시 표본의 CPU·물리 RAM·메모리 구성·swap·Data 볼륨 가용 공간을 확인하고,
[중앙 수신과 Grafana 확인](mac-monitoring-ec2.md#수신과-grafana-확인) 절차로 같은 호스트의
라벨·단위·최신 시각을 대조한다. 수집기 재시작 후에도 표본 시각이 두 번 이상 갱신돼야 한다.
기존 NCP에 연결하는 경우에는 Mac 수신과 기존 Dev·Prod 수신 유지를 각각 확인한다.

## 설정 변경과 재시작

### Mac 설정·인증서 변경

설치기는 업데이트 시 기존 경로를 덮어쓰지 않는다. 현재 설치의 설정·plist·TLS를 사용자만
읽는 비공개 경로에 백업하고, 저장소에서 바꾼 `config.alloy`만 설치 경로에 반영한다. 설정 파일
권한 `0600`, 디렉터리 `0700`을 유지하고 WAL 경로를 새로 만들거나 비우지 않는다.

설치한 바이너리 경로와 현재 HTTPS 주소를 비공개 설정에서 주입한 뒤 Mac에서 검사·재시작한다.
`KNOT_MAC_CONFIG_DIR`은 설치 경로여야 하고 `KNOT_INGEST_BASE_URL`도 validate 프로세스에
환경변수로 전달해야 한다.

```sh
export KNOT_MAC_CONFIG_DIR="$HOME/Library/Application Support/Knot/observability"
"$KNOT_ALLOY_BINARY" validate "$KNOT_MAC_CONFIG_DIR/config.alloy"
launchctl kickstart -k "gui/$(id -u)/kr.knot.mac-metrics"
launchctl print "gui/$(id -u)/kr.knot.mac-metrics"
```

주소를 바꾸면 설치 plist의 `EnvironmentVariables.KNOT_INGEST_BASE_URL`을 함께 바꾼다.
plist 변경은 아래처럼 검사한 뒤 다시 등록한다. 이미 로드된 LaunchAgent에 `kickstart`만
실행해서는 plist 변경을 다시 읽는다고 가정하지 않는다.

```sh
plutil -lint "$HOME/Library/LaunchAgents/kr.knot.mac-metrics.plist"
launchctl bootout "gui/$(id -u)/kr.knot.mac-metrics"
launchctl bootstrap "gui/$(id -u)" "$HOME/Library/LaunchAgents/kr.knot.mac-metrics.plist"
```

인증서 교체 후에는 기존 키와의 일치·clientAuth·Mac CN을 다시 검사하고 새 표본 수신까지
확인한다.

`job=integrations/unix`만 수신되면 `discovery.relabel "mac"`에 다음 규칙이 있는지 확인한다.
`prometheus.scrape.job_name`은 대상에 job이 이미 있으면 덮어쓰지 않는다.

```alloy
rule {
  target_label = "job"
  replacement = "mac-host"
}
```

수정 후 validate·kickstart와 중앙 `job="mac-host"`의 최신 표본을 확인한다. 과거
`integrations/unix` 시계열의 이름이 자동으로 바뀌지는 않는다.

## 기존 NCP 중앙을 확장할 때

이 절차는 NCP 접근 권한을 확보한 뒤 별도로 수행할 경로다. 현재 EC2 배포 결과로 완료 표시하지 않는다.

1. NCP ingress 운영 설정을 백업하고 Mac 전용 경로와 실제 Mac 출발 주소만 추가한다.
   `nginx -t` 후 reload하고 mTLS·Mac CN·POST 제한과 기존 Dev·Prod 수신을 확인한다.
2. 기존 provisioning에 `dashboards/knot-mac.yml`, `mac-dashboards/knot-mac.json`만 추가한다.
   인증된 Grafana 관리 경로로 provisioning을 reload하고 기존 provider·알림은 유지한다.
3. Mac의 endpoint와 해당 중앙 CA의 인증서를 맞춘 뒤 Mac 수신·Grafana 표시·Dev·Prod 수신을
   각각 검증한다. 기존 중앙 Compose 전체를 다시 배포하거나 이번 standalone Compose로 교체하지 않는다.

## 수집 중단과 원복

`launchctl bootout "gui/$(id -u)/kr.knot.mac-metrics"`로 Mac 수집기만 중단한다. 새로 설치한
LaunchAgent와 설치 폴더는 비공개 백업으로 보존하고 WAL·중앙 볼륨을 삭제하지 않는다.

기존 NCP 확장 작업을 원복할 때만 그 중앙의 Mac 경로·provider를 백업본으로 되돌린다.
Dev·Prod 경로·데이터는 삭제하지 않는다.

중앙 EC2 앱과 설정 원복은 [EC2 runbook](mac-monitoring-ec2.md#중앙-앱-중단과-원복)을 따른다.

## 공식 근거

- [Alloy macOS 설치](https://grafana.com/docs/alloy/latest/set-up/install/macos/)
- [Unix exporter와 Darwin collector](https://grafana.com/docs/alloy/latest/reference/components/prometheus/prometheus.exporter.unix/)
- [Darwin 메모리 표본 구현](https://github.com/prometheus/node_exporter/blob/master/collector/meminfo_darwin.go)
- [scrape job_name 기본값](https://grafana.com/docs/alloy/latest/reference/components/prometheus/prometheus.scrape/)
- [exporter 대상의 job·instance relabel 예제](https://grafana.com/docs/grafana-cloud/send-data/alloy/monitor/monitor-linux/#discoveryrelabel-instance-and-job-labels)
