import { describe, expect, it } from "vitest";

import { formatTranscriptText } from "./formatTranscriptText";

describe("formatTranscriptText", () => {
  it("발화마다 시각, 참여자 번호, 문장을 한 줄로 적고 줄바꿈으로 잇는다", () => {
    expect(
      formatTranscriptText([
        { startMillis: 160000, speakerNumber: 1, text: "시작할게요." },
        { startMillis: 252000, speakerNumber: 2, text: "좋아요." },
      ]),
    ).toBe("02:40 참여자 1: 시작할게요.\n04:12 참여자 2: 좋아요.");
  });

  it("화자 번호가 없으면 미상으로 적는다", () => {
    expect(
      formatTranscriptText([
        { startMillis: 0, speakerNumber: null, text: "안녕하세요." },
      ]),
    ).toBe("00:00 미상: 안녕하세요.");
  });
});
