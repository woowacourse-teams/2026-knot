import { describe, expect, it } from "vitest";

import { formatRecordingTitle } from "./formatRecordingTitle";

describe("formatRecordingTitle", () => {
  it("닉네임이 있으면 닉네임을 붙여 읽는다", () => {
    expect(formatRecordingTitle("노티드")).toBe("노티드 님의 녹음");
  });

  it("닉네임이 없으면 녹음으로 읽는다", () => {
    expect(formatRecordingTitle(undefined)).toBe("녹음");
  });

  it("닉네임이 빈 문자열이면 녹음으로 읽는다", () => {
    expect(formatRecordingTitle("")).toBe("녹음");
  });
});
