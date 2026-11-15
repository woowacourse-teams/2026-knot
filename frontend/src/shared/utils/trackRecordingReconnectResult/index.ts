import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";

interface TrackRecordingReconnectResultParams {
  workspaceId: number;
  recordingId: number;
  /** 이어 받기의 결과. `useRecordingControl`의 `reconnectRecording`이 돌려주는 값이에요 */
  result: "reconnected" | "closed" | "takenOver" | "failed";
  /** 이어 받기를 시작한 곳. 녹음 바의 「이어서 녹음」 또는 끝내기 확인 창이에요 */
  trigger: "resume_button" | "end_dialog";
}

const RESULT_BY_RECONNECT_RESULT = {
  reconnected: "reconnected",
  closed: "closed",
  takenOver: "taken_over",
  failed: "failed",
} as const;

/**
 * @description 새로고침 전 녹음을 이어 받으려 한 결과를 사용 기록(`recording_reconnect_result`)으로 보내요
 * @param params - 이어 받으려 한 녹음의 워크스페이스 · 녹음 id, 이어 받기 결과, 시작한 곳
 * @example
 * trackRecordingReconnectResult({ workspaceId: 3, recordingId: 7, result: "takenOver", trigger: "resume_button" });
 */
export const trackRecordingReconnectResult = ({
  workspaceId,
  recordingId,
  result,
  trigger,
}: TrackRecordingReconnectResultParams) =>
  captureAnalyticsEvent({
    event: "recording_reconnect_result",
    properties: {
      result: RESULT_BY_RECONNECT_RESULT[result],
      trigger,
      recording_id: recordingId,
      workspace_id: workspaceId,
    },
  });
