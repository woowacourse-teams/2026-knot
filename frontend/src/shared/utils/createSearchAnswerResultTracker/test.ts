import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { afterEach, describe, expect, it, vi } from "vitest";

import { createSearchAnswerResultTracker } from ".";

vi.mock("@utils/captureAnalyticsEvent", () => ({
  captureAnalyticsEvent: vi.fn(),
}));

const SENT_AT = new Date("2026-10-19T03:00:00.000Z").getTime();

describe("createSearchAnswerResultTracker", () => {
  afterEach(() => {
    vi.mocked(captureAnalyticsEvent).mockClear();
    vi.useRealTimers();
  });

  it.each([
    { outcome: "COMPLETED", result: "completed" },
    { outcome: "STOPPED", result: "stopped" },
    { outcome: "ACCEPT_FAILED", result: "accept_failed" },
    { outcome: "UNAVAILABLE", result: "unavailable" },
  ] as const)(
    "$outcome으로 끝나면 $result와 질문을 보낸 곳, 질문 글, 끝날 때의 답변 글, 만든 뒤 걸린 시간(0.1초 단위)을 보낸다",
    ({ outcome, result }) => {
      vi.useFakeTimers({ toFake: ["Date"] });
      vi.setSystemTime(SENT_AT);
      let answer = "";
      const trackAnswerResult = createSearchAnswerResultTracker({
        question: "지난주에 정한 DB는?",
        isRetry: true,
        isFirstQuestion: false,
        entry: "tutorial",
        getAnswer: () => answer,
        getFailureCode: () => "LLM_STREAM_FAILED",
      });

      answer = "PostgreSQL이에요";
      vi.setSystemTime(SENT_AT + 12_340);
      trackAnswerResult(outcome);

      expect(captureAnalyticsEvent).toHaveBeenCalledWith({
        event: "search_answer_result",
        properties: {
          result,
          is_retry: true,
          entry: "tutorial",
          is_first_question: false,
          answer_seconds: 12.3,
          question: "지난주에 정한 DB는?",
          answer: "PostgreSQL이에요",
        },
      });
      // 실패 코드는 실패했을 때만 있어요
      expect(
        vi.mocked(captureAnalyticsEvent).mock.calls[0][0].properties,
      ).not.toHaveProperty("failure_code");
    },
  );

  it("FAILED로 끝나면 failed와 끝날 때 읽은 서버의 실패 코드를 보낸다", () => {
    let failureCode: string | null = null;
    const trackAnswerResult = createSearchAnswerResultTracker({
      question: "지난주에 정한 DB는?",
      isRetry: false,
      isFirstQuestion: true,
      entry: "dock",
      getAnswer: () => "",
      getFailureCode: () => failureCode,
    });

    failureCode = "LLM_STREAM_FAILED";
    trackAnswerResult("FAILED");

    expect(captureAnalyticsEvent).toHaveBeenCalledWith({
      event: "search_answer_result",
      properties: expect.objectContaining({
        result: "failed",
        entry: "dock",
        failure_code: "LLM_STREAM_FAILED",
      }),
    });
  });

  it("FAILED로 끝났어도 서버가 실패 코드를 주지 않았으면 실패 코드를 빼고 보낸다", () => {
    const trackAnswerResult = createSearchAnswerResultTracker({
      question: "지난주에 정한 DB는?",
      isRetry: false,
      isFirstQuestion: true,
      entry: "dock",
      getAnswer: () => "",
      getFailureCode: () => null,
    });

    trackAnswerResult("FAILED");

    const [[{ properties }]] = vi.mocked(captureAnalyticsEvent).mock.calls;
    expect(properties).toMatchObject({ result: "failed" });
    expect(properties).not.toHaveProperty("failure_code");
  });

  it("만들기만 하고 끝나지 않으면 보내지 않는다", () => {
    createSearchAnswerResultTracker({
      question: "지난주에 정한 DB는?",
      isRetry: false,
      isFirstQuestion: true,
      entry: "dock",
      getAnswer: () => "",
      getFailureCode: () => null,
    });

    expect(captureAnalyticsEvent).not.toHaveBeenCalled();
  });
});
