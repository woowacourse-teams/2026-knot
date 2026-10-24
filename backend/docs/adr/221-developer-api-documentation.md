# Springdoc OpenAPI와 Swagger UI를 도입하고 개발 프로파일에서만 PUBLIC으로 제공한다. 문서 기능 기본값은 false로 두며 운영 서버의 접근 제어는 후속 Issue/ADR에서 결정한다.

## 상태

Proposed

## 관련 Issue

- #221 [BE] OpenAPI 문서 제공

## 한 줄 요약

Springdoc OpenAPI와 Swagger UI를 도입하고 개발 프로파일에서만 PUBLIC으로 제공한다. 문서 기능 기본값은 false로 두며 운영 서버의 접근 제어는 후속 Issue/ADR에서 결정한다.

## 왜 이 결정이 필요했나

Spring Boot 4.1.0 REST 백엔드에 runtime API 문서 UI가 없고, Issue #110의 계약을 개발자가 확인할 경로가 필요하다.

인증·멤버 기능은 도입됐지만 완전히 릴리즈되지 않아 개발 서버에서는 모든 개발자가 로그인 없이 문서를 봐야 하며, 운영 접근 정책은 운영 배포 시점에 재논의한다.

결정 동인:

- 개발 서버 즉시 접근성
- 운영 오배포 방지
- Boot 호환성
- FE-BE 계약 정확성
- 후속 운영 보안 결정과의 분리

## 트레이드 오프

- Springdoc OpenAPI + Swagger UI: runtime 계약으로 브라우저 UI와 JSON/YAML을 제공하지만 drift와 community dependency를 관리해야 한다.
- Spring REST Docs: 테스트 기반 문서 일치를 강제하지만 runtime Swagger UI를 제공하지 않는다.
- Springdoc OpenAPI + Spring REST Docs 병행: UI와 테스트 근거를 모두 얻지만 중복 유지보수와 초기 범위가 커진다.

## 무엇을 결정했나

Springdoc OpenAPI와 Swagger UI를 도입하고 개발 프로파일에서만 PUBLIC으로 제공한다. 문서 기능 기본값은 false로 두며 운영 서버의 접근 제어는 후속 Issue/ADR에서 결정한다.

개발 서버에서 즉시 사용할 브라우저 문서가 필요하므로 Springdoc Swagger UI를 선택한다. 기본 비활성·개발 프로파일 명시 활성화로 운영 오배포를 닫고, 운영 권한 정책은 별도 결정으로 분리한다.

## 결과

- runtime dependency와 문서 annotation/customizer 유지보수가 추가된다.
- 개발 CD가 개발 프로파일을 명시적으로 활성화해야 한다.
- 개발 서버의 전체 API 경로와 스키마가 공개된다.
- 운영 서버 접근 정책은 구현하지 않으며 운영 배포 전에 별도 재검토한다.
- DB migration 없이 기존 API 동작을 유지한다.

## 다시 논의해야 할 조건

- 운영 서버에 문서를 배포할 때
- 인증·멤버 기능이 완전히 릴리즈될 때
- Member ID Allowlist·SSO·영속 권한이 필요해질 때
- 문서 drift·유지보수 비용이 반복될 때
- Springdoc의 Boot 4.1.x 호환성이 깨질 때

## 확인

- 예정 경로: `backend/docs/adr/221-developer-api-documentation.md`
- 결정 주체: Knot 팀
- AI 하네스가 Proposed ADR 파일을 생성했다.
- 팀이 PR에서 승인한 뒤 Accepted로 바꾼다.
