import { describe, expect, it } from "vitest";

import { canScrollContent } from "./scrollableContent";

describe("기술 소개 내부 스크롤", () => {
  it("본문을 아래로 읽을 수 있으면 장면을 넘기지 않고, 끝에 닿으면 다음 장면으로 나간다", () => {
    const content = document.createElement("div");
    content.setAttribute("data-landing-scroll", "");
    const link = document.createElement("a");
    content.append(link);
    Object.defineProperties(content, {
      clientHeight: { value: 400 },
      scrollHeight: { value: 900 },
    });

    expect(canScrollContent({ target: link, delta: 100 })).toBe(true);
    expect(canScrollContent({ target: link, delta: -100 })).toBe(false);
    content.scrollTop = 500;
    expect(canScrollContent({ target: link, delta: 100 })).toBe(false);
    expect(canScrollContent({ target: link, delta: -100 })).toBe(true);
  });

  it("본문이 한 화면에 들어가거나 소개 밖에서 스크롤하면 기존 장면 이동을 쓴다", () => {
    const content = document.createElement("div");
    content.setAttribute("data-landing-scroll", "");
    Object.defineProperties(content, {
      clientHeight: { value: 400 },
      scrollHeight: { value: 400 },
    });
    expect(canScrollContent({ target: content, delta: 100 })).toBe(false);
    expect(canScrollContent({ target: document.body, delta: 100 })).toBe(false);
    expect(canScrollContent({ target: null, delta: 100 })).toBe(false);
  });
});
