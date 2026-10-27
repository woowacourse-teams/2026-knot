import posthog from "posthog-js";
import { afterEach, describe, expect, it, vi } from "vitest";

import { captureAnalyticsEvent } from ".";

vi.mock("posthog-js", () => ({
  default: { capture: vi.fn() },
}));

describe("captureAnalyticsEvent", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.resetAllMocks();
  });

  it("PostHog 키가 있으면 이벤트 이름과 속성을 그대로 PostHog로 보낸다", () => {
    vi.stubEnv("POSTHOG_KEY", "phc_test");

    captureAnalyticsEvent({
      event: "recording_ended",
      properties: {
        result: "uploaded",
        audio_mode: "PCM16_CHUNKS_V1",
        meeting_audio: "off",
        recording_id: 7,
        workspace_id: 3,
      },
    });

    expect(posthog.capture).toHaveBeenCalledTimes(1);
    expect(posthog.capture).toHaveBeenCalledWith("recording_ended", {
      result: "uploaded",
      audio_mode: "PCM16_CHUNKS_V1",
      meeting_audio: "off",
      recording_id: 7,
      workspace_id: 3,
    });
  });

  it("PostHog 키가 없으면(로컬 · 테스트) 보내지 않는다", () => {
    vi.stubEnv("POSTHOG_KEY", "");

    captureAnalyticsEvent({
      event: "search_answer_result",
      properties: {
        result: "completed",
        is_retry: false,
        is_first_question: true,
        answer_seconds: 3.2,
        question: "지난주에 정한 DB는?",
        answer: "PostgreSQL이에요",
      },
    });

    expect(posthog.capture).not.toHaveBeenCalled();
  });
});
