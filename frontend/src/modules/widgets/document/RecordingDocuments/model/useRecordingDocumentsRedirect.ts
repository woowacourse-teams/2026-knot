import useDocumentsByRecordingQuery from "@api/queries/useDocumentsByRecordingQuery";
import useRedirectToLoginOnUnauthorized from "@hooks/domain/auth/useRedirectToLoginOnUnauthorized";
import useNavigateToDocument from "@hooks/domain/document/useNavigateToDocument";
import useNavigateToRecording from "@hooks/domain/recording/useNavigateToRecording";
import useNavigateToWorkspaceHome from "@hooks/domain/workspace/useNavigateToWorkspaceHome";
import { useEffect } from "react";

import type { RecordingDocumentsViewStatus } from "../types/recordingDocuments";

interface UseRecordingDocumentsRedirectParams {
  workspaceId: number;
  recordingId: number;
  viewStatus: RecordingDocumentsViewStatus;
}

/**
 * 정리 화면에 머물 이유가 없는 녹음을 다른 화면으로 보내요.
 *
 * - `recording`: 아직 끝내지 않은 녹음은 녹음 화면으로 돌려보내요.
 * - `completed`: 정리가 끝난 녹음은 그 녹음에서 나온 첫 문서로 보내요. 서버가 준 순서의 첫 문서라, 문서 보기의 스테퍼가 `1 / n`인 문서예요.
 *   정리 화면에서 넘어왔다는 쿼리를 붙여, 문서 보기가 같은 녹음의 문서를 넘기는 스테퍼를 보여주게 해요.
 *   그 녹음에서 나온 문서가 없거나 문서 목록을 불러오지 못하면 워크스페이스 홈으로 보내요.
 *   문서 목록 조회에서 로그인이 풀렸으면(401) 로그인 화면으로 보내요.
 *
 * 문서 목록은 정리가 끝난 뒤에만 조회해요(`isEnabled`). 정리 중에는 아직 문서가 없어, 미리 조회하면 빈 목록을 받아요.
 * 뒤로 가기로 이 주소에 돌아와 다시 보내지는 일이 없도록 지금 기록을 바꿔요(`replace`).
 */
export const useRecordingDocumentsRedirect = ({
  workspaceId,
  recordingId,
  viewStatus,
}: UseRecordingDocumentsRedirectParams) => {
  const { navigateToRecording } = useNavigateToRecording();
  const { navigateToDocument } = useNavigateToDocument();
  const { navigateToWorkspaceHome } = useNavigateToWorkspaceHome();
  const {
    data: recordingDocumentList,
    error: documentsError,
    isError: isDocumentsError,
  } = useDocumentsByRecordingQuery({
    workspaceId,
    recordingSessionId: recordingId,
    isEnabled: viewStatus === "completed",
  });
  const { isUnauthorized } = useRedirectToLoginOnUnauthorized({
    error: documentsError,
  });

  const firstDocumentId = recordingDocumentList?.items[0]?.id;
  // 문서 목록을 받았거나 받지 못한 것으로 끝났는지. 로그인이 풀린 실패는 로그인 화면으로 보내므로 빼요
  const isDocumentListSettled =
    recordingDocumentList !== undefined ||
    (isDocumentsError && !isUnauthorized);

  useEffect(() => {
    if (viewStatus === "recording") {
      navigateToRecording({ workspaceId: String(workspaceId), replace: true });
      return;
    }

    if (viewStatus !== "completed") return;

    if (firstDocumentId !== undefined) {
      navigateToDocument({
        workspaceId,
        documentId: firstDocumentId,
        replace: true,
        isFromRecording: true,
      });
      return;
    }

    if (isDocumentListSettled) {
      navigateToWorkspaceHome({
        workspaceId: String(workspaceId),
        replace: true,
      });
    }
  }, [
    firstDocumentId,
    isDocumentListSettled,
    navigateToDocument,
    navigateToRecording,
    navigateToWorkspaceHome,
    viewStatus,
    workspaceId,
  ]);
};
