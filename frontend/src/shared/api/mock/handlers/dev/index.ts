import { http, HttpResponse } from "msw";

import { AUTH_LOGOUT_API_PATH } from "@api/fetch/api/v1/auth/logout";
import { AUTH_ME_API_PATH } from "@api/fetch/api/v1/auth/me";
import { AUTH_NICKNAME_API_PATH } from "@api/fetch/api/v1/auth/nickname";
import { AUTH_REFRESH_API_PATH } from "@api/fetch/api/v1/auth/refresh";
import { INVITATIONS_ACCEPT_API_PATH } from "@api/fetch/api/v1/invitations/accept";
import { meResponse } from "@api/mock/responses/auth";

/**
 * 개발 서버의 mock 로그인 상태 쿠키.
 *
 * `webpack.config.js`의 mock OAuth 미들웨어가 심고 여기 핸들러가 읽어요.
 * 이름이나 값을 바꾸면 그 미들웨어·`playwright.config.ts`의 storageState와 함께 바꿔야 해요.
 */
export const MOCK_AUTH_COOKIE_NAME = "KNOT_MOCK_AUTH";

/** 실제 백엔드의 접근 토큰(member)·온보딩 토큰(onboarding) 구분을 쿠키 값으로 흉내내요 */
export const MOCK_AUTH_STATUS = {
  MEMBER: "member",
  ONBOARDING: "onboarding",
} as const;

/**
 * 개발 브라우저 전용 인증 핸들러.
 *
 * 실제 백엔드처럼 쿠키로 로그인 상태를 판정해 GitHub 로그인 → 온보딩 → 홈 진입
 * 플로우를 개발 서버에서 재현해요. OAuth 진입 자체는 페이지 네비게이션이라 msw가
 * 가로채지 못하므로 `webpack.config.js`의 devServer 미들웨어가 302로 대신해요.
 *
 * `browser.ts`에서만 기본 핸들러 앞에 둬(먼저 맞는 핸들러가 이겨요) me·nickname·refresh·logout·워크스페이스·초대 참여를
 * 덮어요. vitest(server.ts)는 항상 로그인된 기본 핸들러를 그대로 쓰므로 넣지 않아요.
 */
export const devAuthHandlers = [
  http.get(`*${AUTH_ME_API_PATH}`, ({ cookies }) => {
    if (cookies[MOCK_AUTH_COOKIE_NAME] !== MOCK_AUTH_STATUS.MEMBER) {
      return new HttpResponse(null, { status: 401 });
    }

    return HttpResponse.json(meResponse);
  }),

  http.post(`*${AUTH_NICKNAME_API_PATH}`, ({ cookies }) => {
    if (cookies[MOCK_AUTH_COOKIE_NAME] === undefined) {
      return new HttpResponse(null, { status: 401 });
    }

    // 온보딩 토큰 → 접근 토큰 전환을 재현해요. msw의 Set-Cookie 저장은 새로고침하면
    // 사라지므로 document.cookie에 직접 써요 (resolver는 페이지 컨텍스트에서 돌아요)
    document.cookie = `${MOCK_AUTH_COOKIE_NAME}=${MOCK_AUTH_STATUS.MEMBER}; path=/; SameSite=Lax`;

    return new HttpResponse(null, { status: 200 });
  }),

  // 실제 백엔드처럼 회원 쿠키가 있어야 재발급돼요. 쿠키가 없으면 재발급도 401이라 로그인 화면으로 가요
  http.post(`*${AUTH_REFRESH_API_PATH}`, ({ cookies }) => {
    if (cookies[MOCK_AUTH_COOKIE_NAME] !== MOCK_AUTH_STATUS.MEMBER) {
      return new HttpResponse(null, { status: 401 });
    }

    return new HttpResponse(null, { status: 204 });
  }),

  // 실제 백엔드처럼 쿠키를 만료해요. 쿠키가 없어지면 me와 재발급이 401이라 로그아웃 뒤의 화면을 재현해요
  http.post(`*${AUTH_LOGOUT_API_PATH}`, () => {
    document.cookie = `${MOCK_AUTH_COOKIE_NAME}=; path=/; Max-Age=0`;

    return new HttpResponse(null, { status: 204 });
  }),

  // 실제 백엔드처럼 로그인하지 않은 요청을 거절해, 로그아웃 뒤 뒤로 가기와 다른 탭이 로그인으로 가는 흐름을 재현해요.
  // 쿠키가 있으면 아무것도 돌려주지 않아 다음에 맞는 핸들러가 받아요
  http.all("*/api/v1/workspaces*", ({ cookies }) => {
    if (cookies[MOCK_AUTH_COOKIE_NAME] === undefined) {
      return new HttpResponse(null, { status: 401 });
    }
  }),

  // 로그인 전 초대 참여가 401로 끝나 로그인으로 가는 흐름을 재현해요.
  // 회원이면 아무것도 돌려주지 않아 다음에 맞는 기본 핸들러(201)가 참여를 받아요
  http.post(`*${INVITATIONS_ACCEPT_API_PATH}`, ({ cookies }) => {
    if (cookies[MOCK_AUTH_COOKIE_NAME] !== MOCK_AUTH_STATUS.MEMBER) {
      return new HttpResponse(null, { status: 401 });
    }
  }),
];
