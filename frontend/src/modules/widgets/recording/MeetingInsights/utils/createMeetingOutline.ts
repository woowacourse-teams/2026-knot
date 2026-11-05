import type { MeetingInsightsMeeting } from "../types/dataset";
import { parseDocumentSections } from "./parseDocumentSections";
import {
  parseMeetingSummary,
  type MeetingSummaryOutline,
} from "./parseMeetingSummary";

export interface MeetingOutline extends MeetingSummaryOutline {
  /** 회의 전체 요약에서 만들었으면 `summary`, 주제별 문서로 대신했으면 `documents` */
  source: "summary" | "documents";
}

/**
 * 회의의 뼈대를 만들어요. 회의 전체 요약은 10-27 뒤의 녹음에 있어서,
 * 요약이 없는 회의는 주제별 문서의 제목과 구역으로 같은 모양을 만들어요.
 */
export const createMeetingOutline = ({
  meetingSummary,
  documents,
}: Pick<
  MeetingInsightsMeeting,
  "meetingSummary" | "documents"
>): MeetingOutline => {
  if (meetingSummary !== null) {
    const outline = parseMeetingSummary(meetingSummary);

    if (outline.topics.length > 0) return { ...outline, source: "summary" };
  }

  const sections = documents.map(({ title, content }) => ({
    title,
    ...parseDocumentSections(content),
  }));

  return {
    directions: sections.flatMap(({ decisions }) => decisions),
    undecided: sections.flatMap(({ undecided }) => undecided),
    topics: sections.map(({ title, decisions, todos }) => ({
      title,
      points: decisions.length > 0 ? decisions : todos,
      closing: null,
    })),
    conclusion: null,
    source: "documents",
  };
};
