# Workspace 콘텐츠 소스 연결은 공급자 중립 코어로 관리하고 Notion은 첫 번째 어댑터로 둔다.

## 상태

Proposed

## 관련 Issue

- #254 [BE] 워크스페이스 Notion OAuth 연결 및 상태 조회 구현

## 한 줄 요약

Workspace가 콘텐츠 소스 연결을 소유하고, domain/application은 공급자 중립 계약을 사용하며, Notion Public OAuth는 infrastructure의 첫 번째 구현체로 둔다.

## 왜 이 결정이 필요했나

Knot은 여러 팀의 Notion 데이터를 가져오지만 사용자가 installation token을 직접 입력하게 하지 않는다.

현재 OAuth Connection이 없어 연결 화면이 OAuth를 시작하거나 상태를 판단할 수 없다.

결정 동인:

- token 직접 입력 제거
- workspace별 Notion 연결
- OWNER 중심 권한
- page picker 승인
- 단일 Connection 경계
- 외부 제품명을 도메인 경계로 사용하지 않는 패키지 구조

## 트레이드 오프

- Internal Connection과 수동 installation token
- Public OAuth와 page picker 승인
- Notion 전용 코어와 공급자 중립 코어
- 첫 공급자부터 범용 registry를 만드는 방식과 필요한 port만 두는 방식

## 무엇을 결정했나

Knot Workspace별로 Provider당 하나의 Content Source Connection을 두고 현재 OWNER가 연결을 생성·교체한다.

Public OAuth는 수동 secret 전달을 없애고 각 Knot Workspace가 자신의 Notion workspace를 연결하게 한다. OWNER 변경 비용은 REAUTH_REQUIRED로 관리한다.

`domain`과 `application`은 `ContentSourceAuthorization`, `ContentSourceConnection`, `ContentSourceProvider`와 공급자 중립 port만 사용한다. Notion HTTP 요청·응답, 설정, 암호화 구현과 owner 문자열 변환은 `workspace.infrastructure.notion`에 둔다. 공개 API는 제품 흐름을 드러내기 위해 기존 Notion 경로와 응답 이름을 유지한다.

MVP에는 Provider registry나 동적 plugin 구조를 추가하지 않는다. 현재 필요한 port를 Notion adapter가 구현하고, 두 번째 Provider가 생겨 라우팅 요구가 확인될 때 composition 구조를 다시 판단한다.

## 결과

- OAuth state는 256-bit 난수로 발급하고 10분 뒤 만료한다. 원문은 authorization URL과 callback에서만 이동하며, DB에는 HMAC만 저장한다.
- state는 한 번만 소비한다. 같은 Knot Workspace에서 새 연결을 시작하면 이전 미완료 state를 무효화하고, callback 완료 전 더 최신 흐름이 생기면 이전 결과로 Connection을 교체하지 않는다.
- access token과 refresh token은 key version을 포함한 AES-GCM envelope로 저장한다. 원문 token은 로그·API 응답·OpenAPI 예시에 노출하지 않는다.
- token URI는 client credential을 전송하므로 HTTPS만 허용한다. authorization URI와 callback URI는 HTTPS를 기본으로 하고 로컬 개발 주소에만 HTTP를 허용한다.
- token 응답의 Notion workspace, bot, owner type·user ID, template, request 식별정보는 infrastructure에서 공급자 중립 필드로 변환해 Connection에 보존한다. owner의 이름·이메일·프로필 이미지는 저장하지 않는다.
- DB는 `content_source_authorizations`, `content_source_connections`와 `provider` 컬럼을 사용한다. 미완료 인증과 현재 연결의 유일성은 `(workspace_id, provider)` 단위로 보장한다.
- domain/application에는 Notion HTTP DTO, property, client 구현이 들어가지 않는다. Notion이라는 값은 지원 Provider 식별자와 외부 API 경계에만 남는다.
- OAuth 취소, OWNER 변경, Notion 오류가 발생하면 기존 Connection과 Import 데이터를 변경하지 않고 state에서 복원한 Workspace의 실패 화면으로 보낸다. state를 복원할 수 없는 만료·재사용·위조 요청은 Workspace 목록 수준의 실패 화면으로 보낸다.
- OWNER 변경 시 새 승인이 필요하다.
- 승인된 페이지 범위만 접근한다.
- 후속 API가 단일 Connection과 tenant 격리를 따른다.
- Import 실행, token refresh·revoke, 연결 해제와 가져온 데이터의 soft delete는 후속 Issue에서 다룬다.

## 다시 논의해야 할 조건

- 단일 관리형 Notion workspace 전용이 될 때
- Notion OAuth 정책이 바뀔 때
- 두 번째 외부 콘텐츠 공급자를 연결할 때
- OWNER 재승인이 반복 장애가 될 때
- team-owned connection이 필요할 때

## 확인

- 예정 경로: `backend/docs/adr/254-notion-public-oauth-connection-policy.md`
- 결정 주체: 임현성
- AI 하네스가 Proposed ADR 파일을 생성했다.
- 팀이 PR에서 승인한 뒤 Accepted로 바꾼다.
