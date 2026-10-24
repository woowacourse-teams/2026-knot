---
name: knot-api-spec
description: Create and update frontend-facing Markdown API specifications for the 2026-Knot backend from actual controllers, DTOs, security configuration, exception handling, cookies, and tests.
---

# Knot API Specification

제품 기획 근거는 `backend/docs/notion-context.md`에 따라 관련 API 원문과 필요한 DB 속성만 찾아 읽는다.
도메인·ERD·기획 원문 및 `backend/docs/product/current-v2-mvp.md`의 현재 결정과 대조한다.
이 사본은 요구사항 근거이며 아래 실제 Controller/DTO 기반 HTTP 계약 명세를 대신하지 않는다.
본문·속성 revision과 coverage를 확인하고, 누락·제안·미정은 확정 계약으로 승격하지 않는다.

Knot 백엔드의 실제 HTTP 계약을 프론트엔드가 바로 사용할 수 있는 Markdown API 명세로 정리한다. 모든 엔드포인트는 지정된 Header, Path Parameter, Query Parameter, Request Body, Response, Error Response 형식을 사용한다.

~~~~markdown
## Header

| Key | Type | Required | Example | Description |
|---|---|---|---|---|
| 없음 | - | - | - | 헤더 없음 |

## Path Parameter

| Key | Type | Required | Example | Description |
|---|---|---|---|---|
| 없음 | - | - | - | Path Parameter 없음 |

## Query Parameter

| Key | Type | Required | Example | Description |
|---|---|---|---|---|
| 없음 | - | - | - | Query Parameter 없음 |

## Request Body

| Field | Type | Required | Nullable | Description | Example |
|---|---|---|---|---|---|
| 없음 | - | - | - | Request Body 없음 | - |

### Request Example
```json
{
}
```

## Response

### Status Code

| Status | Description |
|---|---|
| `{status}` | `{description}` |

### Response Body

| Field | Type | Nullable | Description | Example |
|---|---|---|---|---|
| 없음 | - | - | Response Body 없음 | - |

### Response Example
```json
{
}
```

## Error Response

| Status | Error Code | Description |
|---|---|---|
| 없음 | 없음 | 오류 응답 없음 |
~~~~

## 적용 범위

- 사용자가 API 명세서, API 문서, 프론트엔드 전달용 API 계약, 엔드포인트 정리를 요청할 때 사용한다.
- 명세만 요청받은 경우에는 코드 구현, 데이터베이스 변경, branch, commit, push, PR을 수행하지 않는다.
- 사용자가 파일 작성을 명시하지 않으면 답변으로 Markdown 초안을 제공한다. 파일 작성을 명시한 경우에만 문서 파일을 만든다.
- 현재 코드와 사용자가 원하는 계약이 다르면 원하는 내용을 사실처럼 문서화하지 말고, 현재 동작과 불일치를 함께 보고한다.

## 제품 요구사항과 초안의 상태

- API가 제품 범위에 속하는지 판단할 때 저장소 루트의 `backend/docs/product/current-v2-mvp.md`와 해당 작업의 운영 Notion 원문을 먼저 확인한다. Notion 원문 분류와 충돌 처리에는 저장소 루트의 `docs/harness/notion-alignment.md`를 사용한다.
- 운영 Notion API 변경 후보와 알려진 제품·ERD 불일치는 [`backend/docs/notion-api-alignment.md`](../../../docs/notion-api-alignment.md)에서 확인한다. 이 문서는 검토 상태 메모이며 구현 계약을 대신하지 않는다.
- `현재 기준`, `이후 버전`, `배경 참고`, `지난 버전`과 `초안`·`논의 중` 상태를 구분한다. 제목·수정 시각·원문에 적힌 `확정` 표현만으로 승인을 추정하지 않는다.
- 원문 내용은 제품 요구사항의 근거이며 실제 서버 동작을 증명하지 않는다. 구현 계약은 명세 작성 전 조사에서 확인한 Controller, DTO, Security, 예외 처리, 설정과 테스트를 따른다.
- Notion의 구현 `YES`/`NO`는 Notion 표기다. 서버 구현 여부는 현재 코드 및 필요한 runtime/OpenAPI 관찰로 별도 기록한다.
- 제품 요구사항·API 후보·코드가 충돌하면 어느 한쪽을 자동으로 승자로 정하지 않는다. 불일치, 사용자 영향, 필요한 결정과 아직 확인되지 않은 항목을 문서에서 분리해 보고한다.

## 명세 작성 전 조사

백엔드 모듈 디렉터리에서 다음 근거를 확인한다.

1. `@RequestMapping`, `@GetMapping`, `@PostMapping`, `@PutMapping`, `@PatchMapping`, `@DeleteMapping`이 있는 Controller
2. Request/Response DTO와 record의 필드, 타입, nullable 여부
3. `@Valid`, `@NotBlank`, `@Size` 등 입력 제약
4. `SecurityFilterChain`, CORS, CSRF, 인증·인가 설정
5. `ResponseEntity`, `@ResponseStatus`, authentication entry point, logout handler, OAuth handler
6. `@RestControllerAdvice`와 ErrorCode의 HTTP 상태 매핑
7. 해당 동작을 검증하는 단위·통합·인수 테스트
8. `application.properties` 및 환경별 설정의 base URL, redirect URI, cookie 이름과 보안 속성

검색할 때 `node_modules`, `.opencode/node_modules`, `.persona/rules`, `.persona/evidence`는 implementation context로 읽지 않는다. 프로젝트의 `AGENTS.md`와 명세 대상 코드의 하위 지침은 먼저 확인한다.

## 근거 우선순위

명세의 값은 다음 우선순위로 결정한다.

1. 실행 경로를 직접 정의하는 Controller와 Security 설정
2. DTO와 validation annotation
3. 예외 처리기와 테스트가 확인하는 실제 상태 코드·응답
4. 환경 설정과 사용자가 명시한 운영 URL

코드에서 확인되지 않은 필드, 상태 코드, cookie 속성, redirect 주소는 추측하지 않는다. 확인할 수 없으면 `확인 필요`로 표시한다.

## 문서 구조

문서 처음에는 필요한 경우 다음 공통 정보를 적는다.

~~~~markdown
# [도메인] API

Base URL: `{{BASE_URL}}`

## 공통 사항

- 브라우저에서 쿠키 인증을 사용하는 요청은 `credentials: "include"`를 사용한다.
- 운영 Base URL이 코드 설정 또는 사용자 입력으로 확정된 경우에만 실제 URL을 적는다.
~~~~

그 다음 공개 엔드포인트마다 아래 템플릿을 빠짐없이 작성한다. 해당하지 않는 항목도 삭제하지 않고 `없음`으로 표시한다.

~~~~markdown
## [기능 이름]

`[HTTP METHOD] [경로]`

## Header

| Key | Type | Required | Example | Description |
|---|---|---|---|---|
| ... | ... | ... | ... | ... |

## Path Parameter

| Key | Type | Required | Example | Description |
|---|---|---|---|---|
| ... | ... | ... | ... | ... |

## Query Parameter

| Key | Type | Required | Example | Description |
|---|---|---|---|---|
| ... | ... | ... | ... | ... |

## Request Body

| Field | Type | Required | Nullable | Description | Example |
|---|---|---|---|---|---|
| ... | ... | ... | ... | ... | ... |
| 없음 | - | - | - | Request Body 없음 | - |

### Request Example
```json
{
  "field": "value"
}
```

## Response

### Status Code

| Status | Description |
|---|---|
| `{status}` | `{description}` |

### Response Body

| Field | Type | Nullable | Description | Example |
|---|---|---|---|---|
| `field` | `string` | No | ... | `value` |

### Response Example
```json
{
  "field": "value"
}
```

## Error Response

| Status | Error Code | Description |
|---|---|---|
| 400 | ... | ... |
~~~~

### Request 규칙

- 요청 본문이 없으면 `Request Body` 표에 `없음`이라고 적고, 요청 예시는 `없음` 또는 빈 JSON으로 표시한다.
- Header, Path Parameter, Query Parameter, Request Body를 각각 해당 섹션에 작성한다. 없는 섹션도 유지한다.
- Cookie는 Header 섹션에 `Cookie` 또는 `Set-Cookie`의 실제 방향을 구분해 작성한다.
- 필수 여부는 `required = false`, nullable 타입, validation annotation, 기본값을 함께 확인한다.
- JSON body가 있으면 실제 필드명과 예시를 적는다. DTO의 Java 필드명을 임의로 바꾸지 않는다.
- `@NotBlank`, `@Size` 등의 제약과 허용 범위를 명세에 포함한다.
- Cookie 인증은 cookie 값을 프론트가 직접 읽을 수 있는지(`HttpOnly`)와 브라우저가 자동 전송하는지 구분한다.
- CSRF를 사용하는 상태 변경 요청은 CSRF cookie와 요구되는 header 이름을 모두 적고, 토큰을 얻는 API가 있으면 호출 순서도 설명한다.

### Response 규칙

- `Status Code`에는 성공뿐 아니라 프론트가 처리해야 하는 대표적인 실패 상태도 포함한다.
- 반환 DTO의 실제 필드, JSON 이름, 타입, nullable 여부를 기준으로 작성한다.
- `ResponseEntity<Void>` 또는 body가 없는 redirect/204 응답은 `없음`이라고 적는다.
- `Set-Cookie`, `Location` 같은 응답 header는 Response Body가 아니라 별도로 설명한다.
- 오류 응답은 프로젝트의 `ErrorResponse` 형태를 따른다. 보통 `code`, `message`, 필요할 때 `fieldErrors`를 적는다.
- `@JsonInclude` 때문에 생략될 수 있는 필드는 항상 반환된다고 표현하지 않는다.
- 오류 상태와 error code는 `ErrorResponse`와 `ErrorCode`의 실제 매핑을 확인해 `Error Response` 표에 작성한다.

### Status Code 규칙

- 성공 상태 코드는 Controller의 반환 타입, `ResponseEntity`, `@ResponseStatus`, 테스트로 검증한다.
- 일반 예외 상태 코드는 `GlobalExceptionHandler`의 `ErrorCategory` 매핑을 확인한다.
- 인증·인가·CSRF 상태 코드는 `SecurityFilterChain`, entry point, access denied 처리와 테스트를 확인한다.
- 코드에 상태 코드 근거가 없으면 임의로 `200` 또는 `400`을 쓰지 말고 `확인 필요`라고 표시한다.

## 인증·OAuth API 작성 규칙

OAuth 로그인은 단순 JSON API와 다르므로 다음 흐름을 명세에 포함한다.

1. 프론트가 브라우저 이동으로 OAuth 시작 경로를 연다.
2. 제공자 callback 경로는 백엔드가 처리하며 프론트가 직접 호출하지 않는다.
3. 로그인 결과에 따라 access cookie 또는 임시 온보딩 cookie가 발급된다.
4. 백엔드가 설정된 redirect URI로 브라우저를 이동시킨다.

- `302 Found` 응답은 body보다 `Location`과 `Set-Cookie` 동작을 설명한다.
- callback 경로가 실제 Controller에 없더라도 Security OAuth 설정이 처리하는 공개 흐름이면 별도 참고 항목으로 설명한다.
- access token, onboarding token, CSRF token을 같은 종류의 토큰처럼 설명하지 않는다.
- 운영·로컬 cookie 이름과 `Secure`, `HttpOnly`, `SameSite`가 설정에 따라 달라지면 환경별로 나눈다.
- cookie의 실제 JWT 값이나 secret, client secret은 명세서에 작성하지 않는다.

## CORS·CSRF 작성 규칙

프론트엔드 연동에 영향을 주는 경우 공통 사항 또는 해당 API Request에 다음을 포함한다.

- 허용 Origin
- 허용 HTTP method와 요청 header
- `Access-Control-Allow-Credentials` 여부
- `credentials: "include"` 필요 여부
- CSRF token 조회 경로
- CSRF cookie 이름과 header 이름
- preflight(`OPTIONS`)가 별도 검증 대상이면 별도 엔드포인트로 문서화

## 전달 형식

- 프론트 전달용 결과는 내부 package 이름이나 서비스 호출 순서를 중심으로 쓰지 않고 공개 HTTP 계약 중심으로 작성한다.
- API별 제목 바로 아래에 HTTP method와 경로를 표시한다.
- 모든 API는 `Header`, `Path Parameter`, `Query Parameter`, `Request Body`, `Response`, `Error Response` 섹션을 유지한다.
- `Response` 안에는 `Status Code`, `Response Body`, `Response Example`을 유지한다.
- 사용자가 제공한 템플릿의 열 이름과 순서를 우선하며, 타입·필수·nullable·예시·설명을 생략하지 않는다.
- 예시 JSON은 실제 계약을 대표하는 최소 예시만 사용한다. 인증 토큰, 비밀번호, client secret은 실제 값을 넣지 않는다.
- 여러 API의 호출 순서가 중요하면 엔드포인트 설명 뒤에 짧은 순서도 또는 번호 목록을 추가한다.
- 현재 구현되지 않은 API를 구현된 것처럼 작성하지 않는다.

## 파일 작성과 검증

사용자가 문서 파일을 요청하면 기존 문서 위치와 명명 규칙을 먼저 확인한다. 별도 규칙이 없으면 도메인별 파일을 `backend/docs/api/<domain>.md`에 둔다.

작성 후 다음을 확인한다.

- 모든 공개 Controller endpoint가 빠지지 않았는가
- method/path가 실제 annotation과 일치하는가
- Header, Path Parameter, Query Parameter, Request Body, Response, Error Response 섹션이 모두 있는가
- Request 필드와 validation 제약이 DTO와 일치하는가
- Response 필드와 nullable 여부가 DTO와 일치하는가
- cookie/header/redirect/CSRF 동작이 Security 설정과 일치하는가
- 성공과 실패 상태 코드가 실제 코드·테스트 근거를 갖는가
- `{status}`, `{description}` 같은 템플릿 placeholder가 전달 문서에 남아 있지 않은가
- Request Body 표에 실제 필드 행과 `없음` 행이 함께 남아 있지 않은가
- 미확인 내용을 추측으로 채우지 않았는가
- Markdown 표, JSON code fence, 마지막 개행이 정상인가

명세 문서만 작성하면 구현 workflow와 테스트를 실행할 필요가 없다. 코드 동작을 변경하거나 API 계약을 구현하는 요청으로 범위가 넓어지면 프로젝트 `AGENTS.md`의 구현 절차를 따른다.
