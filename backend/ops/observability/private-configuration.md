# 관측성 비공개 설정

공인 원 서버 주소, 인증서·개인키와 비밀번호는 저장소에 넣지 않는다.
이 문서의 환경변수는 배포 서버 또는 운영자의 비공개 파일에만 설정한다.
다음 항목을 준비하기 전에는 관련 배포 스크립트를 실행하지 않는다.

| 항목 | 설정 위치 | 용도 |
| --- | --- | --- |
| `KNOT_INGEST_BASE_URL` | 각 Alloy 서버의 비공개 환경파일 또는 실행 환경 | HTTPS 수집 주소. 마지막 `/` 없이 지정하며 인증서 SAN과 일치해야 한다. |
| `KNOT_INGEST_IP` | IP SAN 인증서 생성 시 OpenSSL 실행 환경 | `tls/server.ext`가 읽는 IP. 실제 값과 키는 Git에 저장하지 않는다. |
| `/etc/nginx/snippets/knot-ingest-allow.conf` | 관측 서버의 비공개 Nginx 파일 | 허용한 Dev·Prod 원 서버의 `allow <주소>;`만 포함한다. |

`nginx/ingest.conf`는 위 allowlist를 포함한 뒤 `deny all`을 적용한다.
허용 목록 파일을 준비하고 `nginx -t`를 통과한 뒤 reload한다. 주소 비공개화는
접근 통제를 대신하지 않으며 mTLS, 환경별 client CN 확인과 POST 제한을 함께 유지한다.
Compose 실행 시 환경변수가 없으면 즉시 실패한다. root의 sudo 실행 환경은 변수가
제거될 수 있으므로 운영자 환경파일을 확인하고 필요한 변수만 전달한다.

원 서버 주소가 과거 공개 커밋에 있었던 사실은 이번 수정으로 사라지지 않는다.
IP 재할당과 원 서버 보안 그룹의 직접 접근 차단은 별도 운영 작업이다.

## 카탈로그 대시보드 재현

외부 원본 JSON과 생성 결과는 로컬 자료라 Git에서 제외한다. 버전이 고정된
17175 revision 2 원본 파일을 `KNOT_CATALOG_SOURCE`에 지정한다. 생성기는 SHA-256을
검증한 뒤 Dev 설정을 적용하므로 다른 원본을 조용히 사용하지 않는다.

```bash
export KNOT_CATALOG_SOURCE=/absolute/private/path/17175-revision-2.json
node ops/observability/build-catalog-dashboard.mjs
node --test ops/observability/catalog-dashboard.test.mjs
```

관측성 스택의 기본 `compose.yml`은 SSH 터널 전용 auth proxy 구성이다.
공용 Grafana를 배포할 때는 반드시 `compose.team.yml`을 함께 적용해 auth proxy를
끄고 사용자 로그인으로 전환한다. 로컬 Viewer Nginx 설정을 공개 리스너에 옮기지 않는다.
