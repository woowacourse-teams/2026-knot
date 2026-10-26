import { describe, expect, it } from "vitest";

import { toMeetingSummaryMarkdown } from "./toMeetingSummaryMarkdown";

describe("toMeetingSummaryMarkdown", () => {
  it("마지막 줄의 회의 핵심 결론을 구역 제목과 그 아래 문단으로 나눈다", () => {
    const content = [
      "## 한눈에 보기",
      "",
      "- 채널은 다음 회의에서 정한다.",
      "",
      "회의 핵심 결론: 알림은 밤에 모아 보낸다.",
    ].join("\n");

    expect(toMeetingSummaryMarkdown(content)).toBe(
      [
        "## 한눈에 보기",
        "",
        "- 채널은 다음 회의에서 정한다.",
        "",
        "## 회의 핵심 결론",
        "",
        "알림은 밤에 모아 보낸다.",
      ].join("\n"),
    );
  });

  it("마지막 줄이 회의 핵심 결론이 아니면 본문을 그대로 돌려준다", () => {
    const content = "## 한눈에 보기\n\n정리: 채널은 데이터를 보고 정한다.";

    expect(toMeetingSummaryMarkdown(content)).toBe(content);
  });

  it("결론 문장이 비어 있으면 본문을 그대로 돌려준다", () => {
    const content = "## 한눈에 보기\n\n회의 핵심 결론:   ";

    expect(toMeetingSummaryMarkdown(content)).toBe(content);
  });

  it("본문 중간의 회의 핵심 결론 줄은 바꾸지 않는다", () => {
    const content =
      "회의 핵심 결론: 앞줄은 그대로 둔다.\n\n## 1. 알림\n\n정리: 끝.";

    expect(toMeetingSummaryMarkdown(content)).toBe(content);
  });
});
