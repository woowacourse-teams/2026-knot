import { getRecordingsApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings";
import { isHttpError } from "@api/httpClient/error";
import { recordingKeys } from "@api/queryKey/recording";
import { useInfiniteQuery } from "@tanstack/react-query";

/** 문서 정리 중인 행이 있는 동안 목록을 다시 받는 간격(ms). 현재 녹음 조회와 같아요 */
const POLL_INTERVAL_MS = 3000;

interface UseRecordingsInfiniteQueryParams {
  workspaceId: number;
}

/**
 * 홈 「녹음 기록」 카드가 쓰는, 워크스페이스의 끝난 녹음을 한 페이지씩 이어 받는 쿼리 훅.
 *
 * 첫 페이지는 커서 없이 받고, 다음 페이지는 앞 응답의 `nextCursor`를 그대로 보내요. `nextCursor`가 null이면 더 받을 페이지가 없어요.
 * 서버는 상태 변화를 밀어 주지 않아서, 받은 행 중 문서 정리 중(`PROCESSING`)이 있는 동안 3초마다 받은 페이지를 다시 받아요.
 * 다시 받아도 결과가 같은 오류(4xx)를 받으면 멈추고, 네트워크 끊김이나 서버 오류는 같은 간격으로 계속 받아요.
 * 홈 재진입과 창 포커스 때는 기본 설정대로 다시 받아요.
 *
 * id가 정수인지는 확인하지 않고 그대로 요청해요. 잘못된 id인지는 서버가 판단해 400으로 답해요.
 */
const useRecordingsInfiniteQuery = ({
  workspaceId,
}: UseRecordingsInfiniteQueryParams) => {
  return useInfiniteQuery({
    queryKey: recordingKeys.list(workspaceId),
    queryFn: ({ pageParam }) =>
      getRecordingsApi({ workspaceId, cursor: pageParam }),
    // 첫 페이지는 커서를 보내지 않아요. 빈 문자열을 보내면 서버가 400으로 거절해요
    initialPageParam: undefined as string | undefined,
    getNextPageParam: ({ nextCursor }) => nextCursor ?? undefined,
    refetchInterval: ({ state: { data, error } }) => {
      if (isHttpError(error) && error.isClientError) return false;

      const hasProcessing = data?.pages.some(({ items }) =>
        items.some(({ processingStatus }) => processingStatus === "PROCESSING"),
      );

      return hasProcessing ? POLL_INTERVAL_MS : false;
    },
  });
};

export default useRecordingsInfiniteQuery;
