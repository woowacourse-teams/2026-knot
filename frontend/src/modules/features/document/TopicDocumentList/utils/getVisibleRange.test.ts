import { describe, expect, it } from "vitest";

import { getVisibleRange } from "./getVisibleRange";

/** 높이 100px인 행 10개의 위치. i번 행은 i * 100px에서 시작해요 */
const OFFSETS = [0, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000];

describe("getVisibleRange", () => {
  it("카드 전체가 화면 안에 있으면 행을 모두 그린다", () => {
    expect(
      getVisibleRange({
        offsets: [0, 100, 200, 300],
        viewStart: -100,
        viewEnd: 600,
        overscanPx: 0,
      }),
    ).toEqual({ start: 0, end: 3 });
  });

  it("화면 위쪽 끝과 아래쪽 끝에 걸친 행도 그린다", () => {
    expect(
      getVisibleRange({
        offsets: OFFSETS,
        viewStart: 150,
        viewEnd: 350,
        overscanPx: 0,
      }),
    ).toEqual({ start: 1, end: 4 });
  });

  it("행의 끝이 화면의 끝과 딱 맞닿으면 그 행은 그리지 않는다", () => {
    expect(
      getVisibleRange({
        offsets: OFFSETS,
        viewStart: 200,
        viewEnd: 400,
        overscanPx: 0,
      }),
    ).toEqual({ start: 2, end: 4 });
  });

  it("화면 위아래를 overscanPx만큼 넓힌 구간에 걸친 행까지 그린다", () => {
    expect(
      getVisibleRange({
        offsets: OFFSETS,
        viewStart: 450,
        viewEnd: 550,
        overscanPx: 150,
      }),
    ).toEqual({ start: 3, end: 7 });
  });

  it("넓힌 구간이 카드 밖으로 나가도 카드 안의 행만 그린다", () => {
    expect(
      getVisibleRange({
        offsets: OFFSETS,
        viewStart: 0,
        viewEnd: 150,
        overscanPx: 500,
      }),
    ).toEqual({ start: 0, end: 7 });
    expect(
      getVisibleRange({
        offsets: OFFSETS,
        viewStart: 850,
        viewEnd: 1000,
        overscanPx: 500,
      }),
    ).toEqual({ start: 3, end: 10 });
  });

  it("카드가 화면 밖에 있어도 넓힌 구간에 걸친 행은 미리 그린다", () => {
    expect(
      getVisibleRange({
        offsets: OFFSETS,
        viewStart: -500,
        viewEnd: -100,
        overscanPx: 150,
      }),
    ).toEqual({ start: 0, end: 1 });
    expect(
      getVisibleRange({
        offsets: OFFSETS,
        viewStart: 1050,
        viewEnd: 1450,
        overscanPx: 150,
      }),
    ).toEqual({ start: 9, end: 10 });
  });

  it("카드가 넓힌 구간보다 아래나 위에 있으면 그릴 행이 없다", () => {
    expect(
      getVisibleRange({
        offsets: OFFSETS,
        viewStart: -500,
        viewEnd: -100,
        overscanPx: 50,
      }),
    ).toEqual({ start: 0, end: 0 });
    expect(
      getVisibleRange({
        offsets: OFFSETS,
        viewStart: 1200,
        viewEnd: 1600,
        overscanPx: 100,
      }),
    ).toEqual({ start: 0, end: 0 });
  });

  it("행이 없는 카드는 그릴 행이 없다", () => {
    expect(
      getVisibleRange({
        offsets: [0],
        viewStart: 0,
        viewEnd: 800,
        overscanPx: 325,
      }),
    ).toEqual({ start: 0, end: 0 });
  });
});
