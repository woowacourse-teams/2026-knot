import { describe, expect, it } from "vitest";

import { sortByCreatedAt } from "./sortByCreatedAt";

describe("sortByCreatedAt", () => {
  it("만든 시각이 이른 것부터 늘어놓는다", () => {
    const items = [
      { id: 3, createdAt: "2026-10-21T03:00:00Z" },
      { id: 1, createdAt: "2026-10-21T01:00:00Z" },
      { id: 2, createdAt: "2026-10-21T02:00:00Z" },
    ];

    expect(sortByCreatedAt(items).map(({ id }) => id)).toEqual([1, 2, 3]);
  });

  it("만든 시각이 같으면 id가 작은 것을 먼저 둔다", () => {
    const items = [
      { id: 12, createdAt: "2026-10-21T01:00:00Z" },
      { id: 11, createdAt: "2026-10-21T01:00:00Z" },
    ];

    expect(sortByCreatedAt(items).map(({ id }) => id)).toEqual([11, 12]);
  });

  it("받은 배열의 순서는 바꾸지 않는다", () => {
    const items = [
      { id: 2, createdAt: "2026-10-21T02:00:00Z" },
      { id: 1, createdAt: "2026-10-21T01:00:00Z" },
    ];

    sortByCreatedAt(items);

    expect(items.map(({ id }) => id)).toEqual([2, 1]);
  });
});
