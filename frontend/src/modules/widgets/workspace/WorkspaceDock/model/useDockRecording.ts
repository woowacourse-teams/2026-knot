import useMicrophoneUnavailableDialog from "@hooks/domain/recording/useMicrophoneUnavailableDialog";
import useNavigateToRecording from "@hooks/domain/recording/useNavigateToRecording";
import useRecordingState from "@hooks/domain/recording/useRecordingState";
import useRecordingControl from "@hooks/domain/recording/useRecordingControl";
import useRecordingElapsedTime from "@hooks/domain/recording/useRecordingElapsedTime";
import useRecordingStartAnalytics from "@hooks/domain/recording/useRecordingStartAnalytics";
import useRecordingStartNetworkErrorDialog from "@hooks/domain/recording/useRecordingStartNetworkErrorDialog";
import useRecordingStartRefusedDialog from "@hooks/domain/recording/useRecordingStartRefusedDialog";
import useServerRecording from "@hooks/domain/recording/useServerRecording";
import { PATH_ROUTE } from "@routes/PATH_ROUTE";
import { formatRecordingTime } from "@utils/formatRecordingTime";
import { markRecordingStart } from "@utils/recordingStartTrace";
import { trackRecordingStartResult } from "@utils/trackRecordingStartResult";
import { useEffect, useState } from "react";
import { useMatch, useParams } from "react-router";

/**
 * 독의 회의 녹음(마이크) 슬롯을 다룹니다.
 *
 * 마이크를 누르면 녹음 화면에 들어가기 전에 마이크 권한을 받고 서버에 녹음을 열어 시작한 뒤 녹음 화면으로 가요.
 * 권한을 받지 못하면 권한을 허용하는 방법을 알리는 [확인] 알림을, 서버가 시작을 거절하면 [닫기]/[새로고침] 모달을 띄워요.
 * 시작 요청이 네트워크 문제로 응답을 받지 못하면 [닫기]/[다시 시도] 모달을 띄우고, [다시 시도]는 마이크를 누른 것처럼 다시 시작해요(ERR-18).
 * 알림을 닫으면 제자리에 남고, 마이크를 다시 누르면 권한을 다시 받아요.
 * 녹음 화면에서는 갈 곳이 없어 마이크를 숨겨요.
 * 누른 뒤 녹음이 열릴 때까지(마이크 권한, 오디오 준비, 서버 시작 요청) 1~2초 걸릴 수 있어, 그동안은 `isRecordingStarting`으로 기다리는 중을 보여줘요.
 * 녹음이 열려도 녹음 화면에 들어가기 전까지는 마이크 자리를 그대로 두어, 홈에 녹음 칩과 파형이 잠깐 비쳤다 사라지지 않게 해요.
 * 누른 결과와 기다린 시간은 사용 기록(`recording_start_result`)으로 남기고, 안내 창을 띄운 실패는 그 이유를 `recording_start_failed`로도 남겨요.
 *
 * 녹음 화면이 아닌 곳에서 녹음이 이어지고 있으면 마이크 대신 녹음 칩을 보여줘요.
 * 칩을 누르면 권한을 다시 묻지 않고 녹음 화면으로 가고, 중지를 누르면 녹음을 끝내 올린 뒤 그 녹음의 문서 정리 화면으로 가요.
 * 이 탭의 녹음이 없을 때 현재 녹음 조회가 녹음 중·일시정지로 답하면 다른 탭의 녹음(`isOtherTabRecording`)으로 칩을 보여줘요.
 * 문서 정리 중인 녹음은 이미 끝난 녹음이라 칩으로 보여 주지 않고, 새 녹음을 시작할 수 있게 마이크를 둬요.
 * 조회 중이거나 실패하면 마이크를 둬요.
 *
 * 독은 어느 화면에서나 떠 있으므로, 녹음 중에 마이크가 끊겼다는 알림도 여기서 같은 알림으로 띄워요.
 * 녹음은 저장소가 이미 일시정지해 두었으므로 서버에도 일시정지를 알리고, 마이크를 다시 받는 일은 녹음 화면의 「이어서 녹음」이 맡아요.
 */
export const useDockRecording = () => {
  const { workspaceId } = useParams();
  const isRecordingPageActive = useMatch(PATH_ROUTE.RECORDING) !== null;
  const { navigateToRecording } = useNavigateToRecording();
  const { openMicrophoneUnavailableDialog } = useMicrophoneUnavailableDialog();
  const { openRecordingStartRefusedDialog } = useRecordingStartRefusedDialog();
  const { openRecordingStartNetworkErrorDialog } =
    useRecordingStartNetworkErrorDialog();
  const {
    status,
    isRecordingActive,
    isMicrophoneLost,
    clearMicrophoneLost,
    analyser,
  } = useRecordingState();
  const { elapsedSeconds } = useRecordingElapsedTime();
  const { status: serverStatus, elapsedTime: serverElapsedTime } =
    useServerRecording();
  const { startRecording, syncPause, endRecording } = useRecordingControl();
  // 안내 창을 띄운 실패는 독에서 시작하지 못한 사용 기록으로도 보내요
  const { startAndTrackRecording } = useRecordingStartAnalytics({
    startRecording,
    surface: "dock",
  });
  const [isRecordingStarting, setIsRecordingStarting] = useState(false);

  const isOtherTabRecording =
    !isRecordingActive &&
    (serverStatus === "recording" || serverStatus === "paused");
  // 방금 연 녹음은 녹음 화면에 들어갈 때까지 마이크 자리에서 기다리는 중으로 보여줘요
  const hasRecording = (isRecordingActive && !isRecordingStarting) || isOtherTabRecording;

  // 녹음 화면에 들어가면 기다림이 끝나요
  useEffect(() => {
    if (isRecordingPageActive) setIsRecordingStarting(false);
  }, [isRecordingPageActive]);

  // 녹음 중 마이크가 끊기면 알림을 띄워요
  useEffect(() => {
    if (!isMicrophoneLost) return;

    // 알림은 한 번만 띄우도록 끊김 표시를 바로 지워요. 녹음은 일시정지로 남아요
    clearMicrophoneLost();
    void syncPause();
    openMicrophoneUnavailableDialog();
  }, [
    clearMicrophoneLost,
    isMicrophoneLost,
    openMicrophoneUnavailableDialog,
    syncPause,
  ]);

  const handleMicClick = async () => {
    if (!workspaceId) return;

    const startedAt = performance.now();
    markRecordingStart("click");
    setIsRecordingStarting(true);
    const result = await startAndTrackRecording(Number(workspaceId));
    trackRecordingStartResult({
      workspaceId: Number(workspaceId),
      result,
      waitMs: performance.now() - startedAt,
    });
    // 열렸으면 녹음 화면에 들어갈 때 기다림을 끝내요. 다시 눌러 무시된 것이면 앞의 누름이 아직 기다리는 중이에요
    if (result !== "started" && result !== "ignored") setIsRecordingStarting(false);

    if (result === "microphoneUnavailable") {
      openMicrophoneUnavailableDialog();
      return;
    }
    if (result === "refused") {
      openRecordingStartRefusedDialog();
      return;
    }
    if (result === "networkFailed") {
      openRecordingStartNetworkErrorDialog({
        onRetry: () => void handleMicClick(),
      });
      return;
    }
    if (result !== "started") return;

    markRecordingStart("navigated");
    navigateToRecording({ workspaceId });
  };

  const handleOpenRecording = () => {
    if (!workspaceId) return;

    navigateToRecording({ workspaceId });
  };

  return {
    isMicVisible: !isRecordingPageActive && !hasRecording,
    isRecordingChipVisible: !isRecordingPageActive && hasRecording,
    isOtherTabRecording,
    /** 녹음 버튼을 누르고 녹음이 열려 녹음 화면에 들어가기를 기다리는 중 */
    isRecordingStarting,
    isRecordingPaused: isOtherTabRecording
      ? serverStatus === "paused"
      : status === "paused",
    elapsedTime: isOtherTabRecording
      ? serverElapsedTime
      : formatRecordingTime(elapsedSeconds),
    /** 이 탭의 녹음 소리를 읽는 분석기. 다른 탭의 녹음이면 없어요 */
    analyser: isOtherTabRecording ? null : analyser,
    handleMicClick,
    handleOpenRecording,
    handleStopRecording: () => void endRecording(),
  };
};
