import { TOAST_MESSAGES } from "@constants/toastMessages";
import useRecordingState from "@hooks/domain/recording/useRecordingState";
import { useDialog } from "@provider/context/dialogContext";
import { useToast } from "@provider/context/toastContext";
import { useState } from "react";

import HeadphonesIcon from "@/assets/icons/headphones.svg";

import type { MeetingSourceState } from "../types/meetingSource";
import MeetingAudioConnectDialog from "../ui/MeetingAudioConnectDialog";
import { useMeetingAudioConnectAnalytics } from "./useMeetingAudioConnectAnalytics";

/** 휴대폰, 태블릿인지. 공유 창으로 회의 소리를 받을 수 없어 컴퓨터에서만 된다고 알려요 */
const isMobileDevice = () =>
  /Android|iPhone|iPad|iPod|Mobi/i.test(navigator.userAgent);

/**
 * 녹음 화면의 「화상 회의 상대방 목소리」 줄을 다룹니다.
 *
 * 「함께 녹음하기」나 「다시 연결하기」를 누르면 연결 안내 모달을 먼저 띄우고, 모달의 「공유할 화면 고르기」를 누른 그 자리에서
 * 공유 창을 열어요. 공유 창은 사용자의 누름 안에서만 열리기 때문이에요. 공유 창에서 고르는 동안은 `connecting`이고,
 * 회의 소리를 받아 연결되면 이어폰 토스트를 띄워요. 녹음을 끝내는 동안에는 버튼을 숨겨요.
 * 연결 안내를 취소했거나 공유 창의 결과가 정해지면 사용 기록으로도 보내요(`useMeetingAudioConnectAnalytics`).
 */
export const useMeetingAudioOption = () => {
  const { open } = useDialog();
  const { show } = useToast();
  const {
    status,
    isEnding,
    isMeetingAudioSupported,
    meetingAudioStatus,
    connectMeetingAudio,
  } = useRecordingState();
  const [isConnecting, setIsConnecting] = useState(false);
  const { trackConnectResult } = useMeetingAudioConnectAnalytics();

  const handleConnectClick = () => {
    const isReconnect =
      meetingAudioStatus === "noAudio" || meetingAudioStatus === "stopped";

    open(({ close }) => (
      <MeetingAudioConnectDialog
        onClose={(location) => {
          trackConnectResult({
            result: "dialogCancelled",
            location,
            isReconnect,
          });
          close();
        }}
        onConfirm={(location) => {
          close();
          setIsConnecting(true);
          void connectMeetingAudio()
            .then((result) => {
              if (result === "connected") {
                show({
                  ...TOAST_MESSAGES.MEETING_AUDIO_CONNECTED,
                  icon: HeadphonesIcon,
                });
              }
              trackConnectResult({ result, location, isReconnect });
            })
            .finally(() => setIsConnecting(false));
        }}
      />
    ));
  };

  // 공유 창을 여는 브라우저가 있어도 모바일은 지원하지 않아요
  const meetingState: MeetingSourceState = isMobileDevice()
    ? "mobile"
    : !isMeetingAudioSupported
      ? "unsupported"
      : isConnecting
        ? "connecting"
        : meetingAudioStatus === "on" && status === "paused"
          ? "paused"
          : meetingAudioStatus;

  return {
    meetingState,
    isEnding,
    handleConnectClick,
  };
};
