# 최초 로그인 뒤 운영 AI와 터널 시작

18766 운영 AI와 전용 reverse SSH를 사용자 LaunchAgent로 실행하는 절차다.
FileVault를 유지하며 재부팅 후 최초 한 번 로그인하면 시작한다. 화면 잠금 중에도 같은 세션에서
실행한다. 로그인 전 무인 기동과 자동 로그인은 범위 밖이다.
결정은 [#823](https://github.com/woowacourse-teams/2026-knot/issues/823),
[Proposed ADR](../adr/823-mac-ai-login-startup.md)에 있다.

이 PR은 설치 도구와 절차를 제공한다. 18766의 실제 설치·기존 프로세스 교체·화면 잠금·로그아웃·재부팅은
수행하지 않았다. 코드 시험과 운영 검증은 [검증 기록](../verification/823-mac-ai-login-startup.md)에서 구분한다.

## 보존하는 대상

| 대상 | 처리 |
| --- | --- |
| `kr.knot.macmini-ai`·`kr.knot.macmini-dev-tunnel` | 기존 18765 서비스. 변경·종료하지 않음 |
| `kr.knot.macmini-ai-service` | 새 18766 AI 전용 label |
| `kr.knot.macmini-service-tunnel` | 새 18766 reverse SSH 전용 label. 기존 local forward를 복제하지 않음 |
| `kr.knot.mac-metrics` | 기존 Alloy의 RunAtLoad·KeepAlive·등록 상태를 검사. 별도 수집기를 설치하지 않음 |
| Alloy config·WAL·TLS·토큰·중앙 데이터 | 수정·삭제하지 않음 |

service 지표 수집은 [#812 연결 절차](https://github.com/woowacourse-teams/2026-knot/pull/812)를 따른다.
그 도구에는 `--port 18766`을 명시한다. 이 도구는 수집 모듈을 추가하거나 대시보드를 배포하지 않는다.

## 비공개 입력 준비

검토한 불변 JAR·runner를 사용자 `~/Library/Application Support` 아래에 준비한다.
Desktop·Documents에 있는 개발 checkout을 LaunchAgent의 실행 경로로 사용하지 않는다.
기존 모델과 인증·버킷·prefix 설정을 유지하고 새 권한이나 토큰을 발급하지 않는다.

설정 JSON에는 아래 일곱 키를 넣는다. 값은 실제 절대 경로이며 `~`나 환경변수는 자동 확장하지 않는다.
저장소에는 실제 설정 파일을 넣지 않는다.

| 키 | 값 |
| --- | --- |
| `java` | 검증한 Java 실행 파일의 절대 경로 |
| `jar` | Application Support 아래의 검증한 불변 JAR |
| `working_directory` | Application Support 아래의 실행 디렉터리 |
| `environment_file` | 비공개 환경 JSON의 절대 경로 |
| `ssh_key` | 기존 SSH 키 파일 경로 |
| `known_hosts` | 해당 호스트를 이미 검증한 기존 known_hosts 파일 경로 |
| `ssh_destination` | 기존 계정과 호스트의 `ubuntu@HOST` 또는 `ec2-user@HOST` |

설정·환경·키·known_hosts는 현재 실행 사용자 소유 일반 파일 0600이어야 한다.
설정과 환경 파일의 부모 디렉터리는 0700이어야 한다. 경로를 외부로 우회하는 symlink는 거절한다.
환경 JSON에는 의도적으로 고른 `AI_*`·`PATH`·`LANG`·`LC_ALL`만 넣는다.
`AI_PORT=18766`, 기존 `AI_APP_TOKEN`, `AI_PROFILE_FILE`, `AI_RUNNER_DIRECTORY`, `AI_WORK_DIRECTORY`가 필요하다.
세 AI 경로도 Application Support 아래에 준비한다. 셸의 전체 환경은 상속하지 않는다.
실제 토큰은 승인된 운영 단계에서 기존 비공개 설정 또는 검증한 실행 프로세스에서 메모리로 읽어
환경 파일에 직접 주입한다. 터미널·인자·문서·Git·로그로 출력하지 않는다.

## 검사와 설치

Mac 로그인 사용자로 release 사본의 `backend` 디렉터리에서 실행한다. sudo를 사용하지 않는다.
`KNOT_AI_LOGIN_CONFIG`에는 비공개 설정 파일의 절대 경로만 넣는다.

```sh
python3 ops/observability/mac/service/install_service.py install --config "$KNOT_AI_LOGIN_CONFIG"
```

기본 검사는 파일을 생성하거나 label을 등록하지 않는다. private 권한·경로·환경·기존 host 키·
Alloy 자동 시작 설정·plist 문법·기존 label과 포트 점유를 검사한다. 실제 `plutil`과 기존 파일을 읽는다.
포트가 점유됐다면 기존 실행을 자동 종료하지 않고 `PORT_BUSY`로 거절한다.
검사가 통과했다는 사실은 실제 로그인이나 중앙 수신의 증거가 아니다.

설치 전에 다른 운영자의 설정 변경을 멈추고 기존 JAR·runner·프로파일·인자·환경·cwd·터널 정보를
비공개 경로에 보존한다. 기존 수동 AI의 이전 방식 복구에 필요하다.
백엔드의 신규 선점·전달을 차단하고 현재 실행과 cleanup이 끝났는지 확인한다.
단순 health 200이나 순간적인 DB 실행 0건만으로 유입 차단을 대신하지 않는다.
전달 차단은 AI 교체와 원복이 끝날 때까지 유지한다. 이 도구는 백엔드 중지·DB 잠금·drain을 구현하지 않는다.

기존 수동 18766 AI와 터널은 PID·실행 파일·포트가 확인된 대상만 정상 종료한다.
이 인계는 별도 승인된 운영 작업에서 수행한다. 구형 18765·다른 Java·SSH를 일괄 종료하지 않는다.
로컬 포트와 EC2의 기존 reverse listener가 모두 해제된 뒤 같은 명령에 `--apply`를 붙인다.

```sh
python3 ops/observability/mac/service/install_service.py install --config "$KNOT_AI_LOGIN_CONFIG" --apply
```

도구는 `~/Library/Application Support/KnotAiServer/service`와 두 새 plist를 만든다.
설정과 실행 wrapper만 배치하며 입력 환경 파일·키·JAR·모델은 덮어쓰지 않는다.
plist에는 wrapper 인자만 들어가고 토큰이나 환경 전체는 들어가지 않는다.
RunAtLoad·KeepAlive·Aqua 세션·30초 재기동 간격·30초 종료 대기를 설정한다.
현재의 명시적 disabled 상태나 다른 등록·기존 파일이 있으면 자동으로 덮어쓰지 않는다.

신규 AI는 별도 슬롯 잠금으로 중복 실행을 막는다. 터널은 BatchMode·IdentitiesOnly·
StrictHostKeyChecking을 유지하고 고정된 18766 loopback reverse만 연다.
원격 포트 충돌 시 ExitOnForwardFailure로 종료하며 ServerAliveInterval=15·CountMax=3으로
단절 후 재시작할 수 있다. 실제 통신 장애의 복구 시간은 후속 운영 시험에서 확인한다.

설치 성공은 로컬 무인증 health 401·정상 인증 health 200과 EC2에서 터널을 경유한 인증 health 200으로
판정한다. 경유 검사 토큰은 기존 SSH의 암호화된 stdin으로만 전달하며 원격 명령 인자에 넣지 않는다.
성공 후 전달 차단을 해제하고 현재 backend→AI 경로를 다시 확인한다.
같은 설정·JAR·환경의 재실행은 등록·재시작 없이 종료한다. 변경 설치는 기존 원복 후 별도로 검토한다.

## 상태와 로그

```sh
python3 ops/observability/mac/service/install_service.py status
```

두 새 label과 기존 Alloy의 loaded/state/pid/runs/last exit code를 제한된 필드로 보여준다.
`logs/ai.log`, `logs/tunnel.log`는 0600이며 각각 2MiB와 회전본 1개를 보존한다.
원문 stdout/stderr는 저장하지 않는다. 고정된 시작·종료·오류 분류와 검증한 전사 JSON의
event/outcome/attemptId/elapsedMs/안전 errorCode만 재구성한다. 요청·전사 원문과 임의 필드는 폐기한다.
단계 레코드의 `observedAtMs`는 wrapper가 읽은 시점의 UTC Unix epoch 밀리초다.
자식이 제공한 timestamp는 사용하지 않으며 Grafana 표시 시간대로 변환해 표본과 대조한다.
호스트 시계 보정의 영향을 받는 관측 시각이므로 실행 시간 계산에는 `elapsedMs`를 사용한다.
오류 코드는 현재 AI의 고정 enum 29개를 보존하고 임의 문자열은 `UNKNOWN_FAILURE`로 바꾼다.
원래 콘솔 전체를 보존하는 로그와 다르며 예외 메시지나 일반 startup 출력은 조회할 수 없다.

종료 신호를 받으면 출력 읽기에서 빠져나와 소유한 자식을 정리한다. 20초 정상 종료 대기 후
응답하지 않으면 해당 자식만 강제 종료하고 최대 5초 동안 회수한다.

5분 안의 자식 실패 3회 이상은 `RESTART_LOOP`로 기록한다.
자식 시작 전 설정 오류도 launchctl의 runs와 마지막 비정상 종료로 `REPEATED_START_FAILURE`를 진단한다.
진단 후 토큰 출력·호스트 검증 해제·원인 없는 반복 kickstart로 우회하지 않는다.
단계 지표와 중앙 표본은 #812의 service/host 확인 절차로 별도 대조한다.

## 원복

설치 결과의 백업 경로를 보관한다. receipt에는 새로 만든 파일 범위와 hash만 있으며 입력 비밀값은 없다.
원복 전에도 신규 전달을 차단하고 실행·정리 완료를 확인한다. 아래는 원복 검사다.

```sh
python3 ops/observability/mac/service/install_service.py restore --backup "$KNOT_AI_LOGIN_BACKUP"
```

검사가 통과하면 `--apply`를 붙인다. 현재 파일·receipt hash와 실제 label의 plist 경로를 다시 검사한 뒤
소유한 두 신규 label만 bootout하고 해당 파일만 제거한다. 로그·상태·백업은 비공개로 보존한다.
설치·원복은 같은 잠금을 사용한다. 사용자 편집이나 다른 등록이 감지되면 보존하고 중단한다.
설치 중 시작·준비 확인 실패도 같은 원복 경로를 쓴다. 원복 자체가 실패하면 성공으로 표시하지 않는다.

이후 이전 불변 JAR·runner·환경·cwd와 원래 터널 인자로 수동 18766을 복구한다.
이 수동 프로세스 복구는 도구가 임의로 수행하지 않는다. 로컬/EC2 인증 health 후 전달을 재개한다.
18765 기존 plist, Alloy 설정·WAL·TLS, 오디오·전사·DB·중앙 시계열은 보존한다.

## 실제 운영 완료 조건

후속 배포에서 최초 로그인 후 새 label·실제 PID·18766 바인딩과 기존 18765 보존을 확인한다.
화면 잠금 중 host/service 새 표본과 작업 완료를 확인한다. 각 서비스의 정상 종료 후
launchd 재시작과 터널 단절·원격 포트 충돌 복구도 확인한다.
AI 재시작은 누적 카운터를 초기화하며 Alloy 재시작은 AI 누적값을 유지해야 한다.
동일한 실행의 backend 결과 수신·DB persist와 AI 단계 로그를 연결한다.
로그아웃·재부팅은 다른 활성 작업과 조율한 후 별도 수행하고 미실행 항목을 통과로 기록하지 않는다.

CLI 의미는 대상 macOS의 `man launchd.plist`, `man launchctl`에서 RunAtLoad·KeepAlive·
ThrottleInterval·ExitTimeOut·bootstrap·bootout·print-disabled를 대조했다.
