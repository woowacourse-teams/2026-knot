import useMicrophoneUnavailableDialog from "@hooks/domain/recording/useMicrophoneUnavailableDialog";
import useRecordingState from "@hooks/domain/recording/useRecordingState";
import useRecordingControl from "@hooks/domain/recording/useRecordingControl";
import useRecordingElapsedTime from "@hooks/domain/recording/useRecordingElapsedTime";
import useRecordingResumeAnalytics from "@hooks/domain/recording/useRecordingResumeAnalytics";
import useServerRecording from "@hooks/domain/recording/useServerRecording";
import { formatRecordingTime } from "@utils/formatRecordingTime";

/**
 * 녹음 화면 상단 바의 녹음 조작을 다룹니다.
 *
 * 이어서 녹음할 때 마이크가 끊겨 있었다면 다시 받아요. 받지 못하면 권한을 허용하는 방법을 알리는 [확인] 알림을
 * 띄우고 일시정지를 유지해요. 알림을 닫은 뒤 「이어서 녹음」을 다시 누르면 마이크를 다시 받아요.
 * 녹음 끝내기는 녹음 파일을 올리고 문서 정리 화면으로 갈 때까지 걸리므로, 그동안 버튼을 막아 두 번 보내지 않게 해요.
 * 새로고침한 탭은 아직 소리를 받지 않아 서버의 시간을 보여 주고, 「이어서 녹음」으로 같은 녹음을 이어 받아요(STT-R26).
 */
export const useRecorderBar = () => {
  const { openMicrophoneUnavailableDialog } = useMicrophoneUnavailableDialog();
  const { status, analyser, isEnding } = useRecordingState();
  const { elapsedSeconds } = useRecordingElapsedTime();
  const {
    status: serverStatus,
    elapsedTime: serverElapsedTime,
    isResumable,
  } = useServerRecording();
  const {
    pauseRecording,
    resumeRecording,
    resumeReloadedRecording,
    endRecording,
  } = useRecordingControl();
  // 마이크를 다시 받지 못한 실패는 녹음 바에서 이어 가지 못한 사용 기록으로도 보내요
  const { resumeAndTrackRecording } = useRecordingResumeAnalytics({
    resumeRecording: isResumable ? resumeReloadedRecording : resumeRecording,
    surface: "recorder_bar",
  });

  const handleResume = async () => {
    const isResumed = await resumeAndTrackRecording();
    if (isResumed) return;

    openMicrophoneUnavailableDialog();
  };

  return {
    isPaused: status === "paused" || isResumable,
    /** 새로고침해 아직 이어 받지 않았는지 */
    isReloaded: isResumable,
    /** 새로고침 전에도 일시정지였는지 */
    isReloadedWhilePaused: isResumable && serverStatus === "paused",
    elapsedTime: isResumable
      ? serverElapsedTime
      : formatRecordingTime(elapsedSeconds),
    analyser,
    isEnding,
    handlePause: () => void pauseRecording(),
    handleResume,
    handleEnd: () => void endRecording(),
  };
};
