import { createHttpError, HTTP_ERROR_TYPE } from "@api/httpClient/error";
import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { afterEach, describe, expect, it, vi } from "vitest";

import { trackRecordingEndOtherTabResult } from ".";

vi.mock("@utils/captureAnalyticsEvent", () => ({
  captureAnalyticsEvent: vi.fn(),
}));

const recording = { workspaceId: 3, recordingId: 7 };

describe("trackRecordingEndOtherTabResult", () => {
  afterEach(() => {
    vi.mocked(captureAnalyticsEvent).mockClear();
  });

  it.each([
    { reason: "종료 요청이 성공하면", error: null, result: "ended" },
    {
      reason: "이미 끝난 녹음이라고 거절되면",
      error: createHttpError({
        type: HTTP_ERROR_TYPE.conflict,
        code: "RECORDING_ALREADY_ENDED",
      }),
      result: "already_ended",
    },
    {
      reason: "이미 버려진 녹음이라고 거절되면",
      error: createHttpError({
        type: HTTP_ERROR_TYPE.conflict,
        code: "RECORDING_ALREADY_DISCARDED",
      }),
      result: "already_ended",
    },
    {
      reason: "서버 오류면",
      error: createHttpError({ type: HTTP_ERROR_TYPE.serverError }),
      result: "failed",
    },
    {
      reason: "응답을 받지 못하면",
      error: createHttpError({ type: HTTP_ERROR_TYPE.network }),
      result: "failed",
    },
  ])(
    "$reason $result와 녹음 · 워크스페이스 id를 보낸다",
    ({ error, result }) => {
      trackRecordingEndOtherTabResult({ ...recording, error });

      expect(captureAnalyticsEvent).toHaveBeenCalledWith({
        event: "recording_end_other_tab_result",
        properties: { result, recording_id: 7, workspace_id: 3 },
      });
    },
  );
});
