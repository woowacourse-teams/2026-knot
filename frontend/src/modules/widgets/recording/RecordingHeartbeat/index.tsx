import { TOAST_MESSAGES } from "@constants/toastMessages";
import useInterval from "@hooks/common/useInterval";
import useRecordingControl from "@hooks/domain/recording/useRecordingControl";
import useRecordingMaxDuration from "@hooks/domain/recording/useRecordingMaxDuration";
import useRecordingState from "@hooks/domain/recording/useRecordingState";
import { useToast } from "@provider/context/toastContext";
import { type ReactNode, useEffect, useRef } from "react";

/** 서버는 30초 동안 신호가 없으면 녹음을 끝내요. 몇 번 놓쳐도 이어지도록 10초마다 보내요 */
const HEARTBEAT_INTERVAL_MS = 10 * 1000;

/** 최대 녹음 시간보다 이만큼 앞서 남은 시간을 알려요(STT-R15) */
const TIME_LIMIT_WARNING_BEFORE_MS = 15 * 60 * 1000;

interface RecordingHeartbeatProps {
  children: ReactNode;
}

/**
 * 녹음하는 동안 이 탭이 살아 있음을 서버에 알리는 컴포넌트.
 *
 * 감싼 화면을 그대로 그리고, 놓여 있는 동안 10초마다 생존 신호를 보내요. 일시정지 중에도 보내고, 끝내는 중에는 보내지 않아요.
 * 신호가 실패하면 곧바로 다시 보내지 않고 다음 주기에 보내요.
 * 연결이 돌아오거나 탭이 다시 보이면 다음 주기를 기다리지 않고 신호를 한 번 보내요. 두 일이 겹쳐도 응답을 기다리는 동안에는 더 보내지 않아요.
 * 녹음한 시간이 2시간에 닿으면 서버가 녹음을 끝내므로, 다음 주기를 기다리지 않고 신호를 한 번 보내 바로 확인해요.
 * 그보다 15분 앞서서는 주의 토스트로 남은 시간을 녹음마다 한 번 알려요.
 * 응답에 맞춰 녹음한 시간을 맞추거나 서버가 끝낸 녹음을 닫는 일은 `useRecordingControl`이 맡아요.
 */
export default function RecordingHeartbeat({
  children,
}: RecordingHeartbeatProps) {
  const { sendHeartbeat } = useRecordingControl();
  const { session } = useRecordingState();
  const { show } = useToast();
  const warnedRecordingIdRef = useRef<number | null>(null);
  const isSendingNowRef = useRef(false);

  const { start } = useInterval({
    callback: sendHeartbeat,
    delay: HEARTBEAT_INTERVAL_MS,
  });

  useEffect(() => {
    start();
  }, [start]);

  useEffect(() => {
    const sendHeartbeatNow = () => {
      if (isSendingNowRef.current) return;

      isSendingNowRef.current = true;
      void sendHeartbeat().finally(() => {
        isSendingNowRef.current = false;
      });
    };
    const handleVisibilityChange = () => {
      if (document.visibilityState === "visible") sendHeartbeatNow();
    };

    window.addEventListener("online", sendHeartbeatNow);
    document.addEventListener("visibilitychange", handleVisibilityChange);

    return () => {
      window.removeEventListener("online", sendHeartbeatNow);
      document.removeEventListener("visibilitychange", handleVisibilityChange);
    };
  }, [sendHeartbeat]);

  useRecordingMaxDuration({ onReached: sendHeartbeat });

  useRecordingMaxDuration({
    beforeMs: TIME_LIMIT_WARNING_BEFORE_MS,
    onReached: () => {
      // 녹음한 시간이 서버 값으로 다시 맞춰지면 또 닿을 수 있어, 녹음마다 한 번만 알려요
      if (!session || warnedRecordingIdRef.current === session.recordingId) {
        return;
      }
      warnedRecordingIdRef.current = session.recordingId;
      show(TOAST_MESSAGES.RECORDING_TIME_LIMIT_APPROACHING);
    },
  });

  return children;
}
