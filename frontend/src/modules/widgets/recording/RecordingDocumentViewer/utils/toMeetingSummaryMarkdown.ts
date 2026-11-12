/** 회의 전체 요약의 핵심 결론 줄이 시작하는 말. */
const CONCLUSION_LABEL = "회의 핵심 결론:";

/** 시안이 회의 핵심 결론 위에 두는 구역 제목 */
const CONCLUSION_HEADING = "## 회의 핵심 결론";

/**
 * `회의 핵심 결론: 문장`을 구역 제목과 그 아래 문단으로 바꿔요.
 *
 * 시안(Doc/MeetingSummary)은 회의 핵심 결론을 다른 구역처럼 제목과 문장으로 나눠 그려요.
 * 새 양식은 전체 정리와 한눈에 보기 사이, 이전 양식은 마지막 줄의 결론을 표시해요.
 * 나머지 줄은 그대로 두고, 결론 문장이 비어 있으면 본문을 바꾸지 않아요.
 *
 * @example
 * toMeetingSummaryMarkdown("## 한눈에 보기\n\n회의 핵심 결론: 채널은 데이터를 보고 정한다.");
 * // "## 한눈에 보기\n\n## 회의 핵심 결론\n\n채널은 데이터를 보고 정한다."
 */
export const toMeetingSummaryMarkdown = (content: string) => {
  const lines = content.split("\n");
  const overviewIndex = lines.indexOf("## 한눈에 보기");
  const conclusionIndex = lines[0] === "## 전체 정리" && overviewIndex > 0
    ? lines.findIndex((line, index) =>
        line.startsWith(CONCLUSION_LABEL) && index < overviewIndex &&
        lines.slice(index + 1, overviewIndex).every((next) => next.trim() === ""),
      )
    : lines.length - 1;
  const conclusionLine = lines[conclusionIndex];

  if (!conclusionLine?.startsWith(CONCLUSION_LABEL)) return content;

  const conclusion = conclusionLine.slice(CONCLUSION_LABEL.length).trim();
  if (conclusion === "") return content;

  return [
    ...lines.slice(0, conclusionIndex),
    CONCLUSION_HEADING,
    "",
    conclusion,
    ...lines.slice(conclusionIndex + 1),
  ].join("\n");
};
