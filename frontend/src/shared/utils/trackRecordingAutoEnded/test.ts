import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { afterEach, describe, expect, it, vi } from "vitest";

import { trackRecordingAutoEnded } from ".";

vi.mock("@utils/captureAnalyticsEvent", () => ({
  captureAnalyticsEvent: vi.fn(),
}));

const session = {
  workspaceId: 3,
  recordingId: 7,
  audioMode: "PCM16_CHUNKS_V1",
} as const;

describe("trackRecordingAutoEnded", () => {
  afterEach(() => {
    vi.mocked(captureAnalyticsEvent).mockClear();
  });

  it.each([
    { reason: "connectionExpired", expected: "connection_expired" },
    { reason: "maxDuration", expected: "max_duration" },
    { reason: "endedByServer", expected: "ended_by_server" },
    { reason: "discarded", expected: "discarded" },
    { reason: "unreachable", expected: "unreachable" },
    { reason: "controlLost", expected: "control_lost" },
  ] as const)(
    "서버가 끝낸 이유가 $reason면 $expected와 음성을 넘겼는지, 녹음 방식 · id를 보낸다",
    ({ reason, expected }) => {
      trackRecordingAutoEnded({ ...session, reason, isUploaded: true });

      expect(captureAnalyticsEvent).toHaveBeenCalledWith({
        event: "recording_auto_ended",
        properties: {
          reason: expected,
          is_uploaded: true,
          audio_mode: "PCM16_CHUNKS_V1",
          recording_id: 7,
          workspace_id: 3,
        },
      });
    },
  );

  it("음성을 넘기지 못했으면 is_uploaded를 false로 보낸다", () => {
    trackRecordingAutoEnded({
      ...session,
      reason: "discarded",
      isUploaded: false,
    });

    expect(captureAnalyticsEvent).toHaveBeenCalledWith({
      event: "recording_auto_ended",
      properties: expect.objectContaining({ is_uploaded: false }),
    });
  });

  it("사용자가 끝낸 녹음(userEnded)은 recording_ended가 맡아 보내지 않는다", () => {
    trackRecordingAutoEnded({
      ...session,
      reason: "userEnded",
      isUploaded: true,
    });

    expect(captureAnalyticsEvent).not.toHaveBeenCalled();
  });
});
