import { useRecordingStore } from "@store/recordingStore";
import { isMeetingAudioSupported } from "@store/recordingStore/meetingAudio";
import { trackRecordingEnded } from "@utils/trackRecordingEnded";
import { useCallback } from "react";

type TrackRecordingEndedParams = Parameters<typeof trackRecordingEnded>[0];

/** 녹음을 끝내고 결과를 돌려주는 함수. `useRecordingControl`의 끝내기예요 */
type EndRecording = () => Promise<TrackRecordingEndedParams["result"]>;

/**
 * 녹음을 끝내는 함수를 감싸, 끝낸 결과를 사용 기록(`recording_ended`)으로도 보내는 도메인 훅.
 *
 * 끝내기의 결과는 상태에 남지 않고 함수의 반환값으로만 나와서, 지켜보지 않고 감싸요. 감싼 함수의 동작과 반환값은 그대로예요.
 * 끝낸 곳(`surface`)은 끝내기를 부른 화면이 넘겨요.
 * 끝내는 동안 녹음 세션과 회의 소리 상태, 녹음한 시간이 비워지므로 끝내기 전에 읽어 둬요.
 * 녹음한 시간은 화면의 시간 표시와 같은 저장소 값으로 재서 일시정지한 동안은 빠져요.
 */
const useRecordingEndedAnalytics = (endRecording: EndRecording) => {
  const endAndTrackRecording = useCallback(
    async (surface: TrackRecordingEndedParams["surface"]) => {
      const { session, meetingAudioStatus, accumulatedMs, resumedAt } =
        useRecordingStore.getState();
      const elapsedMs =
        accumulatedMs +
        (resumedAt === null ? 0 : Math.max(0, Date.now() - resumedAt));
      const result = await endRecording();

      if (session) {
        trackRecordingEnded({
          ...session,
          meetingAudioStatus,
          isMeetingAudioSupported: isMeetingAudioSupported(),
          elapsedMs,
          surface,
          result,
        });
      }

      return result;
    },
    [endRecording],
  );

  return { endAndTrackRecording };
};

export default useRecordingEndedAnalytics;
