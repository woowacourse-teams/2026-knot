import { getRouterPath } from "@routes/PATH_ROUTE";
import { useCallback } from "react";
import { useNavigate } from "react-router";

interface NavigateToRecordingDocumentParams {
  workspaceId: string;
  /** 녹음 문서를 열 녹음의 ID */
  recordingId: number;
  /** `true`면 현재 히스토리 항목을 대체해 뒤로 가기 때 지금 화면으로 돌아오지 않아요. */
  replace?: boolean;
}

/**
 * 녹음 문서 화면(`/workspace/:workspaceId/recordings/:recordingId/document`)으로 이동하는 도메인 훅(STT-R36).
 *
 * 녹음 뒤 문서 정리 화면에서 정리가 끝났을 때 그 녹음의 녹음 문서로 보낼 때 써요(STT-R18).
 * 정리 화면은 뒤로 가기로 다시 볼 이유가 없으니, 그 화면에서 넘어올 때는 `replace`를 켜요.
 * `useEffect` 안에서 부르는 곳(정리 화면)이 있어 참조를 `useCallback`으로 고정해요.
 */
const useNavigateToRecordingDocument = () => {
  const navigate = useNavigate();

  const navigateToRecordingDocument = useCallback(
    ({
      workspaceId,
      recordingId,
      replace = false,
    }: NavigateToRecordingDocumentParams) => {
      navigate(
        getRouterPath({
          routeKey: "RECORDING_DOCUMENT",
          params: { workspaceId, recordingId: String(recordingId) },
        }),
        { replace },
      );
    },
    [navigate],
  );

  return { navigateToRecordingDocument };
};

export default useNavigateToRecordingDocument;
