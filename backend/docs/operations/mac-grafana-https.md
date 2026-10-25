# Mac Grafana 구성과 HTTPS 설정

실제 적용 여부는 현재 환경의 인증서·접속 결과로 확인한다.
EC2·Mac 수집의 기본 구성은 [기존 운영 문서](mac-monitoring-ec2.md)를 따른다.

## 현재 폴더 구성

Mac file provider는 `Knot Mac` 폴더 UID `knot-mac-folder`를 사용한다.
대시보드 UID `knot-mac`, `knot-mac-ai`와 폴더 UID를 구분하고, provider와
`provisioning/mac-dashboards/`의 상대 경로를 함께 유지한다.
UID 비충돌 검사는 `python3 -m unittest discover backend/ops/observability/mac/ai -p test_config.py`로 확인한다.

이전 UID 설치를 옮기는 일회성 도구와 검증 환경은 Git 이력에 보관한다.
이전 설치의 이동이 필요하면 [정리 전 구성과 절차](https://github.com/woowacourse-teams/2026-knot/blob/31b1e332aa0a8545cfd4a320fe42a7de11618bbd/backend/docs/operations/mac-grafana-https.md)를
복구하고 실제 폴더·권한·전체 Grafana 데이터 백업을 확인한 뒤 적용한다.

## HTTPS 후보 만들기

기존 운영의 Cloudflare → Nginx TLS → loopback Grafana 경로를 재현한다.
기본 Compose는 SSH 접속용이고 HTTPS는 `compose.https.yml`을 추가한다.
3000·9090은 loopback을 유지한다. 기존 기본 TLS server의 Mac 전용 mTLS와
공개 Grafana의 일반 TLS는 서로 다른 SNI 경로다. 공개 경로도 Grafana 로그인이 필요하다.

Cloudflare 공식 IPv4 목록을 비공개 작업 파일에 받아 검토한다. plain text 주소가 차단되면
공식 `https://api.cloudflare.com/client/v4/ips`의 `result.ipv4_cidrs`를 줄 단위로 저장한다.
렌더러는 네트워크에서 목록을 임의 갱신하거나 호스트에 적용하지 않는다.
IPv6 origin·AAAA·IPv6 listen은 이 구성에서 지원하지 않는다.

```sh
python3 render_https.py --domain "$KNOT_GRAFANA_DOMAIN" \
  --admin "$ADMIN_IPV4/32" --mac "$MAC_IPV4/32" --interface "$PRIMARY_IFACE" \
  --cloudflare-ipv4 "$PRIVATE/cloudflare-ipv4.txt" --out "$PRIVATE/candidate"
```

출력 디렉터리는 새로 생성되며 재사용을 거부한다. 도메인·출발지·ENI를 실제 값과 대조하고
Cloudflare 대역은 공식 목록과 비교한다. 구문 검사는 해당 대역의 소유자를 증명하지 않는다.

| 생성물 | 설치 위치 |
| --- | --- |
| `host-guard.nft` | `/etc/knot-observability/host-guard.nft` |
| `knot-cloudflare-realip.conf` | `/etc/nginx/snippets/knot-cloudflare-realip.conf` |
| `knot-grafana-public.conf` | `/etc/nginx/conf.d/knot-grafana-public.conf` |
| `grafana-https.env` | `/etc/knot-observability/grafana-https.env` |

realip와 방화벽은 같은 목록을 사용한다. Cloudflare IPv4에만 80·443을 추가하고
기존 관리자 SSH·Mac 443·DHCP·Docker forward 규칙은 보존한다. 전체 ruleset은 지우지 않는다.
신뢰한 프록시의 `CF-Connecting-IP`만 원래 사용자 주소로 쓰고 전달 헤더를 덮어쓴다.

## 인증서와 활성화 순서

운영자 환경에서 `KNOT_GRAFANA_DOMAIN`과 백업 경로를 먼저 설정한다. 기존 서비스가
사용 중인 Compose·Nginx를 먼저 백업한다. root 환경에서 다음 순서를 따른다.

1. DNS origin IPv4·Cloudflare 프록시·SSL **Full (strict)** 설정을 확인한다.
   인증서가 없는 신규 origin은 공개 TLS server를 활성화하기 전에 HTTP ACME challenge부터 준비한다.
2. 기존 `nginx.conf`의 http 블록에서 `knot-ingest.conf`를 유지하고 공개 conf include를
   추가한다. 최초 인증서 발급 전에는 템플릿의 80번 server 두 개와 map만 설치한다.
   `listen 443 ssl` server는 인증서가 준비될 때까지 넣지 않는다. `nginx -t` 후 reload한다.
   access log는 기존처럼 끈다.
3. 방화벽 원본 백업과 시간 제한 원복 작업을 예약하고 `nft -c -f 후보파일`을 실행한다.
   후보를 설치하고 `systemctl reload knot-host-guard`한다. 새 SSH 연결·Mac 수신을 확인한
   후 원복 예약을 해제한다. 기존 SSH 연결 유지만으로 성공을 판정하지 않는다.
   Cloudflare의 80번 접근이 허용된 뒤 `/var/lib/knot-acme/.well-known/acme-challenge`에
   검사용 파일을 만들어 공개 도메인에서 읽히는지 확인한다. Cloudflare 리다이렉트·WAF·Access가
   challenge를 막으면 인증서 발급 전에 해결한다.
4. AL2023에 설치된 Certbot으로 `certbot certonly --webroot -w /var/lib/knot-acme
   -d "$KNOT_GRAFANA_DOMAIN"`을 실행한다. 계정 등록·이메일·약관은 운영자가 처리한다.
   인증서가 이미 있으면 재발급하지 않고 기존 renewal 설정·만료일을 확인한다.
5. 완전한 공개 conf와 realip snippet을 설치해 `nginx -t` 후 reload한다.
   private key는 root만 읽도록 유지하고 저장소에 복사하지 않는다.
6. 두 Compose 파일을 함께 설치한다. 생성한 `grafana-https.env`를 root 소유 `0600`으로
   위 경로에 설치하고 `mac/central/.env`를 그 파일에 대한 심볼릭 링크로 만든다.
   기존 `.env`가 있으면 덮어쓰지 말고 기존 항목을 보존하며 세 키만 병합한다.
   `https.service.conf`를 `knot-mac-observability.service.d/https.conf`로 설치한다.
   따라서 수동 Compose와 systemd 재시작이 같은 HTTPS 설정을 사용한다.
7. `systemctl daemon-reload` 후 `sudo docker compose config --quiet`,
   `sudo docker compose up -d --no-deps grafana`를 실행한다. Prometheus는 재생성하지 않는다.
8. `knot-nginx-reload`를 `/etc/letsencrypt/renewal-hooks/deploy/`에 root 소유 `0755`로
   설치하고 `systemctl enable --now certbot-renew.timer`를 실행한다.
   `certbot renew --dry-run`으로 HTTP challenge 갱신을 확인한다. deploy hook은 기본 dry-run에서
   실행되지 않으므로 `nginx -t`와 hook 실행도 별도로 확인한다. hook은 갱신 성공 뒤에만 reload한다.

HTTPS 활성화 후 일반 HTTP 터널 접속은 도메인 리다이렉트·Secure 쿠키 때문에 로그인용으로
쓸 수 없다. 운영 확인은 공개 HTTPS에서 수행한다. 로컬 API 검사에는 올바른 Host 헤더를
쓰고 인증은 비공개 파일에서 읽는다. 비밀번호를 명령 인자·로그·채팅에 쓰지 않는다.

## 배포·갱신 확인과 복구

- 공개 HTTPS 로그인·목록 펼치기·두 대시보드와 쿠키의 Secure 속성, HTTP→HTTPS를 확인한다.
- 인증 없는 `/api/search`는 401, `/metrics`·`/debug/`는 404인지 확인한다.
- 3000·9090의 loopback 바인딩, Cloudflare 외 출발지 차단, Mac mTLS POST와 최신 표본을 확인한다.
- `systemctl is-enabled certbot-renew.timer`, 인증서 만료일과 갱신 dry-run 결과를 기록한다.
- Cloudflare 대역 변경 때 후보를 다시 만들고 이전 목록과 비교해 같은 방화벽 검증을 반복한다.
- 실패 시 원본 Nginx·Compose·env·drop-in·방화벽을 복구하고 구문 검사→daemon-reload→
  Grafana 재생성·Nginx reload 후 관리 접속과 새 표본을 확인한다. HTTPS 중단 중에는 원본
  loopback 구성과 SSH 터널로 복구할 수 있다. SSH·Mac 수신 예외를 먼저 보존한다.

공식 근거: [Grafana 파일 프로비저닝](https://grafana.com/docs/grafana/latest/administration/provisioning/#dashboards),
[13.2.3 checksum 처리](https://github.com/grafana/grafana/blob/v13.2.3/pkg/services/provisioning/dashboards/file_reader.go),
[Cloudflare IPv4](https://www.cloudflare.com/ips-v4/),
[Certbot 갱신](https://eff-certbot.readthedocs.io/en/stable/using.html#renewing-certificates).
