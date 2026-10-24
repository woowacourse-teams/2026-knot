# #619 공통 예외 처리의 HTTP 오류 분류 보완 구현 계획

## 1. 범위와 브랜치

상태: #619 구현 및 로컬 전체 검증 완료. Persona 전역 종료 인증은 미통과.
확인 날짜: 2026-10-12.
대상: [Issue #619](https://github.com/woowacourse-teams/2026-Knot/issues/619).
담당자: jyt6640. 라벨: 흑곰, BE, BugFix.
계획 당시 브랜치는 `develop`, 조사 기준 SHA는 아래 근거 표에 기록했다.
사용자의 커밋·push·PR 승인 후 저장소 브랜치 규칙에 맞는 `be/bugfix/#619`를 생성했다.
게시 준비 기준은 `origin/develop`의 `9fc1219b1914d72b452cb941fc6eabbc17efaec4`다.
계획 이후 추가된 develop 변경은 frontend 파일에 한정돼 backend 구현·검증 소스에는 차이가 없다.

MVC에 도달한 경로 없음은 404, 지원하지 않는 메서드는 405, 지원하지 않는 요청 Content-Type은 415로 변환한다.
기존 공통 JSON envelope와 405 Allow·415 지원 형식 헤더를 보존한다.
서버 원인 로그는 [#620](https://github.com/woowacourse-teams/2026-Knot/issues/620)의 범위다.
두 Issue는 GlobalExceptionHandler를 공유하므로 #619의 분류 처리를 먼저 적용하는 순서를 권장한다.

| 근거 | 확인 시점·revision | 적용 사실 | 판정 |
| --- | --- | --- | --- |
| 사용자 확정 범위 | 2026-10-12 | 404·405·415 분류, 예외 처리와 로그를 두 Issue로 분리 | 확정 |
| [공통 예외 처리 기반 #155](https://github.com/woowacourse-teams/2026-Knot/issues/155) | 2026-10-12 조회 | ErrorResponse envelope, 내부 정보 비노출 | 기존 계약 |
| [GlobalExceptionHandler.java](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/global/exception/GlobalExceptionHandler.java:103) | 현재 checkout | catch-all이 MVC HTTP 예외도 500으로 변환 | 구현 관측 |
| 로컬 독립 MockMvc 재현 | Spring 7.0.8, 2026-10-12 | 기본 404·405·415가 현재 advice 적용 후 모두 500 | 로컬 재현, 운영 미검증 |
| [현재 V2 기준](../product/current-v2-mvp.md), [Notion 근거](../notion-context.md) | 2026-10-12 읽음 | 해당 작업은 기존 HTTP 계약 결함 보완 | 적용 범위 확인 |
| [신 API 명세](https://app.notion.com/p/API-3deb4351752280e2ad98f2b5c5670fb2) | revision 2026-09-30T06:23:00Z, 캐시 캡처 2026-10-07T13:07:32.841Z | code·message·fieldErrors, Cookie 인증·CSRF | 관련 캐시 확인, unsupported 블록 존재·원문 최신성 미검증 |
| origin/develop | SHA 7da73e04f8e891fb9333d5cb05d6c56951da01fc | 관련 공통 파일은 현재 브랜치와 일치, 조사 시 관련 BE 열린 PR 없음 | 저장소 관측 |
| [#619](https://github.com/woowacourse-teams/2026-Knot/issues/619) 본문 | 2026-10-12 재조회, OPEN·jyt6640 | 두 경로 없음 예외·405/415 헤더·SSE Accept·Security 회귀 범위 | 이번 사용자 확정 작업, 팀 승인 여부는 별도 |
| [SecurityConfig](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/global/config/SecurityConfig.java), [JwtAuthenticationFilter](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/auth/infrastructure/jwt/JwtAuthenticationFilter.java) | 2026-10-12 재확인 | 일반 경로는 authenticated, JWT 필터가 context를 초기화하고 Cookie로 인증 | 현재 구현 관측 |
| [ErrorResponse](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/global/response/ErrorResponse.java) | 2026-10-12 재확인 | 빈 fieldErrors는 NON_EMPTY 직렬화로 생략 | 현재 응답 계약 |
| 로컬 Spring 7.0.8 sources.jar | 2026-10-12 읽음 | 405는 Allow, 415는 Accept와 PATCH의 Accept-Patch, 빈 지원 목록은 빈 헤더 | 설치 패키지 근거, 운영 관측 아님 |

2026-10-12 재조회한 열린 PR 네 건은 FE 작업이다. 관련 BE 변경 충돌은 확인되지 않았다.
Notion manifest에서 위 API의 body_revision과 properties_revision이 모두 2026-09-30T06:23:00Z임을 확인했다.
unsupported 블록 밖의 공통 오류·Cookie·CSRF 본문은 읽을 수 있다. 추가 제품 정책을 추론하지 않는다.

Java 25·Spring Boot 4.1.0·Gradle을 project profile과 build.gradle에서 확인했다.
고위험 공통 계약 검증은 pass이며 자료 충분으로 인터뷰 생략이다.
새 아키텍처 대안을 선택하는 작업이 아니므로 ADR은 필요 없다.

## 2. 구현할 흐름

```text
요청 → 기존 Security 검사 → MVC 경로·메서드·Content-Type 검사
  정상 → 기존 Controller 응답
  경로 없음 → 404 REQUEST_PATH_NOT_FOUND + 공통 JSON
  메서드 불일치 → 405 METHOD_NOT_ALLOWED + Allow + 공통 JSON
  요청 미디어 불일치 → 415 UNSUPPORTED_MEDIA_TYPE + 지원 형식 헤더 + 공통 JSON
```

미인증과 CSRF·권한 실패가 MVC 전에 차단되면 기존 401·403을 유지한다.
SSE를 원하는 Accept: text/event-stream 요청이라도 이 HTTP 오류 응답은 application/json으로 명시한다.
DB 조회·저장이나 트랜잭션은 추가되지 않는다.
도메인 리소스가 없는 기존 404와 매핑 경로가 없는 404는 code로 구분한다.

테스트 전용 `POST /api/v1/test/http-errors/json`은 application/json을 받도록 구성한다.
같은 경로에 text/plain을 보내면 415이고, JSON 구문 자체가 깨졌다면 기존 400이다.
응답 예시는 다음과 같다. message 문구는 기존 한글·마침표 없음 규칙에 맞춘 구현 제안이다.

```json
{"code":"UNSUPPORTED_MEDIA_TYPE","message":"지원하지 않는 요청 본문 형식입니다"}
```

415 응답의 Accept 헤더는 서버가 받을 수 있는 요청 형식을 알려준다.
클라이언트가 응답 형식을 지정하는 요청 Accept와 구분한다. 빈 fieldErrors는 기존처럼 생략한다.

## 3. 나올 코드와 메서드 초안

현재 GlobalExceptionHandler는 ProjectException과 validation 오류를 별도로 처리하고 나머지를 500으로 변환한다.
CommonErrorCode와 ErrorCategory가 상태 분류를 관리하며, respond가 공통 응답을 조립한다.
다음 이름과 구조는 구현 제안이다.

| 기존 위치 | 제안 변경·메서드 | 입력·판단·결과 |
| --- | --- | --- |
| [GlobalExceptionHandler](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/global/exception/GlobalExceptionHandler.java) | handlePathNotFound(Exception ignored) | 두 예외를 명시한 @ExceptionHandler → 404·빈 헤더·공통 JSON. 예외 메시지는 body에 넣지 않음 |
| 같은 클래스 | handleMethodNotSupported(HttpRequestMethodNotSupportedException exception) | 405, exception.getHeaders() 보존 |
| 같은 클래스 | handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException exception) | 415, exception.getHeaders() 보존 |
| 같은 클래스 | respond(ErrorCode, HttpHeaders), toHttpStatus 보완 | 새 HTTP 분류 응답에만 JSON Content-Type·상태·제공 헤더·body 조립 |
| [CommonErrorCode](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/global/exception/CommonErrorCode.java) | REQUEST_PATH_NOT_FOUND·METHOD_NOT_ALLOWED·UNSUPPORTED_MEDIA_TYPE 추가 | 기존 오류 envelope 재사용 |
| [ErrorCategory](/Users/yongtae/Desktop/knot/backend/src/main/java/com/knot/backend/global/exception/ErrorCategory.java) | METHOD_NOT_ALLOWED·UNSUPPORTED_MEDIA_TYPE 추가 | 각각 405·415로 변환 |

기존 handleProjectException과 fieldErrors 응답의 code·message·Retry-After는 유지한다.
기존 respond(ErrorCode)와 respond(ErrorCode, List<FieldErrorResponse>)는 재사용한다.
새 헤더 overload는 404·405·415에 사용하고, 다른 오류의 Content-Type 정책까지 확대 변경하지 않는다.
Spring이 반환한 헤더를 직접 수정하지 않고 ResponseEntity builder에 복사한 뒤 JSON Content-Type을 지정한다.

| 제안 code | category | 상태 | 사용자 message 제안 |
| --- | --- | --- | --- |
| REQUEST_PATH_NOT_FOUND | 기존 NOT_FOUND | 404 | 요청 경로를 찾을 수 없습니다 |
| METHOD_NOT_ALLOWED | 새 METHOD_NOT_ALLOWED | 405 | 지원하지 않는 요청 메서드입니다 |
| UNSUPPORTED_MEDIA_TYPE | 새 UNSUPPORTED_MEDIA_TYPE | 415 | 지원하지 않는 요청 본문 형식입니다 |

ErrorCategory의 exhaustive switch는 현재 production 코드에서 GlobalExceptionHandler.toHttpStatus 한 곳이다.
새 category를 추가할 때 그 매핑을 함께 추가한다. 별도 응답 DTO·서비스·새 의존성은 필요 없다.
응답 조립 변경이 필요한 범위만 수정하고 Controller·Service·Repository 변경은 요구하지 않는다.
삼항 연산자·else·중첩 조건을 피하고 의미별 검증 메서드, 호출당 한 줄 규칙을 따른다.

탐색 전용 advice는 높은 우선순위의 catch-all을 갖는다. 로컬 Spring 매핑 소스상 method/consumes 불일치는
Controller HandlerMethod 선택 전에 발생한다. 따라서 이 세 HTTP 오류를 공통 처리기로 분류하는 것이 제안이다.
실제 탐색 Controller를 등록한 MVC 테스트로 이 선택을 확인한다.
SearchQuestionExceptionHandler 수정은 기본 계획에 포함하지 않고, 해당 테스트에서 우회가 재현될 때만 최소 위임을 검토한다.
이미 시작된 SSE의 응답 재작성과 원인 로깅은 #620 경계를 유지한다.

## 4. 저장 기반과 주의할 점

DB·Entity·Flyway migration·보존 정책 변경은 없다.
Spring 예외의 getHeaders()가 제공하는 Allow·Accept·Accept-Patch 등을 보존한다.
지원 목록이 빈 예외도 빈 헤더 그대로 안전하게 처리한다.
모든 요청을 무조건 404로 만드는 보안 설정 변경이나 406 등의 전체 HTTP 오류 재분류는 범위 밖이다.
운영 프록시·호스트의 실제 응답은 로컬 MVC 재현으로 검증되지 않았다.

정적 리소스 매핑이 있으면 NoResourceFoundException, 없으면 NoHandlerFoundException 경로를 각각 확인한다.
두 번째 예외를 재현하기 위해 production 설정을 바꾸지 않는다. 별도 테스트 context에서 매핑 구성을 분리한다.
HEAD·OPTIONS는 Spring이 자동 지원할 수 있어 405 검증에 무조건 사용하지 않는다.
없는 경로의 잘못된 메서드까지 모두 405로 기대하지 않고, 먼저 존재하는 테스트 경로의 메서드를 틀리게 호출한다.
415 테스트는 consumes를 명시해 미디어 불일치를 만들며 malformed JSON의 400과 구분한다.

## 5. TDD와 검증 순서

아래 표는 계획 당시의 검증 항목이다. 실제 구현·실행 결과는 마지막 절에 기록했다.

| 테스트 위치·방식 | 시나리오 | 기대 결과 |
| --- | --- | --- |
| 기존 GlobalExceptionHandlerTest, 단위 | 두 경로 없음 예외 | ResponseEntity의 404·공통 code·message·JSON 헤더 |
| 같은 단위 테스트 | 메서드·미디어 오류, 지원 목록 없음 | 405·415와 제공 헤더, 빈 목록도 처리 |
| 제안 GlobalExceptionHandlerMvcTest, MockMvc | 정상 GET·JSON POST와 없는 경로·잘못된 메서드·Content-Type | 정상 응답 유지, 404·405·415·헤더 |
| 같은 MVC 테스트 | Accept application/json·text/event-stream | 공통 JSON 오류 응답 |
| 제안 GlobalExceptionHandlerSecurityMvcTest, 실제 Security 체인 | 미인증·CSRF 실패·보안 통과 후 MVC 오류 | 선거절 401·403, 통과 후 404·405·415 |
| 기존 공통 예외 테스트 | validation·도메인 404·429 | code·fieldErrors·Retry-After 유지 |
| 같은 공통 테스트·MVC | 예상 밖 IllegalStateException | 기존 500·INTERNAL_SERVER_ERROR, 내부 메시지 비노출 |
| 탐색 Controller 등록 MVC | 실제 탐색 주소의 잘못된 method·Content-Type, Accept text/event-stream | global 405·415·JSON·헤더, application service 호출 없음 |
| 기존 SearchQuestionExceptionHandlerTest | 시작 후 예외·권한 회수 | 기존 200·빈 body·async 종료 유지 |

계획 당시 GlobalExceptionHandlerTest는 429·Retry-After 사례 한 건만 있었다.
400·도메인 404·500을 이미 충분히 검증한다고 가정하지 않고 필요한 회귀 사례를 추가한다.
새 fixture 후보는 global/exception 패키지의 HttpErrorProbeController와 테스트별 설정 클래스다.
시험 경로의 GET·JSON POST 성공을 먼저 검증해 fixture와 MVC 구성이 정상임을 확인한다.
PATCH의 Accept-Patch는 직접 예외 변환 단위 테스트로 확인한다. 이를 위해 CORS의 허용 메서드를 바꾸지 않는다.

1. 404 실패 테스트 → 두 경로 없음 예외 처리와 code 최소 구현 → 정상 경로 확인.
2. 405·Allow·빈 목록 실패 테스트 → category·code·status와 메서드 처리 최소 구현.
3. 415·지원 헤더 실패 테스트 → 미디어 처리 최소 구현 → 정상 JSON 요청과 SSE Accept 오류 확인.
4. 실제 Security 체인·탐색 Controller 등록·기존 예외 회귀 테스트 → 필요한 응답 조립 보완.
5. 집중 검증 후 공통 코드 변경에 필요한 기존 Backend CI gate 수행.

테스트 Controller·설정 fixture는 별도 파일에 둔다. JWT 필터가 SecurityContext를 초기화하므로
테스트 사용자 주입만으로 인증 통과를 가정하거나 필터를 꺼서 Security 검증을 대체하지 않는다.
Security MVC 테스트는 기존 SecurityConfig·JwtAuthenticationFilter·인증/접근 거절 handler를 사용한다.
AuthTokenProvider.authenticate를 Mockito로 대역 처리해 테스트 Cookie를 정상 AuthenticatedMember로 변환한다.
OAuth 관련 협력자와 ClientRegistrationRepository 등 context 구성에 필요한 bean은 테스트에서만 대역으로 제공한다.
GET 무인증은 401, POST 등 변경 요청의 CSRF 누락은 403을 확인한다.
404·405·415 검증 요청에는 Cookie 인증과 유효한 CSRF를 갖추어 필터 선거절과 MVC 오류를 구분한다.
CSRF 성공·실패는 실제 필터를 통해 확인하고 업무 서비스·DB·외부 공급자는 호출하지 않는다.
MockMvc는 MVC 응답을 검증하며 실제 네트워크·운영 호스트까지 증명하지 않는다.

구현에 사용한 검증 명령:

```sh
cd /Users/yongtae/Desktop/knot/backend
npx ph workflow implement
./gradlew test --tests 'com.knot.backend.global.exception.*'
./gradlew test spotlessCheck
./gradlew integrationTest acceptanceTest bootJar
npx ph workflow finish implement
```

build.gradle과 backend-ci.yml에서 명령·tag 분리를 확인했다.
integration/acceptance는 Docker·Testcontainers가 필요하다.
실제 구현 완료 시 Persona 구현·리뷰 보고서를 작성한다.
커밋은 요청받을 때 404·405·415·문서 단위로 나누고 test → feat 순서를 검토한다.

## 6. 완료 기준과 다음 작업

404·405·415 및 공통 JSON·필수 헤더가 검증되고 기존 인증·CSRF·도메인·validation·429 응답이 유지되면 구현 완료다.
앞선 작업에서 코드 조사·로컬 재현 근거 정리·계약 검증·Issue 게시를 완료했다.
계획 단계에서 #619 원격 본문과 develop 코드·기존 테스트·Security·설치된 Spring 소스를 재대조했다.

### 2026-10-12 구현 결과

- GlobalExceptionHandler에 두 경로 없음 예외의 404, 메서드 오류 405, 미디어 오류 415 전용 처리를 추가했다.
- CommonErrorCode 세 값과 ErrorCategory 두 값을 추가하고 status 매핑을 함께 보완했다.
- 새 HTTP 오류 응답에만 JSON을 명시하고 예외의 제공 헤더를 복사한다. 기존 오류 응답 조립은 유지했다.
- 실제 탐색 Controller 매핑 오류는 global에서 처리되어 SearchQuestionExceptionHandler 수정이 필요하지 않았다.
- MVC·Security 테스트와 fixture를 추가했다. fixture는 TestComponent·TestConfiguration으로 일반 애플리케이션 스캔에서 제외했다.
- 기존 429 시험 예외의 중첩 클래스를 별도 파일로 옮겨 프로젝트 규칙을 적용했다.
- TDD RED: 최초 standalone MockMvc 8건 중 SSE Accept의 404·405·415 세 건이 JSON Content-Type 부재로 실패했다.
  응답 직렬화가 실패한 뒤 기본 resolver가 HTTP 상태를 처리하는 경로이므로 이 실행을 모두 500 실패로 기록하지 않는다.
- GREEN: 공통 예외 집중 37건(단위 8·MVC 11·실제 Security MVC 15·기존 committed SSE 3)이 통과했다.
- 최종 Gradle 검증: spotlessCheck·test·integrationTest·acceptanceTest·bootJar 성공, 4분 48초.
  XML 기준 단위 1,259·통합 608·인수 537 = 총 2,404건, 실패·오류·skip 모두 0이다.
- 실제 SecurityConfig/JwtAuthenticationFilter/CsrfFilter를 사용했다. 토큰 provider는 Mockito 대역,
  CSRF 성공·실패 요청은 Security 테스트 postprocessor로 구성했다. 실제 운영 쿠키 교환·프록시 관측은 아니다.
- 초기 bearshell 기본 30초 제한은 환경 변수 PH_BEARSHELL_TIMEOUT_MS=1200000으로 조정해 전체 실행을 완료했다.
- 편집 시 LSP daemon timeout은 Gradle 컴파일·테스트로 보완했다. LSP 정상 진단으로 표시하지 않는다.
- DB·migration·production Controller·Security 설정·로깅 변경은 없다. 구현 검증 시점에는 커밋·push·PR·배포를 수행하지 않았다.
- 구현·리뷰 보고서 작성 및 두 report-filled 명령은 성공했다.
- `npx ph workflow finish implement`는 verification-failed 및 기존 coverage·toolchain·stale loop·pending 티켓으로 exit 1이었다.
  `--reverify`는 source-identity-git-unavailable·fresh-receipt-unavailable로 재인증할 수 없었다.
  read-only `workflow diagnose`는 foreign-stale-unsafe·diagnostic-only 및 기존 상태 보존을 안내했다.
  별도 실행한 실제 Gradle 2,404건 통과와 Persona 전역 인증을 구분한다. 기존 이력·pending을 초기화하지 않았다.

게시 검증: status=pass, action=publish_issue, requested_action=publish_issue,
remote_write_authorized=true, contract_id=c50b58772c0f1762.
원격 본문이 판정 결과와 일치하고 assignee jyt6640 및 흑곰·BE·BugFix 라벨을 조회했다.
다음 연결 작업은 [#620 로그 보완 계획](620-server-error-logging.md)이다.

### 2026-10-12 커밋·PR 준비 검증

- 사용자가 #619의 작업 단위 커밋·push·PR 게시를 승인했다. 구현 브랜치는 `be/bugfix/#619`, PR base는 `develop`이다.
- 404·405·415를 각각 MVC 실패 테스트 → 최소 수정과 직접 단위 테스트의 순서로 나눴다.
  실제 stage할 소스만 `git write-tree`·`git archive`로 임시 디렉터리에 추출해 실행했다.
- 각 테스트 단계의 MVC 결과는 7건 중 404 두 건 실패, 9건 중 405 두 건 실패, 11건 중 415 두 건 실패였다.
  각 수정 단계에서 `spotlessCheck`와 공통 예외 집중 테스트가 성공했다.
- Security 회귀 테스트까지 포함한 최종 커밋 소스는 집중 37건, 실패·오류·skip 0으로 통과했다.
  stage된 Java 10개가 앞서 전체 2,404건을 통과한 작업 트리 소스와 동일한지도 확인했다.
- Governance 검증기 단위 테스트 8건과 PR 초안의 제목·브랜치·섹션·Issue 연결 검증을 통과했다.
- 기존 사용자 문서, #620 계획, workspace QA 자료는 #619 커밋에서 제외했다.
  Persona 전역 종료 인증 문제를 참고 사항에 명시하고 Draft PR로 게시한다.
