import { PATH_ROUTE } from "@routes/PATH_ROUTE";
import type { AnalyticsScreenTarget } from "@utils/captureAnalyticsEvent";
import { useMatch, useParams } from "react-router";

/**
 * 문서를 읽는 화면(문서 보기 · 녹음 문서)의 사용 기록에 붙일 화면과 id를 주소에서 읽는 도메인 훅.
 *
 * 녹음 문서 주소면 녹음 id를, 그 밖에는 문서 보기로 보고 문서 id를 담아요. 원문 패널처럼 두 화면에만 있는 곳에서 불러요.
 * 주소의 id는 숫자로 바꾸기만 하고 확인하지 않아요.
 */
const useAnalyticsScreenTarget = () => {
  const params = useParams();
  const isRecordingDocument = useMatch(PATH_ROUTE.RECORDING_DOCUMENT) !== null;
  const workspaceId = Number(params.workspaceId);

  const analyticsScreenTarget: AnalyticsScreenTarget = isRecordingDocument
    ? {
        screen: "recording_document",
        recording_id: Number(params.recordingId),
        workspace_id: workspaceId,
      }
    : {
        screen: "document",
        document_id: Number(params.documentId),
        workspace_id: workspaceId,
      };

  return { analyticsScreenTarget };
};

export default useAnalyticsScreenTarget;
