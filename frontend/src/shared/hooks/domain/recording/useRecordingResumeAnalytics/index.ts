import { useRecordingStore } from "@store/recordingStore";
import { readMicrophonePermission } from "@utils/readMicrophonePermission";
import { trackRecordingStartFailed } from "@utils/trackRecordingStartFailed";
import { useCallback } from "react";

interface UseRecordingResumeAnalyticsParams {
  /** 녹음을 이어 가고 이어 갔는지 돌려주는 함수. `useRecordingControl`의 이어서 녹음이에요 */
  resumeRecording: () => Promise<boolean>;
  /** 어디서 「이어서 녹음」을 눌렀는지. 부르는 화면이 정해요 */
  surface: Parameters<typeof trackRecordingStartFailed>[0]["surface"];
}

/**
 * 이어서 녹음 함수를 감싸, 마이크를 다시 받지 못한 실패를 사용 기록(`recording_start_failed`)으로도 보내는 도메인 훅.
 *
 * 감싼 함수의 동작과 반환값은 그대로예요. 이어 가지 못한 이유가 마이크가 아니라 녹음을 끝내는 중이거나 이미 비운 녹음이면
 * 마이크를 받으려 하지 않은 것이라 세지 않아요.
 */
const useRecordingResumeAnalytics = ({
  resumeRecording,
  surface,
}: UseRecordingResumeAnalyticsParams) => {
  const resumeAndTrackRecording = useCallback(async () => {
    const permissionBefore = readMicrophonePermission();
    const { session } = useRecordingStore.getState();
    const isResumed = await resumeRecording();
    const {
      session: currentSession,
      isEnding,
      microphoneErrorName,
    } = useRecordingStore.getState();

    if (!isResumed && session && currentSession && !isEnding) {
      void trackRecordingStartFailed({
        result: "microphoneUnavailable",
        errorName: microphoneErrorName,
        permissionBefore,
        surface,
        workspaceId: session.workspaceId,
      });
    }

    return isResumed;
  }, [resumeRecording, surface]);

  return { resumeAndTrackRecording };
};

export default useRecordingResumeAnalytics;
