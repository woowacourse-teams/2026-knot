import useNavigateToRecording from "@hooks/domain/recording/useNavigateToRecording";
import useNavigateToRecordingDocument from "@hooks/domain/recording/useNavigateToRecordingDocument";
import useNavigateToWorkspaceHome from "@hooks/domain/workspace/useNavigateToWorkspaceHome";
import { useEffect } from "react";

import type { RecordingDocumentsViewStatus } from "../types/recordingDocuments";

interface UseRecordingDocumentsRedirectParams {
  workspaceId: number;
  recordingId: number;
  viewStatus: RecordingDocumentsViewStatus;
  isTranscriptReady?: boolean;
}

/**
 * 정리 화면에 머물 이유가 없는 녹음을 다른 화면으로 보내요.
 *
 * - `recording`: 아직 끝내지 않은 녹음은 녹음 화면으로 돌려보내요.
 * - `audioSaveFailed`: 서버 저장이 끝내 실패하거나 폐기된 녹음은 정리할 음성이 없어 워크스페이스 홈으로 보내요. 다시 시도는 두지 않아요.
 * - `completed`: 정리가 끝난 녹음은 그 녹음의 녹음 문서로 보내요(STT-R18). 첫 주제별 문서로 보내지 않아요.
 *   주제별 문서는 녹음 문서의 「이 회의에서 나온 문서」에서 열어요(STT-R52). 회의 전체 요약이 아직 만드는 중이어도 기다리지 않아요(STT-R41).
 *
 * 뒤로 가기로 이 주소에 돌아와 다시 보내지는 일이 없도록 지금 기록을 바꿔요(`replace`).
 */
export const useRecordingDocumentsRedirect = ({
  workspaceId,
  recordingId,
  viewStatus,
  isTranscriptReady = false,
}: UseRecordingDocumentsRedirectParams) => {
  const { navigateToRecording } = useNavigateToRecording();
  const { navigateToRecordingDocument } = useNavigateToRecordingDocument();
  const { navigateToWorkspaceHome } = useNavigateToWorkspaceHome();

  useEffect(() => {
    if (viewStatus === "recording") {
      navigateToRecording({ workspaceId: String(workspaceId), replace: true });
      return;
    }

    if (viewStatus === "audioSaveFailed") {
      navigateToWorkspaceHome({
        workspaceId: String(workspaceId),
        replace: true,
      });
      return;
    }

    if (viewStatus === "completed" || isTranscriptReady) {
      navigateToRecordingDocument({
        workspaceId: String(workspaceId),
        recordingId,
        replace: true,
      });
    }
  }, [
    navigateToRecording,
    navigateToRecordingDocument,
    navigateToWorkspaceHome,
    recordingId,
    isTranscriptReady,
    viewStatus,
    workspaceId,
  ]);
};
