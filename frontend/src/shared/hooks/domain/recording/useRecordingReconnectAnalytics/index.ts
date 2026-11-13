import { getRecordingResumeProof } from "@utils/recordingControlProof";
import { trackRecordingReconnectResult } from "@utils/trackRecordingReconnectResult";
import { useCallback } from "react";

type TrackRecordingReconnectResultParams = Parameters<
  typeof trackRecordingReconnectResult
>[0];

/** 새로고침 전 녹음을 이어 받고 결과를 돌려주는 함수. `useRecordingControl`의 이어 받기예요 */
type ReconnectRecording = () => Promise<
  TrackRecordingReconnectResultParams["result"]
>;

/**
 * 새로고침 전 녹음을 이어 받는 함수를 감싸, 이어 받은 결과를 사용 기록(`recording_reconnect_result`)으로도 보내는 도메인 훅.
 *
 * 감싼 함수의 동작과 반환값은 그대로예요. 이어 갈 수 없는 녹음이면 이어 받기가 증명을 지우므로, 녹음 id는 이어 받기 전에 증명에서 읽어 둬요.
 * 증명이 없으면 이어 받기 요청을 보내지 않아 기록하지 않아요. 어디서 이어 받기를 시작했는지는 부르는 쪽이 정해요.
 */
const useRecordingReconnectAnalytics = (
  reconnectRecording: ReconnectRecording,
) => {
  const reconnectAndTrackRecording = useCallback(
    async (trigger: TrackRecordingReconnectResultParams["trigger"]) => {
      const resumeProof = getRecordingResumeProof();
      const result = await reconnectRecording();

      if (resumeProof) {
        trackRecordingReconnectResult({
          workspaceId: resumeProof.workspaceId,
          recordingId: resumeProof.recordingId,
          result,
          trigger,
        });
      }

      return result;
    },
    [reconnectRecording],
  );

  return { reconnectAndTrackRecording };
};

export default useRecordingReconnectAnalytics;
