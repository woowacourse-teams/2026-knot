import { getSearchNotionConnectionApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/search/notionConnection";
import { searchKeys } from "@api/queryKey/search";
import { useQuery } from "@tanstack/react-query";

interface UseSearchNotionConnectionQueryParams {
  workspaceId: number;
}

/** 워크스페이스의 노션 연결(연결 상태, 연결한 노션과 사람, 끊을 수 있는지)을 조회하는 쿼리 훅 */
const useSearchNotionConnectionQuery = ({
  workspaceId,
}: UseSearchNotionConnectionQueryParams) => {
  return useQuery({
    queryKey: searchKeys.notionConnection(workspaceId),
    queryFn: () => getSearchNotionConnectionApi(workspaceId),
  });
};

export default useSearchNotionConnectionQuery;
