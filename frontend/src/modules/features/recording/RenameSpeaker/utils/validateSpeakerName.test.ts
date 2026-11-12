import { describe, it, expect } from "vitest";
import { validateSpeakerName } from "./validateSpeakerName";
describe("화자 이름", () => {
  it("공백과 Unicode 코드 포인트 경계를 검증한다", () => {
    expect(validateSpeakerName("　루덴스 ")).toBeNull();
    expect(validateSpeakerName("😀".repeat(50))).toBeNull();
    for (const invalid of [" ", "😀".repeat(51), "a\nb", "a\tb", "a\u2028b"]) {
      expect(validateSpeakerName(invalid)).not.toBeNull();
    }
  });
});
