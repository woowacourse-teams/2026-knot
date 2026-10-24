# Dev 단일 EC2 Blue-Green 자동 배포 계획

상태: PR #872 병합 및 Dev in-place 배포 성공, 앱 수준 Blue-Green 전환은 미검증
확인일: 2026-10-20 21:37 KST
대상: `knot-backend-dev` 단일 EC2, CodeDeploy EC2/On-Premises 배포
결정: 기존 `knot-backend-dev-deployment-group` 재사용. CodeDeploy는 in-place로 유지하고 Nginx upstream으로 앱 슬롯을 전환한다.

## 1. 범위와 근거

현재 Dev에서 수동으로 확인한 Green 기동, Health Check, Nginx 전환, 기존 요청 drain을 CodeDeploy lifecycle hook으로 반복 가능하게 만든다. 기존 `knot-backend-dev-deployment-group`을 유지한다. 최초 전환에서는 이전 revision의 `ApplicationStop`이 `knot-backend.service`를 중지해 잠시 서비스가 끊길 수 있으며, 이 중단은 허용한다. 단, 새 revision의 `BeforeInstall`은 CodeDeploy가 기존 JAR를 덮어쓰기 전에 `/opt/knot-backend/app.jar`를 Blue 슬롯에 보존해야 한다. 이후 배포에서는 활성 슬롯을 유지한 채 비활성 슬롯에서 새 버전을 준비한다.

| 근거 | 확인한 사실 | 분류 |
| --- | --- | --- |
| `backend/appspec.yml`, `backend/scripts/*.sh` | 기존 artifact는 JAR를 `/opt/knot-backend/app.jar`에 복사하고 `ApplicationStop`에서 기존 서비스를 중지한다. | 저장소 구현 관측 |
| `buildspec.yml`, `backend/ops/codebuild/README.md` | CodeBuild artifact에는 루트 `app.jar`, `appspec.yml`, `scripts/`가 들어간다. Dev 프로젝트는 inline buildspec이므로 저장소 buildspec만 수정해도 AWS 설정은 바뀌지 않는다. | 저장소 구현 관측 |
| `backend/docs/operations/aws-dev-and-prod-environments.md` | Dev는 `develop` 파이프라인, CodeDeploy `knot-backend-dev`와 `knot-backend-dev-deployment-group`을 사용한다. | 운영 기록 |
| Dev CodeDeploy 콘솔 (2026-10-20) | 기존 그룹은 EC2/온프레미스, 현재 위치 배포, `AllAtOnce`, 자동 롤백 비활성, 태그 `Name=knot-backend-dev`다. | AWS 콘솔 관측 |
| Dev EC2 SSH 읽기 (2026-10-20) | `knot-backend.service`가 `8080`에서 active, Nginx upstream은 `/etc/nginx/conf.d/knot-backend-dev.conf`의 `127.0.0.1:8080`이다. 서비스 종료 제한은 90초다. | 실제 호스트 관측 |
| 사용자 결정 (2026-10-20) | 기존 `knot-backend-dev-deployment-group`을 재사용하고, 최초 전환의 기존 `ApplicationStop` 중단을 허용한다. `BeforeInstall`에서 기존 JAR를 Blue 슬롯에 보존한다. | 사용자 승인 결정 |
| CodeDeploy 배포 `d-BRQ7SCPGL` (2026-10-20 21:32–21:33 KST) | 기존 그룹으로 1/1 인스턴스 성공. 배포 유형은 `현재 위치(in-place)`, 자동 롤백은 비활성이다. | AWS 콘솔 관측 |
| 배포 후 확인 (2026-10-20 21:36 KST) | 공개 Health `HTTP 200` (0.60초), `knot-backend.service` active, 포트 `8080`만 listen, 서비스 cgroup 537 MiB, host available 668 MiB, Swap 370 MiB다. | 실제 호스트·요청 관측 |
| CodeDeploy 공식 문서 | `ApplicationStop`은 직전 성공 revision의 스크립트를 실행한다. 실패 시 자동 롤백은 직전 정상 revision을 새 배포로 다시 설치한다. | 외부 기술 근거 |
| `backend/docs/product/current-v2-mvp.md`, `backend/docs/notion-context.md` | 배포 자동화에 적용할 별도 제품 동작 계약은 없다. 이 변경은 운영·배포 구조 변경이다. | 적용 범위 확인 |

배포 `d-BRQ7SCPGL` 성공은 서비스 복구와 기존 그룹 배포만 입증한다. 해당 실행은 CodeDeploy in-place였고 배포 후 `8081` listener나 Green 슬롯은 확인되지 않았으므로, 앱 수준 Blue-Green 전환·요청 drain·장애 롤백 검증으로 간주하지 않는다. CodeBuild 프로젝트의 inline buildspec은 기존 `app.jar`, `backend/appspec.yml`, `scripts/`를 직접 패키징하며 `dev-bluegreen` artifact 분기를 호출하지 않는다. 이 설정과 lifecycle hook을 새 결정에 맞춰 후속 수정해야 한다.

## 2. 구현할 흐름

`develop` → Dev CodeBuild inline buildspec이 `DEPLOYMENT_MODE=dev-bluegreen` 전용 artifact 생성 → 기존 `knot-backend-dev-deployment-group`으로 in-place 배포 → (최초 전환에서만) 이전 `ApplicationStop`이 legacy Blue를 중지할 수 있음 → `BeforeInstall`이 기존 JAR를 Blue 슬롯에 보존 → 새 JAR를 Green 슬롯에 설치 → Blue와 Green 별도 systemd 서비스 기동 → readiness 확인 → Nginx upstream 원자 전환 → Blue 요청 drain → 이전 Blue 종료. CodeDeploy 그룹은 계속 단일 EC2 in-place 배포 그룹으로 유지하고, 앱 수준 Blue-Green은 JAR 슬롯과 Nginx 전환으로 구현한다. 기존 `appspec.yml`과 Prod 배포 동작은 유지한다.

Dev Blue-Green AppSpec은 기존 배포 그룹을 대상으로 사용하며 활성 JAR를 덮어쓰지 않는다. 최초 전환의 `BeforeInstall`은 `/opt/knot-backend/app.jar`를 `/opt/knot-backend/slots/blue/`에 복사한다. 새 JAR는 `/opt/knot-backend/slots/green/`에 보관하고, 두 systemd unit은 고정된 슬롯 JAR와 각 포트로 실행한다. 이후 `ApplicationStop`은 활성 슬롯을 중지하지 않으며, 활성 슬롯은 Green readiness와 Nginx 검증이 끝날 때까지 요청을 처리한다. Prod 그룹은 기존 in-place 동작을 유지한다.

실패 분기는 다음과 같다.

- 후보 기동 또는 readiness 실패: Nginx를 바꾸지 않고 기존 Blue를 유지한다.
- Nginx 문법 검사·reload·전환 후 Health Check 실패: 이전 Nginx 설정을 복구하고 기존 Blue를 유지 또는 재기동한다. route 복구를 확인할 수 없으면 두 프로세스를 모두 유지한다.
- 이전 Blue drain: 기존 포트의 ESTABLISHED upstream 연결이 0이 될 때까지 최대 900초 기다린다. 제한 시간 내 drain되지 않으면 이전 route를 복원하고 Green에 남은 연결이 있으면 Green을 종료하지 않는다.
- 전환 검증 성공 뒤 이전 Blue 종료: Spring graceful shutdown에 90초까지 기다린다. 종료 전 route Health와 이전 연결 0을 확인한다.
- CodeDeploy 실패: 현재 기존 그룹의 자동 롤백은 비활성이다. 실패 시나리오 검증 전에 이를 활성화하고 직전 정상 revision 재배포 여부를 확인한다. 최초 전환 중 `ApplicationStop`이 Blue를 중지한 경우에도 보존한 Blue JAR로 서비스를 복구할 수 있어야 한다.

DB migration은 이 자동화에서 처리하지 않는다. 두 버전이 함께 실행되는 동안 양쪽 버전과 호환되는 expand 단계만 허용하고, 파괴적 contract migration은 별도 배포로 분리한다.

## 3. 코드 위치와 책임

| 위치 | 변경 책임 |
| --- | --- |
| `backend/appspec.yml` | 기존 in-place/Prod artifact 동작을 유지한다. |
| `backend/appspec-dev-bluegreen.yml` | 기존 그룹을 유지하며 최초 `BeforeInstall`에서 legacy JAR를 Blue 슬롯에 보존하고, 이후 배포에서는 활성 슬롯을 유지하는 hook을 선언한다. |
| `buildspec.yml`, `backend/ops/codebuild/package-deployment-artifact.sh` | `DEPLOYMENT_MODE=dev-bluegreen`일 때 Dev 전용 AppSpec과 unit 파일을 artifact에 포함한다. 현재 AWS Dev 프로젝트의 inline buildspec도 이 패키징 경로를 실제 호출하도록 갱신한다. |
| `backend/scripts/dev_bluegreen_deploy.sh` | 배포 그룹·현재 Nginx upstream·활성 service·migration 파일 집합 확인, 비활성 slot 설치·기동·readiness, Nginx 원자적 전환, 이전 연결 drain, 서비스 enable/disable·종료, 모든 중간 실패의 라우트 복구를 한 lifecycle hook에서 수행한다. |
| `backend/ops/systemd/knot-backend-{blue,green}.service` | Dev 두 slot을 별도 JAR 경로·포트로 실행한다. 실제 Dev unit의 환경 파일·프로파일, `-Xmx256m`, 90초 종료 제한을 반영한다. |
| `backend/scripts/tests/test_dev_bluegreen_deploy.py` | 임시 fixture와 가짜 systemd/Nginx/curl/ss/jar 명령으로 package 선택, 성공·실패·복구·slot 교대 흐름을 검증한다. 실제 서비스는 건드리지 않는다. |

DB schema와 Java API는 변경하지 않는다.

## 4. TDD와 검증 순서

| 순서 | 실패 테스트 → 최소 구현 | 기대 결과 |
| --- | --- | --- |
| 1 | 배포 artifact package mode를 분리하고 fixture로 확인한다. | 기존 모드는 기존 AppSpec을 유지하고 Dev 모드만 Green AppSpec과 unit 파일을 포함한다. |
| 2 | migration 집합 변경·비활성 slot 선택·후보 Health 실패 fixture로 hook 경계를 확인한다. | migration 집합이 다르거나 후보가 준비되지 않으면 기존 Blue와 Nginx를 건드리지 않는다. |
| 3 | Nginx 검증/reload 및 proxy Health 실패 fixture로 route 복구를 확인한다. | route 복구가 확인되지 않으면 두 프로세스를 보존한다. |
| 4 | 이전 연결이 남은 상태에서 drain timeout fixture를 실행한다. | 제한 시간 안에 drain되지 않으면 Blue route로 복귀하고 Green 연결이 없을 때만 Green을 종료한다. |
| 5 | 성공과 slot 교대 fixture를 실행한다. | Green `200 UP` 확인 후에만 route를 바꾸고, 이전 포트 연결이 0이 된 후 이전 서비스를 종료한다. |
| 6 | shell 구문·YAML·diff 검사를 수행하고 기존 Dev CodeBuild inline buildspec이 전용 패키징 경로를 호출하도록 연결한다. | artifact에 Dev AppSpec·hook·unit이 포함되고 기존 배포 그룹은 재사용된다. |

로컬 검증: fixture 14개, `bash -n`, 기존/신규 AppSpec 및 buildspec YAML parsing, `git diff --check`가 통과했다. fixture는 fake systemd/Nginx/curl/ss/jar를 사용하며 실제 EC2 동작을 입증하지 않는다.

## 5. 완료 기준과 다음 작업

- 기존 배포 그룹을 재사용하는 첫 전환에서 기존 `ApplicationStop`으로 짧은 중단이 발생할 수 있음을 기록하고, `BeforeInstall`이 이전 JAR를 보존해 Blue 복구가 가능한지 확인한다.
- 이후 배포에서 활성 Blue는 Green readiness 전까지 요청을 처리하고, Green readiness 이후 Nginx 전환과 Blue drain이 수행된다.
- Health 실패·Nginx 실패 시 이전 upstream이 유지되거나 복원되고 Blue가 active인 상태가 확인된다.
- 성공 시 진행 중 요청 drain 후 이전 프로세스가 종료되고 신규 Green만 Nginx 요청을 받는다.
- Prod의 기존 배포 흐름을 변경하지 않고 유지한다.
- 기존 Dev CodeDeploy 배포 그룹과 CodePipeline Deploy action을 재사용한다. 기존 그룹에 `Name=knot-backend-dev` 인스턴스가 선택되고 `codedeploy-project`, `AllAtOnce`, 필수 리소스 태그가 유지되는지 확인한다. 실패 자동 롤백은 실제 장애 검증 전에 활성화한다.
- 실제 Dev 배포에는 승인된 revision을 `develop` pipeline으로 보내는 원격 쓰기 단계가 필요하다. 현재 워킹 트리에 기존 사용자 변경이 다수 있고, 이 계획만으로 commit/push를 수행하지 않는다.

별도 미검증: 운영 부하에서 자원 headroom, 자동 배포 3회 반복 요청 실패율, 실제 Green 장애 뒤 AWS automatic rollback. 동일 EC2 동시 실행에서 available memory가 234~265 MiB까지 감소한 이전 측정이 있으므로 첫 자동 배포는 요청·녹음 관측을 함께 수행한다.

현재 CodeDeploy 배포 그룹은 `in-place`, 자동 롤백 비활성이다. 최근 배포는 성공했으나 포트 `8081`과 Green 슬롯은 확인되지 않았다. 그러므로 다음 단계는 저장소 Issue/ADR/PR의 새 결정 정합성, 기존 JAR 보존을 포함한 lifecycle hook 및 CodeBuild inline buildspec 연결이다. 이 구현과 검토가 끝나기 전에는 Green 장애 주입, 자동 롤백, 반복 Blue-Green 배포를 수행하지 않는다. Nginx route 복구를 확인할 수 없는 실패에서는 `/var/lib/knot-backend-deploy/blue-green/route-uncertain` marker가 남고 후속 배포를 거부한다.

공식 근거: [CodeDeploy lifecycle hook 동작](https://docs.aws.amazon.com/codedeploy/latest/userguide/reference-appspec-file-structure-hooks.html), [CodeDeploy 롤백 동작](https://docs.aws.amazon.com/codedeploy/latest/userguide/deployment-steps-server.html).
