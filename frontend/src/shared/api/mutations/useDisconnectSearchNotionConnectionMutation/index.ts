import { disconnectSearchNotionConnectionApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/search/notionConnection";
import { searchKeys } from "@api/queryKey/search";
import { useMutation, useQueryClient } from "@tanstack/react-query";

interface DisconnectSearchNotionConnectionMutationVariables {
  workspaceId: number;
}

/**
 * 워크스페이스의 노션 연결을 끊는 뮤테이션 훅.
 *
 * 성공하면 연결 없음으로, 실패하면 이미 끊겼거나(404) 끊을 수 없게 된(403) 지금 상태로 보이도록 끝나면 노션 연결 조회를 다시 받아요.
 */
const useDisconnectSearchNotionConnectionMutation = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({
      workspaceId,
    }: DisconnectSearchNotionConnectionMutationVariables) =>
      disconnectSearchNotionConnectionApi(workspaceId),
    onSettled: (_data, _error, { workspaceId }) =>
      queryClient.invalidateQueries({
        queryKey: searchKeys.notionConnection(workspaceId),
      }),
  });
};

export default useDisconnectSearchNotionConnectionMutation;
