# Dev·Prod Blue-Green 마이그레이션 검토 (2026-10-25)

## 문제와 실제 관측

Dev `d-CH9KB5XJL`, Prod `d-V93FM3VJL`은 빌드·통합 테스트가 성공한 뒤
ApplicationStart의 SQL 파일 목록 비교에서 중단됐다. 후보 JVM 시작 전의 차단이며
기동 시간 초과가 아니다. CodePipeline은 이전 정상 아티팩트로 자동 롤백했다.

SSM으로 실제 활성 Green JAR, 실패 배포 아카이브, RDS Flyway 이력을 확인했다.
배포 전 양쪽 활성 JAR는 V61까지의 SQL 54개를 포함하며 DB도 V61이었다.
이전 SQL의 수정·삭제는 양쪽 모두 없다. 이후 버전 번호 중 누락 번호는 기존 이력이며
`54개`와 `V61`은 서로 다른 값이다.

| 환경 | 실제 활성 JAR SHA-256 | 후보 JAR SHA-256 | 추가 SQL |
|---|---|---|---|
| Dev | `8773ae35c52fc7d3abf5d5dd382164ec94d17f62947b7e67bc5d177f63e2fdee` | `e16f01d8724fb7e2000ca7adb94ba35ee4c7ac4863d7b2d4af82d8ffb2926f70` | V62~V69 (8개) |
| Prod | `38886f60f819a1d3ec772393d3bb213833fba0dbb3b43034f7ab9d53138ac366` | `4f98619a02c9ad39b5b590810b7b5dc229adde24f60e434ad77619ad194c890f` | V62~V66 (5개) |

## SQL별 판단

| 버전 | 변경 | V61 구버전과의 관계 |
|---|---|---|
| V62 | 대분류·체크포인트 테이블, documents/job 컬럼·제약 | 구버전 INSERT에는 `LEGACY`, `WRITE_BODY`, 0 기본값 적용. 기존 attempt 합계 규칙은 phase_resume_count=0에서 유지. 신형 phase의 작업을 구 Worker가 가져가지 않도록 기능 플래그/교체 순서를 별도 확인해야 함 |
| V63 | 주제 재정리 operation/category/target/recovery, 문서 복합 UNIQUE | 신규 테이블 중심. 기존 LEGACY 문서 제약은 유지하고 새 대분류 출처 범위를 확장 |
| V64 | operation.started_at | 신규 테이블에 nullable 컬럼 추가 |
| V65 | recovery.id identity unique | 신규 테이블에 자동 생성 식별자 추가 |
| V66 | 취소 시각 제약, dispatch/cancellation 인덱스 | V61에는 해당 테이블이 없어 이 최초 확장 시 기존 operation 데이터가 없음. 일반적인 후속 배포의 취소 상태 데이터까지 자동 승인하는 근거는 아님 |
| V67 | 복원 대상·메타데이터 제약 | 신규 operation 기능에만 적용 |
| V68 | 재정리 대상별 재시도 횟수·시각·인덱스 | 신규 테이블에 기본값/nullable 컬럼 추가 |
| V69 | 2분 전사 pipeline 허용, decode_policy | 기존 30초 pipeline은 계속 유효. 그러나 구 Worker는 새로운 prepared 2분 작업 형식을 이해하지 못하므로 동시 실행 승인 불가 |

## 배포 방식 결정

- Prod: 기존 Blue-Green 유지. `DOCUMENT_CATEGORY_ENABLED`와 재정리 플래그는 실제 실행
  프로세스에서 미설정이고 후보의 기본값은 false다. 새 문서 생성 phase를 활성화하지 않는다.
  진행 중으로 표시된 녹음 1건은 수정·종료하지 않는다.
- Dev: 사용자가 짧은 점검 시간을 명시적으로 선택했다. Nginx에서 health 외 요청을 503으로
  막고 기존 요청을 drain한 뒤 구 Worker 종료 → 새 Worker 기동 → health·metrics 검사 →
  route 전환 → 점검 해제 순서로 실행한다. 준비 시점의 녹음/문서 생성 대기·실행은 0건이었다.
- 실패 시 후보 Worker를 먼저 종료하고 구 Worker/라우트를 복구해 두 형식의 Worker가
  동시에 실행되지 않게 한다. 점검 중에는 새 업무 요청을 받지 않는다.
- DB 스키마는 앱 롤백으로 내려가지 않는다. SQL을 삭제하거나 Flyway repair/clean을
  실행하지 않는다. 이미 적용한 확장 스키마에서 구버전이 동작해야 한다.

## 안전 검사 변경

`verify_migration_compatibility.py`가 실제 JAR ZIP의 SQL 내용을 SHA-256으로 비교한다.
동일 파일명 아래 내용 변경, 기존 파일 삭제, 과거 버전 끼워 넣기는 승인 파일이 있어도 거부한다.
SQL이 완전히 같으면 일반 배포를 허용한다. 추가 SQL은 정확한 환경·활성 JAR·후보 JAR
조합에 대한 운영자 소유 승인 파일이 있어야 한다.

승인 위치: `/var/lib/knot-backend-deploy/blue-green/migration-reviews/`

승인 파일과 부모 경로의 소유자/쓰기 권한·심볼릭 링크를 검사하고, 유효기간은 최대
24시간이다. 이번 운영 승인은 15분으로 발급한다. `backward_compatible`, 검증 근거,
`runtime_preconditions_verified`가 필요하며 Dev의 점검은 `execution_mode=serial-workers`로
명시한다. 알려지지 않은 조합을 허용하는 전역 우회 변수는 추가하지 않는다.

## 검증과 한계

- 격리한 실제 PostgreSQL 18 + pgvector에서 V61 스키마 생성 → 구버전 컬럼만 사용하는
  생성/재시도/완료/문서 보관 → V66 또는 V69 적용 → 동일 쓰기 재실행: 두 경우 모두 통과.
- SQL·JAR 변조, 미승인/만료/다른 환경/권한·symlink/과거 migration 차단 CLI 테스트 통과.
- 기존 슬롯 전환과 실패 복구 테스트, Dev 순차 Worker 성공·실패 복구 테스트 통과.
- Dev 실제 Nginx 전체 설정에 점검 guard를 넣은 별도 파일의 `nginx -t` 통과.
- Prod의 기존 mTLS 중앙 로그 조회 preflight 통과.
- SQL 테스트는 구버전 Java 전체 기능 E2E를 뜻하지 않는다. 특히 Worker 형식 변경은
  SQL 테스트와 별도로 처리한다. 실제 배포 결과는 아래에 구분해 기록했다.

## 운영 결과

2026-10-25 KST 기준. 기존 실패 배포 아카이브를 별도 보호 경로에 복사하고 JAR는
바꾸지 않은 채 검토한 배포 hook을 직접 실행했다. CodeDeploy에 새 실행을 등록한 것은
아니므로 hook 로그의 성공과 기존 CodePipeline/CodeDeploy 실패 이력을 구분해야 한다.
원본 아카이브·GitHub 연결·IAM·파이프라인 설정은 수정하지 않았다.

| 항목 | Dev | Prod |
|---|---|---|
| 스키마 | V61 → V69, 추가 8개 적용 | V61 → V66, 추가 5개 적용 |
| 활성 서비스 | Blue 8080 active, Green inactive | Blue 8080 active, Green inactive |
| 활성 JAR | 위 후보 SHA-256과 일치 | 위 후보 SHA-256과 일치 |
| health | 외부 도메인 HTTP 200·UP | 외부 도메인 HTTP 200·UP, Origin CA 검증한 Nginx 경유 UP |
| API 문서 | `/v3/api-docs` HTTP 200 | `/v3/api-docs` 비인증 요청 HTTP 401 (내용 검증 안 함) |
| JVM 지표 | Alloy 루프백 프록시 수신 확인 | Alloy 루프백 프록시 수신 확인 |
| 중앙 Loki | 수신 확인, 아래 요청 ID | 수신 확인, 아래 요청 ID |
| 검토 hook 종료 코드 | 두 번째 실행 0 | 첫 실행 0 |

중앙 로그 수신 증거:

- Dev: `0ce1ed2b180024db50cc2aa9da8c193a`, environment=dev, slot=blue, matching_lines=1.
- Prod: `051ff831c8cd58ba470b57df16fe6efe`, environment=prod, slot=blue, matching_lines=1.
- Prod Flyway 로그: 59개 검증, 추가 5개 적용, now at version v66.

### Dev 점검과 수정 과정

첫 점검은 19:42:41~19:46:01 KST(약 3분 20초)였다. 후보가 V69까지 적용하고 기동했지만,
순차 Worker 모드에서 모니터링 프록시가 종료한 기존 포트를 바라보는 버그가 있어
metrics 검사가 실패했다. 안전 검사가 이를 차단했고 후보 종료 → 구버전 재기동 → 기존
Nginx 복원으로 복구했다. 이때 V61 구버전 바이너리가 확장된 V69 DB에서 시작하고 health와
API 문서가 응답하는 것을 실제 확인했다. 전체 업무 기능의 역호환 E2E를 대신하지는 않는다.

동일 원인을 재현하도록 fake metrics가 실제 활성 슬롯을 검사하는 회귀 테스트를 추가해
실패를 확인했다. 순차 모드는 후보 포트, 일반 Blue-Green은 기존 포트를 검사하도록 수정한 뒤
테스트가 통과했다. 두 번째 점검은 19:47:36~19:48:23 KST(47초)였고 정상 완료했다.
최종 Nginx 검사 통과, 점검 guard 제거, 구 Worker inactive를 확인했다.

### 배포 기록 위치와 자동화 범위

서버별 보호 경로:

- Dev: `/var/lib/knot-backend-deploy/blue-green/reviewed-dev-20261025/`
  - `reviewed-deploy.log`, `reviewed-deploy.exit`: 첫 실패·안전 복구
  - `reviewed-deploy-2.log`, `reviewed-deploy-2.exit`: 수정 후 성공
- Prod: `/var/lib/knot-backend-deploy/blue-green/reviewed-prod-20261025/`
  - `reviewed-deploy-1.log`, `reviewed-deploy-1.exit`: Blue-Green 성공

Dev의 원래 실패 실행 `7fcc81f4-ed2f-4ac2-ba23-419dc91e1b99`에서 Deploy 단계만 재시도하려
했지만, 자동 롤백 실행으로 superseded되어 AWS가 거부했다. IAM 권한 거부는 아니다.
기존 실패 이력을 성공으로 덮어쓰지 않았고, 상태 표시만 바꾸기 위한 추가 CodeBuild는 실행하지 않았다.
현재 서버 배포 성공과 기존 파이프라인 실패 이력은 함께 남는다.

소스 변경은 Dev/develop와 Prod/main이 서로 다른 hook 구조여서 별도 worktree에 준비했다.
수동 운영 작업 당시에는 커밋·푸시·PR을 실행하지 않았다. 이후 #989에서 이 변경을 저장소에
반영할 PR을 준비했다. PR이 병합되기 전까지 새 검사 방식이 향후 자동 배포에 영구 적용됐다고
판단하지 않는다. 향후 신규 SQL은 재검토·해당 JAR 조합의 한시 승인이 필요하며,
이번 승인은 다른 JAR나 이후 마이그레이션을 포괄하지 않는다.

## 로컬 검증 결과

- SQL/JAR 안전 검사 CLI: 각 worktree 16개 통과.
- Dev 배포 hook: 29개 통과(순차 교체·실패 복구·metrics 회귀 포함).
- Prod 기존 배포 hook 회귀: 43개 통과.
- 실제 PostgreSQL 18 + pgvector: V61 → V66 / V61 → V69 구버전 컬럼 쓰기 시나리오 모두 통과.
- Bash 문법·Python 컴파일·diff 공백 검사 통과.
- 호스트 관측은 위 운영 결과에 한정된다. 부하 검증, 모든 업무 API, 이후 신규 기능 플래그
  활성화의 호환성까지 검증한 것은 아니다.

## Persona 종료 절차 상태

두 worktree에 작업별 구현·검토 기록을 작성하고 `npx ph workflow finish implement`를 실행했다.
종료 판정은 verification/evidence 및 workflow/ralph 상태 부재와 보고서 형식 미충족으로 통과하지 않았다.
Persona 설정과 계획은 작업 종료 시 보완했으며, 사전 승인이나 공식 하네스 완료 증거로 주장하지 않는다.
별도 사용자 작업 중인 주 checkout의 Persona 이력은 수정하지 않았다.
위 로컬 테스트와 실제 호스트 배포·로그 수신 증거는 이 형식 판정과 구분한다.

## #989 저장소 반영 시 추가 확인

- 이슈 계약은 자료 충분으로 인터뷰 생략, Grill Pass, 게시 pass로 검증했고 ADR은 Proposed다.
- develop은 독립 Dev hook, main은 공통 hook과 Prod wrapper여서 기본 브랜치를 각각 유지한
  PR 두 개로 분리한다. main 공통 hook의 Dev 모드에도 동일한 순차 교체를 이식해 향후 릴리즈의
  Dev 경로가 승인 모드를 무시하고 동시 실행하지 않게 한다. Prod는 rolling만 허용한다.
- main 공통 hook의 Dev 순차 경로와 후보 종료 실패 보호는 이번 PR의 로컬 회귀 근거다.
  앞선 Prod 서버 관측은 rolling 경로이며 이 추가 코드로 서버를 다시 배포하지 않았다.
- 이슈 #989 하나에 두 PR을 연결하고 자동 종료 키워드는 사용하지 않는다.
- 재발 대응 시 [검토 승인 운영 절차](bluegreen-migration-review-runbook.md)를 따른다.

- PR 준비 중 PostgreSQL fixture 재실행에서 로컬 임시 초기화 서버를 준비 완료로 판단해
  서버 재기동 순간 연결이 끊겼다. 최종 서버만 여는 TCP `127.0.0.1` readiness로 변경한 뒤
  V61→V66/V69 두 경로가 다시 통과했다. 실제 운영 DB 실패나 마이그레이션 실패는 아니다.
- #989 로컬 최종 검증: CLI 16개씩, Dev 배포 29개, main 공통 배포 43개, Governance 10개씩
  통과. bash 문법·diff 공백·새 Python 파일 규칙 검사를 통과했다. Java 코드는 바꾸지 않아
  로컬 Gradle 전체 테스트는 반복하지 않으며 GitHub PR CI 결과를 별도로 확인한다.
