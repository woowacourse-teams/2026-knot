# develop CI와 NCP 개발 서버 배포 readiness 판정 분리

## 상태

Proposed

## 관련 Issue

- #212 [BE] develop CI 및 NCP 개발 서버 자동 배포 안정화

## 한 줄 요약

운영 `main`의 AWS 배포와 개발 `develop`의 NCP 배포를 분리하고, NCP CD는
서비스 재시작 후 bounded readiness polling으로 배포 완료를 판정한다.

## 왜 이 결정이 필요했나

Knot은 운영 환경과 개발 환경의 배포 대상을 분리한다. 운영 `main`은 AWS에서
사용하고, 개발 `develop`은 AWS 사용량 제한을 고려해 NCP 개발 서버에 배포한다.

기존 NCP 배포는 JAR를 교체하고 systemd 서비스를 재시작한 직후 health check를
한 번만 실행했다. systemd 서비스가 `active`여도 Spring Boot가 포트를 열고
Actuator 요청을 처리하기 전일 수 있어, 실제로는 정상 기동 중인 배포가
`connection refused`로 실패할 수 있었다.

## 트레이드 오프

| 선택지 | 장점 | 단점 | 채택 여부 |
| --- | --- | --- | --- |
| 운영과 개발을 모두 AWS에 배포 | 배포 플랫폼과 운영 방식이 하나로 통일된다. | AWS 사용량 제한을 개발 배포에도 함께 사용한다. | 미채택 |
| 운영 `main`은 AWS, 개발 `develop`은 NCP로 분리 | 사용량을 분산하고 개발 배포를 운영 배포와 격리할 수 있다. | 두 클라우드의 배포 경로와 운영 점검 지점이 늘어난다. | 채택 |
| 서비스 재시작 직후 health check를 한 번만 실행 | 구현이 단순하고 배포 완료를 빠르게 판정한다. | JVM/Spring Boot 기동 중 일시적인 연결 거부를 배포 실패로 오인할 수 있다. | 미채택 |
| 서비스 재시작 후 bounded readiness polling 실행 | 일시적인 기동 지연을 허용하면서도 제한 시간 안에 정상 여부를 판정한다. | 정상 기동이 늦으면 최대 60초를 기다리고, 제한 시간 초과 시 실패한다. | 채택 |

## 무엇을 결정했나

- `backend-ci.yml`은 `main`과 `develop` push에서 백엔드 검증을 실행한다.
- `deploy-backend-dev.yml`은 `develop`의 백엔드 변경을 NCP 개발 서버에 배포한다.
- NCP 배포는 JAR 교체와 systemd 재시작 후 `actuator/health`를 2초 간격으로
  최대 30회 확인한다.
- health check가 성공하면 배포를 성공 처리한다.
- polling 중 systemd 서비스가 비활성화되면 즉시 실패 처리한다.
- 60초 안에 health check가 성공하지 않으면 실패 처리하고 임시 JAR를 정리한다.
- 기존 SSH private key와 `known_hosts`를 이용한 엄격한 호스트 키 검증은 유지한다.

## 결과

- 개발자가 `develop`에 백엔드 변경을 반영하면 CI 검증 후 NCP 개발 서버 배포를
  자동 실행할 수 있다.
- Spring Boot의 정상적인 초기 기동 지연 때문에 배포가 실패하는 오탐을 줄인다.
- 서비스가 실제로 종료됐거나 60초 안에 준비되지 않은 경우에는 성공으로
  간주하지 않는다.
- 운영 AWS 배포와 개발 NCP 배포가 분리되므로 각 workflow와 서버의 상태를
  별도로 확인해야 한다.
- 현재 구성은 단일 개발 서버의 현재 위치 배포를 전제로 하며, 다중 인스턴스와
  무중단 배포가 필요해지면 별도 배포 전략을 검토한다.

## 다시 논의해야 할 조건

- AWS 사용량 제한 또는 개발 서버 운영 정책이 변경되는 경우
- NCP 개발 서버가 추가되어 단일 서버 배포로 운영하기 어려워지는 경우
- Spring Boot 기동 시간이 60초를 반복적으로 초과하는 경우
- health endpoint 경로 또는 인증 정책이 변경되는 경우
- 개발 환경에도 무중단 배포나 롤백이 필요한 경우

## 확인

이 ADR은 Issue #212의 구현과 함께 검토하기 위해 `Proposed`로 작성한다.
팀 리뷰에서 결정을 승인한 뒤에만 `Accepted`로 변경한다.
