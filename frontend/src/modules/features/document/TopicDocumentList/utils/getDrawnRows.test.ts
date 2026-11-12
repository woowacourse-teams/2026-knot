import { describe, expect, it } from "vitest";

import { getDrawnRows } from "./getDrawnRows";

/** 높이 100px인 행 10개의 위치. i번 행은 i * 100px에서 시작해요 */
const OFFSETS = [0, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000];

const rows = (...indexes: number[]) =>
  indexes.map((index) => ({ type: "row", index }));

describe("getDrawnRows", () => {
  it("남길 행이 없으면 범위의 행만 그리고, 그린 첫 행의 위쪽 끝까지 비운다", () => {
    expect(
      getDrawnRows({ offsets: OFFSETS, start: 3, end: 6, keptIndex: null }),
    ).toEqual({ firstRowTop: 300, items: rows(3, 4, 5) });
  });

  it("남길 행이 범위 안에 있으면 범위의 행만 그린다", () => {
    expect(
      getDrawnRows({ offsets: OFFSETS, start: 3, end: 6, keptIndex: 4 }),
    ).toEqual({ firstRowTop: 300, items: rows(3, 4, 5) });
  });

  it("남길 행이 범위보다 위에 있으면 그 행을 먼저 그리고, 범위의 첫 행까지 비운다", () => {
    expect(
      getDrawnRows({ offsets: OFFSETS, start: 6, end: 9, keptIndex: 1 }),
    ).toEqual({
      firstRowTop: 100,
      items: [...rows(1), { type: "gap", height: 400 }, ...rows(6, 7, 8)],
    });
  });

  it("남길 행이 범위보다 아래에 있으면 범위의 행 다음에 비우고 그 행을 그린다", () => {
    expect(
      getDrawnRows({ offsets: OFFSETS, start: 0, end: 3, keptIndex: 8 }),
    ).toEqual({
      firstRowTop: 0,
      items: [...rows(0, 1, 2), { type: "gap", height: 500 }, ...rows(8)],
    });
  });

  it("남길 행과 범위가 맞닿으면 비울 칸 없이 이어 그린다", () => {
    expect(
      getDrawnRows({ offsets: OFFSETS, start: 3, end: 6, keptIndex: 2 }),
    ).toEqual({ firstRowTop: 200, items: rows(2, 3, 4, 5) });
    expect(
      getDrawnRows({ offsets: OFFSETS, start: 3, end: 6, keptIndex: 6 }),
    ).toEqual({ firstRowTop: 300, items: rows(3, 4, 5, 6) });
  });

  it("범위가 비어 있으면 남길 행만 그린다", () => {
    expect(
      getDrawnRows({ offsets: OFFSETS, start: 0, end: 0, keptIndex: 5 }),
    ).toEqual({ firstRowTop: 500, items: rows(5) });
  });
});
