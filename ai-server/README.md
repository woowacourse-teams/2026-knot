# Knot AI 서버

맥미니에서 S3의 녹음을 내려받아 Whisper 전사와 화자 분리를 실행합니다. EC2 백엔드와 별도로 빌드하는 Java/Spring 모듈이며, TypeScript runner가 모델 실행 파일을 호출합니다.

현재 작업 하나와 마지막 결과만 메모리에 보관합니다. 새 작업을 받으면 이전 결과를 교체하고, 재시작하면 상태와 결과가 사라집니다. EC2는 결과를 저장한 뒤 다음 작업을 요청해야 합니다. EC2의 호출·폴링·DB 저장은 `backend/` 모듈이 담당합니다.

## 설치와 검증

Java 25, Node.js 22 이상, pnpm 10.33.0, Python 3이 필요합니다. 프로세스 감독에는 POSIX 프로세스 그룹과 `ps`가 필요합니다. Gradle은 모듈의 Wrapper를 사용합니다.

저장소 루트에서 실행합니다. runner 실행에 `tsx`가 필요하므로 개발 의존성도 설치합니다.

```sh
cd ai-server
pnpm --dir runner install --frozen-lockfile
pnpm --dir runner typecheck
pnpm --dir runner test
./gradlew check bootJar
```

기본 테스트는 실제 S3·모델을 사용하지 않습니다. Java 테스트는 HTTP 인증·입력·결과 불변식·동시 접수·정리 실패와 JVM 종료 후 소유 프로세스 회수를 확인합니다.

runner 테스트는 한글 복원·정규화·발화 병합·구간 결과와 자식 프로세스 종료를 확인합니다. 실제 S3·EC2 연결과 전사 품질은 별도로 검증합니다.

## 모델 준비와 실행

FFmpeg, `whisper-cli`, sherpa-onnx 화자 분리 실행 파일과 모델 파일을 별도로 준비합니다. 도구·모델의 출처는 [외부 코드와 실행 도구](THIRD_PARTY_NOTICES.md)를 참고합니다.

1. [프로필 예시](examples/profile.example.json)를 `ai-server/profile.json`으로 복사하고 바이너리·모델의 절대 경로와 스레드 수를 설정합니다.
2. [.env.example](.env.example)을 `ai-server/.env`로 복사하고 인증 토큰과 허용할 S3 호스트를 설정합니다. EC2도 같은 토큰을 사용합니다.
3. `ai-server/`에서 환경 변수를 읽고 빌드한 JAR를 실행합니다. Spring은 `.env`를 자동으로 읽지 않습니다.

```sh
set -a
. ./.env
set +a
java -jar build/libs/knot-ai-server-0.0.1-SNAPSHOT.jar
```

`AI_RUNNER_DIRECTORY`는 `runner/`의 절대 경로, `AI_WORK_DIRECTORY`는 이 서버 전용 작업 디렉터리입니다. `.env.example`의 경로는 `ai-server/`에서 읽는 것을 전제로 합니다. 모델·녹음·비밀값·실행 결과·개인 설정은 Git에 포함하지 않습니다.

기본 주소는 `127.0.0.1:18765`이며 포트는 `AI_PORT`로 변경합니다. 모든 API에 `Authorization: Bearer <AI_APP_TOKEN>`이 필요합니다. `GET /health`가 200과 `{"status":"UP"}`을 반환하면 실행 준비가 된 상태입니다. EC2에서 접근할 네트워크 경로는 별도로 구성해야 합니다.

## 요청과 코드 읽기

`POST /api/v1/transcription-jobs`에 `attemptId`, `downloadUrl`, 선택적인 `speakerCount`를 전달합니다. 응답의 `Location`으로 GET을 반복해 상태와 결과를 조회합니다. 입력·응답·재시도 규칙은 [HTTP 계약](docs/http-contract.md)을 기준으로 합니다.

| 읽는 순서 | 책임 |
| --- | --- |
| [TranscriptionJobController](src/main/java/com/knot/ai/transcription/presentation/TranscriptionJobController.java) | HTTP 요청 검증, Request를 Command로 변환, 접수·조회·health 응답 |
| [TranscriptionJobService](src/main/java/com/knot/ai/transcription/application/TranscriptionJobService.java) | 현재 작업 하나, worker와 정리 순서 |
| [HeadlessTranscriptionEngine](src/main/java/com/knot/ai/transcription/infrastructure/HeadlessTranscriptionEngine.java) | 다운로드, PCM 변환·검사, runner 실행, 결과 읽기 |
| [serverPipeline.ts](runner/scripts/serverPipeline.ts) | 음량 정규화, 전사·화자 분리 병렬 실행, 발화 병합 |
| [serverSupervisor.py](runner/scripts/serverSupervisor.py) | 실행 제한 시간, 부모 JVM 감시와 소유 프로세스 정리 |

Java/Spring은 HTTP와 작업 수명을, TypeScript는 모델 실행과 결과 조립을, Python은 실행 제한 시간과 부모 JVM 종료 감시를 맡습니다. worker는 프로세스·파일 정리가 끝난 뒤 결과와 실행 슬롯을 공개하며, 정리 실패 시 다음 접수를 차단합니다.

## 실제 모델 HTTP 테스트

다음 테스트는 Spring HTTP·FFmpeg·runner·설치된 모델을 실행하고 S3 다운로드만 stub으로 대체합니다. `AI_REAL_AUDIO`에는 테스트용 한국어 WebM 녹음을 지정합니다.

```sh
AI_REAL_PIPELINE=true \
AI_REAL_PROFILE=/absolute/path/profile.json \
AI_REAL_AUDIO=/absolute/path/test.webm \
./gradlew test --tests '*RealPipelineHttpTest' --rerun-tasks
```

같은 `RealPipelineHttpTest`에서 모델 실행 없는 canonical WAV 변환 준비도 검사합니다. 구간 전사를 함께 검사하려면 `AI_REAL_WINDOW_AUDIO`에 60초 이하 16kHz mono PCM16 WAV를 추가로 지정합니다.

기본 Gradle 실행은 실제 모델 테스트를 생략합니다. 위 opt-in 테스트도 S3는 stub이며, 실제 S3·EC2 연결·DB 저장·운영 배포와 회의 전사 품질은 별도로 검증합니다. 프로필·음성·실행 결과는 저장소에 추가하지 않습니다.
