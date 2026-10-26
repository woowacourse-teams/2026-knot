import { getRouterPath } from "@routes/PATH_ROUTE";
import { useNavigate } from "react-router";

interface NavigateToDocumentsParams {
  workspaceId: string;
}

/**
 * 문서 탭의 「녹음별」 탭(`/workspace/:workspaceId/documents/recordings`)으로 이동하는 도메인 훅.
 *
 * 문서 탭은 상황과 관계없이 「녹음별」 탭으로 열려요(DOC-R9). 탭 없는 주소로 보내 다시 이동시키지 않고 바로 가요.
 * 히스토리에 push하므로 뒤로 가기 때 원래 화면으로 돌아와요.
 */
const useNavigateToDocuments = () => {
  const navigate = useNavigate();

  const navigateToDocuments = ({ workspaceId }: NavigateToDocumentsParams) => {
    navigate(
      getRouterPath({
        routeKey: "DOCUMENTS_BY_RECORDING",
        params: { workspaceId },
      }),
    );
  };

  return { navigateToDocuments };
};

export default useNavigateToDocuments;
