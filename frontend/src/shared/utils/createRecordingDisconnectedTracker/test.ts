import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { afterEach, describe, expect, it, vi } from "vitest";

import { createRecordingDisconnectedTracker } from ".";

vi.mock("@utils/captureAnalyticsEvent", () => ({
  captureAnalyticsEvent: vi.fn(),
}));

const DISCONNECTED_AT = new Date("2026-11-12T03:00:00.000Z").getTime();

const session = { workspaceId: 3, recordingId: 7 };

describe("createRecordingDisconnectedTracker", () => {
  afterEach(() => {
    vi.mocked(captureAnalyticsEvent).mockClear();
    vi.useRealTimers();
  });

  it.each([
    { outcome: "restored", expected: "restored" },
    { outcome: "recordingClosed", expected: "recording_closed" },
  ] as const)(
    "끊김이 $outcome으로 끝나면 $expected와 만든 뒤 끝날 때까지의 시간(0.1초 단위), 녹음 id를 보낸다",
    ({ outcome, expected }) => {
      vi.useFakeTimers({ toFake: ["Date"] });
      vi.setSystemTime(DISCONNECTED_AT);
      const trackDisconnected = createRecordingDisconnectedTracker(session);

      vi.setSystemTime(DISCONNECTED_AT + 12_340);
      trackDisconnected(outcome);

      expect(captureAnalyticsEvent).toHaveBeenCalledWith({
        event: "recording_disconnected",
        properties: {
          outcome: expected,
          disconnected_seconds: 12.3,
          recording_id: 7,
          workspace_id: 3,
        },
      });
    },
  );

  it("만들기만 하고 끊김이 끝나지 않으면 보내지 않는다", () => {
    createRecordingDisconnectedTracker(session);

    expect(captureAnalyticsEvent).not.toHaveBeenCalled();
  });

  it("끊김 하나는 한 번만 보낸다", () => {
    const trackDisconnected = createRecordingDisconnectedTracker(session);

    trackDisconnected("restored");
    trackDisconnected("recordingClosed");

    expect(captureAnalyticsEvent).toHaveBeenCalledTimes(1);
  });
});
