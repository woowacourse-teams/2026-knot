import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { isClosedRecordingError } from "@utils/isClosedRecordingError";

interface TrackRecordingEndOtherTabResultParams {
  /** 종료 요청의 오류. 끝냈으면 `null`이에요 */
  error: unknown;
  workspaceId: number;
  recordingId: number;
}

/**
 * @description 독의 녹음 칩으로 다른 탭의 녹음을 끝낸 결과를 사용 기록(`recording_end_other_tab_result`)으로 보내요
 * @param params - 종료 요청의 오류(끝냈으면 `null`), 그 녹음의 워크스페이스 · 녹음 id
 * @example
 * trackRecordingEndOtherTabResult({ error: null, workspaceId: 3, recordingId: 7 });
 *
 * 서버가 이미 끝났거나 버려진 녹음이라고 답하면 끝낸 것과 같아 `already_ended`, 그 밖의 오류는 `failed`로 보내요.
 */
export const trackRecordingEndOtherTabResult = ({
  error,
  workspaceId,
  recordingId,
}: TrackRecordingEndOtherTabResultParams) => {
  captureAnalyticsEvent({
    event: "recording_end_other_tab_result",
    properties: {
      result:
        error === null
          ? "ended"
          : isClosedRecordingError(error)
            ? "already_ended"
            : "failed",
      recording_id: recordingId,
      workspace_id: workspaceId,
    },
  });
};
