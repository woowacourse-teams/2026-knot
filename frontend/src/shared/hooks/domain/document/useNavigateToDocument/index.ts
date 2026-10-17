import {
  DOCUMENT_FROM_PARAM,
  DOCUMENT_FROM_RECORDING,
} from "@constants/document";
import { getRouterPath } from "@routes/PATH_ROUTE";
import { useCallback } from "react";
import { useNavigate } from "react-router";

interface NavigateToDocumentParams {
  workspaceId: number;
  documentId: number;
  /** `true`면 현재 히스토리 항목을 대체해 뒤로 가기 때 지금 화면으로 돌아오지 않아요. */
  replace?: boolean;
  /** `true`면 녹음 직후 정리 화면에서 넘어왔다는 쿼리를 붙여, 문서 보기가 같은 녹음의 문서를 넘기는 스테퍼를 보여줘요. */
  isFromRecording?: boolean;
}

/**
 * 문서 보기 화면(`/workspace/:workspaceId/documents/:documentId`)으로 이동하는 도메인 훅.
 *
 * 기본은 히스토리에 push해서 뒤로 가기 때 원래 화면으로 돌아와요.
 * 녹음 뒤 문서 정리 화면처럼 뒤로 가기로 다시 볼 이유가 없는 곳에서 넘어올 때는 `replace`를 켜요.
 * 정리 화면과 스테퍼처럼 녹음 직후 문서를 이어서 확인하는 흐름에서만 `isFromRecording`을 켜요(DOC-R2).
 * `useEffect` 안에서 부르는 곳(정리 화면)이 있어 참조를 `useCallback`으로 고정해요.
 */
const useNavigateToDocument = () => {
  const navigate = useNavigate();

  const navigateToDocument = useCallback(
    ({
      workspaceId,
      documentId,
      replace = false,
      isFromRecording = false,
    }: NavigateToDocumentParams) => {
      navigate(
        {
          pathname: getRouterPath({
            routeKey: "DOCUMENT",
            params: {
              workspaceId: String(workspaceId),
              documentId: String(documentId),
            },
          }),
          search: isFromRecording
            ? `?${DOCUMENT_FROM_PARAM}=${DOCUMENT_FROM_RECORDING}`
            : "",
        },
        { replace },
      );
    },
    [navigate],
  );

  return { navigateToDocument };
};

export default useNavigateToDocument;
