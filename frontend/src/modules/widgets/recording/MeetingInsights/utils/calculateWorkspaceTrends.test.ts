import { describe, expect, it } from "vitest";

import type {
  MeetingInsightsDocument,
  MeetingInsightsMeeting,
} from "../types/dataset";
import { calculateWorkspaceTrends } from "./calculateWorkspaceTrends";

const createDocument = (
  overrides: Partial<MeetingInsightsDocument> = {},
): MeetingInsightsDocument => ({
  id: 1,
  topic: "주제",
  folderName: "폴더",
  title: "문서",
  summary: null,
  content: "",
  createdAt: "2026-11-02T01:03:00Z",
  confirmedCount: 0,
  searchEvidenceCount: 0,
  viewCount: 0,
  ...overrides,
});

const createMeeting = (
  overrides: Partial<MeetingInsightsMeeting> = {},
): MeetingInsightsMeeting => ({
  recordingId: 1,
  title: null,
  startedAt: "2026-11-02T01:00:00Z",
  endedAt: "2026-11-02T02:00:00Z",
  recordingDurationSeconds: 3600,
  meetingSummary: null,
  viewCount: 0,
  segments: [],
  documents: [],
  ...overrides,
});

describe("calculateWorkspaceTrends", () => {
  const meetings = [
    createMeeting({
      recordingId: 1,
      documents: [
        createDocument({
          id: 1,
          folderName: "로그인",
          content: "## 결정\n- 가\n## 미결정\n- 나\n- 다",
          viewCount: 2,
          searchEvidenceCount: 1,
        }),
      ],
      viewCount: 3,
    }),
    createMeeting({
      recordingId: 2,
      startedAt: "2026-11-02T15:30:00Z",
      endedAt: "2026-11-02T16:00:00Z",
      recordingDurationSeconds: 1800,
      documents: [
        createDocument({
          id: 2,
          folderName: "로그인",
          content: "## 미결정\n- 라",
        }),
        createDocument({
          id: 3,
          folderName: "배포",
          content: "## 할 일\n- 마",
        }),
      ],
    }),
  ];

  it("회의마다 길이와 결과 개수를 시간 순서로 돌려준다", () => {
    const { points } = calculateWorkspaceTrends(meetings);

    expect(
      points.map(({ durationMinutes, decisionCount, documentCount }) => [
        durationMinutes,
        decisionCount,
        documentCount,
      ]),
    ).toEqual([
      [60, 1, 1],
      [30, 0, 2],
    ]);
  });

  it("미결정 항목을 회의 순서대로 쌓는다", () => {
    const { points } = calculateWorkspaceTrends(meetings);

    expect(points.map(({ undecidedTotal }) => undecidedTotal)).toEqual([2, 3]);
  });

  it("주마다 결정 없이 끝난 회의 비율을 계산한다", () => {
    const { weeks } = calculateWorkspaceTrends(meetings);

    expect(weeks).toEqual([
      { weekStart: "2026-11-02", meetings: 2, withoutDecision: 1, ratio: 0.5 },
    ]);
  });

  it("한국 시간 기준 날짜로 회의를 묶어 달력 값을 만든다", () => {
    const { calendar } = calculateWorkspaceTrends(meetings);

    expect(calendar).toEqual([
      { dateKey: "2026-11-02", count: 1, minutes: 60 },
      { dateKey: "2026-11-03", count: 1, minutes: 30 },
    ]);
  });

  it("요일과 시간대로 시작한 회의 수를 센다", () => {
    const { weekdayHours } = calculateWorkspaceTrends(meetings);

    expect(weekdayHours).toEqual([
      { weekday: 0, hour: 10, count: 1 },
      { weekday: 1, hour: 0, count: 1 },
    ]);
  });

  it("같은 폴더가 나온 회의 수를 많은 순서로 센다", () => {
    const { repeatedFolders } = calculateWorkspaceTrends(meetings);

    expect(repeatedFolders).toEqual([
      { folderName: "로그인", meetings: 2, documents: 2 },
      { folderName: "배포", meetings: 1, documents: 1 },
    ]);
  });

  it("회의마다 화자 번호 수를 센다", () => {
    const { points } = calculateWorkspaceTrends([
      createMeeting({
        segments: [
          { startMillis: 0, endMillis: 1000, speakerNumber: 1, text: "가" },
          { startMillis: 1000, endMillis: 2000, speakerNumber: 2, text: "나" },
          { startMillis: 2000, endMillis: 3000, speakerNumber: 1, text: "다" },
        ],
      }),
    ]);

    expect(points[0].speakerCount).toBe(2);
  });

  it("회의가 다시 쓰인 횟수를 합한다", () => {
    const { points } = calculateWorkspaceTrends(meetings);

    expect(points[0].viewTotal).toBe(5);
    expect(points[0].searchEvidenceTotal).toBe(1);
  });
});
