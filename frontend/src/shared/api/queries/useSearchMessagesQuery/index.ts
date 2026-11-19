import { getSearchMessagesApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/search/conversations/[conversationId]/messages";
import { searchKeys } from "@api/queryKey/search";
import { keepPreviousData, skipToken, useQuery } from "@tanstack/react-query";

interface UseSearchMessagesQueryParams {
  workspaceId: number;
  /** 대화 ID. 없으면 아직 질문을 보내지 않은 새 대화예요 */
  conversationId?: number;
}

/**
 * 탐색 대화의 최근 메시지 한 페이지를 조회하는 쿼리 훅.
 *
 * 대화가 없는 새 대화 화면에서는 조회할 대상이 없으므로 요청을 보내지 않아요.
 * 다른 대화로 옮기면 새 대화의 응답이 올 때까지 보던 대화의 메시지를 그대로 줘요(SEARCH-15).
 * 새 대화로 옮길 때는 보던 대화가 남으면 안 되므로 이어 주지 않아요.
 */
const useSearchMessagesQuery = ({
  workspaceId,
  conversationId,
}: UseSearchMessagesQueryParams) => {
  return useQuery({
    queryKey: searchKeys.messages({ workspaceId, conversationId }),
    queryFn:
      conversationId === undefined
        ? skipToken
        : () => getSearchMessagesApi({ workspaceId, conversationId }),
    placeholderData:
      conversationId === undefined ? undefined : keepPreviousData,
  });
};

export default useSearchMessagesQuery;
