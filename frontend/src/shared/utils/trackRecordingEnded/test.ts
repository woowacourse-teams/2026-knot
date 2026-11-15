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

/** 녹음 바에서 12분 34.2초 녹음하고 끝냈어요 */
const ending = { elapsedMs: 754_240, surface: "recorder_bar" } as const;

describe("trackRecordingEnded", () => {
  afterEach(() => {
    vi.mocked(captureAnalyticsEvent).mockClear();
  });

  it.each([
    { result: "uploaded", expected: "uploaded" },
    { result: "notUploaded", expected: "not_uploaded" },
  ] as const)(
    "끝낸 결과가 $result면 $expected와 녹음 방식 · 녹음한 시간 · 끝낸 곳 · id를 보낸다",
    ({ result, expected }) => {
      trackRecordingEnded({ ...session, ...microphoneOnly, ...ending, result });

      expect(captureAnalyticsEvent).toHaveBeenCalledWith({
        event: "recording_ended",
        properties: {
          result: expected,
          audio_mode: "PCM16_CHUNKS_V1",
          meeting_audio: "off",
          duration_seconds: 754.2,
          surface: "recorder_bar",
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
        ...ending,
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

  it.each([
    { elapsedMs: 0, expected: 0 },
    { elapsedMs: 1_049, expected: 1 },
    { elapsedMs: 754_250, expected: 754.3 },
  ])(
    "녹음한 시간 $elapsedMs ms는 소수 첫째 자리까지 반올림한 초 $expected로 보낸다",
    ({ elapsedMs, expected }) => {
      trackRecordingEnded({
        ...session,
        ...microphoneOnly,
        ...ending,
        elapsedMs,
        result: "uploaded",
      });

      expect(captureAnalyticsEvent).toHaveBeenCalledWith({
        event: "recording_ended",
        properties: expect.objectContaining({ duration_seconds: expected }),
      });
    },
  );

  it.each(["recorder_bar", "dock", "logout", "workspace_move"] as const)(
    "끝낸 곳 %s를 그대로 보낸다",
    (surface) => {
      trackRecordingEnded({
        ...session,
        ...microphoneOnly,
        ...ending,
        surface,
        result: "uploaded",
      });

      expect(captureAnalyticsEvent).toHaveBeenCalledWith({
        event: "recording_ended",
        properties: expect.objectContaining({ surface }),
      });
    },
  );

  it("끝내지 못했으면(notEnded) 보내지 않는다", () => {
    trackRecordingEnded({
      ...session,
      ...microphoneOnly,
      ...ending,
      result: "notEnded",
    });

    expect(captureAnalyticsEvent).not.toHaveBeenCalled();
  });
});
