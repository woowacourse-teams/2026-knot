# Mac 관측 EC2 운영

Mac의 native Alloy가 CPU·메모리·swap·디스크 지표를 30초마다 수집해 별도 EC2의
Prometheus에 전송한다. Grafana의 `Knot-mac` 폴더, `Mac AI 서버 자원` 대시보드에서 조회한다.
2026-10-18 KST에 실제 Mac 설치·중앙 수신·Grafana 표시와 EC2 재부팅 후 서비스 복구를
확인했다. 재부팅 후에도 약 30초 간격의 새 표본이 계속 수신됐다. [검증 결과](../verification/762-mac-host-metrics.md)에 증명 범위와 남은 검증을 기록한다.

기존 NCP 접속 권한이 없어 사용자가 새 EC2 구축을 승인했다. 이 서버는 Mac 지표 전용이다.
기존 NCP Dev·Prod의 구성·데이터·수신 주소는 변경하지 않았으며, 이번 확인을 NCP 이관이나
Dev·Prod 운영 수신 검증으로 해석하지 않는다. AI 업무 성공·실패, 모델 진행 상태와 GPU·알림은
이 문서의 운영 범위에 포함하지 않는다.

Mac의 신규 설치·수집기 설정 갱신은 [Mac AI 호스트 자원 수집](mac-host-metrics.md)을 따른다.
이 문서는 별도 EC2의 방화벽·앱·인증서·관리 접속과 복구를 담당한다.

## 이번 EC2의 구성과 접근 경계

| 항목 | 운영 구성 |
| --- | --- |
| 호스트 | 서울 AL2023 arm64, `t4g.small` 2GiB, CPU credit `Standard`, IMDSv2 |
| 디스크 | 암호화 gp3 30GiB |
| 보안 그룹 | 기존 공유 `project-public` 재사용. 공유 규칙은 변경하지 않음 |
| 호스트 방화벽 | nftables 전용 `inet knot_guard`의 INPUT·FORWARD 기본 drop |
| INPUT 예외 | 관리자 IPv4 `/32`의 SSH 22, Mac IPv4 `/32`의 HTTPS 443, loopback, established/related, ICMP·ICMPv6, 실제 주 ENI `ens5`의 DHCPv4 UDP 67→68 |
| FORWARD 예외 | established/related와 `docker0`·`br-*`에서 시작하는 트래픽. 외부에서 컨테이너로 새로 들어오는 연결은 허용하지 않음 |
| 중앙 앱 | `mac/central/compose.yml`의 Prometheus·Grafana 두 서비스만 실행 |
| 앱 바인딩 | host network, Prometheus `127.0.0.1:9090`, Grafana `127.0.0.1:3000`. `ports` publish 없음 |
| 앱 권한·자원 | 이미지 기본 non-root, `cap_drop: [ALL]`, `no-new-privileges`, Prometheus 메모리 512MiB·CPU 1개, Grafana 메모리 1GiB·CPU 1개와 `GOMEMLIMIT=640MiB` |
| 중앙 보존 | Prometheus 14일 또는 `2GB`(2GiB) 중 먼저 도달하는 조건으로 오래된 블록 정리 |
| Grafana 관리 | 비공개 파일의 무작위 관리자 비밀번호, SSH 터널의 로컬 13000 포트 |

AL2023 공식 Docker 25에서는 localhost publish의 동일 L2 노출 경고가 적용될 수 있다.
따라서 포트 publish 없이 호스트 네트워크에서 앱 자체를 loopback에 바인딩한다. 이 방식은
컨테이너와 호스트 사이의 네트워크 격리를 제공하지 않는다. 전용 호스트의 두 앱이 서로의
localhost에 접근할 수 있다는 전제로 권한과 바인딩을 유지한다. 3000·9090 포트는 공개하지 않는다.

공유 SG의 SSH 허용 범위는 호스트의 `/32`보다 넓다. 최초 부팅 후 nftables를 설치하고
guard를 적용하기 전에는 공유 SG의 SSH 허용 범위가 적용됐고 첫 SSH도 그 범위에서 접속했다.
cloud-init은 임시 SSH 키만 추가했으며 nftables 설치·guard 적용은 SSH 초기 설치 단계에서
실행했다. 이 구성을 전용 SG와 같은 경계로 간주하지 않는다. 설치 후에는
`sshd`·`nginx`·`docker` 모두 guard를 요구하고 뒤에 시작한다.

중앙 보존은 디스크 사용량의 하드 제한이 아니다. WAL·head와 블록 병합 중 임시 공간 때문에
설정 크기를 넘을 수 있으므로 호스트 여유 공간도 확인한다. Mac의 로컬 WAL은 별도이며 같은
`wal/` 경로를 재사용하고 최대 2시간 보존한다. 장기 단절이나 용량 부족의 무손실을 보장하지 않는다.

### Grafana 자원 부족과 보완

2026-10-17 16:42:19 UTC에 대시보드 쿼리 중 Grafana의 메모리 사용량이 초기 컨테이너 제한
512MiB에 근접한 511.2MiB까지 올라갔다. 커널의 `CONSTRAINT_MEMCG` OOM kill과 Docker
자동 재시작 1회를 확인했다. 당시 Prometheus는 ready였고 기존 데이터는 유지됐다.

Grafana만 컨테이너 제한을 1GiB·CPU 1개로 올리고 `GOMEMLIMIT=640MiB`를 추가한다.
Prometheus의 512MiB·CPU 1개와 보존 설정은 유지한다. Grafana 공식 최소 512MB·1코어는
평가 시작점이며 실제 대시보드 부하를 수용한다는 보장이 아니다. GOMEMLIMIT도 Go 런타임의
soft limit으로, 프로세스 전체 RSS·플러그인·컨테이너 메모리의 하드 제한이 아니다.

운영 반영 시 아래의 구문 검사 뒤 Grafana 서비스만 재생성한다. Prometheus와 영속 볼륨을
내리지 않고 Grafana health·loopback 바인딩·최신 표본과 대시보드 조회를 다시 확인한다.

```sh
cd /opt/knot-observability/mac/central
if sudo env KNOT_CENTRAL_SECRETS_DIR=/opt/knot-observability/private docker compose config --quiet; then
  sudo env KNOT_CENTRAL_SECRETS_DIR=/opt/knot-observability/private docker compose up -d --no-deps grafana
fi
```

이 보완은 관측된 cgroup OOM의 여유를 늘리는 조치이며 장기 안정성의 증거가 아니다.
같은 대시보드 부하에서 재시작 횟수·컨테이너 메모리·호스트 가용 메모리와 새 OOM 로그를
계속 확인한다. 호스트 RAM은 여전히 2GiB이므로 두 컨테이너의 제한과 OS 여유를 함께 봐야 한다.
반복 OOM이나 호스트 메모리 부족이 생기면 쿼리·갱신 부하와 용량을 다시 검토한다.

### 중앙 파일과 서비스

아래 경로에는 실제 주소나 비밀값을 문서 대신 비공개 운영 설정으로 넣는다. 저장소의 상대
경로는 `backend/ops/observability/` 기준이다.

| 원본 또는 운영 파일 | 역할 |
| --- | --- |
| `mac/central/` → `/opt/knot-observability/mac/central/` | Compose·Prometheus·datasource 설정 |
| `provisioning/dashboards/knot-mac.yml`, `provisioning/mac-dashboards/` | 기존 상대 디렉터리 구조를 유지해 Mac provider만 바인딩 |
| `mac/central/host-guard.nft.template` → `/etc/knot-observability/host-guard.nft` | 비공개 관리자·Mac IPv4 `/32`와 주 ENI를 주입한 규칙 |
| `mac/central/knot-host-guard.service` | 규칙 검사 후 원자적 적용. 종료 시 규칙을 flush하지 않음 |
| `mac/central/requires-host-guard.conf` | `/etc/systemd/system/{sshd,nginx,docker}.service.d/` 각각의 drop-in |
| `mac/central/knot-mac-observability.service` | guard·Docker 뒤에 Compose 시작. 작업 경로와 secrets 경로 지정 |
| `/etc/nginx/nginx.conf` | 기본 80 수신을 제거하고 http 블록에서 아래 conf를 include |
| `/etc/nginx/conf.d/knot-ingest.conf` | `nginx/ingest.conf`를 배치한 호스트 mTLS 수신 설정 |
| `/etc/nginx/snippets/knot-ingest-allow.conf` | 실제 Mac 출발 IPv4 한 곳 허용 |
| `/opt/knot-observability/tls/` | Nginx의 `ca.crt`, `server.crt`, `server.key` |
| `/opt/knot-observability/pki/ca.key` | 중앙 root만 읽는 CA 개인키, 권한 `0400` |
| `/opt/knot-observability/private/grafana-admin-password` | Grafana UID 472가 읽는 `0400` 파일. 상위 private 디렉터리는 root `0700` |

`nginx/ingest.conf`에는 기존 Dev·Prod 경로도 있지만, standalone Compose는 Loki나 기존
Dev·Prod 수집기를 구성하지 않는다. 이번 EC2를 기존 운영 수신 주소로 배포하지 않는다.

## 수신과 Grafana 확인

중앙 EC2에서 다음 명령으로 서비스를 확인한다.

```sh
sudo systemctl is-active knot-host-guard docker nginx knot-mac-observability
curl --fail --silent http://127.0.0.1:9090/-/ready
curl --fail --silent http://127.0.0.1:3000/api/health
sudo ss -ltnp
```

모든 서비스가 active이고 Prometheus ready·Grafana DB health가 정상이어야 한다. `ss`에서
3000·9090이 IPv4 loopback에만 바인딩됐는지 확인한다. 앱의 정상 실행만으로 최신 수신을
판정하지 않는다. 중앙 Prometheus에서 아래 조건과 표본 시각의 두 차례 이상 갱신을 확인한다.

```promql
up{project="knot",env="shared",job="mac-host",instance="knot-ai-macmini"}
```

값은 `1`이어야 한다. `node_memory_total_bytes`의 물리 RAM 16GiB, CPU·메모리·swap·Data 볼륨의
이름·단위도 원시 표본과 대조한다. 인증된 GET은 403이어야 한다. Dev·Prod 교차 CN과
클라이언트 인증서 없는 요청의 거절은 각각 검사하며 로컬 합성 검사를 운영 검사로 대체하지 않는다.

관리 Mac에서 비공개 SSH 설정과 현재 중앙 주소를 사용해 터널을 연다. SSH 호스트 키 검증을
끄지 않는다. 임시 작업 키는 2026-10-18 KST 기준 Mac 쪽 06:18, EC2 쪽 06:50에
만료된다. 이후 SSH 관리·터널에는 승인된 팀 키가 필요하다. 이 키의 만료는 LaunchAgent의
mTLS 전송과 별개이며 계속 수집을 중단시키지 않는다.

```sh
ssh -i "$KNOT_SSH_KEY" -o IdentitiesOnly=yes -o ExitOnForwardFailure=yes \
  -N -L 127.0.0.1:13000:127.0.0.1:3000 "ec2-user@$KNOT_CENTRAL_HOST"
```

`http://127.0.0.1:13000`에 접속하고 비공개 비밀번호로 `knot-admin` 계정에 로그인한다.
`Knot-mac` 폴더에서 RAM 16GiB와 최신 표본 시각을 확인한다. 데이터 없음이 `0`으로 보이거나
수집 중단 뒤 과거 값만 남아 있으면 완료로 판단하지 않는다.

## 중앙 설정·방화벽 변경

중앙 설정을 비공개 경로에 백업하고 `mac/central`과 Mac provisioning의 상대 디렉터리 구조를
유지해 반영한다. 비밀번호나 TLS 파일은 저장소 파일 복사에 섞지 않는다. 앱 설정 변경 후에는
중앙 EC2에서 다음 명령으로 구문을 검사하고 적용한다. `config --quiet`는 비밀값이 포함될 수
있는 전체 렌더링 출력을 남기지 않는다.

```sh
cd /opt/knot-observability/mac/central
sudo env KNOT_CENTRAL_SECRETS_DIR=/opt/knot-observability/private docker compose config --quiet
sudo systemctl restart knot-mac-observability
sudo nginx -t
sudo systemctl reload nginx
```

Nginx 변경은 `/etc/nginx/conf.d/knot-ingest.conf`와 Mac 출발 주소 snippet을 대상으로 한다.
기본 80 수신을 다시 열지 않는다. 서비스 unit이나 drop-in 변경 후에는
`sudo systemctl daemon-reload`를 실행한다. `systemctl show`의 `Requires`·`After`에서
`sshd`·`nginx`·`docker` 세 서비스가 모두 guard에 의존하는지 확인한다.

방화벽은 관리자·Mac IPv4 `/32`와 실제 주 ENI를 비공개 설정으로 template에 주입한다.
잘못된 주소로 SSH를 차단하지 않도록 비공개 백업과 시간 제한 원복 작업을 준비한 뒤 적용한다.
전체 `flush ruleset`을 사용하지 않는다.

```sh
sudo nft -c -f /etc/knot-observability/host-guard.nft
sudo systemctl reload knot-host-guard
sudo nft list table inet knot_guard
```

새 SSH 세션과 Mac mTLS 수신을 확인한 뒤 원복 예약을 해제한다. 기존 SSH 세션은
established 예외로 살아 있을 수 있으므로 새 연결 성공을 대신하지 못한다.

## 중앙 EC2 재부팅 확인

재부팅 전 최신 표본 시각과 boot ID를 기록하고 `sudo systemctl reboot`를 실행한다. 현재
관리자 주소의 새 SSH 세션으로 다시 접속한 뒤 boot ID 변경, 위 서비스 4개의 active,
Prometheus ready·Grafana health, loopback 바인딩, guard·drop-in 유지와 새 Mac 표본을 확인한다.
새 표본 시각이 두 차례 이상 증가해야 수신 복구로 판정한다. 기존 볼륨은 유지하며
`docker compose down -v`를 실행하지 않는다. EC2 재부팅 확인은 Mac 재부팅 복구 검증과 다르다.

TLS 인증서는 365일로 발급했으므로 만료 전에 갱신한다. 중앙 공개 IP가 바뀌면 새 주소에 맞게
서버 인증서 SAN·Mac HTTPS endpoint·SSH 접속 대상을 갱신하고, Mac 출발 주소나 관리자
출발 주소도 바뀌었다면 nftables와 Nginx allow를 함께 조정한다. 서버의 공개 IP를 고정이라고
가정하지 않는다. 변경 뒤 TLS 호스트 검증과 최신 표본 수신을 다시 확인한다.

## 중앙 앱 중단과 원복

이번 EC2 앱만 멈출 때는 `sudo systemctl stop knot-mac-observability`를 사용하고 guard는
유지한다. 설정 원복은 백업을 복구한 뒤 validate·`nginx -t`·앱 재시작과 새 표본으로 확인한다.

## 공식 근거

- [Docker host network](https://docs.docker.com/engine/network/drivers/host/)
- [localhost publish의 구버전 경고](https://docs.docker.com/engine/network/port-publishing/)
- [Prometheus 보존 조건과 디스크 여유](https://prometheus.io/docs/prometheus/latest/storage/)
- [Grafana 최소 자원과 실제 부하 검증](https://grafana.com/docs/grafana/latest/setup-grafana/installation/)
- [Go 런타임 GOMEMLIMIT](https://pkg.go.dev/runtime#hdr-Environment_Variables)
- [Go memory limit의 범위와 여유](https://go.dev/doc/gc-guide#Memory_limit)
