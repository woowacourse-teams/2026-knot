import { afterEach, describe, expect, it, vi } from "vitest";

import { clearFeedbackDraft, readFeedbackDraft, saveFeedbackDraft } from ".";

describe("feedbackDraft", () => {
  afterEach(() => sessionStorage.clear());

  it("남긴 유형과 내용을 그대로 돌려준다", () => {
    saveFeedbackDraft({ type: "bug", content: "표가 깨져요" });

    expect(readFeedbackDraft()).toEqual({
      type: "bug",
      content: "표가 깨져요",
    });
  });

  it("남긴 내용이 없으면 빈 내용을 돌려준다", () => {
    expect(readFeedbackDraft()).toEqual({ type: null, content: "" });
  });

  it("지우면 빈 내용을 돌려준다", () => {
    saveFeedbackDraft({ type: "idea", content: "내보내기" });

    clearFeedbackDraft();

    expect(readFeedbackDraft()).toEqual({ type: null, content: "" });
  });

  it.each([
    { reason: "JSON이 아니면", saved: "{" },
    { reason: "객체가 아니면", saved: '"bug"' },
  ])("$reason 빈 내용을 돌려준다", ({ saved }) => {
    sessionStorage.setItem("knot.feedback.draft", saved);

    expect(readFeedbackDraft()).toEqual({ type: null, content: "" });
  });

  it("모르는 유형은 고르지 않은 것으로 보고 내용은 남긴다", () => {
    sessionStorage.setItem(
      "knot.feedback.draft",
      JSON.stringify({ type: "toString", content: "내용" }),
    );

    expect(readFeedbackDraft()).toEqual({ type: null, content: "내용" });
  });

  it("sessionStorage를 쓸 수 없으면 빈 내용을 돌려주고 남기기와 지우기는 넘어간다", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("SecurityError");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("SecurityError");
    });
    vi.spyOn(Storage.prototype, "removeItem").mockImplementation(() => {
      throw new Error("SecurityError");
    });

    expect(readFeedbackDraft()).toEqual({ type: null, content: "" });
    expect(() => saveFeedbackDraft({ type: "bug", content: "" })).not.toThrow();
    expect(() => clearFeedbackDraft()).not.toThrow();
  });
});
