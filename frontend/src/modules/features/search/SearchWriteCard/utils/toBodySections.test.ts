import { describe, expect, it } from "vitest";

import { toBodySections } from "./toBodySections";

describe("toBodySections", () => {
  it("소제목 없는 본문은 빈 줄을 빼고 소제목이 없는 묶음 한 개로 만든다", () => {
    expect(toBodySections("첫 줄\n\n둘째 줄")).toEqual([
      { heading: null, lines: ["첫 줄", "둘째 줄"] },
    ]);
  });

  it("# 소제목마다 묶음을 새로 만들고, - 글머리는 • 로 바꾼다", () => {
    expect(
      toBodySections("## 결정\n- 출시일은 12월 3일\n\n## 할 일\n* 도넛: 체크리스트\n1. 번호 그대로"),
    ).toEqual([
      { heading: "결정", lines: ["• 출시일은 12월 3일"] },
      { heading: "할 일", lines: ["• 도넛: 체크리스트", "1. 번호 그대로"] },
    ]);
  });

  it("소제목보다 먼저 온 줄은 소제목 없는 묶음으로 둔다", () => {
    expect(toBodySections("머리말\n# 결정")).toEqual([
      { heading: null, lines: ["머리말"] },
      { heading: "결정", lines: [] },
    ]);
  });

  it("빈 본문은 빈 배열을 돌려준다", () => {
    expect(toBodySections("  \n")).toEqual([]);
  });
});
