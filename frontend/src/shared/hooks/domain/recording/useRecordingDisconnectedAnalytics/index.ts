import useRecordingState from "@hooks/domain/recording/useRecordingState";
import { createRecordingDisconnectedTracker } from "@utils/createRecordingDisconnectedTracker";
import { useEffect, useRef } from "react";

/** 끝나기를 기다리는 끊김. 어느 녹음이 끊겼는지와 끊긴 때부터 재는 보내기 함수예요 */
interface Disconnection {
  recordingId: number;
  trackDisconnected: ReturnType<typeof createRecordingDisconnectedTracker>;
}

/**
 * 녹음 중 서버와의 연결이 끊겼다가 그 끊김이 끝나면 사용 기록(`recording_disconnected`)으로 보내는 도메인 훅.
 *
 * 끊김은 연결 끊김 토스트를 띄우는 조건(`isConnectionLost`)과 같아요. 녹음 중 생존 신호나 조각이 서버에 닿지 못하는 동안이에요.
 * 끊길 때 시각을 기억해 두고, 같은 녹음에서 연결이 돌아오면 `restored`, 돌아오지 못하고 녹음이 끝나면 `recording_closed`로
 * 끊긴 시간과 함께 한 번 보내요. 끊긴 채 탭을 닫으면 끊김이 끝나지 않아 보내지 않아요.
 *
 * 연결 상태를 지켜보기만 하고 녹음과 안내는 바꾸지 않아요. 녹음은 화면을 옮겨도 이어지므로 모든 화면을 감싸는 곳에서 한 번만 불러요.
 */
const useRecordingDisconnectedAnalytics = () => {
  const { session, isConnectionLost } = useRecordingState();
  const disconnectionRef = useRef<Disconnection | null>(null);

  useEffect(() => {
    const lostRecordingId = isConnectionLost ? session?.recordingId : undefined;
    const disconnection = disconnectionRef.current;

    if (disconnection && disconnection.recordingId !== lostRecordingId) {
      disconnectionRef.current = null;
      // 끊긴 녹음이 아직 이어지고 있으면 연결이 돌아온 것이고, 없으면 끊긴 채 끝난 거예요
      disconnection.trackDisconnected(
        session?.recordingId === disconnection.recordingId
          ? "restored"
          : "recordingClosed",
      );
    }

    if (session && isConnectionLost && !disconnectionRef.current) {
      disconnectionRef.current = {
        recordingId: session.recordingId,
        trackDisconnected: createRecordingDisconnectedTracker(session),
      };
    }
  }, [isConnectionLost, session]);
};

export default useRecordingDisconnectedAnalytics;
