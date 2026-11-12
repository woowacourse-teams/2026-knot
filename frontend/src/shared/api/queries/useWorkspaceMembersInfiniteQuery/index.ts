import { getWorkspaceMembersApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/members";
import { workspaceKeys } from "@api/queryKey/workspace";
import { useInfiniteQuery } from "@tanstack/react-query";
/** 이름 선택에 쓸 현재 멤버 목록. 목록 실패는 직접 이름 입력을 막지 않아요. */
export default function useWorkspaceMembersInfiniteQuery(workspaceId: number) {
  return useInfiniteQuery({
    queryKey: workspaceKeys.members(workspaceId),
    initialPageParam: null as string | null,
    queryFn: ({ pageParam }) =>
      getWorkspaceMembersApi({ workspaceId, cursor: pageParam }),
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    retry: false,
  });
}
