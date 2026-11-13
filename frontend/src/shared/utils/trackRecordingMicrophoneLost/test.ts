import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { afterEach, describe, expect, it, vi } from "vitest";

import { trackRecordingMicrophoneLost } from ".";

vi.mock("@utils/captureAnalyticsEvent", () => ({
  captureAnalyticsEvent: vi.fn(),
}));

describe("trackRecordingMicrophoneLost", () => {
  afterEach(() => {
    vi.mocked(captureAnalyticsEvent).mockClear();
  });

  it.each([
    { elapsedMs: 0, expected: 0 },
    { elapsedMs: 7_249, expected: 7.2 },
    { elapsedMs: 754_250, expected: 754.3 },
  ])(
    "끊길 때까지 녹음한 시간 $elapsedMs ms를 소수 첫째 자리까지 반올림한 초 $expected로 녹음 · 워크스페이스 id와 함께 보낸다",
    ({ elapsedMs, expected }) => {
      trackRecordingMicrophoneLost({
        workspaceId: 3,
        recordingId: 7,
        elapsedMs,
      });

      expect(captureAnalyticsEvent).toHaveBeenCalledWith({
        event: "recording_microphone_lost",
        properties: {
          elapsed_seconds: expected,
          recording_id: 7,
          workspace_id: 3,
        },
      });
    },
  );
});
