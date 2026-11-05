import type { MeetingInsightsMeeting } from "../types/dataset";

const MEANINGFUL_SECONDS = 600;

/** 처음 보여 줄 회의를 골라요. 요약이 있는 10분 이상 회의 중 가장 최근이고, 없으면 가장 최근 회의예요 */
export const pickDefaultMeeting = (meetings: MeetingInsightsMeeting[]) => {
  const latestFirst = [...meetings].reverse();

  return (
    latestFirst.find(
      ({ meetingSummary, recordingDurationSeconds }) =>
        meetingSummary !== null &&
        recordingDurationSeconds >= MEANINGFUL_SECONDS,
    ) ?? latestFirst[0]
  );
};
