import { describe, expect, it } from "vitest";

import type { ReleaseNote } from "@/shared/types/releaseNote";

import { getUnreadNoteIds } from "./getUnreadNoteIds";

/** 최신부터 놓은 노트 세 개 */
const NOTES: ReleaseNote[] = ["v0.3.0", "v0.2.0", "v0.1.0"].map((id) => ({
  id,
  releasedAt: "2026-10-20",
  items: [],
}));

describe("getUnreadNoteIds", () => {
  it("새 소식을 연 적이 없으면 모든 노트가 안 읽음이다", () => {
    expect(getUnreadNoteIds({ notes: NOTES, lastSeenId: null })).toEqual([
      "v0.3.0",
      "v0.2.0",
      "v0.1.0",
    ]);
  });

  it("마지막으로 연 노트가 목록 중간에 있으면 그보다 위의 노트만 안 읽음이다", () => {
    expect(getUnreadNoteIds({ notes: NOTES, lastSeenId: "v0.2.0" })).toEqual([
      "v0.3.0",
    ]);
  });

  it("마지막으로 연 노트가 맨 위면 안 읽은 노트가 없다", () => {
    expect(getUnreadNoteIds({ notes: NOTES, lastSeenId: "v0.3.0" })).toEqual(
      [],
    );
  });

  it("마지막으로 연 노트가 목록에 없으면 모두 읽은 것으로 본다", () => {
    expect(getUnreadNoteIds({ notes: NOTES, lastSeenId: "v0.0.1" })).toEqual(
      [],
    );
  });

  it("localStorage를 쓸 수 없어 마지막으로 연 노트를 모르면 모두 읽은 것으로 본다", () => {
    expect(getUnreadNoteIds({ notes: NOTES, lastSeenId: undefined })).toEqual(
      [],
    );
  });

  it("노트가 없으면 안 읽은 노트도 없다", () => {
    expect(getUnreadNoteIds({ notes: [], lastSeenId: null })).toEqual([]);
  });
});
