import { describe, expect, it } from "vitest";

import type { MeetingInsightsMeeting } from "../types/dataset";
import { calculateMeetingMetrics } from "./calculateMeetingMetrics";

const createMeeting = (
  overrides: Partial<MeetingInsightsMeeting> = {},
): MeetingInsightsMeeting => ({
  recordingId: 1,
  title: null,
  startedAt: "2026-11-02T01:00:00Z",
  endedAt: "2026-11-02T01:02:00Z",
  recordingDurationSeconds: 120,
  meetingSummary: null,
  viewCount: 0,
  segments: [],
  documents: [],
  ...overrides,
});

const createDocument = (id: number, content: string, confirmedCount = 0) => ({
  id,
  topic: `주제 ${id}`,
  folderName: "폴더",
  title: `문서 ${id}`,
  summary: null,
  content,
  createdAt: "2026-11-02T01:03:00Z",
  confirmedCount,
  searchEvidenceCount: 0,
  viewCount: 0,
});

describe("calculateMeetingMetrics", () => {
  it("겹친 구간은 한 번만 세어 말한 시간을 계산한다", () => {
    const { spokenSeconds, spokenRatio } = calculateMeetingMetrics(
      createMeeting({
        segments: [
          { startMillis: 0, endMillis: 30_000, speakerNumber: 1, text: "가" },
          {
            startMillis: 20_000,
            endMillis: 60_000,
            speakerNumber: 2,
            text: "나",
          },
        ],
      }),
    );

    expect(spokenSeconds).toBe(60);
    expect(spokenRatio).toBeCloseTo(0.5);
  });

  it("끝 시각이 없는 구간은 시간 계산에서 빼고 개수를 알려 준다", () => {
    const { spokenSeconds, missingEndCount } = calculateMeetingMetrics(
      createMeeting({
        segments: [
          { startMillis: 0, endMillis: 10_000, speakerNumber: 1, text: "가" },
          {
            startMillis: 20_000,
            endMillis: null,
            speakerNumber: 1,
            text: "나",
          },
        ],
      }),
    );

    expect(spokenSeconds).toBe(10);
    expect(missingEndCount).toBe(1);
  });

  it("문서 구역의 항목 수를 더하고 결정이 나온 주제 비율을 계산한다", () => {
    const { resultCounts, topicCount, decidedTopicCount, decidedTopicRatio } =
      calculateMeetingMetrics(
        createMeeting({
          documents: [
            createDocument(1, "## 결정\n- 가\n- 나\n## 할 일\n- 다"),
            createDocument(2, "## 미결정\n- 라\n## 보류\n- 마"),
          ],
        }),
      );

    expect(resultCounts).toEqual({
      decisions: 2,
      holds: 1,
      undecided: 1,
      todos: 1,
    });
    expect(topicCount).toBe(2);
    expect(decidedTopicCount).toBe(1);
    expect(decidedTopicRatio).toBeCloseTo(0.5);
  });

  it("구간의 글자를 시간에 맞춰 1분 칸에 나눠 담는다", () => {
    const { density } = calculateMeetingMetrics(
      createMeeting({
        segments: [
          {
            startMillis: 30_000,
            endMillis: 90_000,
            speakerNumber: 1,
            text: "가".repeat(60),
          },
        ],
      }),
    );

    expect(density).toEqual([
      { minute: 0, characters: 30 },
      { minute: 1, characters: 30 },
    ]);
  });

  it("15초 이상 말이 없던 구간을 긴 순서로 최대 5개 돌려준다", () => {
    const { longSilences } = calculateMeetingMetrics(
      createMeeting({
        segments: [
          { startMillis: 0, endMillis: 5_000, speakerNumber: 1, text: "가" },
          {
            startMillis: 10_000,
            endMillis: 15_000,
            speakerNumber: 1,
            text: "나",
          },
          {
            startMillis: 45_000,
            endMillis: 50_000,
            speakerNumber: 1,
            text: "다",
          },
        ],
      }),
    );

    expect(longSilences).toEqual([
      { startMillis: 15_000, endMillis: 45_000, seconds: 30 },
    ]);
  });

  it("물음표로 끝나는 5글자 이상 문장을 질문으로 센다", () => {
    const { questions } = calculateMeetingMetrics(
      createMeeting({
        segments: [
          {
            startMillis: 1_000,
            endMillis: 5_000,
            speakerNumber: 1,
            text: "네? 그럼 언제 배포할까요? 금요일이요.",
          },
        ],
      }),
    );

    expect(questions).toEqual([
      { startMillis: 1_000, speakerNumber: 1, text: "그럼 언제 배포할까요?" },
    ]);
  });

  it("날짜와 수치 언급을 센다", () => {
    const { dateMentionCount, numberMentionCount } = calculateMeetingMetrics(
      createMeeting({
        segments: [
          {
            startMillis: 0,
            endMillis: 1_000,
            speakerNumber: 1,
            text: "다음 주 금요일까지 3개를 끝내고 11월 5일에 20% 올려요",
          },
        ],
      }),
    );

    expect(dateMentionCount).toBe(3);
    expect(numberMentionCount).toBe(2);
  });

  it("조사를 떼고 자주 나온 말을 세며 말버릇은 뺀다", () => {
    const { frequentWords } = calculateMeetingMetrics(
      createMeeting({
        segments: [
          {
            startMillis: 0,
            endMillis: 1_000,
            speakerNumber: 1,
            text: "회의는 그냥 회의를 회의 문서가 문서를 그냥 합니다",
          },
        ],
      }),
    );

    expect(frequentWords.slice(0, 2)).toEqual([
      { word: "회의", count: 3 },
      { word: "문서", count: 2 },
    ]);
    expect(frequentWords.map(({ word }) => word)).not.toContain("그냥");
    expect(frequentWords.map(({ word }) => word)).not.toContain("합니다");
  });

  it("문서 확인 수를 합한다", () => {
    const { confirmedTotal } = calculateMeetingMetrics(
      createMeeting({
        documents: [createDocument(1, "", 2), createDocument(2, "", 3)],
      }),
    );

    expect(confirmedTotal).toBe(5);
  });
});
