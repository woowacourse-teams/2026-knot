import { describe, expect, it } from "vitest";

import { getRowOffsets } from "./getRowOffsets";

/** 브라우저 기본 글자 크기. 1rem이 16px이에요 */
const DEFAULT_REM_PX = 16;

const WITHOUT_SUMMARY = { summary: null };
const WITH_SUMMARY = { summary: "회의에서 정한 일정" };

describe("getRowOffsets", () => {
  it("행이 없으면 카드 높이가 0이다", () => {
    expect(getRowOffsets({ documents: [], remPx: DEFAULT_REM_PX })).toEqual([
      0,
    ]);
  });

  it("요약이 없는 행 하나는 64px이다", () => {
    expect(
      getRowOffsets({ documents: [WITHOUT_SUMMARY], remPx: DEFAULT_REM_PX }),
    ).toEqual([0, 64]);
  });

  it("요약이 있는 행 하나는 92px이다", () => {
    expect(
      getRowOffsets({ documents: [WITH_SUMMARY], remPx: DEFAULT_REM_PX }),
    ).toEqual([0, 92]);
  });

  it("두 번째 행부터 위쪽 구분선 1px만큼 더 내려간다", () => {
    expect(
      getRowOffsets({
        documents: [WITHOUT_SUMMARY, WITHOUT_SUMMARY, WITHOUT_SUMMARY],
        remPx: DEFAULT_REM_PX,
      }),
    ).toEqual([0, 64, 129, 194]);
  });

  it("요약이 있는 행과 없는 행이 섞이면 행마다 제 높이만큼 내려간다", () => {
    expect(
      getRowOffsets({
        documents: [WITH_SUMMARY, WITHOUT_SUMMARY, WITH_SUMMARY],
        remPx: DEFAULT_REM_PX,
      }),
    ).toEqual([0, 92, 157, 250]);
  });

  it("브라우저 글자 크기를 키워 1rem이 20px이면 행이 커지고 구분선은 1px 그대로다", () => {
    expect(
      getRowOffsets({
        documents: [WITHOUT_SUMMARY, WITHOUT_SUMMARY],
        remPx: 20,
      }),
    ).toEqual([0, 80, 161]);
  });
});
