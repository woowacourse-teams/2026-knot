/** 회의 전체 요약 본문의 마지막 줄이 시작하는 말. 백엔드 요약 양식이 정한 문구예요 */
const CONCLUSION_LABEL = "회의 핵심 결론:";

/** 시안이 회의 핵심 결론 위에 두는 구역 제목 */
const CONCLUSION_HEADING = "## 회의 핵심 결론";

/**
 * 회의 전체 요약 본문의 마지막 줄 `회의 핵심 결론: 문장`을 `## 회의 핵심 결론` 제목과 그 아래 문단으로 바꿔요.
 *
 * 시안(Doc/MeetingSummary)은 회의 핵심 결론을 다른 구역처럼 제목과 문장으로 나눠 그려요.
 * 나머지 줄은 그대로 두고, 마지막 줄이 이 모양이 아니거나 결론 문장이 비어 있으면 본문을 바꾸지 않아요.
 *
 * @example
 * toMeetingSummaryMarkdown("## 한눈에 보기\n\n회의 핵심 결론: 채널은 데이터를 보고 정한다.");
 * // "## 한눈에 보기\n\n## 회의 핵심 결론\n\n채널은 데이터를 보고 정한다."
 */
export const toMeetingSummaryMarkdown = (content: string) => {
  const lines = content.split("\n");
  const lastIndex = lines.length - 1;
  const lastLine = lines[lastIndex];

  if (!lastLine.startsWith(CONCLUSION_LABEL)) return content;

  const conclusion = lastLine.slice(CONCLUSION_LABEL.length).trim();
  if (conclusion === "") return content;

  return [
    ...lines.slice(0, lastIndex),
    CONCLUSION_HEADING,
    "",
    conclusion,
  ].join("\n");
};
