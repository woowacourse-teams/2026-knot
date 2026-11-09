import { meResponse } from "@api/mock/responses/auth";
import { mockServer } from "@api/mock/server";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { devAuthHandlers, MOCK_AUTH_COOKIE_NAME, MOCK_AUTH_STATUS } from ".";

// 절대 URL이어야 Node fetch가 보낼 수 있어요. 핸들러는 오리진 와일드카드라 아무 오리진이나 맞아요
const ME_URL = "http://localhost:3000/api/v1/auth/me";
const NICKNAME_URL = "http://localhost:3000/api/v1/auth/nickname";
const REFRESH_URL = "http://localhost:3000/api/v1/auth/refresh";
const ACCEPT_URL = "http://localhost:3000/api/v1/invitations/accept";
const LOGOUT_URL = "http://localhost:3000/api/v1/auth/logout";
const WORKSPACES_URL = "http://localhost:3000/api/v1/workspaces";

// axios(jsdom XHR)는 jar의 쿠키를 요청에 싣지 않아, fetch로 쿠키 헤더를 직접 실어 보내요
const requestMe = (cookie?: string) =>
  fetch(ME_URL, { headers: cookie === undefined ? {} : { cookie } });

const requestNickname = (cookie?: string) =>
  fetch(NICKNAME_URL, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      ...(cookie === undefined ? {} : { cookie }),
    },
    body: JSON.stringify({ nickname: "노티드" }),
  });

const requestRefresh = (cookie?: string) =>
  fetch(REFRESH_URL, {
    method: "POST",
    headers: cookie === undefined ? {} : { cookie },
  });

const requestAccept = (cookie?: string) =>
  fetch(ACCEPT_URL, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      ...(cookie === undefined ? {} : { cookie }),
    },
    body: JSON.stringify({ credential: "X35D3S" }),
  });

const requestWorkspaces = (cookie?: string) =>
  fetch(WORKSPACES_URL, { headers: cookie === undefined ? {} : { cookie } });

const memberCookie = `${MOCK_AUTH_COOKIE_NAME}=${MOCK_AUTH_STATUS.MEMBER}`;
const onboardingCookie = `${MOCK_AUTH_COOKIE_NAME}=${MOCK_AUTH_STATUS.ONBOARDING}`;

// 개발 브라우저(browser.ts)에서만 기본 핸들러를 덮는 핸들러라 여기서도 use로 등록해 검증해요
beforeEach(() => mockServer.use(...devAuthHandlers));

// 닉네임 등록 핸들러가 승격하며 쓴 쿠키가 다음 테스트로 새지 않게 지워요
afterEach(() => {
  document.cookie = `${MOCK_AUTH_COOKIE_NAME}=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT`;
});

describe("개발 브라우저 전용 인증 핸들러", () => {
  describe("GET /api/v1/auth/me", () => {
    it("로그인 쿠키가 없으면 401을 돌려준다", async () => {
      const response = await requestMe();

      expect(response.status).toBe(401);
    });

    it("member 쿠키가 있으면 meResponse를 돌려준다", async () => {
      const response = await requestMe(memberCookie);

      expect(response.status).toBe(200);
      await expect(response.json()).resolves.toEqual(meResponse);
    });

    it("온보딩 쿠키만 있으면 아직 회원이 아니므로 401을 돌려준다", async () => {
      const response = await requestMe(onboardingCookie);

      expect(response.status).toBe(401);
    });
  });

  describe("POST /api/v1/auth/nickname", () => {
    it("로그인 쿠키가 없으면 401을 돌려주고 쿠키를 만들지 않는다", async () => {
      const response = await requestNickname();

      expect(response.status).toBe(401);
      expect(document.cookie).not.toContain(MOCK_AUTH_COOKIE_NAME);
    });

    it("온보딩 쿠키가 있으면 성공하고 member 쿠키로 승격한다", async () => {
      const response = await requestNickname(onboardingCookie);

      expect(response.status).toBe(200);
      expect(document.cookie).toContain(memberCookie);
    });
  });

  describe("POST /api/v1/auth/refresh", () => {
    it("로그인 쿠키가 없으면 재발급할 수 없어 401을 돌려준다", async () => {
      const response = await requestRefresh();

      expect(response.status).toBe(401);
    });

    it("온보딩 쿠키만 있으면 아직 회원이 아니므로 401을 돌려준다", async () => {
      const response = await requestRefresh(onboardingCookie);

      expect(response.status).toBe(401);
    });

    it("member 쿠키가 있으면 재발급에 성공해 204를 돌려준다", async () => {
      const response = await requestRefresh(memberCookie);

      expect(response.status).toBe(204);
    });
  });

  describe("POST /api/v1/auth/logout", () => {
    it("204로 답하고 로그인 쿠키를 지워 로그인 확인이 401이 된다", async () => {
      document.cookie = `${memberCookie}; path=/`;

      const response = await fetch(LOGOUT_URL, { method: "POST" });

      expect(response.status).toBe(204);
      expect(document.cookie).not.toContain(MOCK_AUTH_COOKIE_NAME);
    });
  });

  describe("/api/v1/workspaces", () => {
    it("로그인 쿠키가 없으면 401을 돌려준다", async () => {
      const response = await requestWorkspaces();

      expect(response.status).toBe(401);
    });

    it("member 쿠키가 있으면 기본 핸들러가 응답한다", async () => {
      const response = await requestWorkspaces(memberCookie);

      expect(response.status).toBe(200);
    });
  });

  describe("POST /api/v1/invitations/accept", () => {
    it("로그인 쿠키가 없으면 401을 돌려준다", async () => {
      const response = await requestAccept();

      expect(response.status).toBe(401);
    });

    it("온보딩 쿠키만 있으면 아직 회원이 아니므로 401을 돌려준다", async () => {
      const response = await requestAccept(onboardingCookie);

      expect(response.status).toBe(401);
    });

    it("member 쿠키가 있으면 기본 핸들러가 참여를 받아 201을 돌려준다", async () => {
      const response = await requestAccept(memberCookie);

      expect(response.status).toBe(201);
    });
  });
});
