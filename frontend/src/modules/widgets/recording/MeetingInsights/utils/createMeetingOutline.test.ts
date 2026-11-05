import { describe, expect, it } from "vitest";

import { createMeetingOutline } from "./createMeetingOutline";

const document = {
  id: 1,
  topic: "주제",
  folderName: "폴더",
  title: "로그인 방식",
  summary: null,
  content: "## 결정\n- 구글로 한다\n## 미결정\n- 카카오 시기",
  createdAt: "2026-11-02T01:03:00Z",
  confirmedCount: 0,
  searchEvidenceCount: 0,
  viewCount: 0,
};

describe("createMeetingOutline", () => {
  it("회의 전체 요약이 있으면 요약으로 만든다", () => {
    const outline = createMeetingOutline({
      meetingSummary:
        "## 한눈에 보기\n### 정한 방향\n- 가\n## 1. 첫 주제\n- 나",
      documents: [document],
    });

    expect(outline.source).toBe("summary");
    expect(outline.topics[0].title).toBe("첫 주제");
  });

  it("요약이 없으면 주제별 문서의 구역으로 만든다", () => {
    const outline = createMeetingOutline({
      meetingSummary: null,
      documents: [document],
    });

    expect(outline).toEqual({
      directions: ["구글로 한다"],
      undecided: ["카카오 시기"],
      topics: [
        { title: "로그인 방식", points: ["구글로 한다"], closing: null },
      ],
      conclusion: null,
      source: "documents",
    });
  });

  it("요약의 형식이 달라 주제를 찾지 못하면 문서로 대신한다", () => {
    const outline = createMeetingOutline({
      meetingSummary: "아무 글",
      documents: [document],
    });

    expect(outline.source).toBe("documents");
  });
});
