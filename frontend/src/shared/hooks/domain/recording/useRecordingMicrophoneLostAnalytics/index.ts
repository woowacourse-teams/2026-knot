import { useRecordingStore } from "@store/recordingStore";
import { trackRecordingMicrophoneLost } from "@utils/trackRecordingMicrophoneLost";
import { useCallback, useRef } from "react";

/**
 * 녹음 중 마이크가 끊긴 것을 사용 기록(`recording_microphone_lost`)으로 보내는 도메인 훅.
 *
 * 끊김 안내 창을 띄우는 곳이 띄울 때 `trackMicrophoneLost`를 불러요.
 * 마이크가 끊기면 저장소가 저절로 일시정지하며 녹음한 시간을 멈춰 두므로, 멈춘 시간을 끊길 때까지 녹음한 시간으로 보내요.
 * 마이크와 PCM 수집기가 같은 끊김을 따로 알려 안내 창이 다시 뜰 수 있어, 같은 녹음에서 멈춘 시간이 같으면 한 번만 보내요.
 * 이어서 녹음하면 시간이 흐르므로 그 뒤의 끊김은 다시 보내요.
 */
const useRecordingMicrophoneLostAnalytics = () => {
  /** 마지막으로 보낸 끊김. 녹음 id와 멈춘 시간(ms)으로 알아봐요 */
  const trackedLossRef = useRef<string | null>(null);

  // 독의 마이크 끊김 알림처럼 효과 안에서 부르는 곳이 있어 참조를 고정해요
  const trackMicrophoneLost = useCallback(() => {
    const { session, accumulatedMs } = useRecordingStore.getState();
    if (!session) return;

    const loss = `${session.recordingId}:${accumulatedMs}`;
    if (trackedLossRef.current === loss) return;

    trackedLossRef.current = loss;
    trackRecordingMicrophoneLost({
      workspaceId: session.workspaceId,
      recordingId: session.recordingId,
      elapsedMs: accumulatedMs,
    });
  }, []);

  return { trackMicrophoneLost };
};

export default useRecordingMicrophoneLostAnalytics;
