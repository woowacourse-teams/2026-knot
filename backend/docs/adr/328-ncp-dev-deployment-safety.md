# develop Backend CI 성공 후 NCP 개발 배포를 진행하고 후보 실패 시 직전 정상 버전으로 자동 복구한다.

## 상태

Proposed

## 관련 Issue

- #328 [BE] develop CI 게이트 및 NCP 개발 서버 자동 롤백

## 한 줄 요약

develop Backend CI 성공 후 NCP 개발 배포를 진행하고 후보 실패 시 직전 정상 버전으로 자동 복구한다.

## 왜 이 결정이 필요했나

AWS 사용량 제한과 develop merge 이후 실패한 후보가 개발 서버 통신 장애로 이어질 수 있는 상황에서 개발 배포의 실패 격리가 필요하다.

기존 workflow는 develop push 후 빌드와 JAR 교체·systemd 재시작을 수행했으며, CI 실패·후보 기동 실패·SSH 중단 시 정상 버전 유지가 보장되지 않았다.

결정 동인:

- 실패한 커밋의 서버 적용을 막는다.
- 단일 개발 서버의 장애 시간을 줄인다.
- 개발 서버 비용과 운영 복잡도를 제한한다.
- 실패 원인과 복구 결과를 Discord에서 확인할 수 있어야 한다.

## 트레이드 오프

- CI 결과와 관계없이 develop push마다 배포: 단순하지만 실패한 커밋도 서버에 적용될 수 있다.
- Backend CI 성공을 확인한 뒤 단일 NCP 서버에 배포하고 실패 시 백업을 복구: 현재 비용·구성 범위에 맞고 실패 격리가 가능하다.
- 여러 서버 또는 blue-green 방식으로 무중단 배포: 가용성이 높지만 현재 개발 서버 비용·구성 범위를 넘어선다.

## 무엇을 결정했나

develop Backend CI 성공 후 NCP 개발 배포를 진행하고 후보 실패 시 직전 정상 버전으로 자동 복구한다.

개발 환경은 단일 NCP 서버를 유지하되 동일 커밋의 Backend CI 성공을 배포 전제로 삼고, 배포 전 JAR·환경·systemd 설정을 백업한다. 후보 재시작·health check 또는 SSH 세션이 실패하면 서버 측 watchdog과 workflow가 직전 정상 버전 복구를 시도하도록 해 비용을 늘리지 않고 실패 버전의 장기 실행을 막는다.

health check를 통과한 후보는 배포별 root 소유 snapshot 안에 다시 보존하고, `/opt/knot-backend/.last-known-good/READY`에는 완성된 snapshot의 배포 ID만 기록한다. READY 포인터는 후보 snapshot과 내부 `READY` marker가 완성된 뒤 마지막에 갱신하며, rollback에는 이전 포인터를 함께 보존한다. 다음 배포 시작 시 현재 서비스가 이미 비정상이면 후보 파일을 덮어쓰지 않고 이 포인터가 가리키는 snapshot을 먼저 복구한 뒤 해당 배포를 보류한다.

## 결과

- develop Backend CI와 NCP CD가 같은 커밋을 기준으로 연결된다.
- 배포 실패 시 단일 서버 재시작 공백은 남지만 직전 정상 버전 복구를 시도한다.
- NCP 서버에 임시 백업과 rollback watchdog을 위한 권한·경로가 필요하다.
- 배포별 root 소유 snapshot이 복구 근거로 남으므로 디스크 사용량과 보존 주기를 별도로 관리해야 한다.
- AWS main 운영 무중단과 Flyway migration 호환성 검증은 별도 ADR·구현 범위로 남는다.

## 다시 논의해야 할 조건

- NCP 개발 서버가 여러 대가 되거나 개발 환경도 무중단이 필요해질 때
- 단일 서버 재시작 공백이 개발 검증에 문제가 될 때
- AWS main 운영 배포를 다중 인스턴스로 전환할 때
- Flyway migration 호환성 정책을 실제로 구현할 때
- root 소유 snapshot 보존량이 디스크 운영 기준을 초과할 때

## 확인

- 예정 경로: `backend/docs/adr/328-ncp-dev-deployment-safety.md`
- 결정 주체: Knot 백엔드 팀
- AI 하네스가 Proposed ADR 파일을 생성했다.
- 팀이 PR에서 승인한 뒤 Accepted로 바꾼다.
