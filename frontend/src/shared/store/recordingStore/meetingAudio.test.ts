import { describe, expect, it, vi } from "vitest";

import { isMeetingAudioSupported } from "./meetingAudio";

const CHROME_UA =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36";
const EDGE_UA = `${CHROME_UA} Edg/155.0.0.0`;
const SAFARI_UA =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.6 Safari/605.1.15";
const FIREFOX_UA =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:150.0) Gecko/20100101 Firefox/150.0";

describe("회의 소리를 받을 수 있는 브라우저 판정", () => {
  it.each([
    ["Chrome", CHROME_UA],
    ["Edge", EDGE_UA],
  ])("%s는 공유 창이 있으면 받을 수 있다", (_, userAgent) => {
    vi.spyOn(navigator, "userAgent", "get").mockReturnValue(userAgent);

    expect(isMeetingAudioSupported()).toBe(true);
  });

  it.each([
    ["Safari", SAFARI_UA],
    ["Firefox", FIREFOX_UA],
  ])("%s는 공유 창이 있어도 회의 소리를 받을 수 없다", (_, userAgent) => {
    vi.spyOn(navigator, "userAgent", "get").mockReturnValue(userAgent);

    expect(isMeetingAudioSupported()).toBe(false);
  });

  it("공유 창이 없는 Chrome(휴대폰)은 받을 수 없다", () => {
    vi.spyOn(navigator, "userAgent", "get").mockReturnValue(CHROME_UA);
    const { getDisplayMedia } = navigator.mediaDevices;
    Object.assign(navigator.mediaDevices, { getDisplayMedia: undefined });

    try {
      expect(isMeetingAudioSupported()).toBe(false);
    } finally {
      Object.assign(navigator.mediaDevices, { getDisplayMedia });
    }
  });
});
