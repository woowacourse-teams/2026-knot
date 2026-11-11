import useNavigateToWorkspaceHome from "@hooks/domain/workspace/useNavigateToWorkspaceHome";
import LoadingIndicator from "@primitives/ui/LoadingIndicator";
import { useParams } from "react-router";

import { useRecordingDocuments } from "./model/useRecordingDocuments";
import { useRecordingDocumentsAnalytics } from "./model/useRecordingDocumentsAnalytics";
import { useRecordingDocumentsRedirect } from "./model/useRecordingDocumentsRedirect";
import AudioSaveCheckFailedState from "./ui/AudioSaveCheckFailedState";
import DocumentFailedState from "./ui/DocumentFailedState";
import DraftingState from "./ui/DraftingState";
import LoadFailedState from "./ui/LoadFailedState";
import NothingToOrganizeState from "./ui/NothingToOrganizeState";

/**
 * 녹음을 끝낸 뒤 문서가 만들어질 때까지 보여 주는 정리 화면 섹션.
 *
 * 주소의 녹음 상태를 3초마다 다시 조회하고, 상태에 따라 정리 중 · 문서로 만들 내용 없음 · 문서를 만들지 못함 · 불러오지 못함 가운데 하나를 보여줘요.
 * 문서 만들기나 전사에 실패했으면 그 자리에서 다시 시도할 수 있고, 접수되면 정리 중으로 돌아가요.
 * 정리가 끝나면 그 녹음의 녹음 문서로 보내고, 아직 끝내지 않은 녹음은 녹음 화면으로 보내요.
 * PCM 조각 녹음의 서버 저장이 끝내 실패하거나 폐기됐으면 안내 없이 워크스페이스 홈으로 보내요. 저장된 음성이 없는 실패는 정리할 내용 없음으로 보여요.
 * 저장 상태를 확인하지 못하는 동안에는 정리 중 대신 확인하지 못했다는 안내와 「다시 시도」를 그 자리에 둬요.
 *
 * 주소의 두 id는 숫자인지 확인하지 않고 그대로 요청해요. 잘못된 주소인지는 서버가 판단하고, 여기서는 그 응답에 따라 문서를 불러오지 못했다고 알려요.
 * 같은 판단을 프론트에도 두면 기준이 두 곳에 생기기 때문이에요.
 */
export default function RecordingDocuments() {
  const params = useParams();

  const workspaceId = Number(params.workspaceId);
  const recordingId = Number(params.recordingId);
  const {
    isTranscriptReady,
    viewStatus,
    isTranscriptionFailed,
    isAudioSaveCheckFailed,
    retryLoad,
    retryAudioSaveCheck,
    retryGeneration,
    isRetrying,
  } = useRecordingDocuments({ workspaceId, recordingId });
  useRecordingDocumentsRedirect({
    workspaceId,
    recordingId,
    viewStatus,
    isTranscriptReady,
  });
  useRecordingDocumentsAnalytics({ workspaceId, recordingId, viewStatus });
  const { navigateToWorkspaceHome } = useNavigateToWorkspaceHome();

  const handleGoHome = () =>
    navigateToWorkspaceHome({ workspaceId: String(workspaceId) });

  // 아직 끝내지 않은 녹음은 녹음 화면으로, 저장이 실패한 녹음은 홈으로 보내는 중이라 그릴 것이 없어요
  if (viewStatus === "recording" || viewStatus === "audioSaveFailed") {
    return null;
  }

  if (viewStatus === "loading") {
    return <LoadingIndicator label="문서 정리 상태를 확인하고 있어요" />;
  }

  if (viewStatus === "organizing" && isAudioSaveCheckFailed) {
    return <AudioSaveCheckFailedState onRetry={retryAudioSaveCheck} />;
  }

  // 정리가 끝난 녹음은 녹음 문서로 넘어가는 동안 정리 중 화면을 그대로 둬요
  if (viewStatus === "organizing" || viewStatus === "completed") {
    return <DraftingState />;
  }

  if (viewStatus === "noContent") {
    return <NothingToOrganizeState onGoHome={handleGoHome} />;
  }

  if (viewStatus === "failed") {
    return (
      <DocumentFailedState
        isTranscriptionFailed={isTranscriptionFailed}
        onRetry={retryGeneration}
        isRetrying={isRetrying}
        onGoHome={handleGoHome}
      />
    );
  }

  return <LoadFailedState onRetry={retryLoad} />;
}
