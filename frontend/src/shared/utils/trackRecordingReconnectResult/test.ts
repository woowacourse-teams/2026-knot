import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { afterEach, describe, expect, it, vi } from "vitest";

import { trackRecordingReconnectResult } from ".";

vi.mock("@utils/captureAnalyticsEvent", () => ({
  captureAnalyticsEvent: vi.fn(),
}));

const session = { workspaceId: 3, recordingId: 7 };

describe("trackRecordingReconnectResult", () => {
  afterEach(() => {
    vi.mocked(captureAnalyticsEvent).mockClear();
  });

  it.each([
    { result: "reconnected", expected: "reconnected" },
    { result: "closed", expected: "closed" },
    { result: "takenOver", expected: "taken_over" },
    { result: "failed", expected: "failed" },
  ] as const)(
    "이어 받기 결과가 $result면 $expected와 시작한 곳, 녹음 id를 보낸다",
    ({ result, expected }) => {
      trackRecordingReconnectResult({
        ...session,
        result,
        trigger: "resume_button",
      });

      expect(captureAnalyticsEvent).toHaveBeenCalledWith({
        event: "recording_reconnect_result",
        properties: {
          result: expected,
          trigger: "resume_button",
          recording_id: 7,
          workspace_id: 3,
        },
      });
    },
  );

  it("끝내기 확인 창에서 이어 받았으면 end_dialog로 보낸다", () => {
    trackRecordingReconnectResult({
      ...session,
      result: "reconnected",
      trigger: "end_dialog",
    });

    expect(captureAnalyticsEvent).toHaveBeenCalledWith({
      event: "recording_reconnect_result",
      properties: expect.objectContaining({ trigger: "end_dialog" }),
    });
  });
});
