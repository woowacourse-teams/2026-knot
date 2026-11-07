import { afterEach, describe, expect, it, vi } from "vitest";

import {
  clearAllLastConversationIds,
  clearLastConversationId,
  readLastConversationId,
  saveLastConversationId,
} from ".";

describe("lastConversation", () => {
  afterEach(() => sessionStorage.clear());

  it("남긴 대화가 없으면 undefined를 돌려준다", () => {
    expect(readLastConversationId("1")).toBeUndefined();
  });

  it("남긴 대화를 sessionStorage에 보관해 새로고침 뒤에도 같은 값을 읽는다", () => {
    saveLastConversationId({ workspaceId: "1", conversationId: 10 });

    expect(JSON.stringify(sessionStorage)).toContain("10");
    expect(readLastConversationId("1")).toBe(10);
  });

  it("남긴 값이 망가졌으면 본 적이 없는 것으로 본다", () => {
    sessionStorage.setItem("knot.lastConversation.1", "abc");

    expect(readLastConversationId("1")).toBeUndefined();
  });

  it("워크스페이스마다 따로 남긴다", () => {
    saveLastConversationId({ workspaceId: "1", conversationId: 10 });
    saveLastConversationId({ workspaceId: "2", conversationId: 20 });

    expect(readLastConversationId("1")).toBe(10);
    expect(readLastConversationId("2")).toBe(20);
  });

  it("비우면 그 워크스페이스의 대화만 지운다", () => {
    saveLastConversationId({ workspaceId: "1", conversationId: 10 });
    saveLastConversationId({ workspaceId: "2", conversationId: 20 });

    clearLastConversationId("1");

    expect(readLastConversationId("1")).toBeUndefined();
    expect(readLastConversationId("2")).toBe(20);
  });

  it("모두 비우면 워크스페이스마다 남긴 대화를 지우고 다른 기록은 그대로 둔다", () => {
    saveLastConversationId({ workspaceId: "1", conversationId: 10 });
    saveLastConversationId({ workspaceId: "2", conversationId: 20 });
    sessionStorage.setItem("knot.other", "남는 값");

    clearAllLastConversationIds();

    expect(readLastConversationId("1")).toBeUndefined();
    expect(readLastConversationId("2")).toBeUndefined();
    expect(sessionStorage.getItem("knot.other")).toBe("남는 값");
  });

  it("저장소를 쓸 수 없어도 오류를 던지지 않고, 읽으면 남긴 대화가 없는 것으로 본다", () => {
    saveLastConversationId({ workspaceId: "1", conversationId: 10 });
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("저장소를 쓸 수 없어요");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("저장소를 쓸 수 없어요");
    });
    vi.spyOn(Storage.prototype, "removeItem").mockImplementation(() => {
      throw new Error("저장소를 쓸 수 없어요");
    });

    expect(() =>
      saveLastConversationId({ workspaceId: "1", conversationId: 10 }),
    ).not.toThrow();
    expect(() => clearLastConversationId("1")).not.toThrow();
    expect(() => clearAllLastConversationIds()).not.toThrow();
    expect(readLastConversationId("1")).toBeUndefined();
  });
});
