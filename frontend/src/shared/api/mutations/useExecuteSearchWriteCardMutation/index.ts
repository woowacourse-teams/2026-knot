import { executeSearchWriteCardApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/search/conversations/[conversationId]/writeCards/[cardId]/execute";
import { searchKeys } from "@api/queryKey/search";
import { useMutation, useQueryClient } from "@tanstack/react-query";

interface ExecuteSearchWriteCardMutationVariables {
  workspaceId: number;
  conversationId: number;
  cardId: number;
}

/**
 * 쓰기 확인 카드의 [만들기]를 실행하는 뮤테이션 훅. 응답 카드의 상태로 성공, 다시 누를 수 있는 오류, 실패를 알아요.
 *
 * 끝나면 그 대화의 메시지 조회를 다시 받아, 새로고침하지 않아도 저장된 카드가 지금 상태로 보여요.
 */
const useExecuteSearchWriteCardMutation = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (variables: ExecuteSearchWriteCardMutationVariables) =>
      executeSearchWriteCardApi(variables),
    onSettled: (_data, _error, { workspaceId, conversationId }) =>
      queryClient.invalidateQueries({
        queryKey: searchKeys.messages({ workspaceId, conversationId }),
      }),
  });
};

export default useExecuteSearchWriteCardMutation;
