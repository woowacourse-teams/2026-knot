import { afterEach, describe, expect, it, vi } from "vitest";

import {
  getSearchTutorialEntry,
  readSearchTutorialRecord,
  toLocalDate,
  writeSearchTutorialRecord,
} from "./searchTutorialRecord";

describe("getSearchTutorialEntry", () => {
  it("기록이 없으면 처음으로 띄운다", () => {
    expect(getSearchTutorialEntry(null, "2026-11-11")).toBe("first");
  });

  it("끝냈거나 닫은 기록이 있으면 띄우지 않는다", () => {
    expect(getSearchTutorialEntry({ state: "done" }, "2026-11-11")).toBeNull();
  });

  it("미룬 날에는 다시 띄우지 않는다", () => {
    expect(getSearchTutorialEntry({ state: "snoozed", at: "2026-11-11" }, "2026-11-11")).toBeNull();
  });

  it("미룬 다음 날부터는 두 번째로 띄운다", () => {
    expect(getSearchTutorialEntry({ state: "snoozed", at: "2026-11-11" }, "2026-11-12")).toBe("again");
    expect(getSearchTutorialEntry({ state: "snoozed", at: "2026-11-11" }, "2026-12-01")).toBe("again");
  });
});

describe("toLocalDate", () => {
  it("로컬 날짜를 YYYY-MM-DD로 만든다", () => {
    expect(toLocalDate(new Date(2026, 0, 5, 23, 59))).toBe("2026-01-05");
  });
});

describe("readSearchTutorialRecord · writeSearchTutorialRecord", () => {
  afterEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it("멤버마다 따로 남기고 읽는다", () => {
    writeSearchTutorialRecord(1, { state: "snoozed", at: "2026-11-11" });

    expect(readSearchTutorialRecord(1)).toEqual({ state: "snoozed", at: "2026-11-11" });
    expect(readSearchTutorialRecord(2)).toBeNull();
  });

  it("깨진 값은 처음으로 본다", () => {
    localStorage.setItem("knot.searchTutorial.1", "{broken");

    expect(readSearchTutorialRecord(1)).toBeNull();
  });

  it("저장소를 못 쓰면 다 본 것으로 답해 띄우지 않는다", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });

    expect(readSearchTutorialRecord(1)).toEqual({ state: "done" });
  });
});
