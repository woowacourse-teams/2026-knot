# 검증 안내

## 2026-10-17 WAV 다운로드 호환 — Issue #767 사전 검증

녹음 조각을 합친 최종 음원을 받을 수 있도록 `audio/wav`를 추가했습니다. 다운로드한 WAV에 기존 PCM 검사기를 적용하고 RIFF 길이·전체 chunk 경계·중복 fmt/data를 검사합니다. 파일 크기 조회 전·헤더 조회 전·각 chunk·결과 반환 전에 다운로드의 취소와 마감 검사를 실행합니다. 작은 WAV와 제어된 checkpoint로 순회 중 취소·마감, 파일 조회 전 취소를 검증하며 실제 시간 경과에 의존하지 않습니다. WAV 입력은 PCM16LE mono 16kHz, 양수 길이, 최대 2시간입니다. 기존 WebM의 변환·상한 절단 경로는 유지합니다.

- `./gradlew spotlessApply test --tests '*S3AudioDownloaderTest' --tests '*HeadlessTranscriptionEngineTest'`: 성공
- `./gradlew spotlessApply test spotlessCheck bootJar`: Java 119개 통과, 실제 모델 opt-in 1개 생략
- `pnpm --dir runner install --offline --frozen-lockfile`, `pnpm --dir runner typecheck`, `pnpm --dir runner test`: 성공, Node 12개·Python 5개 통과
- 정상 WAV 3종(기본 헤더·data 앞/뒤 추가 chunk와 홀수 padding), 잘못된 WAV 11종(MIME 위장·잘림·RIFF 길이·다른 PCM 형식·chunk 경계)을 검증했습니다. 다운로드 URL 제한·크기·시간 제한과 기존 WebM 테스트도 통과했습니다.
- 원문 hash 검사와 수정 Java 작성 규칙 검사는 통과했습니다. 공통 감사는 DB 없는 AI 모듈에 해당하지 않는 기존 STACK 진단 4개를 반환합니다. 전체 소스 감사에는 이번 변경 밖 `TranscriptionJobControllerTest`의 기존 GWT 진단 4개도 남아 있습니다.

이 검증은 #767의 WAV 입력 호환 부분만 다룹니다. 실제 Chrome·S3·모델 경로, 백엔드 최종화·대기열·문서 생성과 운영 배포는 별도 검증 대상입니다. jar는 `build/libs/knot-ai-server-0.0.1-SNAPSHOT.jar`입니다.

## 2026-10-16 앞 2시간 전사 — Issue #723

자동 종료 응답 지연으로 파일이 2시간을 넘더라도 전사할 수 있도록, FFmpeg 출력에 `-t 7200`을 적용했습니다. PCM 검증과 출력 바이트 제한은 유지합니다.

- `./gradlew spotlessApply check bootJar`: Java 102개 통과, 실제 모델 opt-in 1개 생략
- `pnpm --dir runner typecheck`, `pnpm --dir runner test`: 타입 검사와 Node 12개·Python 5개 통과
- Governance 검증기 테스트 10개와 `git diff --check`: 통과
- `HeadlessTranscriptionEngineTest`: 5개 입력 길이에 대해 제한된 WAV가 runner에 전달되는지, 다운로드한 원본이 유지되는지 확인합니다. 변환기가 상한을 무시한 PCM은 기존처럼 거절합니다. 이 테스트의 변환기·runner는 stub입니다.

로컬 FFmpeg 8.1로 실제 변환도 별도 확인했습니다. 16kHz mono PCM16 입력의 샘플 수별 결과는 다음과 같습니다. 115,200,000샘플이 7,200초입니다.

| 입력 샘플 수 | 출력 샘플 수 |
| ---: | ---: |
| 16,000 | 16,000 |
| 115,199,999 | 115,199,999 |
| 115,200,000 | 115,200,000 |
| 115,200,001 | 115,200,000 |
| 115,216,000 | 115,200,000 |

모든 출력 PCM이 입력의 앞부분과 바이트 단위로 같고, 입력 SHA-256이 유지됐습니다. 7,201초 합성 Opus WebM에서도 제한 결과가 전체 디코딩 결과의 앞 7,200초와 일치했습니다. `-t`를 빼면 7,201초가 출력돼 기존 상한 검증에 걸리는 것도 확인했습니다.

```sh
ffmpeg -f lavfi -i 'sine=frequency=997:sample_rate=16000:duration=7201' -c:a libopus -b:a 16k source.webm
ffmpeg -i source.webm -vn -ac 1 -ar 16000 -c:a pcm_s16le -t 7200 -fs 231448576 output.wav
```

공통 컨벤션 감사는 변경 파일·전체 범위 모두 DB 없는 모듈에 적용되지 않는 기존 진단 4개(JPA·Flyway·PostgreSQL·DB Testcontainers)만 반환했습니다. 원문 hash 검사는 통과했으며 새 Java 작성 규칙 진단은 없습니다. 실제 모델의 2시간 전사, S3·EC2·문서 생성 전체 흐름과 배포는 이번 검증에 포함하지 않습니다.

## 2026-10-13 로컬 검증

- `./gradlew spotlessApply check bootJar`: 성공. Java 97개 통과, opt-in 실제 모델 테스트 1개 생략
- `pnpm --dir runner install --offline --frozen-lockfile`: 성공
- `pnpm --dir runner typecheck`: 성공
- `pnpm --dir runner test`: Node 12개·Python 5개 통과
- Governance 검증기 테스트 8개와 `git diff --check`: 성공
- 독립 코드 리뷰: 수정이 필요한 확정 결함 없음

Knot 컨벤션 00~08 원문 hash 검사와 AI 모듈 전체 Java 작성 규칙 검사를 확인했습니다. 공통 감사기는 DB 없는 AI 모듈에도 JPA·Flyway·PostgreSQL·DB Testcontainers를 요구해 적용되지 않는 진단 4개를 반환합니다. 자동 감사 전체 통과와 구분합니다.

| 규칙군 | 적용 |
| --- | --- |
| STACK·LIB | 팀 Java 25·Boot 4.1·Gradle 유지, 기존 Spring Validation과 runner 실행 도구 사용 |
| LAYER·MODEL | Request→Command, 단일 작업 조정, 결과 불변식, 외부 실행 Port 유지 |
| API | Bearer 인증, 202·Location, 현재 작업 조회, 한글 오류·검증, 민감값 비노출 |
| DB | 해당 없음. 메모리의 현재 작업만 관리 |
| JAVA·TEST | 팀 formatter·작성 규칙과 정상·실패·동시성·프로세스 회귀 검증 |
| AI·DELIVERY | 실제 코드 검토와 실행 증거 확인, #632·#634·#635·#636을 한 PR에 포함 |

## 기본 검증 범위

`ai-server/`에서 다음 검사를 실행합니다. 실행 결과는 해당 PR의 검증 기록을 기준으로 합니다.

```sh
pnpm --dir runner install --frozen-lockfile
pnpm --dir runner typecheck
pnpm --dir runner test
./gradlew check bootJar
```

| 검사 | 확인하는 범위 |
| --- | --- |
| Java 테스트 | 결과 불변식, 중복·동시 접수, cleanup 전 슬롯 유지, 결과 교체, 종료·정리 실패 후 접수 차단 |
| MVC 테스트 | Bearer 인증, 요청 검증, 202·Location, 조회·health·오류 응답 |
| Infrastructure 테스트 | S3 URL·리다이렉트·다운로드 제한, 실제 PCM 형식·길이, fake engine·runner 호출 |
| Node 테스트 | 한글 복원, 모델 기본 인자, RMS 정규화, 발화 병합과 자식 프로세스 종료 |
| Python·JVM 종료 테스트 | 실행 제한 시간, 부모 JVM 강제 종료와 재시작 후 소유 프로세스 회수 |
| Spotless·컴파일·JAR 빌드 | Java 포맷과 독립 모듈 빌드 |

기본 검사에는 실제 모델·S3·EC2 연결이 포함되지 않습니다. 다운로드 제한 시간 검사는 stub 기반이므로 실제 네트워크가 멈춘 환경의 정확한 종료 시간을 입증하지 않습니다. DB를 사용하지 않으므로 AI 모듈의 검사에 JPA·Flyway·DB Testcontainers는 포함하지 않습니다.

`RealPipelineHttpTest`는 `AI_REAL_PIPELINE=true`와 모델·녹음 경로를 제공할 때만 실행됩니다. 이 테스트도 S3는 stub으로 대체합니다. 실행 명령은 [README](../README.md#실제-모델-http-테스트)에 있습니다. 실제 S3·EC2 연결과 전사 품질·운영 배포는 별도로 확인해야 합니다.

## 2026-10-13 실제 모델 검사

설치된 Whisper·sherpa 모델과 10.7초 한국어 합성 음성으로 `RealPipelineHttpTest`가 통과했습니다. HTTP 접수·폴링, FFmpeg 변환, runner의 전사·화자 분리, 한국어 발화 결과 반환까지 확인했습니다. S3 전송은 stub으로 대체했으며, 테스트 음성과 로컬 모델 경로는 커밋에 포함하지 않습니다.

이 결과는 실제 S3·EC2 연결이나 회의 녹음의 전사 품질을 입증하지 않습니다.

## 녹음 중 구간 전사 검증 (2026-10-18)

- `./gradlew spotlessApply check bootJar`: 테스트 123건, 실패 0건, 실제 HTTP 환경 선택 테스트 1건 생략.
- `pnpm run typecheck && pnpm test` (runner): Node 16건, Python 7건 통과.
- 실제 설치된 whisper/sherpa 모델로 비공개 음성의 10초 구간을 TS 파이프라인에 입력했다.
  결과: 단어 토큰 21개, 지역 화자 1명, 실제 512차원 벡터, 결과 파일 35,260 bytes.
- 이 실행은 입출력 연결과 실제 벡터 계산을 확인한다. 화자 정답 정확도, 동시 녹음 2개,
  종료 후 30초 목표는 백엔드 스케줄러와 최종 병합이 연결된 별도 통합 검증 대상이다.

### 실제 구간 길이별 처리 시간

같은 비공개 음성의 앞부분을 16 kHz PCM16 WAV로 잘라 실제 runner를 실행했다.
각 실행은 모델 초기화와 whisper·diarization·embedding 계산을 포함한다.

| 입력 | 완료 시간 | 결과 |
| --- | ---: | --- |
| 30초 | 7.397초 | 단어 64개, 화자 1명 |
| 40초 | 9.459초 | 단어 77개, 화자 2명 |
| 30초 tail 2개 직렬 | 첫 번째 7.415초, 두 번째 누적 14.818초 | 모두 성공 |

처음 30초 실험에서는 Whisper가 WAV 범위 밖 30.000~30.020초 단어를 출력해 실패했다.
실제 단어 시간과 입력 구간의 교집합을 보존하고 완전히 벗어난 단어를 제외하도록 수정한 뒤
동일 입력을 다시 실행해 위 결과를 얻었다. 해당 경계는 Node 회귀 테스트로 검증한다.
이 측정은 로컬 runner만 포함하며 BE 다운로드·DB·최종 병합, 긴 작업의 대기 시간은 제외한다.

### 실제 HTTP·모델 연결 검증

`RealPipelineHttpTest`에 구간 전사 선택 테스트를 추가했다.
2026-10-18 실제 설치 모델과 10초 비공개 PCM WAV로 두 테스트를 직접 실행했다.
결과는 2건 통과, 생략 0건이다. 기존 전체 전사 테스트는 5.798초,
구간 전사 테스트는 5.240초 걸렸으며 테스트 시간에는 Spring context 수명이 포함된다.

구간 테스트는 로컬 S3 응답 대역 → 실제 다운로드·변환 → HTTP POST/GET →
Whisper·sherpa → 결과 역직렬화를 연결한다. 요청의 pipeline/hash/global frame 범위 보존,
지역 단어 frame 범위, 실제 512차원 유한수 벡터, 성공 후 작업 디렉터리 제거를 검증한다.
실제 S3와 네트워크 지연, BE 스케줄링, 화자 정답 정확도는 이 테스트 범위 밖이다.

선택 실행에는 `AI_REAL_PIPELINE=true`, `AI_REAL_AUDIO`, `AI_REAL_PROFILE`을 설정한다.
구간 검증은 추가로 `AI_REAL_WINDOW_AUDIO`에 130초 이하 16 kHz mono PCM16 WAV를 지정한다.
`AI_REAL_WINDOW_DECODE_POLICY`에 `WITH_VOCABULARY`를 주면 용어 디코딩까지 실행하고, 생략하면 `DEFAULT_ONLY`다.
프로필과 음성은 저장소에 추가하지 않으며 실제 실행 후 임시 파일을 삭제했다.

### 변환 준비 분리 검증

2026-10-18 `AI_REAL_PIPELINE=true`와 비공개 10초 WAV를 지정해
`./gradlew spotlessApply check bootJar`를 실행했다. **131건 전부 통과, 생략 0건**이다.
실제 준비 HTTP 테스트는 1.089초, 기존 전체 전사 6.187초, 구간 전사 5.215초였다.
시간은 테스트 context 수명을 포함하며 긴 입력 성능 보장을 의미하지 않는다.

준비 전용 테스트는 canonical 44바이트 헤더, 정확한 길이와 SHA256, 인증, 결과 재생,
다음 작업 접수 전까지 파일 유지, 전송 중 파일 고정, 실패 시 부분 파일 제거,
재시작 후 UUID 부분 작업 정리, 외부 심볼릭 링크 거부를 검증한다.
기존 supervisor의 종료·기한·JVM 소실 테스트도 같은 전체 실행에서 통과했다.
새 준비 경로는 그 supervisor의 decoding 실행과 소유권 정리를 그대로 사용한다.
임시 음성·프로필·오디오 결과는 실행 후 삭제했다.

### 입력 안의 길이 0 단어 보존 (2026-10-18)

브라우저 검증에서 보관한 실제 Whisper 출력의 단어 27개 중 하나가 길이 0이어서
구간 결과 조립이 `INVALID_RESULT`로 실패했다. 같은 segment의 인접한 양의 길이 단어와
묶어 텍스트를 보존하고, 원래 관측된 시작·끝의 최솟값·최댓값을 사용하도록 수정했다.
각 단어의 독립 시각 대신 묶음 시각을 제공하며, 입력 밖 단어 제외와 잘못된 시각 거절은 유지한다.

- 회귀 5개가 수정 전 실패했고 수정 후 통과했다. 선행·중간·후행·연속·frame 0을 포함한다.
  다른 segment에서 시각을 빌리지 않는 실패, 역전·비유한 시각 거절,
  한글 중간 바이트 분할과 문장부호 보존도 확인했다.
- runner 타입 검사, Node 27건·Python 7건 통과.
- `./gradlew spotlessCheck check bootJar --no-parallel` 성공. Java 140건 중 137건 통과,
  실제 모델 opt-in 3건 생략, 실패·오류 0건.
- 보관한 실제 모델 출력·화자 증거·window 메타데이터를 수정된 조립기에 다시 입력해
  단어 27개를 토큰 26개로 보존했다. 텍스트·원본 JSON·화자 증거 보존과
  양의 길이·입력 범위를 확인했다. native 모델 추론을 재실행한 검증은 아니다.
- 기존 #819·#821 통합 검증 사본에서 BE 소비자 테스트 47건을 통과했다.
  `IncrementalTranscriptMergerTest` 18건과 `AiWindowEvidenceTest` 29건이며 제품 소스 변경은 없다.
- 컨벤션 00~08 원문 hash gate 통과. 공통 전체 검증기는 DB 없는 AI 모듈에
  JPA·Flyway·PostgreSQL·Testcontainers를 요구하는 4건으로 감사 단계에서 중단돼
  위 Gradle 검증을 직접 실행했다. 전체 감사 통과로 기록하지 않는다.

전체 브라우저 시나리오 6건 재실행, 실제 S3·운영 배포와 출시 성능·화자 품질 판정은 포함하지 않는다.

| 컨벤션 범위 | 적용 근거 |
| --- | --- |
| STACK | 기존 Node·TypeScript·Java 빌드 사용 |
| LIB | 의존성·버전 변경 없음 |
| LAYER | window 결과 조립에서만 정규화, 공용 Whisper parser 유지 |
| MODEL | 양의 길이·범위·화자 참조 검증 유지 |
| API | 내부 토큰의 여러 단어 묶음 의미를 계약 문서에 명시 |
| DB | 스키마·저장 경로 변경 없음 |
| JAVA | Java 수정 없음, 기존 Spotless 검사 통과 |
| TEST | 회귀의 Given–When–Then, 정상·실패·경계·소비자 테스트 확인 |
| AI | 작성과 분리한 코드 검토, 한글 분할 바이트·문장부호 추가 검증 |
| DELIVERY | 기존 Issue #816·PR #819 브랜치에서 로컬 수정, 원격 미반영 |
