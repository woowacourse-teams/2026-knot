# 운영 AI의 지표 연결과 복구

이 절차는 기존 Mac 호스트 수집기에 운영 AI의 상태 지표를 추가한다. 연결 도구는 AI 앱을
교체하거나 전사 작업을 요청하지 않는다. `--apply`를 붙이기 전에는 인증·지표·후보 설정만 검사한다.
구현 범위는 [Issue #810](https://github.com/woowacourse-teams/2026-knot/issues/810)이다.

## 적용 전 조건

1. 백엔드 녹음 로그 #751 → 전사 로그 #752 → AI 계측 #782의 의존 관계를 검토한다.
   운영 AI에 `/internal/metrics`를 제공하는 코드가 포함되어야 한다. PR 승인이나 로컬 테스트
   통과를 운영 반영으로 간주하지 않는다. 통합한 커밋에서 AI 테스트와 빌드를 다시 실행한다.
2. AI의 실행 사용자·실행 방식·빌드 SHA·프로파일·모델 설정·현재 아티팩트 위치를 비공개
   운영 기록에 남기고 기존 아티팩트와 설정을 백업한다. 값을 전체 출력하거나 저장소에 넣지 않는다.
3. AI 교체가 필요하면 먼저 백엔드의 새 작업 전달을 중지하고 진행 중 작업과 정리가 끝났는지
   확인한다. `ready=1`은 유휴를 뜻하지 않으며 `active_jobs=0`도 새 요청 유입을 막지는 않는다.
   교체 후 준비 상태를 확인한 다음 작업 전달을 재개한다. 배포 명령은 확인한 기존 실행 방식에 따른다.
4. 기존 [Mac 호스트 수집](mac-host-metrics.md)의 LaunchAgent가 실행 중이고 중앙 `mac-host`
   표본이 갱신되어야 한다. 도구는 동일 사용자의 표준 host 설정만 자동 연결한다.
5. 기존 AI 앱의 `AI_APP_TOKEN`과 일치하는 비공개 파일을 준비한다. 새 권한이나 토큰을
   발급하는 절차가 아니다. 파일은 실행 사용자 소유 `0600`, 부모 디렉터리는 `0700`이어야 한다.
   토큰 값은 명령 인자·셸 이력·PR·로그에 넣지 않는다. 파일 경로만 전달한다.

현재 AI 토큰은 지표뿐 아니라 작업 API에도 쓰인다. 연결 도구는 proxy와 redirect를 사용하지
않고 `127.0.0.1`의 GET 요청만 한다. 수집 endpoint를 외부에 공개할 필요는 없다.

## 검사와 적용

Mac에서 AI와 기존 Alloy를 실행하는 사용자로 저장소의 `backend` 디렉터리에서 실행한다.
`KNOT_AI_METRICS_TOKEN_FILE`에는 준비한 파일의 절대 경로만 지정한다. sudo를 사용하지 않는다.

```sh
python3 ops/observability/mac/ai/connect_service.py \
  --token-file "$KNOT_AI_METRICS_TOKEN_FILE"
```

도구는 미인증 요청 401, 인증 요청 200, 필수 지표·고정 라벨, 파일 권한, 기존 LaunchAgent,
실제 Alloy 바이너리의 `validate`를 확인한다. 기본 포트는 18765이며 앱 설정이 다를 때만
`--port`를 명시한다. 응답 본문이나 토큰은 출력하지 않는다.

검사 통과 후 같은 명령에 `--apply`를 붙인다. 다른 운영자가 설정을 편집하지 않는 시간에 실행한다.

```sh
python3 ops/observability/mac/ai/connect_service.py \
  --token-file "$KNOT_AI_METRICS_TOKEN_FILE" --apply
```

host 설정에 `ai_scope=service` 수집 인스턴스를 추가하고 기존 remote_write를 재사용한다.
토큰·인증서·WAL·plist·AI 실행 프로세스는 변경하지 않는다. 활성 설정을 백업한 뒤 원자 교체하고
기존 수집기만 재시작한다. 재시작 또는 준비 확인 실패 시 원본 설정으로 되돌려 재시작한다.
복구 확인까지 실패하면 실패로 종료한다. 중간에 다른 작업자가 설정을 바꾸면 덮어쓰지 않는다.

같은 설정으로 재실행하면 중복 추가나 재시작을 하지 않는다. 기존 validation·사용자 수정 설정,
다른 토큰 경로·포트가 있으면 자동 교체를 거절한다. 이때 기존 설정을 지워 도구를 통과시키지 말고
[수동 모듈 설치](mac-ai-metrics.md)를 따라 두 scope를 보존해 검토한다.

성공 메시지의 백업 디렉터리를 보관한다. `config.alloy`는 원본, `installed.alloy`는 설치본이다.
모듈은 내용 해시가 포함된 파일명으로 보관하므로 기존 모듈을 덮어쓰지 않는다.

## 운영 완료 확인

Alloy `/-/ready` 성공은 최초 설정이 로드됐다는 뜻이다. AI scrape나 중앙 전송 성공까지
증명하지 않는다. 아래 확인을 끝내기 전에는 운영 연결 완료로 기록하지 않는다.

- [ ] 중앙에 `up{job="mac-ai",ai_scope="service"}=1`의 최신 표본이 5초 간격으로 두 번 이상 갱신된다.
- [ ] 기존 `up{job="mac-host"}=1`도 갱신되고 기존 project/env/instance 라벨이 유지된다.
- [ ] `knot-mac-ai.json`을 기존 중앙 provider에 반영하고 `Mac AI 전사 진행`의 기본 대상이 service인지 확인한다.
- [ ] 유휴 → 실제 작업 → 정리 완료 후 유휴의 상태와 단계·경과 시간이 앱 동작과 일치한다.
- [ ] 백엔드의 작업 ID로 결과 저장까지 확인한다. 작업 ID는 로그에서 찾고 지표 라벨에는 넣지 않는다.
- [ ] 수집기만 재시작한 뒤 두 job의 새 표본이 돌아오고 AI 앱 누적 완료 횟수가 유지된다.
- [ ] 별도 validation 앱의 인증 오류·중단에서 up=0 또는 NoData가 보이고 service 표본은 유지된다.
- [ ] 프로세스 시작 방식과 아래 로그인·잠자기·재부팅 조건을 운영 기록에 남긴다.

Grafana 기본값만 service로 바뀌고 아직 연결하지 않았다면 NoData가 정상적인 표시다.
NoData를 작업 없음이나 성공으로 바꾸지 않는다. AI 앱을 재시작하면 누적 횟수는 초기화된다.
검증용 데이터와 service 데이터를 합쳐 정상 처리 시간을 계산하지 않는다.

## 로그인과 자동 복구의 범위

현재 수집기는 사용자 LaunchAgent다. 화면 잠금은 로그아웃이 아니므로 로그인 세션이 유지된다.
Mac이 잠자기에 들어가면 수집과 전송은 계속된다고 보장할 수 없다. 로그아웃하면 수집기가
종료되고, 재부팅한 뒤에는 사용자 로그인이 필요하다.

로그인 없이 재부팅 후 복구해야 하는지는 아직 결정되지 않았다. 필요하다면 AI 앱과 수집기의
시작 계정·LaunchDaemon 전환·FileVault 해제 조건·잠자기 설정을 함께 검토해야 한다.
이 PR은 해당 정책이나 시스템 권한을 변경하지 않는다.

## 중앙 검증 실패 시 수동 원복

운영 기록에서 방금 생성한 `ai-service-backup-*` 경로를 `KNOT_AI_BACKUP_DIR`로 지정한다.
기존 LaunchAgent의 실제 바이너리와 환경변수를 유지한 셸에서 수행한다. 아래 비교가 실패하면
후속 변경이 있으므로 자동 원복하지 않고 두 설정을 검토한다. 동시 설정 작업을 중지한 뒤 실행한다.

```sh
if cmp -s "$KNOT_MAC_CONFIG_DIR/config.alloy" "$KNOT_AI_BACKUP_DIR/installed.alloy"; then
  cp -p "$KNOT_AI_BACKUP_DIR/config.alloy" "$KNOT_AI_BACKUP_DIR/restore.alloy"
  if "$KNOT_ALLOY_BINARY" validate "$KNOT_AI_BACKUP_DIR/restore.alloy"; then
    mv "$KNOT_AI_BACKUP_DIR/restore.alloy" "$KNOT_MAC_CONFIG_DIR/config.alloy"
    launchctl kickstart -k "gui/$(id -u)/kr.knot.mac-metrics"
  fi
fi
```

이후 host 새 표본을 중앙에서 확인한다. 새 모듈은 참조가 해제된 비공개 파일로 남겨도 된다.
WAL·인증서·토큰·중앙 시계열을 삭제하지 않는다. AI 앱 자체의 원복은 별도 백업한 아티팩트와
설정으로 수행하며 수집기 원복과 구분한다.

## 검증 근거

코드 검사 통과와 실제 서비스 기동·복구 검증은 구분해 기록한다.
Alloy 동작은 [v1.20.1 validate](https://github.com/grafana/alloy/blob/v1.20.1/docs/sources/reference/cli/validate.md),
[run](https://github.com/grafana/alloy/blob/v1.20.1/docs/sources/reference/cli/run.md),
[HTTP 준비 endpoint](https://grafana.com/docs/grafana-cloud/send-data/alloy/reference/http/)를 참고한다.
설정 디렉터리 전체 대신 단일 후보 파일을 validate해 백업이나 모듈을 최상위 설정으로 중복 로드하지 않는다.
