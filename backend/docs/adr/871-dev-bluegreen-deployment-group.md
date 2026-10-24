# Dev 단일 EC2 Blue-Green 전환은 기존 CodeDeploy 그룹을 재사용하고 첫 이행 중 legacy 중단을 허용한다.

## 상태

Proposed

## 관련 Issue

- #871 [BE] Dev 단일 EC2 Blue-Green 배포 자동화

## 한 줄 요약

Dev CodePipeline의 기존 `knot-backend-dev-deployment-group`을 유지한다. 첫 Blue-Green 이행에서 직전 revision의 `ApplicationStop`이 legacy Blue를 잠시 중지할 수 있음을 허용하고, `BeforeInstall`에서 JAR를 보존해 새 hook이 Blue를 다시 준비하도록 한다.

## 왜 이 결정이 필요했나

현재 Dev 배포는 기존 CodeDeploy 그룹의 in-place 배포다. 이전 revision의 `ApplicationStop`이 새 revision의 hook보다 먼저 실행되어 `knot-backend.service`를 정지할 수 있다. 새 CodeDeploy 그룹을 만들면 이 경로를 분리할 수 있지만, 그룹과 파이프라인 대상을 별도로 관리해야 한다.

2026-10-20에 기존 그룹을 유지하는 방향을 선택했다. 첫 이행에서 legacy 서비스가 정지되는 짧은 구간을 허용하고, 이전 JAR와 이후 배포 슬롯을 보존해 수동 복구와 반복 전환을 가능하게 한다.

## 고려한 대안

- 기존 그룹을 유지하고 `BeforeInstall`에서 legacy JAR를 보존한 뒤 첫 Blue 슬롯을 bootstrap한다.
- 별도 `knot-backend-dev-bluegreen` 그룹을 만들고 기존 Dev 배포 경로를 격리한다.

## 무엇을 결정했나

기존 `knot-backend-dev-deployment-group`을 재사용한다. CodePipeline의 배포 대상 그룹은 바꾸지 않는다.

최초 이행은 다음 순서로 진행한다.

1. 기존 revision의 `ApplicationStop`이 legacy 서비스를 중지할 수 있다. 일시 중단을 허용한다.
2. 신규 revision의 `BeforeInstall`이 `/opt/knot-backend/app.jar`를 `/opt/knot-backend/slots/blue/app.jar`에 원자적으로 보존한다.
3. 신규 revision의 `ApplicationStart`가 8080을 가리키는 Nginx 설정과 서비스 상태를 확인한다. Blue 및 legacy 서비스가 모두 내려가 있으면 보존된 JAR와 Blue systemd unit으로 8080에서 Blue를 준비한다.
4. 기존 Blue가 정상 응답하는 것을 확인한 뒤 비활성 slot에 Green을 기동하고, readiness 확인 후 Nginx를 전환한다.
5. 이전 slot의 연결이 drain된 뒤 이전 서비스를 종료한다. 이후 배포는 활성 slot을 보존하고 반대 slot을 교체한다.

Blue bootstrap이 실패하면 우선 기존 JAR로 legacy 서비스를 복구한다. legacy 복구도 실패하면 보존된 Blue slot을 다시 시작한다. Green 준비·route·drain 단계가 실패하면 이전 route를 복원한다. route 복구를 확인할 수 없으면 두 프로세스를 유지하고 후속 배포를 차단하는 marker를 남긴다.

## 결과와 제한

- 기존 Dev CodePipeline과 CodeDeploy 그룹을 계속 사용한다. 별도 그룹은 만들지 않는다.
- 기존 그룹의 첫 배포에서 `ApplicationStop`에 따른 일시 중단이 발생할 수 있음을 받아들인다. 이 경로는 로컬 fixture와 별도로 Dev 실배포에서 관측해야 한다.
- AWS Dev CodeBuild는 현재 inline buildspec을 사용하므로 저장소 `buildspec.yml` 변경만으로 Blue-Green artifact가 만들어지지 않는다. AWS buildspec을 연결하고 `DEPLOYMENT_MODE=dev-bluegreen`으로 설정해야 한다.
- 2026-10-20 관측에서 실제 CodeDeploy 그룹의 automatic rollback은 비활성이었다. 배포 hook 내부 route 복구와 CodeDeploy의 이전 revision 재배포는 별개이며, 후자는 AWS 설정을 변경하고 검증해야 한다.
- GitHub Issue #871 본문에는 이전의 별도 그룹 결정이 남아 있어 이 ADR 및 현재 구현 지시와 불일치한다. Issue 본문 동기화가 끝날 때까지 이 파일은 Proposed 상태다.

## 다시 논의해야 할 조건

- 첫 이행 중 허용한 서비스 중단을 받아들일 수 없는 경우
- Dev가 여러 EC2 또는 Load Balancer 뒤의 다중 인스턴스로 바뀌는 경우
- Blue/Green slot, CodeDeploy lifecycle hook 또는 rollback 방식을 변경하는 경우

## 확인

- 결정 주체: 사용자 승인에 따른 구현 선택, 2026-10-20
- 최초 별도 그룹 제안은 사용자 결정으로 대체되었으며, 팀 승인 전까지 상태는 `Proposed`다.
