# 개발 CodeBuild 실행 구조

`build-stage.yml`은 `knot-backend-dev-pipeline`의 기존 Build 단계를 설명하는
CodePipeline 구성 조각임. 전체 파이프라인이나 IAM 역할을 생성하는 템플릿은 아님.

## 병렬 실행과 배포 조건

- `IntegrationTest`: `CI_GROUP=integration`, 전체 통합 테스트, `IntegrationReports` 출력임.
- 기존 `Build`: `CI_GROUP=remaining`, Spotless → 단위 → 인수 → bootJar와 패키징,
  기존 `BuildArtifact` 출력임.
- 두 작업의 `runOrder`는 1이며 같은 `SourceArtifact`를 사용함.
- Deploy는 다음 단계에서 기존 `BuildArtifact`를 입력으로 사용함.
  현재 Build 단계의 두 작업이 모두 성공해야 다음 단계로 넘어감.
- 두 작업은 기존 프로젝트 `knot-backend-dev-build`와 그 역할 `codebuild-project`를
  사용함. 배치 빌드와 별도 배치 역할은 사용하지 않음.

루트 `buildspec.yml`의 기본 `CI_GROUP=all`은 단독 빌드에서 전체 검증을 수행함.
등록하지 않은 그룹은 실패 처리함. 실제 테스트는 매 실행 수행하며 컴파일 준비와
Spotless, bootJar에는 Gradle build cache를 허용함.

명령 블록은 `set -euo pipefail`로 검증 실패 뒤 패키징을 중단함. 이전 phase 실패는
`CODEBUILD_BUILD_SUCCEEDING`으로 차단함. `on-failure: ABORT`는 리포트 업로드도
생략하는 동작을 AWS canary에서 확인해 사용하지 않음. 기본 실패 전이로 빌드는 실패 상태를
유지하고 `UPLOAD_ARTIFACTS`에서 이미 생성된 XML과 HTML을 수집함.

## 실제 AWS 설정

대상은 ap-northeast-2의 개발 프로젝트와 개발 pipeline임. 운영 프로젝트에 자동 적용하지 않음.
프로젝트가 inline buildspec을 사용하므로 저장소 파일 변경만으로 AWS 명령이 갱신되지 않음.
inline 내용과 루트 `buildspec.yml`을 함께 갱신하고 적용된 화면을 확인해야 함.

S3 캐시는 `techcourse-project-2026-artifacts/knot/codebuild-cache/dev`를 사용함.
키는 그룹, Gradle 설정 해시와 소스 파일 해시로 구분하고 같은 설정의 이전 키를
fallback으로 조회함. 소스 해시는 현재 파일 확장자가 있는 소스와 리소스를 대상으로 함.
확장자 없는 빌드 입력을 추가하면 해시 패턴도 함께 검토해야 함.

캐시에는 Gradle wrapper와 caches만 포함함. 테스트 결과, DB 데이터, 배포 파일과
Gradle 인증 설정을 넣지 않음. 동일 키의 S3 캐시는 불변이므로 소스 해시를 포함함.

배포 artifact의 루트에는 `app.jar`, `appspec.yml`, `scripts/`를 유지함.
XML과 HTML도 artifact 수집 대상으로 두며 실패 실행에서는 CloudWatch 로그와
실제로 업로드된 리포트를 각각 확인해야 함. 설정에 경로가 있다는 사실만으로
실패 리포트 업로드 성공을 판정하지 않음.

## 검증 기록

로컬 shell fixture와 실제 Gradle 실행, AWS 실행은 구분해 기록함.
캐시가 없는 첫 실행과 cache hit 실행의 시간, 빌드 ID, 소스 ZIP 해시와 적용한
buildspec 해시를 남김. 서로 다른 소스나 키의 실행으로 전후 개선율을 계산하지 않음.
5분 이내 완료는 목표이며 실제 측정 없이 보장하지 않음.

- [동일 runOrder의 병렬 실행](https://docs.aws.amazon.com/codepipeline/latest/userguide/action-requirements.html)
- [CodePipeline 단계 실행 규칙](https://docs.aws.amazon.com/codepipeline/latest/userguide/concepts-how-it-works.html)
- [S3 캐시와 동적 키](https://docs.aws.amazon.com/codebuild/latest/userguide/caching-s3.html)
- [CodeBuild action 설정](https://docs.aws.amazon.com/codepipeline/latest/userguide/action-reference-CodeBuild.html)
