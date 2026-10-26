# Prod v0.6.0 배포 복구와 다음 배포 사전 점검 (2026-10-26)

## 문제와 원인

릴리즈 #994, 커밋 `4952572106fc9a434f7b43219b84278655bc20c5`의 CodePipeline 실행
`b3511bb1-f429-4517-8616-1d341f3246f7`은 빌드 두 그룹이 통과한 뒤 CodeDeploy
`d-CLKH9TCKL`의 ApplicationStart에서 중단됐다. V67~V72를 포함한 정확한 JAR 조합의
검토 승인 파일이 없었다. 후보 기동 전 차단됐고 자동 롤백 `60a43f6a-b3dc-46d1-8111-89ca22d4e6ed`
후 기존 V66 바이너리가 실행 중이었다. 롤백 성공을 새 릴리즈 성공으로 해석하지 않았다.

## 검토 근거

- 활성 JAR: `4f98619a02c9ad39b5b590810b7b5dc229adde24f60e434ad77619ad194c890f`
- 후보 JAR: `2aaf66aa1c9843c6945013b4f5e19c86acf7afd1cb17e795d2506701ad999080`
- 기존 SQL 수정·삭제 0개, V67~V72 6개 추가.
- V67~V68은 신규 문서 재정리/복원 구조 확장, V69는 기존 30초 pipeline을 유지하면서
  2분 형식과 nullable decode policy를 추가한다. V70은 사용자 재시도 횟수 제약 완화,
  V71~V72는 녹음 제목/회의 요약 작업의 신규 테이블·인덱스다.
- 실제 격리 PostgreSQL 18 + pgvector에서 V66 생성 → 구버전 컬럼으로 생성·재시도·완료·보관 →
  30초 pipeline 데이터 설정 → V67~V72 적용 → 구버전 쓰기 재실행이 통과했다.
- Prod RDS 읽기 전용 조회: V66, 문서 생성 QUEUED/RUNNING 0건, 녹음 131건 모두 ENDED,
  incremental pipeline은 모두 null, 전사 window 0건, 문서 주제 operation 0건이었다.
- 실제 활성 프로세스의 incremental/category/topic 플래그는 미설정이며 활성/후보 모두 기본값 false다.
  SPRING_APPLICATION_JSON override는 없었다. 구·신 Worker 형식이 공존하는 작업이 없어 rolling으로 검토했다.
- 후보 슬롯 green의 중앙 로그 조회 preflight가 통과했다.
- SQL fixture는 구버전 Java의 모든 업무 API E2E 검증을 의미하지 않는다.

## 조치

운영자 소유 보호 경로에 위 환경·두 JAR 해시를 묶은 15분 검토 승인 파일을 만들었다.
원본 배포의 검사기로 V67~V72와 `mode=rolling`을 확인한 뒤 CodeDeploy에서 같은 산출물을
재시도했다. 승인 검사를 삭제하거나 기존 SQL을 수정하지 않았다.

## 첫 복구 결과

- CodeDeploy `d-ZTXHANDKL`: **성공**, 인스턴스 1/1, ValidateService 통과.
- 수명 주기 약 35초. 11:40:48 KST 시작, 11:41:20 배포 hook 완료.
- 실제 활성 Green 8081, 이전 Blue 종료, 후보 JAR SHA-256 일치.
- Flyway: 65개 검증, 추가 6개 적용, now at version v72.
- 외부 `https://api.knoted.kr/actuator/health`: HTTP 200, UP.
- Alloy JVM 지표 프록시 확인.
- 중앙 Loki 수신: request ID `c16dad7fb668395a0e67c378e4ff5f88`, env=prod, slot=green, matching_lines=1.
- 추가 CodeBuild 없이 기존 S3 BuildArtif/Y67RxdW 산출물을 재사용했다.
- Mac mini AI(18767) 교체나 2분 전사 기능 활성화는 이번 작업에서 수행하지 않았다.

## 다음 배포 준비

Prod에 읽기 전용 `sudo knot-deploy-preflight prod <deployment-archive>`를 설치했다.
실제 Nginx가 가리키는 슬롯을 찾아 운영자 소유 검사기로 SQL과 승인 필요 여부를 확인한다.
후보 아카이브의 코드를 실행하거나 승인 파일을 자동 생성하지 않는다.

- 운영 실행에서 active_service=green, added=none, mode=rolling으로 통과했다.
- 실제 ZIP을 사용하는 로컬 회귀 7개와 shell 문법·새 Python 규칙 검사가 통과했다.
- JAR 바이너리는 달라도 SQL이 같으면 승인 없이 통과하는 fixture를 검증했다.
- 후보 검사기가 서버 설치본과 다르면 도구 버전 검토를 요구하고 후보 코드를 실행하지 않는다.
- 새 SQL/미승인은 자동 통과하지 않으며 슬롯 불일치·비정상 라우트·기존 SQL 내용 변경도 차단한다.
- 승인 판단과 발급은 [운영 절차](bluegreen-migration-review-runbook.md)를 따른다.
- 도구는 CodeDeploy 아카이브 밖 `/usr/local/lib/knot-deploy`와 `/usr/local/sbin`에 설치해
  이후 앱 배포에 덮어써지지 않는다. 복구 당시 로컬에 준비한 소스와 운영 기록을 후속 PR로
  보존한다. 서버 설치·운영 검증과 저장소 병합은 별개다.

## CodePipeline 성공 기준 갱신

CodeDeploy 재시도만으로 원래 CodePipeline 실패 이력은 바뀌지 않는다. 다음 자동 롤백의
성공 기준도 #994로 갱신하기 위해 기존 파이프라인을 한 번 재실행했다.

- 실행: `8e9fba61-6d0b-4045-b6a3-19f7469bfa0f` (11:48 KST 시작), 소스 커밋을 위 #994 SHA로 고정.
- 이 확인은 CodeBuild 두 그룹을 다시 실행한다. 원래 빌드/테스트·배포 검사를 유지한다.
- 파이프라인·IAM·GitHub 연결을 변경하거나 새 파이프라인을 만들지 않았다.
- **최종 성공**: Source → Build/IntegrationTest → Deploy 전부 통과, 총 11분 56초.
- 배포 `d-D7H7UJDKL`: 추가 검토 승인 발급 없이 `added=<none> mode=rolling` 통과.
- 최종 활성 Blue 8080, Green inactive. JAR 해시는 재빌드 전 후보와 같은
  `2aaf66aa1c9843c6945013b4f5e19c86acf7afd1cb17e795d2506701ad999080`이었다.
- 외부 health HTTP 200·UP, JVM 지표, 중앙 Loki 수신
  `bb2827b9f66777b0ddc550ac26ad58fd` (env=prod, slot=blue, matching_lines=1) 확인.
- 이 성공 실행이 이후 파이프라인의 새 정상 릴리즈 기준이다. 과거 실패/롤백 기록은 보존한다.
- 서버 운영 가이드: `/var/lib/knot-backend-deploy/blue-green/DEPLOYMENT-REVIEW.md`.
  복구·최종 실행 증거는 `release-v0.6.0-review-20261026/evidence.json`에 보존했다.

확인 링크: [최종 CodePipeline 실행](https://ap-northeast-2.console.aws.amazon.com/codesuite/codepipeline/pipelines/knot-backend-pipeline/executions/8e9fba61-6d0b-4045-b6a3-19f7469bfa0f/visualization?region=ap-northeast-2),
[최종 CodeDeploy 배포](https://ap-northeast-2.console.aws.amazon.com/codesuite/codedeploy/deployments/d-D7H7UJDKL?region=ap-northeast-2).
이 기록의 운영 검증은 위 복구 시점의 관측이며, 후속 문서 PR에서 재배포한 결과가 아니다.

## 최종 런타임 플래그 관측

초기 V66 프로세스에서는 category/topic 플래그가 미설정이었지만, 최종 새 Blue 프로세스에서는
`DOCUMENT_CATEGORY_ENABLED=true`, `DOCUMENT_TOPIC_REORGANIZATION_ENABLED=true`를 확인했다.
이 작업은 EnvironmentFile/properties 또는 해당 플래그를 수정하지 않았으며 관측된 설정을 보존했다.
2분 전사의 `RECORDING_INCREMENTAL_TRANSCRIPTION_ENABLED`는 미설정(default false)이다.
V66 Worker 소스도 ASSIGN_CATEGORY/PUBLISH 단계를 처리하는 것을 확인했다.
다음 검토에서는 기존 프로세스뿐 아니라 후보가 읽을 파일의 유효 플래그도 함께 확인하도록
운영 절차를 보완했다. 전체 env/DB 비밀번호/토큰은 기록하거나 출력하지 않았다.
