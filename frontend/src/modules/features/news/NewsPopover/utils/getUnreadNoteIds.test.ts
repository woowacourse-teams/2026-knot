import { describe, expect, it } from "vitest";

import type { ReleaseNote } from "@/shared/types/releaseNote";

import { getUnreadNoteIds } from "./getUnreadNoteIds";

const toNotes = (ids: string[]): ReleaseNote[] =>
  ids.map((id) => ({ id, releasedAt: "2026-10-20", items: [] }));

/** 최신부터 놓은 노트 세 개. 모두 패널에 보여요 */
const NOTE_IDS = ["v0.3.0", "v0.2.0", "v0.1.0"];
const NOTES = toNotes(NOTE_IDS);

describe("getUnreadNoteIds", () => {
  it("새 소식을 연 적이 없으면 모든 노트가 안 읽음이다", () => {
    expect(
      getUnreadNoteIds({ notes: NOTES, noteIds: NOTE_IDS, lastSeenId: null }),
    ).toEqual(["v0.3.0", "v0.2.0", "v0.1.0"]);
  });

  it("마지막으로 연 노트가 목록 중간에 있으면 그보다 위의 노트만 안 읽음이다", () => {
    expect(
      getUnreadNoteIds({
        notes: NOTES,
        noteIds: NOTE_IDS,
        lastSeenId: "v0.2.0",
      }),
    ).toEqual(["v0.3.0"]);
  });

  it("마지막으로 연 노트가 맨 위면 안 읽은 노트가 없다", () => {
    expect(
      getUnreadNoteIds({
        notes: NOTES,
        noteIds: NOTE_IDS,
        lastSeenId: "v0.3.0",
      }),
    ).toEqual([]);
  });

  it("마지막으로 연 노트가 목록에 없으면 모두 읽은 것으로 본다", () => {
    expect(
      getUnreadNoteIds({
        notes: NOTES,
        noteIds: NOTE_IDS,
        lastSeenId: "v0.0.1",
      }),
    ).toEqual([]);
  });

  it("localStorage를 쓸 수 없어 마지막으로 연 노트를 모르면 모두 읽은 것으로 본다", () => {
    expect(
      getUnreadNoteIds({
        notes: NOTES,
        noteIds: NOTE_IDS,
        lastSeenId: undefined,
      }),
    ).toEqual([]);
  });

  it("노트가 없으면 안 읽은 노트도 없다", () => {
    expect(
      getUnreadNoteIds({ notes: [], noteIds: [], lastSeenId: null }),
    ).toEqual([]);
  });

  describe("패널에 보이지 않는 노트(NEWS-R16)", () => {
    // pr-1050은 3일이 지나 패널에서 빠졌고, v0.1.0은 「항상 보이기」로 남긴 옛 노트예요
    const noteIds = ["pr-1060", "pr-1050", "v0.1.0"];
    const notes = toNotes(["pr-1060", "v0.1.0"]);

    it("마지막으로 연 노트가 패널에서 빠져도 그 뒤에 나온 노트는 안 읽음이다", () => {
      expect(
        getUnreadNoteIds({ notes, noteIds, lastSeenId: "pr-1050" }),
      ).toEqual(["pr-1060"]);
    });

    it("마지막으로 연 노트보다 앞선 노트는 「항상 보이기」로 패널에 남아도 읽음이다", () => {
      expect(
        getUnreadNoteIds({ notes, noteIds, lastSeenId: "pr-1050" }),
      ).not.toContain("v0.1.0");
    });

    it("마지막으로 연 노트 뒤에 나온 노트라도 패널에 보이지 않으면 안 읽음으로 세지 않는다", () => {
      expect(
        getUnreadNoteIds({
          notes: toNotes(["v0.1.0"]),
          noteIds,
          lastSeenId: "v0.1.0",
        }),
      ).toEqual([]);
    });

    it("새 소식을 연 적이 없으면 패널에 보이는 노트만 안 읽음이다", () => {
      expect(getUnreadNoteIds({ notes, noteIds, lastSeenId: null })).toEqual([
        "pr-1060",
        "v0.1.0",
      ]);
    });
  });
});
