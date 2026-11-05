import type { MeetingInsightsMeeting } from "../types/dataset";
import { toKstDateParts } from "./toKstDateParts";

/** 회의를 고를 때 쓰는 이름. AI 제목이 없는 녹음은 첫 문서 제목을 써요 */
export const getMeetingLabel = ({
  title,
  startedAt,
  documents,
}: MeetingInsightsMeeting) => {
  const date = toKstDateParts(startedAt).dateKey.slice(5);
  const name = title ?? documents[0]?.title ?? "제목 없는 회의";

  return `${date} ${name}`;
};
