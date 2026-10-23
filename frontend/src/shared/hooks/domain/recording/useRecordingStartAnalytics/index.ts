import { useRecordingStore } from "@store/recordingStore";
import { readMicrophonePermission } from "@utils/readMicrophonePermission";
import { trackRecordingStartFailed } from "@utils/trackRecordingStartFailed";
import { useCallback } from "react";

type TrackRecordingStartFailedParams = Parameters<
  typeof trackRecordingStartFailed
>[0];

type StartFailure = TrackRecordingStartFailedParams["result"];

/** 안내 창을 띄우는 시작 결과. 안내 없이 끝나는 실패(`failed`)는 세지 않아요 */
const START_FAILURES: StartFailure[] = [
  "microphoneUnavailable",
  "refused",
  "networkFailed",
];

const isStartFailure = (result: string): result is StartFailure =>
  START_FAILURES.some((failure) => failure === result);

interface UseRecordingStartAnalyticsParams<StartResult extends string> {
  /** 녹음을 시작하고 결과를 돌려주는 함수. `useRecordingControl`의 시작이에요 */
  startRecording: (workspaceId: number) => Promise<StartResult>;
  /** 어디서 녹음을 눌렀는지. 부르는 화면이 정해요 */
  surface: TrackRecordingStartFailedParams["surface"];
}

/**
 * 녹음 시작 함수를 감싸, 안내 창을 띄우는 실패를 사용 기록(`recording_start_failed`)으로도 보내는 도메인 훅.
 *
 * 감싼 함수의 동작과 반환값은 그대로예요. 권한 창이 뜨기 전의 권한 상태를 읽기 시작하되 시작을 기다리게 하지 않고,
 * 사용 기록은 결과를 돌려준 뒤에 보내요.
 */
const useRecordingStartAnalytics = <StartResult extends string>({
  startRecording,
  surface,
}: UseRecordingStartAnalyticsParams<StartResult>) => {
  const startAndTrackRecording = useCallback(
    async (workspaceId: number) => {
      const permissionBefore = readMicrophonePermission();
      const result = await startRecording(workspaceId);

      if (isStartFailure(result)) {
        void trackRecordingStartFailed({
          result,
          errorName: useRecordingStore.getState().microphoneErrorName,
          permissionBefore,
          surface,
          workspaceId,
        });
      }

      return result;
    },
    [startRecording, surface],
  );

  return { startAndTrackRecording };
};

export default useRecordingStartAnalytics;
