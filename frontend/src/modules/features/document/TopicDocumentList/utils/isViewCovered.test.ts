import { describe, expect, it } from "vitest";

import { isViewCovered } from "./isViewCovered";

/** 높이 100px인 행 10개의 위치. i번 행은 i * 100px에서 시작해요 */
const OFFSETS = [0, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000];

describe("isViewCovered", () => {
  it("그린 행이 화면에 보이는 카드 부분을 모두 덮으면 true를 돌려준다", () => {
    expect(
      isViewCovered({
        offsets: OFFSETS,
        start: 1,
        end: 4,
        viewStart: 150,
        viewEnd: 350,
      }),
    ).toBe(true);
  });

  it("화면 위쪽이나 아래쪽에 그린 행이 없는 부분이 보이면 false를 돌려준다", () => {
    expect(
      isViewCovered({
        offsets: OFFSETS,
        start: 2,
        end: 4,
        viewStart: 150,
        viewEnd: 350,
      }),
    ).toBe(false);
    expect(
      isViewCovered({
        offsets: OFFSETS,
        start: 1,
        end: 4,
        viewStart: 350,
        viewEnd: 650,
      }),
    ).toBe(false);
  });

  it("카드가 화면 밖에 있으면 덮을 곳이 없어 true를 돌려준다", () => {
    expect(
      isViewCovered({
        offsets: OFFSETS,
        start: 0,
        end: 0,
        viewStart: -500,
        viewEnd: -100,
      }),
    ).toBe(true);
    expect(
      isViewCovered({
        offsets: OFFSETS,
        start: 0,
        end: 0,
        viewStart: 1000,
        viewEnd: 1400,
      }),
    ).toBe(true);
  });

  it("카드가 화면에 보이는데 그린 행이 없으면 false를 돌려준다", () => {
    expect(
      isViewCovered({
        offsets: OFFSETS,
        start: 0,
        end: 0,
        viewStart: -100,
        viewEnd: 200,
      }),
    ).toBe(false);
  });

  it("화면이 카드 위쪽 끝이나 아래쪽 끝 밖까지 걸치면 카드 안 부분만 덮었는지 본다", () => {
    expect(
      isViewCovered({
        offsets: OFFSETS,
        start: 7,
        end: 10,
        viewStart: 800,
        viewEnd: 1200,
      }),
    ).toBe(true);
    expect(
      isViewCovered({
        offsets: OFFSETS,
        start: 0,
        end: 3,
        viewStart: -200,
        viewEnd: 250,
      }),
    ).toBe(true);
  });
});
