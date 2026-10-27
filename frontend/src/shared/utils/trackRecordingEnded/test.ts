import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { afterEach, describe, expect, it, vi } from "vitest";

import { trackRecordingEnded } from ".";

vi.mock("@utils/captureAnalyticsEvent", () => ({
  captureAnalyticsEvent: vi.fn(),
}));

const session = {
  workspaceId: 3,
  recordingId: 7,
  audioMode: "PCM16_CHUNKS_V1",
} as const;

const microphoneOnly = {
  meetingAudioStatus: "off",
  isMeetingAudioSupported: true,
} as const;

describe("trackRecordingEnded", () => {
  afterEach(() => {
    vi.mocked(captureAnalyticsEvent).mockClear();
  });

  it.each([
    { result: "uploaded", expected: "uploaded" },
    { result: "notUploaded", expected: "not_uploaded" },
  ] as const)(
    "끝낸 결과가 $result면 $expected와 녹음 방식 · id를 보낸다",
    ({ result, expected }) => {
      trackRecordingEnded({ ...session, ...microphoneOnly, result });

      expect(captureAnalyticsEvent).toHaveBeenCalledWith({
        event: "recording_ended",
        properties: {
          result: expected,
          audio_mode: "PCM16_CHUNKS_V1",
          meeting_audio: "off",
          recording_id: 7,
          workspace_id: 3,
        },
      });
    },
  );

  it.each([
    { status: "on", isSupported: true, expected: "on" },
    { status: "stopped", isSupported: true, expected: "stopped" },
    { status: "noAudio", isSupported: true, expected: "no_audio" },
    { status: "off", isSupported: true, expected: "off" },
    { status: "off", isSupported: false, expected: "unsupported" },
  ] as const)(
    "회의 소리 상태가 $status이고 받을 수 있는 브라우저 여부가 $isSupported면 meeting_audio를 $expected로 보낸다",
    ({ status, isSupported, expected }) => {
      trackRecordingEnded({
        ...session,
        meetingAudioStatus: status,
        isMeetingAudioSupported: isSupported,
        result: "uploaded",
      });

      expect(captureAnalyticsEvent).toHaveBeenCalledWith({
        event: "recording_ended",
        properties: expect.objectContaining({ meeting_audio: expected }),
      });
    },
  );

  it("끝내지 못했으면(notEnded) 보내지 않는다", () => {
    trackRecordingEnded({ ...session, ...microphoneOnly, result: "notEnded" });

    expect(captureAnalyticsEvent).not.toHaveBeenCalled();
  });
});
