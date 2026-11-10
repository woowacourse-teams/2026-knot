import { getRouterPath } from "@routes/PATH_ROUTE";
import { useCallback } from "react";
import { useNavigate } from "react-router";

interface NavigateToDocumentParams {
  workspaceId: number;
  documentId: number;
}

/**
 * 문서 보기 화면(`/workspace/:workspaceId/documents/:documentId`)으로 이동하는 도메인 훅.
 *
 * 히스토리에 push해서 뒤로 가기 때 원래 화면으로 돌아와요.
 */
const useNavigateToDocument = () => {
  const navigate = useNavigate();

  const navigateToDocument = useCallback(
    ({ workspaceId, documentId }: NavigateToDocumentParams) => {
      navigate(
        getRouterPath({
          routeKey: "DOCUMENT",
          params: {
            workspaceId: String(workspaceId),
            documentId: String(documentId),
          },
        }),
      );
    },
    [navigate],
  );

  return { navigateToDocument };
};

export default useNavigateToDocument;
