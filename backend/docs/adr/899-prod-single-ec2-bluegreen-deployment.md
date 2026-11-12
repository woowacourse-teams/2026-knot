# Prod Blue-Green은 기존 CodeDeploy group과 현재 EC2를 재사용하고, 최초 legacy 이행 및 실제 운영 전환은 무녹음 배포 단계로 분리한다.

## 상태

Proposed

## 관련 Issue

- #899 [BE] Prod 단일 EC2 Blue-Green 배포 자동화 준비

## 한 줄 요약

Prod Blue-Green은 기존 CodeDeploy group과 현재 EC2를 재사용하고, 최초 legacy 이행 및 실제 운영 전환은 무녹음 배포 단계로 분리한다.

## 왜 이 결정이 필요했나

Prod pipeline은 main·CodeBuild·기존 in-place CodeDeploy group으로 운영되고 Dev에서 검증한 단일 EC2 Blue-Green 전환을 재사용하려 한다.

이전 revision의 ApplicationStop은 최초 Blue-Green revision이 시작되기 전에 legacy 서비스를 잠시 정지할 수 있고, 진행 중인 녹음은 이 연결 중단을 복구하지 못할 수 있다.

결정 동인:

- 기존 Prod pipeline·deployment group 유지
- 진행 중인 녹음 연속성
- t4g.small 자원 한도
- 기존 Nginx·Alloy 관측 경로와 복구 가능성

## 트레이드 오프

- 기존 EC2와 Prod CodeDeploy group을 유지해 비활성 slot을 필요할 때만 시작하고 hook 내부에서 route 전환·복구한다.
- 별도 CodeDeploy group 또는 별도 EC2를 사용해 기존 revision과 첫 이행을 격리한다.
- 최초 배포 전에 Blue-Green 서비스를 상시 함께 기동하고 proxy를 미리 전환해 legacy ApplicationStop 영향 구간을 줄인다.

## 무엇을 결정했나

Prod Blue-Green은 기존 CodeDeploy group과 현재 EC2를 재사용하고, 최초 legacy 이행 및 실제 운영 전환은 무녹음 배포 단계로 분리한다.

기존 pipeline·EC2 구성을 유지하면서 Dev에서 확인한 hook 기반 전환을 사용하고, 첫 이행은 운영 녹음이 없는 별도 배포 단계에 둔다. 새 인스턴스 또는 group을 추가하는 비용과 상시 두 JVM의 memory pressure를 피한다.

## 결과

- CodeBuild artifact는 prod-bluegreen mode로 구성하고 기존 Prod group에서 배포한다.
- Prod Blue·Green의 heap limit은 -Xmx256m이며 총 RSS 안정성은 실제 배포 뒤 별도로 측정한다.
- 첫 legacy 이행에는 짧은 연결·요청 중단이 있을 수 있어 무녹음 배포 창이 필요하다.
- AWS Automatic Rollback은 별도 설정·검증이며 이 결정만으로 활성화되지 않는다.

## 다시 논의해야 할 조건

- t4g.small 자원 안정성 기준을 충족하지 못함
- 기존 group의 lifecycle 순서가 실제 예상과 다름
- 요청 중단을 허용할 수 없는 첫 이행 요구가 생김
- Prod가 다중 EC2 또는 Load Balancer 구성으로 변경됨

## 확인

- 예정 경로: `backend/docs/adr/899-prod-single-ec2-bluegreen-deployment.md`
- 결정 주체: Knot team
- AI 하네스가 Proposed ADR 파일을 생성했다.
- 팀이 PR에서 승인한 뒤 Accepted로 바꾼다.
