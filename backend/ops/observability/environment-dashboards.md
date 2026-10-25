# Dev·Prod 대시보드와 Discord 알림 읽는 법

## 구성 기준

저장소의 환경별 기본 구성은 Dev 7개·Prod 5개다. Dev 카탈로그 17175 revision 2는
[비공개 설정과 재현 절차](private-configuration.md)에 따라 별도 원본에서 생성하며,
포함하면 Dev는 8개가 된다. 실제 설치 상태는 Grafana의 파일 공급자·폴더·지표 수신으로 확인한다.

기존 공통 6개 대시보드와 알림 연결을 유지한다. `Knot-dev`, `Knot-prod` 폴더의 쿼리는
환경을 고정하며, 혼합 화면 안의 환경 선택 변수로 조회 대상을 바꾸지 않는다.

## 화면 유지 기준

- Grafana 기본 테마·패널·24칸 grid를 사용하고 로딩·오류·키보드 동작은 기본 컴포넌트를 따른다.
- 화면과 탐색 링크에 DEV·PROD를 명시한다. 상태는 색과 텍스트를 함께 표시하고 패널 제목·설명은 한국어로 쓴다.
- 안내 → 상태 → 로그 → 서버 → 런타임·DB 순서를 유지한다. 구체적인 배치와 갱신 주기는 JSON과 생성기에서 관리한다.
- 지표 없음은 정상 0으로 채우지 않고 로그와 시계열의 빈 구간을 연결하지 않는다. 환경 간 링크는 시간만 전달한다.
- 실제 Grafana의 좁은 화면·키보드·한국어 줄바꿈·쿼리 응답은 별도로 확인한다. 정적 구성 검사를 시각 QA 결과로 대신하지 않는다.

## 어디서 무엇을 보는가

| 화면 | 답하려는 질문 | DEV | PROD |
| --- | --- | --- | --- |
| 01 전체 상태 | 지금 health가 응답하나? 지표는 들어오나? 자원이 찼나? | 상태·자원·health 이력 | 상태·자원·health 이력 |
| 02 API 요청·오류 | 실제 요청이 얼마나 오고 어떤 경로가 실패하거나 느린가? | Nginx 기준 | Nginx 기준 |
| 03 로그 | 그 시각 실제로 무슨 일이 있었나? | Spring·Nginx·Nginx 5xx | 프로토타입·Nginx·Nginx 5xx |
| 04 서버 자원 | AWS 앱 서버 CPU·메모리·디스크가 시간에 따라 변했나? | 호스트 지표 | 호스트 지표 |
| 05 Spring 앱 | JVM·GC·앱 지연 분포는 어떤가? | Spring 내부 지표 | 해당 없음 |
| 06 Tomcat·DB 연결 풀 | 요청 처리 스레드나 DB 연결을 기다리는가? | Tomcat·Hikari | 해당 없음 |
| RDS | 지정한 AWS RDS의 상태는 어떤가? | 07 `knot-dev-database` | 05 `knot-database`·별도 AWS DB |
| 08 Spring Boot Observability · 17175 | 앱 시작 이후 요청·최근 지연·앱 로그를 원본 배치로 볼 수 있나? | 추가 카탈로그 화면 | 해당 없음 |

Prod 프로토타입의 실제 DB는 별도 Docker PostgreSQL이다. Prod RDS 화면을 실제 앱 DB 상태로 해석하지 않는다. 서버 자원은 AWS 앱 서버 지표이며 NCP Grafana 서버의 메모리가 아니다.

## 질문에 맞는 시각화

| 질문 | 시각화 | 위치·주의점 |
| --- | --- | --- |
| 지금 정상인가? | Stat | 외부 health·최근 지표 존재. 수집 중은 앱 정상이나 EC2 running의 증거가 아니다. |
| 자원이 얼마나 찼나? | Gauge | 현재 CPU·메모리·디스크 %. 색상은 알림 지속 시간 조건 충족을 뜻하지 않는다. |
| 시간에 따라 어떻게 변했나? | Time series | 요청 수·오류 비율·응답 시간·상세 자원. 요청이 없는 지연·비율은 비워 둔다. |
| 상태가 언제 바뀌었나? | State timeline | 외부 health 성공/실패 이력. 배포나 전원 상태 이력이 아니며 빈 구간을 성공으로 연결하지 않는다. |
| 어떤 경로에 오류가 많나? | Table | 선택 기간 전체의 URI·HTTP 상태별 4xx/5xx 건수. 최근 5분 비율과 구분한다. |
| 로그 원문은 무엇인가? | Logs | 앱 / 전체 Nginx / Nginx 5xx. 기본 1시간, 줄바꿈·시간·레이블 표시. |
| 응답 시간이 어떤 구간에 몰리나? | Heatmap | Dev Spring의 실제 histogram bucket. Prod에는 없는 데이터를 만들지 않는다. |

## 문제가 생겼을 때의 순서

1. 해당 환경 `01 전체 상태`에서 health와 지표 수집 상태를 구분한다.
2. 시간 범위를 문제 발생 시각으로 맞춘다. 화면 링크는 시간을 전달한다.
3. `02 API 요청·오류`에서 실제 요청량, 4xx/5xx, p95와 경로별 오류를 본다.
4. `03 로그`에서 같은 시각의 Nginx 요청과 앱 예외를 대조한다.
5. CPU·메모리·디스크이면 `04 서버 자원`, Dev 앱 내부이면 Spring·연결 풀·RDS로 좁힌다.

| 증상 | 먼저 확인할 곳 | 단정하면 안 되는 것 |
| --- | --- | --- |
| Spring 지표 수집 끊김 | Dev health → Spring 수집 상태 → Alloy 상태·로그 → actuator 수집 경로 | API가 죽었다고 단정하지 않는다. |
| health 실패 | health 이력 → 배포 시각 → Nginx·앱 로그 | health 하나로 모든 기능·원인을 확정하지 않는다. |
| API 5xx 증가 | API 오류 경로 → Nginx 5xx → 앱 예외 | health 200을 전체 API 정상으로 보지 않는다. |
| p95 증가 | 요청 수 → 느린 경로 로그 → 자원 → Dev 앱·풀 | 저트래픽 단일 요청이나 서로 다른 런타임을 단순 비교하지 않는다. |
| 로그 없음 | 시간 범위를 넓히기 → 앱/요청 로그 분리 → Alloy 로그 | 조용한 앱·좁은 조회 구간을 수집 장애와 혼동하지 않는다. |
| Grafana 자체 502 | NCP 프록시·Grafana 컨테이너 상태/로그·OOM·메모리 | AWS 앱 서버 메모리 그래프로 Grafana 자원을 진단하지 않는다. |

## 읽기 쉬운 Discord 알림

설계 예시이며 실제 Discord 캡처는 아니다.

```text
[확인 필요] DEV · Spring 지표 수집 끊김

문제: Spring 지표 수집 끊김
감지: 최근 3분 동안 Spring scrape 성공이 없거나 데이터가 끊겼습니다.
확인: API 중단을 확정하지 않습니다. DEV health → Spring 수집 상태 → Alloy 로그·actuator 수집 경로
시각: 10/02 15:55:00 KST (발생 시작)
전체 상태 · 로그 · 알림 상세
```

- 알림 조건·쿼리·대기 시간·Dev/Prod 라우팅·Webhook·복구 알림 활성화는 유지한다.
- `A=0, B=1`, 전체 라벨, 긴 Silence·Panel URL 대신 짧은 링크를 표시한다. 평가 수식은 알림 상세에서 확인한다.
- 테스트는 `[테스트]`, 해제는 `[알림 해제]`로 표시한다. 규칙 변경으로 해제됐을 때도 복구 완료라고 단정하지 않는다.
- KST 시각은 알림 인스턴스의 발생 시작/해제 시각이며 Discord 수신 시각과 다를 수 있다.
- 그룹 상세는 최대 2건이며 나머지는 전체 알림 목록으로 안내한다. env가 없으면 추측하지 않고 `환경 확인 필요`로 표시한다.

## 구성 파일과 설치 후 검증

대시보드 원본 생성기는 `build-environment-dashboards.mjs`, 산출물은 `provisioning/environment-dashboards/dev`와 `prod`에 있다. 파일 공급자 설정은 `provisioning/dashboards/knot.yml`이다. 기존 Compose의 provisioning 읽기 전용 mount를 사용한다.

Discord 연결 설정은 `provisioning/alerting/knot.json`, 알림 템플릿 그룹은 `provisioning/alerting/discord-templates.json`이다. 두 파일을 함께 반영해야 한다. 템플릿 내부 `$index`는 환경변수 치환 대상이 아닌 `templates[].template`에 둔다.

`deploy-environment-dashboards.py`는 환경별 JSON과 공급자·알림·템플릿을 함께 적용하는 서버용 스크립트다. 원본 상대 경로를 보존한 stage와 각 프로비저닝 파일의 SHA-256을 담은 `manifest.json`이 필요하다. 카탈로그까지 포함한 현재 구성은 NCP에서 `python3 deploy-environment-dashboards.py --stage <stage> --dev-count 8`로 읽기 전용 사전 검사를 수행한 뒤, 승인된 적용 단계에서 `--apply`를 추가한다. 최초 7개 구성은 기본 `--dev-count 7`을 사용한다.

사전 검사는 실제 읽기 전용 마운트, 기존 공급자, 공통 6개, 알림 15개, 기존 설정과 새 설정의 불변 계약을 확인한다. 알림의 `display_name`·`summary`·`description`, Discord의 `title`·`message`만 계약 비교에서 제외한다. 쿼리·조건·대기·라우팅·Webhook·알림 패널 링크의 차이가 있으면 백업·설치 전에 중단한다. 운영 설정의 차이를 이 스크립트로 덮어쓰지 않는다.

적용 시 서버 안의 `environment-backup.*` 디렉터리(0700)에 대상 파일, 공통 대시보드, Compose, 실행 알림·연락처·정책·기존 템플릿을 백업한다. 변경된 리소스의 Admin reload만 수행하고, 실행 알림 계약·공통 JSON·컨테이너 환경·폴더별 개수·템플릿을 재확인한다.

새 대시보드 디렉터리는 Grafana가 읽을 수 있는 권한으로 준비하고 비밀값은 별도 비공개 파일로 유지한다.
실패하면 적용 직전 백업과 실행 계약을 대조해 복구한다. Preview 또는 연락처 사용자 정의 테스트에서
`labels.test="true"`를 지정하면 `[테스트]`를 표시한다. 실제 수신 여부는 별도로 확인한다.

로컬 검증:

```sh
node --test ops/observability/environment-dashboards.test.mjs ops/observability/discord-notifications.test.mjs
node --test ops/observability/catalog-dashboard.test.mjs
node --check ops/observability/build-environment-dashboards.mjs
node --check ops/observability/check-dashboard-data.mjs
```

`check-dashboard-data.mjs`로 운영 Prometheus/Loki의 쿼리 오류와 비유한 값을 확인한다.
요청이 없는 빈 결과는 0으로 채우지 않는다. 카탈로그 테스트는 고정 원본과 생성 파일을 준비한 뒤 실행한다.
로컬 검사와 실제 서버의 수집·알림 전달·화면 확인 결과는 구분해 기록한다.

공식 근거: [파일 프로비저닝](https://grafana.com/docs/grafana/latest/alerting/set-up/provision-alerting-resources/file-provisioning/), [알림 템플릿 데이터·KST 함수](https://grafana.com/docs/grafana/latest/alerting/configure-notifications/template-notifications/reference/), [Discord 제목·본문 설정](https://github.com/grafana/alerting/blob/main/receivers/discord/v1/config.go).
