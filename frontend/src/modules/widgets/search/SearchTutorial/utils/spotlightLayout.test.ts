import { describe, expect, it } from "vitest";

import {
  placeAboveStart,
  placeBelowCenter,
  toDimClipPath,
  toHole,
  unionRect,
} from "./spotlightLayout";

const viewport = { width: 1440, height: 1024 };

describe("toHole", () => {
  it("대상보다 사방으로 6px 넓히고 낮은 대상은 알약 모양으로 둥글린다", () => {
    expect(toHole({ top: 30, left: 684, width: 61, height: 32 })).toEqual({
      top: 24,
      left: 678,
      width: 73,
      height: 44,
      radius: 20,
    });
  });
});

describe("unionRect", () => {
  it("두 영역을 함께 덮는 가장 작은 영역을 만든다", () => {
    expect(
      unionRect({ top: 780, left: 340, width: 760, height: 100 }, { top: 904, left: 340, width: 760, height: 64 }),
    ).toEqual({ top: 780, left: 340, width: 760, height: 188 });
  });
});

describe("toDimClipPath", () => {
  it("화면 전체와 구멍을 evenodd 경로로 만든다", () => {
    const clipPath = toDimClipPath(viewport, [toHole({ top: 30, left: 684, width: 61, height: 32 })]);

    expect(clipPath.startsWith('path(evenodd, "M0 0 H1440 V1024 H0 Z')).toBe(true);
    expect(clipPath).toContain("M698 24");
  });
});

describe("안내 카드 위치", () => {
  it("영역 아래 가운데에 두되 화면 밖으로 나가지 않게 한다", () => {
    expect(placeBelowCenter({ top: 24, left: 678, width: 73, height: 44 }, 1440)).toEqual({ top: 84, left: 544.5 });
    expect(placeBelowCenter({ top: 24, left: 0, width: 40, height: 44 }, 1440).left).toBe(16);
  });

  it("영역 위 왼쪽에 둔다", () => {
    expect(placeAboveStart({ top: 898, left: 334, width: 772, height: 76 }, viewport)).toEqual({ bottom: 142, left: 334 });
  });
});
