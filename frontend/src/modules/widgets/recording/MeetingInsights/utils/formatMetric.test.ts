import { describe, expect, it } from "vitest";

import {
  formatPercent,
  formatSeconds,
  getNiceMax,
  getTimeTicks,
} from "./formatMetric";

describe("formatMetric", () => {
  it("비율을 정수 백분율로 바꾼다", () => {
    expect(formatPercent(0.624)).toBe("62%");
  });

  it("1분 미만은 초로, 1분 이상은 분:초로 쓴다", () => {
    expect(formatSeconds(42.4)).toBe("42초");
    expect(formatSeconds(90)).toBe("01:30");
  });

  it("최댓값을 읽기 쉬운 수로 올린다", () => {
    expect(getNiceMax(37)).toBe(50);
    expect(getNiceMax(180)).toBe(200);
    expect(getNiceMax(0)).toBe(1);
  });

  it("회의 길이에 맞춰 시간 눈금 간격을 고른다", () => {
    expect(getTimeTicks(3600)).toEqual([
      0, 600_000, 1_200_000, 1_800_000, 2_400_000, 3_000_000, 3_600_000,
    ]);
    expect(getTimeTicks(120)).toEqual([0, 60_000, 120_000]);
  });
});
