# 구간 전사 연결·종료 지연 검증 도구

실제 S3 버전 저장소, 임시 PostgreSQL, BE 서비스, 별도 localhost native AI를 연결해 녹음 두 개와 준비형 legacy 전사를 함께 측정한다. **제품 Chrome E2E와 사람 회의 화자 정확도 검증은 포함하지 않는다.** 이 도구의 성공으로 출시 품질 Hold를 해제하지 않는다.

`--run`이 없으면 WAV 형식·길이와 경로만 확인한다. 네트워크 요청, 프로세스 실행, 임시 파일·S3 객체·보고서 생성을 시작하지 않는다. `--run`은 실제 S3 쓰기와 로컬 native 실행을 명시적으로 선택한다.

## 필요한 환경

- **#816·#817의 녹음 저장·구간 전사 구현을 모두 포함한 BE checkout**, Java 25·Docker·실행 중인 Docker daemon. #818 검증 도구 브랜치만으로는 기반 클래스가 없어 standalone 컴파일할 수 없다. 도구의 `--repo-root`에는 구현 checkout을 지정한다.
- 별도로 검증·빌드한 AI 모듈의 실행 JAR 한 개, 준비된 runner 의존성·FFmpeg·모델.
- 모든 모델·실행 파일의 절대 경로를 담은 profile. 기존 필드 외에 `sherpaLibrary`, `pythonBinary`도 필요하다.
- 사용 권한이 있는 **PCM16·16 kHz·mono WAV**. 원본 파일은 변경하지 않는다.
- S3 Versioning이 켜진 명시적 버킷과 실험 prefix를 읽고 쓰고 삭제할 수 있는 자격 증명.

Java AWS SDK의 표준 credential provider chain을 사용한다. `AWS_PROFILE`, 환경 변수 또는 현재 SDK 구성에서 지원하는 role provider 환경을 준비한다. 추가 SDK 의존성을 설치하지 않으므로 별도 SSO·STS 모듈이 필요한 profile은 해당 checkout에서 지원되는지 먼저 확인한다. 이 도구는 SSH나 다른 서버에서 credential을 가져오지 않는다. credential 값을 출력·직렬화하지 않는다. 자격 증명은 선택한 실험 시간 전체 동안 유효하거나 provider가 갱신할 수 있어야 한다.

## 입력과 실행

아래 경로·버킷은 실행자가 선택한 값으로 바꾼다. `--repo-root`는 `backend/`를 포함한 저장소 루트, `--ai-source-root`는 `runner/`와 검증된 `build/libs/`를 포함한 AI 모듈 경로다. 두 경로가 같은 checkout에 있을 필요는 없다.

```sh
python3 tools/incremental-transcription/run_benchmark.py \
  --repo-root /absolute/path/to/knot-checkout \
  --ai-source-root /absolute/path/to/ai-server \
  --audio-file /absolute/path/to/authorized.wav \
  --profile /absolute/path/to/complete-profile.json \
  --bucket sandbox-versioned-audio \
  --region ap-northeast-2 \
  --seconds 70 --rounds 3 --without-legacy \
  --report /absolute/path/to/new-sanitized-report.json
```

검증된 입력으로 실제 실행하려면 같은 명령에 `--run`을 붙인다. 기본은 `--feed-pause-ms 5000`이다. 5초 PCM 조각을 절대 시계에 맞춰 공급하며 종료 전 catch-up을 기다리지 않는다. 공식 두 녹음 조건은 예시처럼 `--without-legacy`로 측정한다. 이 옵션을 생략하면 55초 시점 부근에 같은 오디오에서 변환한 legacy WebM을 추가 접수하는 별도 스트레스 조건이다. 보고서의 `includePreparedLegacyContention`으로 두 조건을 구분한다. `--feed-pause-ms 0`은 가속 공급 후 catch-up 실험이며 실제 녹음 속도 결과와 구분한다.

`--seconds`는 1~7,200, `--rounds`는 1~3이다. `--start-seconds`로 원본의 시작 위치를 명시할 수 있다. 요청 길이가 남은 원본보다 길면 실패한다. 반복 fixture가 필요할 때만 `--repeat`를 추가한다. 보고서에 실제 반복 여부를 남기며, 반복한 두 시간 파일을 자연스러운 두 시간 회의나 화자 품질 자료로 해석하지 않는다.

프로세스 실행·오디오·원문·벡터·profile·보호 로그는 0700 임시 디렉터리와 0600 파일에 둔다. 공개 보고서는 허용한 시간·상태·개수·정제된 오류 종류와 재현용 git HEAD·dirty 여부·AI JAR SHA256만 포함한다. `repoSource`, `aiSource`, `aiJarSha256`으로 실제 사용한 소스 commit과 실행 바이너리를 확인한다. dirty가 true이면 HEAD만으로 같은 소스를 재현할 수 없으므로 검증한 변경을 별도로 보존·확정해야 한다. 기존 보고서를 덮어쓰지 않는다.

## 정리와 성공 판정

각 실행은 새 UUID의 `knot/experiments/recording-release/incremental-e2e-{UUID}/` 하나만 사용한다. Java 검증기도 이 형식 외의 prefix를 거절한다. 버킷 설정·실제 제품 DB를 바꾸지 않는다.

정상 종료와 테스트 실패 시 모두 BE worker를 중단한 뒤 이 prefix의 객체 버전·삭제 표식을 삭제한다. BE Gradle과 AI는 각각 새 프로세스 그룹으로 시작한다. 드라이버의 시간 초과·중단 때도 자신이 시작한 PID·시작 시각이 일치하는 그룹만 종료하며, 그룹 안의 실행 중인 자식·손자 프로세스가 사라졌는지 확인한다. 신원을 증명하지 못한 프로세스나 다른 실행의 그룹에는 신호를 보내지 않는다. native supervisor는 AI JVM 그룹과 분리된 그룹으로 실행하므로 JVM 그룹 종료만으로 native 종료를 확정하지 않는다. AI 종료 후 private `ai-work` 아래 존재하는 모든 `process-owner.json`이 schemaVersion 1과 `state=CLEANED`인지 추가 확인한다. RUNNING·CLEANUP_FAILED·파싱 불가·심볼릭 링크는 종료 미확인으로 처리하며, 분리된 native 그룹을 드라이버가 임의로 kill하지 않는다. 두 번 연속 빈 버전 목록을 확인해야 정리를 완료한 것으로 본다. 별도 AI JVM도 종료한다. 다음을 함께 확인한다.

- `gradleExitCode=0`, `execution=passed`.
- `workersStopped=true`, `backendProcessStopped=true`, `nativeProcessStopped=true`, `nativeOwnershipClean=true`.
- `cleanupRemainingVersions=0`, `cleanupStableEmptyScans>=2`.
- 각 round의 `bothWithin30Seconds`와 공급 방식. 기능 검증 통과와 지연 목표 통과는 별도다.

전체 실행 상한은 `bootstrap + rounds × (공급 시간 + 종료 결과 대기 + 해당 catch-up 대기) + 종료 여유 90초`로 계산하며 `overallDeadlineSeconds`에 기록한다. paced 공급 시간은 `--seconds`, 가속 공급은 0초다. 가속 공급은 두 녹음의 catch-up 대기를 각각 더하고, legacy 스트레스 조건이면 legacy 완료 대기도 더한다. `--timeout-seconds`는 회차별 최종 결과 대기, `--catchup-timeout-seconds`는 각 catch-up/legacy 대기다. `--bootstrap-timeout-seconds`의 기본값은 300초이며 Gradle 시작 후 private Java 보고서가 생기기 전까지의 정체를 별도로 제한한다. 실행 시간 상한 전에 종료 여유를 확보하고 BE·AI 그룹에 각각 TERM 후 최대 30초, 필요하면 KILL 후 최대 10초를 기다린다.

실패해도 정제 보고서를 남긴다. `cleanupStatus`가 완료되지 않았으면 정리 성공으로 해석하지 않는다. worker 종료를 확인할 수 없으면 private 임시 디렉터리를 보존하고 로컬 콘솔에 복구 경로를 표시한다. 먼저 관련 프로세스가 종료됐는지 확인한 뒤 보고서의 정확한 UUID prefix만 정리해야 한다. 넓은 prefix나 버킷 전체를 정리하는 명령은 제공하지 않는다.

## 도구 검증

Python 회귀 16건은 모델·네트워크·Gradle을 실행하지 않는다. 프로세스 회귀는 짧게 대기하는 실제 자식·손자 Python 프로세스만 사용해 정상 종료·전체/부팅 시간 초과·중단 정리와 외부 PID 보존을 확인한다.

```sh
python3 -m unittest discover -s tools/incremental-transcription -p 'test_*.py' -v
```

공개 Java는 저장소의 Eclipse formatter로 검증한다. Spotless는 project directory 밖의 파일을 직접 포맷할 수 없으므로 이 init은 외부 소스의 컴파일만 추가한다. 구현 checkout과 도구 checkout이 다를 때도 다음 명령으로 컴파일·순수 공급 시계 회귀를 확인할 수 있다. native 모델이나 S3는 실행하지 않는다.

```sh
KNOT_E2E_SOURCE_DIR=/absolute/path/to/tool-checkout/tools/incremental-transcription/java \
  /absolute/path/to/implementation-checkout/backend/gradlew \
  -p /absolute/path/to/implementation-checkout/backend \
  -I /absolute/path/to/tool-checkout/tools/incremental-transcription/init.gradle \
  compileTestJava test --tests '*PcmSupplyClockTest'
```

마지막 부분 조각은 실제 누적 PCM frame 수로 공급 시각을 계산한다. 61초 입력의 마지막 1초 조각은 65초가 아닌 61초에 준비되며, 순수 시계 테스트가 이 조건과 5초·70초·2시간 경계를 검증한다.

개발 환경의 모델·S3 속도, 두 녹음의 backlog, 준비 작업의 경합이 결과에 영향을 준다. 요약 생성은 mock 접수까지만 확인한다. 실제 FE API 연결·Chrome 생명주기·실제 사람 화자 정답 검증은 별도 완료 조건이다.
