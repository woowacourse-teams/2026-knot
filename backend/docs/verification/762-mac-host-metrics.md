# Issue #762 Mac 호스트 지표 검증

검증일: 2026-10-17~18 KST. 실제 Mac LaunchAgent와 새 EC2 중앙 수신·Grafana 표시를 확인했다.
EC2 재부팅 뒤 서비스·loopback 바인딩과 약 30초 간격의 새 표본 수신도 복구됐다.
Mac 설치·갱신은 [Mac AI 호스트 자원 수집](../operations/mac-host-metrics.md), 중앙 방화벽·앱·
터널·재부팅은 [Mac 관측 EC2 운영](../operations/mac-monitoring-ec2.md)을 따른다.

## 구현·운영 범위

- 브랜치 `be/chore/#762`, 기준 `origin/develop`의 `10ee7a06`. Mac 수집기와 중앙 EC2를 별도 PR로 전달한다.
- native Alloy `v1.20.1`, Mac 전용 mTLS 경로, 별도 `Knot-mac` Grafana provider.
- 사용자 LaunchAgent, loopback 관리 포트, 로컬 로그 회전과 WAL 지속 경로.
- NCP 접속 권한이 없어 사용자가 승인한 새 EC2에 standalone Prometheus·Grafana를 배포했다.
  기존 NCP Dev·Prod 구성·수신 주소·데이터는 변경하지 않았다. NCP 이관이나 Dev·Prod의
  실제 운영 수신을 이번 EC2에서 검증한 것은 아니다.
- 업무 API·DB·AI 단계 계측·GPU·알림·Mac 전원 정책은 변경하지 않는다.

## 전달 책임 분리

전체 변경이 비테스트 1,000줄을 넘어 Mac 수집기와 중앙 EC2를 분리했다. 원본과 독립 patch는
개인 작업 폴더에 보존했고 각각 별도 임시 index에서 base 적용 검사를 통과했다.

| 묶음 | 포함 범위 | 전달 |
| --- | --- | --- |
| Mac 수집기 | Alloy 설치·실행·라벨, ingress, Mac dashboard·provider, 직접 테스트와 Mac 운영·검증 문서 | Issue #762, `be/chore/#762` |
| 중앙 EC2 | `ops/observability/mac/central/*`와 `mac-monitoring-ec2.md` | [Issue #774](https://github.com/woowacourse-teams/2026-knot/issues/774), [PR #777](https://github.com/woowacourse-teams/2026-knot/pull/777) |

중앙 PR은 8파일·비테스트/전체 285줄이며 Mac 수집기와 동반 적용해야 한다.
게시된 PR의 본문과 최신 base 대비 diff에서 최종 변경량·검증·병합 순서를 확인한다.
별도 AI 작업 관측 Issue #775는 이 Mac 호스트 수집 구현에 섞지 않는다.

## 실제 운영에서 확인한 결과

| 검증 | 결과 | 증명 범위 |
| --- | --- | --- |
| 새 중앙 EC2 | 생성·배포 완료 | 서울 AL2023 arm64, `t4g.small` 2GiB, 암호화 gp3 30GiB, CPU credit Standard, IMDSv2 |
| 공유 SG | 기존 `project-public` 사용, 규칙 미변경 | SG의 넓은 허용 범위는 호스트 nftables로 제한. 전용 SG와 동일한 경계라는 뜻은 아님 |
| nftables | aarch64 1.0.4, 실제 `nft -c` 통과 | 전용 INPUT·FORWARD 기본 drop, 관리자 SSH `/32`·Mac HTTPS `/32`·loopback·established/related·ICMP/ICMPv6·ens5 DHCPv4 67→68 허용 |
| 규칙 재적용 | 전용 테이블 두 번 적용 성공 | 다른 sentinel 테이블 보존. 전체 ruleset flush 없이 재실행 가능 |
| 서비스 의존성 | sshd·nginx·docker의 Requires·After 확인 | guard 성공 뒤 서비스 시작. 새 SSH 연결 성공 후 시간 제한 원복 예약 해제 |
| 앱 네트워크 | health 정상·127.0.0.1 바인딩 확인 | Docker 25에서 host network·앱 listen 주소 사용. 3000·9090 publish 없음 |
| 앱 권한·자원 | non-root·cap_drop·no-new-privileges, 각각 512MiB 제한 | 전용 EC2의 두 서비스. Prometheus 14일 또는 2GiB 중 먼저 충족하는 보존 조건 |
| 인증서·비밀번호 | 비공개 파일로 준비 | CA 키는 중앙 root만 보관. client.key는 Mac 생성 후 CSR 서명, 공개 인증서만 반환. Grafana 무작위 비밀번호는 저장소 밖 파일 |
| Mac 설치 | LaunchAgent 설치·running 확인 | 실제 사용자 세션에서 native Alloy 시작. 관리 포트는 loopback |
| 실제 mTLS 경계 | Mac GET `/mac` 403, Mac 인증서 POST `/dev`·`/prod` 각각 403, 인증서 없는 POST `/mac` 400 | 경로는 각각 `/api/v1/write`를 붙여 검사. 실제 중앙의 메서드·교차 CN·인증서 필수 조건 |
| 외부 TCP 접근 | Mac 위치에서 443만 열림, 관리자 위치에서 22만 열림 | 양쪽에서 80·3000·8080·9090 차단. Mac의 22와 관리자의 443도 차단 |
| 중앙 수신 | `up{job="mac-host",instance="knot-ai-macmini"}=1` | 고정 라벨을 가진 실제 Mac 표본 저장 |
| 수집 간격 | 두 표본 30.013초 간격 | 재부팅 전 실제 중앙의 시각 갱신 |
| 메모리·화면 | RAM 16GiB·활성/고정/압축 메모리·swap GiB·Data 약 239GiB·up=1·최신 시각 표시 | 실제 UI의 아래 패널까지 확인하고 raw sysctl·중앙 단위를 대조. 서로 다른 시각의 변동값이 정확히 같다는 주장은 아님 |
| 중앙 EC2 재부팅 | boot ID 변경, guard·docker·nginx·central 4개 active | 재부팅 후 새 SSH·Prometheus ready·Grafana DB health 정상, IPv4 loopback 유지. 새 표본 4개가 약 30초 간격으로 계속 수신 |

실주소·개인 IP·개인키·비밀번호와 비공개 운영 출력은 저장소나 Vault에 복사하지 않았다.
TLS 인증서는 365일로 발급해 갱신이 필요하다. 공개 IP가 바뀌면 endpoint·서버 SAN 인증서·
SSH 대상을 갱신하고, 출발 주소 변경 시 호스트 방화벽·Nginx allow도 수정해야 한다.

## 재현된 문제와 수정

### exporter job 라벨이 대시보드 조회 조건과 달랐음

실제 exporter 대상의 `job=integrations/unix`가 `prometheus.scrape.job_name="mac-host"`보다
우선해 대시보드의 `job="mac-host"` 조회로 표본을 찾지 못했다. `instance` relabel은 적용됐다.
Alloy 공식 문서에서 `job_name`은 대상에 job이 없을 때의 기본값임을 확인했다.

`discovery.relabel "mac"`에 `target_label="job"`, `replacement="mac-host"` 규칙을 추가했다.
실제 Mac의 validate·LaunchAgent kickstart 뒤 중앙에서 고정 job의 `up=1`과 새 표본을 확인했다.
라이브러리·`honor_labels`는 변경하지 않았다. 과거 `integrations/unix` 데이터의 이름을 바꾸거나
삭제하지 않았다. [scrape 공식 동작](https://grafana.com/docs/alloy/latest/reference/components/prometheus/prometheus.scrape/),
[공식 relabel 예제](https://grafana.com/docs/grafana-cloud/send-data/alloy/monitor/monitor-linux/#discoveryrelabel-instance-and-job-labels)

### AL2023 Docker 25의 localhost publish 경계

실제 공식 패키지는 `25.0.16-1.amzn2023.0.5`였다. Docker 공식 문서는 28 미만의 localhost
publish에 동일 L2 접근 위험을 경고한다. 이 문제에 대한 AWS backport를 확인했다고 주장하지 않는다.
standalone 두 서비스의 `ports`를 제거하고 host network와 앱 자체 IPv4 loopback listen,
non-root·모든 capability 제거·no-new-privileges를 적용했다. 실제 health·바인딩을 확인했다.
네트워크 namespace 격리를 유지한 해결은 아니며 두 앱은 호스트 localhost를 공유한다.
[Docker 경고](https://docs.docker.com/engine/network/port-publishing/),
[host network 동작](https://docs.docker.com/engine/network/drivers/host/)

### 물리 RAM stat 패널의 잘못된 경고 색상

Grafana 기본 threshold 80이 물리 RAM 바이트 값에 적용돼 정상적인 16GiB 용량이 빨간색으로
표시됐다. 이 용량 패널은 장애 판정값이 아니므로 `colorMode: none`으로 중립 표시하고,
빈 legend에는 한글 패널명을 넣었다. 수집 내용과 판정 조건은 변경하지 않았다.
기존 Python 11건에 포함되는 no-data·dashboard 테스트 1건을 수정 후 다시 실행해 통과했다.

### 기존 Mac 검증기의 LibreSSL·종료 처리

대상 Mac의 Python 3.9.6·LibreSSL 3.3.6에서 인증서 주체가 `subject= CN=...`으로 출력되어
최초 검사가 실패했다. `subject=` 뒤 공백만 정규화하고 전체 주체가 정확히
`CN=knot-mac-alloy`인지 확인하는 제한은 유지했다. 두 표기의 회귀 검사를 추가했다.

종료 handler도 신호 전달 뒤 읽기 대기에서 빠져나와 15초 대기·강제 종료를 실행하도록 보완했다.
TERM을 무시하고 stdout을 연 채 대기하는 합성 자식의 회수까지 실제 동적 검사에 포함했다.
이 테스트의 `launchctl`은 모킹이며 자동 로그인·Mac 재부팅 복구 증거를 대신하지 않는다.

## 코드와 격리 환경 검증

아래 자동 검사는 최신 변경 후 로컬에서 다시 통과했다. 실제 운영 검증은 앞 표와 구분한다.

| 검증 | 결과 | 증명 범위 |
| --- | --- | --- |
| 팀 컨벤션 원문 hash gate | 최신 재실행 passed | source.status passed. 원문 drift 없음. 전체 컨벤션 통과와 구분 |
| Python 설치·인증·보존 테스트 | 최신 로컬 11건 통과 | 잘못된 주소·Dev CN 거절, OpenSSL·LibreSSL 표기, dry-run, 기존 설치 보존, 파일 권한, 시작 실패 정리, 로그 회전, 무응답 자식 종료, no-data 계약 |
| 실제 AI Mac의 Python 테스트 | 이전 실행 11건 통과 | Darwin Python 3.9.6·LibreSSL 호환. 최신 로컬 재실행과 구분 |
| 기존 Node 관측 테스트 | 최신 로컬 24건 통과 | 기존 Dev·Prod 화면·접근 제한·알림 계약 보존 |
| 기존 배포 테스트 | 최신 로컬 4건 통과 | 백업·원복·설정 drift 검증 유지 |
| Governance 단위 | 최신 로컬 10건 통과 | 저장소 GitHub 형식 검증 유지 |
| 컨벤션 자동 검사 | 최신 재실행도 기존 의존성 2건으로 실패 | 변경하지 않은 `build.gradle:32–33`의 LIB-VERSION. 전체 통과 아님 |
| Alloy `v1.20.1` validate | 공식 Darwin arm64·체크섬 확인, 운영 수정 뒤 validate 통과 | 구문·구성 유효성. 실제 저장은 운영 수신으로 별도 확인 |
| 로컬 Alloy → 임시 TLS 수신기 | 이전 실행 POST 도착 | Mac CN·경로·비어 있지 않은 remote_write 요청. 실제 중앙 저장과 구분 |
| 격리 Nginx mTLS 검사 | 이전 실행 8개 시나리오 통과 | 합성 인증서의 CN·메서드 경계. NCP 운영 서버 검사 아님 |

격리 Nginx 검사는 Mac POST 204, Dev·Prod POST 각각 204, Dev 인증서의 Mac 경로 접근 403,
Mac 인증서의 Dev·Prod 경로 접근 각각 403, 인증된 GET 403, 인증서 없는 요청 400을 확인했다.
loopback 임시 컨테이너와 합성 인증서를 사용한 결과이며, 컨테이너는 제거했다.
실제 새 EC2의 메서드·교차 CN·인증서 필수 검사와 구분하고 NCP의 실제 수신 증거로 사용하지 않는다.

대상 Mac에서 `node_cpu_seconds_total`, `node_memory_total_bytes`, `node_memory_active_bytes`,
`node_memory_wired_bytes`, `node_memory_compressed_bytes`, `node_memory_swap_used_bytes`,
`node_filesystem_avail_bytes`와 Data 볼륨 mountpoint를 확인했다. 최초 임시 probe는 종료했고,
이후 정식 LaunchAgent 설치·시작과 중앙 수신을 앞 표처럼 확인했다. 기존 앱도 실행 중이므로
현재 수치를 AI 단독 baseline이나 장애 임계값의 근거로 사용하지 않는다.

컨벤션 검사에서 보고한 두 의존성 버전은 기준 브랜치와 동일하다. 원문 hash 통과를 전체
컨벤션 통과로 해석하지 않는다. Spring 테스트·Spotless·전체 Gradle 검증과 원격 CI는 실행하지 않았다.

## 적용 기준과 재실행 명령

| 규칙군 | 적용 |
| --- | --- |
| STACK·LIB | 공식 Alloy·기존 pinned 관측 이미지, AL2023 공식 패키지 사용. Spring·Gradle 의존성 미변경 |
| LAYER·MODEL·API·DB·JAVA | 해당 없음: 업무 코드·HTTP API·도메인·영속성·Java 변경 없음 |
| TEST | Python·Node 회귀, native 수집·합성 mTLS·격리 Nginx·새 EC2 실제 수신 구분 |
| AI | 작성과 읽기 전용 독립 검토 분리, 인증정보·개인 작업 내용 제외 |
| DELIVERY | Issue #762와 `be/chore/#762` 연결. 다른 작업 변경 보존, Mac 수집기와 중앙 설정을 별도 PR로 전달 |

Node 카탈로그 검사는 공식 17175 revision 2 원본을 비공개 임시 경로에 준비하고 기존 계약의
SHA-256과 일치함을 확인한 뒤 실행했다. 원본·생성물의 기존 ignore 규칙을 유지하며 Git에 추가하지 않는다.
저장소 루트에서 다음 명령으로 자동 검사를 다시 실행한다. 비공개 fixture 값은 출력하지 않는다.

```sh
python3 -m unittest discover -s backend/ops/observability/mac -p 'test_*.py' -v
KNOT_CATALOG_SOURCE="$PRIVATE_CATALOG_FIXTURE" node --test backend/ops/observability/*.test.mjs
python3 backend/ops/observability/deployment.test.py
python3 -m unittest discover .github/scripts -p 'test_*.py' -v
git diff --check
```

## 남은 검증과 운영 한계

- Mac의 로그아웃·재부팅·로그인 전 동작·장기 단절·휴면 복구와 WAL 무손실은 검증하지 않았다.
- AI만 실행한 전사 전·중·후 baseline, GPU 지표와 알림은 후속 범위다. NoData 탐지나
  중앙 EC2 자체를 외부에서 감시하는 운영 체계까지 완료한 것은 아니다.
- TLS 갱신·중앙 IP 변경에 따른 인증서·주소·방화벽 변경은 운영 절차로 남으며 자동 갱신을 검증한 것은 아니다.
- 기존 NCP Dev·Prod는 미변경이며 해당 운영 수신을 이번 작업의 새 중앙에서 입증하지 않는다.

임시 작업 키는 2026-10-18 KST에 Mac 쪽 06:18, EC2 쪽 06:50 만료다. 이후 SSH 관리·터널은
승인된 팀 키가 필요하며 mTLS 상시 수집의 수명과 구분한다.

현재 판정: 정식 Mac 설치, 새 EC2의 실제 수신·Grafana 표시와 짧은 중앙 재부팅 후
새 SSH·서비스·약 30초 표본 갱신 복구까지 확인했다. 장기 가용성이나 무손실을 입증한 결과는 아니다.
