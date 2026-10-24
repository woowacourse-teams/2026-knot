# 인증 API

## 로그아웃

`POST /api/v1/auth/logout`

## Header

| Key | Type | Required | Example | Description |
| --- | --- | --- | --- | --- |
| Cookie | String | Yes | `XSRF-TOKEN=...; __Host-KNOT_REFRESH_TOKEN=...` | CSRF 쿠키는 필수다. access·refresh·온보딩 쿠키는 선택이며, 로컬 HTTP에서는 refresh 쿠키 이름이 `KNOT_REFRESH_TOKEN`이다. |
| X-XSRF-TOKEN | String | Yes | `csrf-token-value` | `XSRF-TOKEN` 쿠키와 함께 보내는 CSRF 토큰. 브라우저 요청은 `credentials: "include"`를 사용한다. |

## Path Parameter

| Key | Type | Required | Example | Description |
| --- | --- | --- | --- | --- |
| 없음 | - | - | - | Path Parameter 없음 |

## Query Parameter

| Key | Type | Required | Example | Description |
| --- | --- | --- | --- | --- |
| 없음 | - | - | - | Query Parameter 없음 |

## Request Body

| Field | Type | Required | Nullable | Description | Example |
| --- | --- | --- | --- | --- | --- |
| 없음 | - | - | - | Request Body 없음 |

### Request Example

없음.

## Response

### Status Code

| Status | Description |
| --- | --- |
| 204 No Content | 로그아웃 처리 완료. refresh token이 없거나 유효하지 않아도 같은 응답을 반환한다. |
| 403 Forbidden | CSRF 토큰 검증 실패 |
| 500 Internal Server Error | 로그인 세션 폐기 저장 실패 |

### Response Body

| Field | Type | Nullable | Description | Example |
| --- | --- | --- | --- | --- |
| 없음 | - | - | 204 응답 본문 없음. 오류 응답은 공통 `ErrorResponse`를 사용한다. | - |

### Response Example

```text
HTTP/1.1 204 No Content
Set-Cookie: __Host-KNOT_ACCESS_TOKEN=; Max-Age=0; Path=/; Secure; HttpOnly; SameSite=Lax
Set-Cookie: __Host-KNOT_REFRESH_TOKEN=; Max-Age=0; Path=/; Secure; HttpOnly; SameSite=Lax
Set-Cookie: KNOT_NICKNAME_TOKEN=; Max-Age=0; Path=/; Secure; HttpOnly; SameSite=Lax
```

운영의 `auth.jwt.secure=true` 설정에서는 위처럼 `__Host-` 이름과 `Secure` 속성을 사용한다. 로컬 HTTP에서 `auth.jwt.secure=false`이면 access·refresh 쿠키 이름에서 `__Host-`를 빼고 `Secure` 속성도 사용하지 않는다. 세 쿠키 모두 `Path=/`, `HttpOnly`, `SameSite=Lax`로 만료된다. `Domain`은 설정하지 않는다.

## Error Response

| Status | Error Code | Description |
| --- | --- | --- |
| 403 | `CSRF_INVALID` | CSRF 쿠키가 없거나 헤더 토큰이 일치하지 않는다. 이 경우 세션을 폐기하거나 인증 쿠키를 만료하지 않는다. |
| 500 | `AUTHENTICATION_INTERNAL_ERROR` | 로그인 세션 폐기에 실패했다. 이 경우 인증 쿠키를 만료하지 않아 재시도할 수 있다. |

## 처리 규칙

- 로그아웃은 access token 인증 없이 호출할 수 있다. access 쿠키가 없거나 만료·유효하지 않아도 refresh 쿠키와 CSRF 검증으로 처리한다.
- refresh 쿠키가 유효한 현재 로그인 세션을 가리키면 해당 세션만 폐기한다. 다른 기기의 로그인 세션은 유지한다.
- refresh 쿠키가 없거나 세션이 없거나 만료·이미 폐기된 경우에도 `204 No Content`를 반환하고 인증 쿠키를 만료한다. 반복 요청도 같은 결과다.
- 성공하면 access, refresh, 온보딩 쿠키를 `Max-Age=0`으로 만료한다.
- 변경 요청이므로 `X-XSRF-TOKEN` 검증이 필요하다. CSRF 검증 실패 시 세션 폐기와 쿠키 만료를 수행하지 않는다.
- access token은 stateless JWT이므로 로그아웃 후에도 발급된 access token 자체는 만료 시각까지 최대 1시간 유효할 수 있다.
