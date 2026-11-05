export interface SummaryTopic {
  title: string;
  points: string[];
  /** 주제 끝의 「적용 방향」 또는 「정리」 문장 */
  closing: string | null;
}

export interface MeetingSummaryOutline {
  directions: string[];
  undecided: string[];
  topics: SummaryTopic[];
  conclusion: string | null;
}

const TOPIC_HEADING_PATTERN = /^##\s+\d+\.\s+(.+?)\s*$/;
const LIST_ITEM_PATTERN = /^\s*[-*]\s+(.+?)\s*$/;
const CLOSING_PATTERN = /^(?:적용 방향|정리):\s*(.+?)\s*$/;
const CONCLUSION_PATTERN = /^회의 핵심 결론:\s*(.+?)\s*$/;

const removeEmphasis = (text: string) => text.replace(/\*\*/g, "");

/**
 * 회의 전체 요약 마크다운을 그림으로 그릴 수 있게 나눠요.
 * 서버는 첫 줄과 마지막 줄만 검사해서, 형식이 어긋난 줄은 건너뛰어요.
 */
export const parseMeetingSummary = (content: string): MeetingSummaryOutline => {
  const outline: MeetingSummaryOutline = {
    directions: [],
    undecided: [],
    topics: [],
    conclusion: null,
  };
  let currentList: string[] | null = null;

  content.split("\n").forEach((rawLine) => {
    const line = removeEmphasis(rawLine);
    const conclusion = CONCLUSION_PATTERN.exec(line);
    const closing = CLOSING_PATTERN.exec(line);
    const topicHeading = TOPIC_HEADING_PATTERN.exec(line);
    const listItem = LIST_ITEM_PATTERN.exec(line);

    if (conclusion !== null) {
      outline.conclusion = conclusion[1];
    } else if (line.startsWith("### 정한 방향")) {
      currentList = outline.directions;
    } else if (line.startsWith("### 아직 결정하지 못한 것")) {
      currentList = outline.undecided;
    } else if (topicHeading !== null) {
      const topic = { title: topicHeading[1], points: [], closing: null };

      outline.topics.push(topic);
      currentList = topic.points;
    } else if (closing !== null && outline.topics.length > 0) {
      outline.topics[outline.topics.length - 1].closing = closing[1];
    } else if (listItem !== null) {
      currentList?.push(listItem[1]);
    }
  });

  return outline;
};
