import { describe, expect, it } from "vitest";

import { formatDatabaseFieldValue } from "./formatDatabaseFieldValue";

describe("formatDatabaseFieldValue", () => {
  it("날짜(YYYY-MM-DD)는 앞에 0을 붙이지 않은 「M월 D일」로 바꾼다", () => {
    expect(formatDatabaseFieldValue("2026-11-06")).toBe("11월 6일");
  });

  it("날짜가 아닌 값은 그대로 둔다", () => {
    expect(formatDatabaseFieldValue("11월 13일 주간 회의")).toBe("11월 13일 주간 회의");
    expect(formatDatabaseFieldValue("2026-11-06T01:00:00Z")).toBe("2026-11-06T01:00:00Z");
  });
});
