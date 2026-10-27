import useNavigateToRecording from "@hooks/domain/recording/useNavigateToRecording";
import useRecordingElapsedTime from "@hooks/domain/recording/useRecordingElapsedTime";
import useRecordingState from "@hooks/domain/recording/useRecordingState";
import { formatRecordingTime } from "@utils/formatRecordingTime";

import { useWorkspaceId } from "./useWorkspaceId";

/**
 * 이 탭에서 시작해 이어지고 있는 녹음(녹음 탭)의 상태와 시간.
 *
 * 이 탭에서 녹음하고 있지 않으면 `status`가 `idle`이에요.
 * 녹음 화면에 갈 수 있는 건 녹음을 시작한 이 탭뿐이라 녹음 화면으로 가는 핸들러도 함께 돌려줘요.
 */
export const useThisTabRecording = () => {
  const workspaceId = useWorkspaceId();
  const { navigateToRecording } = useNavigateToRecording();
  const { status } = useRecordingState();
  const { elapsedSeconds } = useRecordingElapsedTime();

  const handleOpenRecording = () => {
    if (workspaceId) navigateToRecording({ workspaceId });
  };

  return {
    status,
    elapsedTime: formatRecordingTime(elapsedSeconds),
    handleOpenRecording,
  };
};
