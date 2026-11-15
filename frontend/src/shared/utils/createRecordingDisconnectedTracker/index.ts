import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";

interface CreateRecordingDisconnectedTrackerParams {
  workspaceId: number;
  recordingId: number;
}

/** 끊김이 끝난 방식. 연결이 돌아와 녹음이 이어짐(`restored`) · 돌아오지 못하고 녹음이 끝남(`recordingClosed`)이에요 */
type RecordingDisconnectedOutcome = "restored" | "recordingClosed";

const OUTCOME_BY_DISCONNECTED_OUTCOME = {
  restored: "restored",
  recordingClosed: "recording_closed",
} as const;

/**
 * @description 녹음 중 연결이 끊길 때 만들어 두고, 끊김이 끝나면 끝난 방식만 넘겨 사용 기록(`recording_disconnected`)을 보내는 함수를 돌려줘요
 * @param params - 끊긴 녹음의 워크스페이스 · 녹음 id
 * @returns 끊김이 끝난 방식을 받아 보내는 함수. 끊김 하나는 한 번만 보내요
 * @example
 * const trackDisconnected = createRecordingDisconnectedTracker({ workspaceId: 3, recordingId: 7 });
 * trackDisconnected("restored");
 *
 * 끊긴 시간은 만든 때(끊긴 때)부터 보낼 때까지를 0.1초 단위로 반올림해요.
 */
export const createRecordingDisconnectedTracker = ({
  workspaceId,
  recordingId,
}: CreateRecordingDisconnectedTrackerParams) => {
  const disconnectedAt = Date.now();
  let isSent = false;

  return (outcome: RecordingDisconnectedOutcome) => {
    if (isSent) return;
    isSent = true;

    captureAnalyticsEvent({
      event: "recording_disconnected",
      properties: {
        outcome: OUTCOME_BY_DISCONNECTED_OUTCOME[outcome],
        disconnected_seconds:
          Math.round((Date.now() - disconnectedAt) / 100) / 10,
        recording_id: recordingId,
        workspace_id: workspaceId,
      },
    });
  };
};
