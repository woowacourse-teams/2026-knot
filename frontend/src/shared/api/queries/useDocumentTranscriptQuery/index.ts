import { getDocumentTranscriptApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/documents/[documentId]/transcript";
import { documentKeys } from "@api/queryKey/document";
import { useQuery } from "@tanstack/react-query";

interface UseDocumentTranscriptQueryParams {
  workspaceId: number;
  documentId: number;
  /** `false`면 조회하지 않아요. 원문 패널이 녹음 문서의 전사를 보여 줄 때 꺼 둬요. 기본값은 `true` */
  isEnabled?: boolean;
}

/**
 * 문서를 만든 전사 원문을 조회하는 쿼리 훅.
 *
 * 두 id가 정수인지는 확인하지 않고 그대로 요청해요. 잘못된 id인지는 서버가 판단해 400 · 404로 답해요.
 */
const useDocumentTranscriptQuery = ({
  workspaceId,
  documentId,
  isEnabled = true,
}: UseDocumentTranscriptQueryParams) => {
  return useQuery({
    queryKey: documentKeys.transcript({ workspaceId, documentId }),
    queryFn: () => getDocumentTranscriptApi({ workspaceId, documentId }),
    enabled: isEnabled,
  });
};

export default useDocumentTranscriptQuery;
