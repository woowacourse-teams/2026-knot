import { http, HttpResponse } from "msw";

import { AUTH_LOGOUT_API_PATH } from "@api/fetch/api/v1/auth/logout";

// 기본 응답은 로그아웃 성공(204)이에요. 쿠키 만료는 브라우저가 받는 것이라 본문이 없어요
export const authLogoutHandlers = [
  http.post(
    `*${AUTH_LOGOUT_API_PATH}`,
    () => new HttpResponse(null, { status: 204 }),
  ),
];
