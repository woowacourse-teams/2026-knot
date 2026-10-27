import { createHttpError, HTTP_ERROR_TYPE } from "@api/httpClient/error";
import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { afterEach, describe, expect, it, vi } from "vitest";

import { trackRecordingRetryResult } from ".";

vi.mock("@utils/captureAnalyticsEvent", () => ({
  captureAnalyticsEvent: vi.fn(),
}));

const conflict = createHttpError({ type: HTTP_ERROR_TYPE.conflict });
const unauthorized = createHttpError({ type: HTTP_ERROR_TYPE.unauthorized });

describe("trackRecordingRetryResult", () => {
  afterEach(() => {
    vi.mocked(captureAnalyticsEvent).mockClear();
  });

  it("다시 시도를 누른 곳 · 단계와 응답 결과를 recording_retry_result로 보낸다", () => {
    trackRecordingRetryResult({
      surface: "recording_history",
      stage: "document_generation",
      errors: [conflict, null],
      workspaceId: 3,
      recordingId: 7,
    });

    expect(captureAnalyticsEvent).toHaveBeenCalledWith({
      event: "recording_retry_result",
      properties: {
        surface: "recording_history",
        stage: "document_generation",
        result: "accepted",
        recording_id: 7,
        workspace_id: 3,
      },
    });
  });

  it.each([
    { reason: "로그인이 풀렸으면(401)", errors: [unauthorized] },
    { reason: "보낸 요청이 없으면", errors: [] },
  ])("$reason 보내지 않는다", ({ errors }) => {
    trackRecordingRetryResult({
      surface: "recording_document",
      stage: "meeting_summary",
      errors,
      workspaceId: 3,
      recordingId: 7,
    });

    expect(captureAnalyticsEvent).not.toHaveBeenCalled();
  });
});
