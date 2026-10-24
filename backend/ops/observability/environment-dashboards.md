# Dev·Prod 대시보드와 Discord 알림 읽는 법

## 현재 반영 상태

2026-10-02 운영 Grafana에 환경별 구성과 Discord 템플릿을 설치했다. 처음 요청한 Dev 7개·Prod 5개를 확인한 뒤 추가 요청한 카탈로그 17175를 Dev에만 넣어 최종 폴더는 **Dev 8개·Prod 5개**다. 기존 공통 6개와 알림 15개는 보존했다. 부모 클라우드 브라우저에서 양쪽 폴더·전체 상태·로그·RDS 구분과 Dev Spring·풀 실데이터, 추가 17175 화면을 확인했다.

AWS Dev 현재 배포본에는 관측성 자산이 없어 내부 지표가 비었다. 사용자의 추가 재배포 승인 후 현 앱의 나머지 클래스·리소스와 기존 라이브러리가 동일한 후보를 백업·설치하고 Dev 앱만 재시작했다. NCP에서 Spring up=1, JVM·Tomcat·Hikari·HTTP histogram 도착과 외부 health 정상, 공개 metrics 403·EC2 직접 metrics 401을 확인했다. NCP Grafana는 재시작하지 않았다.

실제 Grafana Preview 8개는 통과했다. 기존 팀 수신처를 검증하고 Dev·Prod 테스트를 요청했다. Prod는 테스트 API의 `success`를 확인했고 Dev는 HTTP 200 뒤 응답 검사 코드가 구형 성공값을 기대해 멈췄으므로 본문 성공 여부를 확정하지 않는다. 사람의 새 메시지 수신 화면은 아직 확인하지 않았다. 이전 수신 캡처와 이번 검증을 구분한다. 이 내용은 당시 관측 요약이며 현재 수신 여부는 운영 환경에서 다시 확인한다.

기존 공통 6개 대시보드와 알림 연결은 유지한다. 새 최상위 폴더는 `Knot-dev`, `Knot-prod`이며 환경 선택 변수 없이 실제 쿼리에 env를 고정한다. 혼합 화면 안에서 env를 바꾸는 방식이 아니라 각 폴더에 독립 대시보드를 둔다.

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

이번 운영 반영에서는 파일·알림 리소스를 먼저 백업하고 새 JSON 디렉터리·공급자·알림·템플릿을 함께 반영한 뒤 Admin reload를 수행했다. 기존 `deploy-dashboard-expansion.sh`는 새 디렉터리와 템플릿을 빠뜨리므로 사용하지 않았다. Grafana 컨테이너 재시작 횟수는 적용 전후 8로 동일했다.

`deploy-environment-dashboards.py`는 환경별 JSON과 공급자·알림·템플릿을 함께 적용하는 서버용 스크립트다. 원본 상대 경로를 보존한 stage와 각 프로비저닝 파일의 SHA-256을 담은 `manifest.json`이 필요하다. 카탈로그까지 포함한 현재 구성은 NCP에서 `python3 deploy-environment-dashboards.py --stage <stage> --dev-count 8`로 읽기 전용 사전 검사를 수행한 뒤, 승인된 적용 단계에서 `--apply`를 추가한다. 최초 7개 구성은 기본 `--dev-count 7`을 사용한다.

사전 검사는 실제 읽기 전용 마운트, 기존 공급자, 공통 6개, 알림 15개, 기존 설정과 새 설정의 불변 계약을 확인한다. 알림의 `display_name`·`summary`·`description`, Discord의 `title`·`message`만 계약 비교에서 제외한다. 쿼리·조건·대기·라우팅·Webhook·알림 패널 링크의 차이가 있으면 백업·설치 전에 중단한다. 운영 설정의 차이를 이 스크립트로 덮어쓰지 않는다.

적용 시 서버 안의 `environment-backup.*` 디렉터리(0700)에 대상 파일, 공통 대시보드, Compose, 실행 알림·연락처·정책·기존 템플릿을 백업한다. 변경된 리소스의 Admin reload만 수행하고, 실행 알림 계약·공통 JSON·컨테이너 환경·폴더별 개수·템플릿을 재확인한다. 최초 적용 전 백업은 `/opt/knot-observability/environment-backup.cm7d6ba1`, 최종 표시 수정 전 백업은 `/opt/knot-observability/environment-backup._0ems54o`다.

첫 설치는 새 상위 디렉터리의 0700 때문에 dashboards reload가 실패해 원본으로 복원했다. 비밀값 없는 새 대시보드 경로를 0755로 설정하도록 수정해 해결했다. 두 번째 검사에서는 API가 템플릿 끝 개행 한 글자를 제거한 정규화 차이를 발견해 복원했고, 다른 내용이 동일함을 확인해 비교를 수정했다. 이후 전체 설치와 최종 dashboards reload는 성공했다. 이전 백업도 보존했다.

2026-10-02 배포 스크립트 로컬 검증: 읽기 전용 사전 검사, 전체 번들/0700 백업, reload 실패 시 원본 복원, 운영 쿼리 차이 사전 차단의 모의 실행 4개를 통과했다. 별도로 쿼리·대기·라우팅·Webhook·패널 링크 변조 5개를 계약 비교가 탐지했다. 이 결과는 실제 서버 API 검증을 대신하지 않는다. Preview 또는 연락처 사용자 정의 테스트에서는 `labels.test="true"`를 지정해야 `[테스트]`를 표시한다.

실제 확인한 항목과 남은 한계:

- Dev 7개·Prod 5개 최초 폴더와 같은 환경 링크, 추가 후 Dev 8개를 API·부모 UI로 확인했다.
- 양쪽 Nginx 실제 로그와 KST·시간 전달을 확인했다. 최근 구간 앱 로그가 없었던 사실을 수집 정상의 근거로 바꾸지 않았다.
- Gauge와 Spring·Tomcat·Hikari 실데이터를 확인했다. 요청·오류가 없는 일부 API Table/Heatmap 결과와 작은 화면·키보드 동작은 검증 완료라고 주장하지 않는다. 임의 부하는 생성하지 않았다.
- 실제 Go 엔진 Preview에서 Dev·Prod 발생/해제/테스트·env 누락·Updated 해제의 8개 사례를 확인했다. 규칙을 만들거나 실제 장애를 유발하지 않았다.
- 알림 조건·쿼리·대기·라우팅·Webhook·패널 링크의 파일 및 실행 계약은 동일했다. 새 Discord 사람 수신 화면은 미확인이다.

로컬 검증:

```sh
node --test ops/observability/environment-dashboards.test.mjs ops/observability/discord-notifications.test.mjs
node --test ops/observability/catalog-dashboard.test.mjs
node --check ops/observability/build-environment-dashboards.mjs
node --check ops/observability/check-dashboard-data.mjs
```

`check-dashboard-data.mjs`는 기존 공통 6개와 새 13개를 포함하며, 전용 화면은 해당 환경만 조회한다. 실제 운영 Prometheus/Loki로 394개 쿼리와 알림 15개를 검사해 query error·non-finite 결과가 없음을 확인했다. 빈 쿼리 58개를 0으로 대체하지 않았다.

2026-10-02 검증 결과: Node 계약 20/20, 배포 모의 4/4, 기존 metrics acceptance 5/5, 일반 bootJar 패키징, 실제 서버 사전/사후 검사 통과. 운영 적용은 위 근거로 완료했다. Persona 보고서 filled gate는 기존 필수 구간 template-like/incomplete로 거부됐고 finish는 `source-read-runtime-unavailable`로 실패했다. 운영 성공을 저장소 finish 인증으로 대체하지 않는다.

공식 근거: [파일 프로비저닝](https://grafana.com/docs/grafana/latest/alerting/set-up/provision-alerting-resources/file-provisioning/), [알림 템플릿 데이터·KST 함수](https://grafana.com/docs/grafana/latest/alerting/configure-notifications/template-notifications/reference/), [Discord 제목·본문 설정](https://github.com/grafana/alerting/blob/main/receivers/discord/v1/config.go).
