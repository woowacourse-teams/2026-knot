import { describe, expect, it } from "vitest";

import type { MeetingInsightsMeeting } from "../types/dataset";
import { pickDefaultMeeting } from "./pickDefaultMeeting";

const createMeeting = (
  recordingId: number,
  recordingDurationSeconds: number,
  meetingSummary: string | null,
): MeetingInsightsMeeting => ({
  recordingId,
  title: null,
  startedAt: "2026-11-02T01:00:00Z",
  endedAt: "2026-11-02T02:00:00Z",
  recordingDurationSeconds,
  meetingSummary,
  viewCount: 0,
  segments: [],
  documents: [],
});

describe("pickDefaultMeeting", () => {
  it("요약이 있는 10분 이상 회의 중 가장 최근 회의를 고른다", () => {
    const meeting = pickDefaultMeeting([
      createMeeting(1, 1200, "요약"),
      createMeeting(2, 1200, "요약"),
      createMeeting(3, 300, "요약"),
      createMeeting(4, 1200, null),
    ]);

    expect(meeting?.recordingId).toBe(2);
  });

  it("조건에 맞는 회의가 없으면 가장 최근 회의를 고른다", () => {
    const meeting = pickDefaultMeeting([
      createMeeting(1, 100, null),
      createMeeting(2, 100, null),
    ]);

    expect(meeting?.recordingId).toBe(2);
  });

  it("회의가 없으면 undefined를 돌려준다", () => {
    expect(pickDefaultMeeting([])).toBeUndefined();
  });
});
