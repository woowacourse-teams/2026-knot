import useRecordingsInfiniteQuery from "@api/queries/useRecordingsInfiniteQuery";
import { recordingKeys } from "@api/queryKey/recording";
import useLoadMoreOnReachEnd from "@hooks/common/useLoadMoreOnReachEnd";
import useWorkspaceAccessGuard from "@hooks/domain/workspace/useWorkspaceAccessGuard";
import { useQueryClient } from "@tanstack/react-query";
import { isBadRequestError } from "@utils/isBadRequestError";

import { useWorkspaceId } from "./useWorkspaceId";

/**
 * 녹음 기록 목록(STT-R31 · R33). 목록 끝의 `sentinelRef`가 보이면 다음 묶음을 이어 받아요.
 *
 * 다시 받은 페이지 경계에서 같은 녹음이 겹쳐 올 수 있어 녹음 ID로 한 행만 둬요.
 * 첫 묶음이든 다음 묶음이든 받지 못하면 `isLoadFailed`예요. 한 번 받은 뒤 다시 받기만 실패하면 받은 행을 그대로 보여 줘요.
 * `handleRetry`는 이 카드의 목록만 다시 받아요. 커서가 거절(400)된 다음 묶음은 같은 커서로 다시 보내도 같아서 처음부터 다시 받아요.
 * 401 · 403 · 404면 다른 화면으로 보내고, 보내는 동안에는 실패 안내 대신 불러오는 중이에요.
 */
export const useRecordingHistory = () => {
  const workspaceId = Number(useWorkspaceId());
  const queryClient = useQueryClient();
  const {
    data,
    error,
    isError,
    refetch,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
    isFetchNextPageError,
  } = useRecordingsInfiniteQuery({ workspaceId });

  // 로그인이 풀렸으면 로그인 화면으로, 워크스페이스에 들어갈 수 없으면 마지막으로 본 다른 워크스페이스나 선택 화면으로 보내고 알려요
  const { isRedirecting } = useWorkspaceAccessGuard({ error });

  const { sentinelRef } = useLoadMoreOnReachEnd({
    isEnabled: hasNextPage && !isFetchingNextPage && !isFetchNextPageError,
    onReachEnd: () => {
      fetchNextPage();
    },
  });

  // 화면을 새로고침하지 않고 목록만 다시 받아요. 새로고침하면 브라우저 안에만 있는 진행 중인 녹음이 사라져요
  const handleRetry = () => {
    if (!isFetchNextPageError) {
      refetch();
      return;
    }

    if (isBadRequestError(error)) {
      queryClient.resetQueries({ queryKey: recordingKeys.list(workspaceId) });
      return;
    }

    fetchNextPage();
  };

  const recordings = [
    ...new Map(
      (data?.pages ?? [])
        .flatMap(({ items }) => items)
        .map((item) => [item.recordingId, item]),
    ).values(),
  ];

  return {
    workspaceId,
    recordings,
    isLoading: data === undefined && (!isError || isRedirecting),
    isLoadFailed:
      ((data === undefined && isError) || isFetchNextPageError) &&
      !isRedirecting,
    hasNextPage,
    isFetchingNextPage,
    sentinelRef,
    handleRetry,
  };
};
