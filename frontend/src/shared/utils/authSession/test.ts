import { afterEach, describe, expect, it } from "vitest";

import {
  clearAuthSession,
  consumeAuthSessionExpired,
  markAuthSessionConfirmed,
  markAuthSessionExpired,
} from ".";

describe("authSession", () => {
  afterEach(() => {
    sessionStorage.clear();
  });

  it("로그인을 확인한 탭에서 재발급이 실패하면 만료로 기록하고, 한 번 읽으면 지운다", () => {
    markAuthSessionConfirmed();
    markAuthSessionExpired();

    expect(consumeAuthSessionExpired()).toBe(true);
    expect(consumeAuthSessionExpired()).toBe(false);
  });

  it("로그인을 확인한 적 없는 탭(처음 온 비로그인 방문)에서는 재발급이 실패해도 만료로 기록하지 않는다", () => {
    markAuthSessionExpired();

    expect(consumeAuthSessionExpired()).toBe(false);
  });

  it("만료를 알린 뒤에 재발급이 또 실패해도 다시 로그인을 확인하기 전에는 만료로 기록하지 않는다", () => {
    markAuthSessionConfirmed();
    markAuthSessionExpired();
    consumeAuthSessionExpired();

    markAuthSessionExpired();

    expect(consumeAuthSessionExpired()).toBe(false);
  });

  it("기록을 지운 뒤에는 재발급이 실패해도 만료로 기록하지 않는다", () => {
    markAuthSessionConfirmed();

    clearAuthSession();
    markAuthSessionExpired();

    expect(consumeAuthSessionExpired()).toBe(false);
  });

  it("이미 남은 만료 기록도 지운다", () => {
    markAuthSessionConfirmed();
    markAuthSessionExpired();

    clearAuthSession();

    expect(consumeAuthSessionExpired()).toBe(false);
  });

  it("로그인을 확인했지만 재발급이 실패하지 않았으면 만료가 아니다", () => {
    markAuthSessionConfirmed();

    expect(consumeAuthSessionExpired()).toBe(false);
  });
});
