# Prod 배포 로그 수신 검사

기존 #899 Blue-Green 배포의 후속 보완이다. 운영 관측 설정은 별도 Issue #963~#966의 develop 계열 PR에서 관리하며, 이 브랜치는 prod 배포 hook·번들 verifier·회귀 검사만 변경한다.

## 배포 후 대상 슬롯 로그 확인

prod 배포 코드 기준 `c986a6eb` 위의 후속 변경은 `scripts/bluegreen_deploy.sh`에 두 검사를 연결한다.
후보 시작 전 mTLS 조회 경로를 검사하고, Nginx·metrics 전환 후 **기존 슬롯 drain/종료 전에**
새 HTTP 요청의 완료 로그를 확인한다. 기존 packaging이 `backend/scripts`의 verifier도 번들에 넣는다.
이 PR이 main에 병합되고 새 artifact가 만들어지기 전에는 실행 중인 파이프라인에 자동 검사가 포함되지 않는다. 중앙 조회 경로와 slot/unit 라벨 설정(Issue #963, #966)이 선행되어야 한다.

검사 도구 `scripts/verify_deployment_logs.py`는 새 연결로 loopback Nginx의 health를 요청하고
HTTP 200·최상위 `status=UP`·정상 `X-Request-ID`를 확인한다. prod Origin 인증서는 번들의
Cloudflare Origin RSA root로 검증한다. 이어 기존 Alloy mTLS 인증서로 중앙의 제한된
`/<env>/log-receipt`를 조회한다. env·slot·unit·요청 ID·`HTTP dispatch finished`가 일치해야 성공한다.

중앙의 `nginx/log-receipt-locations.conf`는 Issue #966의 별도 PR에서 제공하며 기존 ingest server에 include한다. 기존 trace·Windows
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


## 로컬 검증

```sh
python3 backend/scripts/tests/test_verify_deployment_logs.py
python3 backend/scripts/tests/test_dev_bluegreen_deploy.py
bash -n backend/scripts/bluegreen_deploy.sh
```

실제 배포나 AWS API 실행 없이 fixture로 전환·rollback 순서를 확인한다. 로컬 fixture의 실패 없는 결과를 실제 green 배포 성공으로 해석하지 않는다.
