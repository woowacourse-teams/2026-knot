import { getDocumentRecordingsApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/documentRecordings";
import { documentKeys } from "@api/queryKey/document";
import { useInfiniteQuery } from "@tanstack/react-query";

interface UseDocumentRecordingsInfiniteQueryParams {
  workspaceId: number;
}

/**
 * 문서 탭 「녹음별」 탭이 쓰는, 문서가 나온 녹음 묶음을 한 페이지씩 이어 받는 쿼리 훅.
 *
 * 첫 페이지는 커서 없이 받고, 다음 페이지는 앞 응답의 `nextCursor`를 그대로 보내요. `nextCursor`가 null이면 더 받을 페이지가 없어요.
 * 녹음 하나의 문서는 한 페이지에 모두 담겨 와서, 페이지를 합쳐도 같은 녹음이 두 번 나오지 않아요.
 *
 * id가 정수인지는 확인하지 않고 그대로 요청해요. 잘못된 id인지는 서버가 판단해 400으로 답해요.
 */
const useDocumentRecordingsInfiniteQuery = ({
  workspaceId,
}: UseDocumentRecordingsInfiniteQueryParams) => {
  return useInfiniteQuery({
    queryKey: documentKeys.recordingList({ workspaceId }),
    queryFn: ({ pageParam }) =>
      getDocumentRecordingsApi({ workspaceId, cursor: pageParam }),
    // 첫 페이지는 커서를 보내지 않아요. 빈 문자열을 보내면 서버가 400으로 거절해요
    initialPageParam: undefined as string | undefined,
    getNextPageParam: ({ nextCursor }) => nextCursor ?? undefined,
  });
};

export default useDocumentRecordingsInfiniteQuery;
