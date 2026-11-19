import {
  PostSearchNotionConnectionAuthorizationRequestDto,
  type PostSearchNotionConnectionAuthorizationRequestInput,
} from "@api/dto/search";
import { startSearchNotionConnectionApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/search/notionConnection/authorization";
import { searchKeys } from "@api/queryKey/search";
import { useMutation, useQueryClient } from "@tanstack/react-query";

interface StartSearchNotionConnectionMutationVariables
  extends PostSearchNotionConnectionAuthorizationRequestInput {
  workspaceId: number;
}

/**
 * 보이는 범위 안내에 동의한 뒤 노션 연결(만료면 다시 연결)을 시작하는 뮤테이션 훅. 성공하면 노션 동의 화면 주소를 받아요.
 *
 * 실패하면 연결 상태가 달라졌을 수 있어(다른 멤버가 먼저 연결해 409 NOTION_CONNECTION_ALREADY_CONNECTED) 노션 연결 조회를 다시 받아요.
 */
const useStartSearchNotionConnectionMutation = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({
      workspaceId,
      ...input
    }: StartSearchNotionConnectionMutationVariables) =>
      startSearchNotionConnectionApi({
        workspaceId,
        body: new PostSearchNotionConnectionAuthorizationRequestDto(input),
      }),
    onError: (_error, { workspaceId }) =>
      queryClient.invalidateQueries({
        queryKey: searchKeys.notionConnection(workspaceId),
      }),
  });
};

export default useStartSearchNotionConnectionMutation;
