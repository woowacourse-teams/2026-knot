import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";

interface TrackRecordingMicrophoneLostParams {
  workspaceId: number;
  recordingId: number;
  /** 끊길 때까지 녹음한 시간(ms). 일시정지한 동안은 빠져요 */
  elapsedMs: number;
}

/**
 * @description 녹음 중 마이크가 끊겨 안내 창을 띄운 것을 사용 기록(`recording_microphone_lost`)으로 보내요
 * @param params - 녹음 세션의 워크스페이스 · 녹음 id, 끊길 때까지 녹음한 시간(ms)
 * @example
 * trackRecordingMicrophoneLost({ workspaceId: 3, recordingId: 7, elapsedMs: 7_240 });
 *
 * 녹음한 시간은 초로 바꿔 소수 첫째 자리까지 반올림해요. 같은 끊김을 한 번만 보내는 일은 부르는 쪽이 맡아요.
 */
export const trackRecordingMicrophoneLost = ({
  workspaceId,
  recordingId,
  elapsedMs,
}: TrackRecordingMicrophoneLostParams) => {
  captureAnalyticsEvent({
    event: "recording_microphone_lost",
    properties: {
      elapsed_seconds: Math.round(elapsedMs / 100) / 10,
      recording_id: recordingId,
      workspace_id: workspaceId,
    },
  });
};
