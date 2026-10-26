import useRecordingState from "@hooks/domain/recording/useRecordingState";
import { useDialog } from "@provider/context/dialogContext";

import { MEETING_AUDIO_COPY } from "../constants/meetingAudioCopy";
import MeetingAudioNoticeDialog from "../ui/MeetingAudioNoticeDialog";

const NOTICES = {
  noAudio: MEETING_AUDIO_COPY.NO_AUDIO,
  stopped: MEETING_AUDIO_COPY.STOPPED,
} as const;

/**
 * 녹음 화면의 「회의 소리도 함께 녹음」 선택지를 다룹니다.
 *
 * 누르면 녹음 고지와 이어폰 권장 모달을 먼저 띄우고, 모달의 확인을 누른 그 자리에서 공유 창을 열어요.
 * 공유 창은 사용자의 누름 안에서만 열리기 때문이에요.
 * 함께 녹음하는 동안과 녹음을 끝내는 동안에는 선택지를 숨기고, 공유가 멈추면 다시 보여 줘요.
 */
export const useMeetingAudioOption = () => {
  const { open } = useDialog();
  const {
    isEnding,
    isMeetingAudioSupported,
    meetingAudioStatus,
    connectMeetingAudio,
  } = useRecordingState();

  const handleOptionClick = () =>
    open(({ close }) => (
      <MeetingAudioNoticeDialog
        onClose={close}
        onConfirm={() => {
          close();
          void connectMeetingAudio();
        }}
      />
    ));

  return {
    isSupported: isMeetingAudioSupported,
    isOptionVisible: meetingAudioStatus !== "on" && !isEnding,
    notice:
      meetingAudioStatus === "noAudio" || meetingAudioStatus === "stopped"
        ? NOTICES[meetingAudioStatus]
        : null,
    handleOptionClick,
  };
};
