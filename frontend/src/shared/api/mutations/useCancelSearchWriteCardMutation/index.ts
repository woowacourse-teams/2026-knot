import { cancelSearchWriteCardApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/search/conversations/[conversationId]/writeCards/[cardId]/cancel";
import { searchKeys } from "@api/queryKey/search";
import { useMutation, useQueryClient } from "@tanstack/react-query";

interface CancelSearchWriteCardMutationVariables {
  workspaceId: number;
  conversationId: number;
  cardId: number;
}

/**
 * 대기인 쓰기 확인 카드를 [취소]하는 뮤테이션 훅.
 *
 * 끝나면 그 대화의 메시지 조회를 다시 받아, 새로고침하지 않아도 저장된 카드가 지금 상태로 보여요.
 */
const useCancelSearchWriteCardMutation = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (variables: CancelSearchWriteCardMutationVariables) =>
      cancelSearchWriteCardApi(variables),
    onSettled: (_data, _error, { workspaceId, conversationId }) =>
      queryClient.invalidateQueries({
        queryKey: searchKeys.messages({ workspaceId, conversationId }),
      }),
  });
};

export default useCancelSearchWriteCardMutation;
