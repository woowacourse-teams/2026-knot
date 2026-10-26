import { useRecordingStore } from "@store/recordingStore";
import { isMeetingAudioSupported } from "@store/recordingStore/meetingAudio";

/**
 * 녹음을 화면에 보여 주기 위해 녹음 상태를 정제한 담당하는 도메인 훅.
 *
 * 녹음 상태는 전역 저장소에 있어 어느 화면에서 불러도 같은 녹음을 봐요.
 * 녹음한 시간은 매초 다시 그려야 해서 `useRecordingElapsedTime`이 맡아요.
 *
 * 녹음을 시작한 사람만 조작하므로 권한 구분은 없어요. 끝내면 처음 상태로 돌아가요.
 * 시작·일시정지·이어서 녹음·끝내기는 서버 세션과 맞춰야 해서 `useRecordingControl`이 맡아요.
 * 회의 소리 함께 녹음은 서버에 알리지 않고 같은 녹음에 섞기만 해서 여기서 다뤄요.
 */
const useRecordingState = () => {
  const status = useRecordingStore((state) => state.status);
  const session = useRecordingStore((state) => state.session);
  const isEnding = useRecordingStore((state) => state.isEnding);
  const analyser = useRecordingStore((state) => state.analyser);
  const isMicrophoneLost = useRecordingStore((state) => state.isMicrophoneLost);
  const clearMicrophoneLost = useRecordingStore(
    (state) => state.clearMicrophoneLost,
  );
  const meetingAudioStatus = useRecordingStore(
    (state) => state.meetingAudioStatus,
  );
  const connectMeetingAudio = useRecordingStore(
    (state) => state.connectMeetingAudio,
  );

  return {
    status,
    session,
    isRecordingActive: status !== "idle",
    analyser,
    isMicrophoneLost,
    isEnding,
    clearMicrophoneLost,
    /** 이 브라우저가 회의 소리를 받을 수 있는지(Chrome 계열) */
    isMeetingAudioSupported: isMeetingAudioSupported(),
    meetingAudioStatus,
    connectMeetingAudio,
  };
};

export default useRecordingState;
