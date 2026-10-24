# CodeBuild 두 그룹 병렬 실행과 S3 캐시 검증

관련 이슈: [#715 Backend CI 실행 시간 개선](https://github.com/woowacourse-teams/2026-knot/issues/715)

관련 PR: [#769](https://github.com/woowacourse-teams/2026-knot/pull/769)

상태: 2026-10-18, 개발 AWS 설정 적용과 캐시 전후 각 3회 검증을 마쳤음. 5분 미만 목표는 미달성임.

## 1. 문제 인식

GitHub Actions는 통합 그룹과 나머지 그룹을 병렬 실행하지만, 개발 CodeBuild는 inline `clean build` 한 개를 실행했음. 콘솔 조사 당시 캐시는 없었고 2 vCPU, 메모리 3GB의 Small 환경이었음. 최근 성공 이력의 약 12분은 통제한 A/B 기준선으로 사용하지 않음.

저장소 YAML만 바꿔도 현재 프로젝트의 inline 명령이 자동으로 바뀌지는 않음. 실행 명령과 AWS 프로젝트 설정을 함께 맞춰야 했음.

## 2. 가설과 선택

같은 CodePipeline Build 단계에 일반 CodeBuild 작업 두 개를 `runOrder=1`로 두면 기존 역할을 사용해 두 그룹을 병렬 실행할 수 있음. 다음 Deploy 단계는 Build 단계의 두 작업이 모두 성공해야 진입함.

별도 배치 역할을 요구하는 batch 대안은 채택하지 않았음. `batch` YAML은 실행 그래프를 정의하지만 IAM 권한을 생성하는 설정은 아님. 현재 방식은 기존 `codebuild-project`와 `codepipeline-project` 역할을 사용함.

| 작업 | 실행 범위 | 출력 |
| --- | --- | --- |
| IntegrationTest | 전체 통합 테스트 | IntegrationReports |
| Build | Spotless → 전체 단위 → 전체 인수 → bootJar | 기존 BuildArtifact |

입력은 둘 다 같은 `SourceArtifact`임. Deploy 입력은 기존 `BuildArtifact`를 유지했음. 기본 `CI_GROUP=all` 단독 실행도 유지했음.

## 3. 가설 검증

### 비교 조건

- Java와 테스트 소스는 `99d7b56dfab430a6dea78520b9cb370512a2408a`로 고정했음. 소스 ZIP SHA-256은 `fc00c21f522aeb0df36f92defabc376fa790d87a3ab30b6b753c71c6bd31a005`임.
- 비교 실행은 같은 최종 inline buildspec을 사용했음. 테스트 명령은 매번 `--rerun --no-build-cache`로 실행했음.
- 컴파일 준비와 Spotless에는 Gradle build cache를 허용했음. S3 캐시는 `techcourse-project-2026-artifacts/knot/codebuild-cache/dev`에 저장했음.
- 캐시 키는 그룹, Gradle 설정 해시와 소스 해시로 구분함. 같은 설정의 이전 소스 키는 fallback으로 조회함.
- 테스트 결과, DB 데이터와 배포 파일은 캐시하지 않았음. 원본 XML과 HTML은 별도 artifact로 보존했음.
- 캐시 없음은 실행별 `NO_CACHE` override를 사용했음. 캐시 있음은 이미 저장한 동일 키를 사용하는 재실행임. 각 조건에서 그룹별 3회씩 비교했음.
- 캐시가 비어 있는 첫 저장 실행은 별도 표본으로 기록했음. 이를 `NO_CACHE` 기준선으로 혼용하지 않았음.
- AWS의 서로 다른 관리형 빌드 호스트를 사용했음. 조건별 실행 시점과 호스트 부하를 완전히 통제하지 못했음.

### 기존 검증과 artifact

단위 1,544개, 통합 681개, 인수 567개, 총 2,792개를 기준으로 XML의 테스트 목록과 실패, 오류, 스킵을 대조했음. 통합과 인수 목록은 원문 기준으로 비교했음. 단위 목록 중 JVM 실행마다 바뀌는 lambda 주소는 그 주소만 정규화한 후 비교했음. assertion과 테스트 코드는 변경하지 않았음.

이 변경에는 테스트 데이터 초기화나 스레드 생명주기 수정이 없음. 각 CodeBuild는 독립 작업 공간과 테스트 JVM에서 실행함. 앞서 검증한 테스트 격리 로직을 유지하고 전체 목록이 실제 실행되는지 확인했음. 별도의 SQL 격리 부정 사례를 이번 YAML 변경에서 다시 실행했다고 주장하지 않음.

remaining 출력에는 루트 `app.jar`, `appspec.yml`, `scripts/`의 기존 배포 파일 7개가 있어야 함. integration 출력에는 배포 JAR가 없어야 함. 각 ZIP에 해당 그룹의 XML과 HTML이 있는지 확인했음.

### 실패 로그 보존에서 발견한 문제

처음에는 `on-failure: ABORT`를 사용했음. AWS 실패 canary에서 BUILD가 `FAILED_WITH_ABORT`로 끝나며 후속 artifact 업로드도 건너뛰었음. 실패 리포트 수집 목적에 맞지 않아 이 설정을 제거했음.

최종 명령은 `set -euo pipefail`로 테스트 실패 뒤 패키징을 중단함. 앞선 phase가 실패한 경우에는 `CODEBUILD_BUILD_SUCCEEDING` 확인으로 BUILD 진입을 차단함. 기본 실패 전이는 유지해 이미 생성된 XML과 HTML을 업로드하도록 했음.

수정 후 canary는 BUILD가 실패한 상태에서 `POST_BUILD`와 `UPLOAD_ARTIFACTS`가 성공했음. S3 ZIP의 실패 XML 1개와 HTML 1개를 직접 내려받아 확인했고 JAR는 없었음. 이는 의도한 실패 fixture이며 제품 테스트 실패로 집계하지 않음.

로컬 shell fixture 15개, 실제 로컬 전체 2,792개와 패키징도 통과했음. shell fixture, 실제 Gradle 실행과 AWS 관측은 구분했음.

## 4. 결과

### 실행 전체 시간

아래 값은 CodeBuild 콘솔의 개별 빌드 전체 소요 시간임. Gradle 테스트 Duration이나 실제 Pipeline 전체 완료 시간이 아님.

| 반복 | 통합 캐시 없음 | 통합 캐시 재사용 | 나머지 캐시 없음 | 나머지 캐시 재사용 |
| --- | --- | --- | --- | --- |
| 1 | 8분 10초 | 5분 1초 | 7분 55초 | 5분 11초 |
| 2 | 7분 55초 | 5분 54초 | 7분 40초 | 5분 37초 |
| 3 | 7분 45초 | 5분 56초 | 7분 32초 | 5분 0초 |
| 중앙값 | 7분 55초 | 5분 54초 | 7분 40초 | 5분 11초 |

통합 그룹의 중앙값은 121초, 약 25.5% 감소했음. 나머지 그룹은 149초, 약 32.4% 감소했음. 모든 실행에서 해당 그룹의 테스트가 통과했고 실패, 오류와 스킵은 0개였음. 매 비교 쌍은 총 2,792개 검증을 유지했음.

캐시가 비어 있는 최초 실행은 통합 9분 23초, 나머지 9분 25초였음. 이 실행의 POST_BUILD는 각각 99초, 107초로 캐시를 최초 저장하는 시간이 포함됐음. 캐시 전후 표에는 이 값을 넣지 않았음.

### 줄어든 비용과 남은 시간

- `testClasses`는 통합 캐시 없음 161/157/156초에서 재사용 8/15/15초, 나머지 162/163/158초에서 16/17/9초로 줄었음. 재사용 6개 빌드 모두 compileJava와 compileTestJava의 FROM-CACHE를 확인했음.
- 통합 테스트 자체는 캐시 재사용 실행에서도 258/271/274초 걸렸음. 테스트 실행 비용은 그대로 남았음.
- 재사용 실행의 DOWNLOAD_SOURCE는 통합 11/40/39초, 나머지 38/42/35초였음. 이 단계에는 소스와 캐시 복원 비용이 함께 포함되므로 전부 캐시 시간으로 단정하지 않음.
- UPLOAD_ARTIFACTS는 통합 9초, 나머지 29~34초였음. 리포트 보존과 배포 파일 업로드는 유지했음.
- QUEUED는 12개 비교 빌드 모두 콘솔에서 1초 미만으로 표시됐음. 호스트 성능과 네트워크 변동까지 통제했다는 의미는 아님.

### 실행별 근거

| 조건 | 그룹 | 회차 | 빌드 ID | 전체 시간 |
| --- | --- | --- | --- | --- |
| 캐시 없음 | integration | 1 | `bad5b02c-b49a-4228-b21e-ea81007a11f7` | 490초 |
| 캐시 없음 | integration | 2 | `57f809d4-a765-4195-b204-9323acf7a63d` | 475초 |
| 캐시 없음 | integration | 3 | `93960a65-21de-478f-a6b0-e249491ced2a` | 465초 |
| 캐시 없음 | remaining | 1 | `2caf74cd-6839-453c-b71a-be303cf7c38f` | 475초 |
| 캐시 없음 | remaining | 2 | `f758987f-137a-4a2e-8fe7-b0d05e21287a` | 460초 |
| 캐시 없음 | remaining | 3 | `567ddec5-47d2-4e7f-94ec-046692a5445e` | 452초 |
| 캐시 재사용 | integration | 1 | `145b0206-b111-4340-b79d-cb8392d12415` | 301초 |
| 캐시 재사용 | integration | 2 | `14fb4218-afbc-427b-af31-96e1c4591a5f` | 354초 |
| 캐시 재사용 | integration | 3 | `03da40b7-dad1-4375-ad94-20d40be50987` | 356초 |
| 캐시 재사용 | remaining | 1 | `70b9791e-3153-4acc-ac46-5cd870b419df` | 311초 |
| 캐시 재사용 | remaining | 2 | `febc0329-f6c9-4387-9666-c96d914003db` | 337초 |
| 캐시 재사용 | remaining | 3 | `3c67b3ca-be48-435a-ad95-ca18bb5ee4f1` | 300초 |

최초 전체 AWS 사전 검증은 `64f071b9-b6f9-443f-95c2-252f3e2d6e49`에서 2,792개를 통과했음. 실패 리포트 canary 수정 전은 `75f57adc-79f9-4ea6-b824-520b5105bfef`, 수정 후는 `53f43284-20e3-4ae8-9cb3-35084ccacc88`임.

잘못된 소스 ZIP 루트로 실패한 `cf5e91e0-6845-4e3e-830a-e765574f5e4b`와 소스 해시 glob이 디렉터리를 포함해 중단한 `76fecd24-308c-4a15-96f2-05776f2f74a0`의 로그도 보존했음. 설정을 바로잡은 뒤 같은 소스로 비교했고 이 두 실행과 canary는 제품 성능 표본에서 제외했음. 제품 테스트 실패 표본을 임의로 제외한 것은 아님.

로컬 `CI_GROUP=all` 실제 실행은 330.533초였음. 로컬 수치를 AWS 전후 개선율에 혼용하지 않았음. 최종 buildspec SHA-256은 `b2e41abc8a19ceff3f61276a49b2595a9c3a651458d1d2018fc601cd9b1fe3b3`임.


## 5. 판단

일반 CodeBuild 두 작업의 병렬 구성과 S3 캐시는 개발 AWS에 적용했음. 실제 IAM 역할과 공용 정책은 변경하지 않았음. 필수 리소스 태그 세 개도 확인했음.

이번 검증은 배포를 시작하지 않는 직접 CodeBuild 실행임. 실제 CodePipeline을 실패시키는 종단 간 배포 차단 시험과 Pipeline 전체 경과 시간은 측정하지 않았음. 다음 단계 진입 조건은 적용한 단계 구성과 AWS 계약으로 확인한 범위임.

캐시 재사용에서도 통합 그룹이 301~356초, 나머지 그룹이 300~337초였으므로 5분 미만 목표는 미달성임. 병렬화와 준비 비용 감소는 채택하되 시간 보장으로 표현하지 않음. 다음 개선 후보는 통합 테스트 수행 비용과 캐시 복원 범위를 분리해서 측정하는 것임. 추가 sharding이나 자원 확대를 이번 변경에 포함하지 않았음.

기존 Actions의 35.1% 감소율을 CodeBuild 결과에 가져오거나 두 그룹 개별 시간을 실제 Pipeline 완료 시간으로 간주하지 않음. 최초 캐시 hit과 이후 반복의 시간 차이도 숨기지 않고 전체 표본을 유지했음.

추가 영구 컴퓨팅이나 공용 IAM 변경은 없음. 실험 빌드와 S3, 로그에는 사용량이 발생함. 월 예산 잔액과 실제 청구 금액을 확인한 기록은 없으므로 예산 내 사용을 보장하지 않음.

## 근거와 보존 위치

- [후속 7 계획](https://app.notion.com/p/3fc0691a002381e29eb4eccfa0ff2e6b)
- [후속 7 결과와 증거 이미지](https://app.notion.com/p/3fc0691a002381e988d0d456fffc76c4)
- 로컬 원시 로그, 소스 manifest, 단계 화면과 XML 감사: `backend/output/ci-715/codebuild-batch-20261018/`임. 폴더 이름의 batch는 이전 대안의 흔적이며 채택한 방식은 일반 병렬 작업임.
- S3 실행별 artifact: `techcourse-project-2026-artifacts/knot/ci-715/codebuild-20261018/`임.
- CloudWatch 로그 그룹: `/aws/codebuild/project-2026`임.
- [동일 runOrder의 병렬 실행](https://docs.aws.amazon.com/codepipeline/latest/userguide/action-requirements.html)
- [단계 성공 후 다음 단계 진행](https://docs.aws.amazon.com/codepipeline/latest/userguide/concepts-how-it-works.html)
- [S3 캐시 키와 불변성](https://docs.aws.amazon.com/codebuild/latest/userguide/caching-s3.html)
- [실패 전이와 on-failure](https://docs.aws.amazon.com/codebuild/latest/userguide/build-spec-ref.html)
- [BUILD 실패 뒤 artifact 수집 단계](https://docs.aws.amazon.com/codebuild/latest/userguide/view-build-details-phases.html)
