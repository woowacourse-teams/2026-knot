import { getDocumentApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/documents/[documentId]";
import { documentKeys } from "@api/queryKey/document";
import { useQuery } from "@tanstack/react-query";

interface UseDocumentQueryParams {
  workspaceId: number;
  documentId: number;
  /** `false`면 조회하지 않아요. GNB처럼 문서 화면이 아닐 때도 그려지는 곳에서 꺼 둬요. 기본값은 `true` */
  isEnabled?: boolean;
}

/**
 * 문서 하나의 상세를 조회하는 쿼리 훅.
 *
 * 문서 보기 위젯이 써요. 복사 버튼 · 확인 수 · 확인 버튼은 위젯이 받은 값을 넘겨받아, 문서를 다시 조회하지 않아요.
 * GNB의 위치 표시도 문서 제목을 보여 주려고 같은 키로 조회해서, 요청은 한 번만 나가요.
 *
 * 두 id가 정수인지는 확인하지 않고 그대로 요청해요. 잘못된 id인지는 서버가 판단해 400 · 404로 답해요.
 */
const useDocumentQuery = ({
  workspaceId,
  documentId,
  isEnabled = true,
}: UseDocumentQueryParams) => {
  return useQuery({
    queryKey: documentKeys.detail({ workspaceId, documentId }),
    queryFn: () => getDocumentApi({ workspaceId, documentId }),
    enabled: isEnabled,
  });
};

export default useDocumentQuery;
