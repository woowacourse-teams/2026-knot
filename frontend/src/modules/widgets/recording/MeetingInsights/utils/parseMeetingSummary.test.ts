import { describe, expect, it } from "vitest";

import { parseMeetingSummary } from "./parseMeetingSummary";

const SUMMARY = [
  "## 한눈에 보기",
  "",
  "### 정한 방향",
  "- 첫 화면은 **요약**으로 간다",
  "",
  "### 아직 결정하지 못한 것",
  "- 알림 채널",
  "",
  "## 1. 첫 화면",
  "- 요약을 맨 위에 둔다",
  "- 문서는 아래에 둔다",
  "적용 방향: 다음 배포에 넣는다",
  "",
  "## 2. 알림",
  "- 메일과 푸시를 비교했다",
  "정리: 다음 회의에서 정한다",
  "",
  "회의 핵심 결론: 첫 화면을 요약으로 바꾼다",
].join("\n");

describe("parseMeetingSummary", () => {
  it("정한 방향과 아직 결정하지 못한 것을 나눈다", () => {
    const { directions, undecided } = parseMeetingSummary(SUMMARY);

    expect(directions).toEqual(["첫 화면은 요약으로 간다"]);
    expect(undecided).toEqual(["알림 채널"]);
  });

  it("주제마다 불릿과 맺음말을 묶는다", () => {
    expect(parseMeetingSummary(SUMMARY).topics).toEqual([
      {
        title: "첫 화면",
        points: ["요약을 맨 위에 둔다", "문서는 아래에 둔다"],
        closing: "다음 배포에 넣는다",
      },
      {
        title: "알림",
        points: ["메일과 푸시를 비교했다"],
        closing: "다음 회의에서 정한다",
      },
    ]);
  });

  it("회의 핵심 결론을 꺼낸다", () => {
    expect(parseMeetingSummary(SUMMARY).conclusion).toBe(
      "첫 화면을 요약으로 바꾼다",
    );
  });

  it("형식이 달라도 빈 값으로 돌려준다", () => {
    expect(parseMeetingSummary("아무 글")).toEqual({
      directions: [],
      undecided: [],
      topics: [],
      conclusion: null,
    });
  });
});
