import useDocumentRecordingsInfiniteQuery from "@api/queries/useDocumentRecordingsInfiniteQuery";
import { documentKeys } from "@api/queryKey/document";
import styled from "@emotion/styled";
import useApiSupportState from "@hooks/common/useApiSupportState";
import useLoadMoreOnReachEnd from "@hooks/common/useLoadMoreOnReachEnd";
import useWorkspaceAccessGuard from "@hooks/domain/workspace/useWorkspaceAccessGuard";
import InlineError from "@primitives/ui/InlineError";
import LoadingIndicator from "@primitives/ui/LoadingIndicator";
import UpdatePreparingNotice from "@primitives/ui/UpdatePreparingNotice";
import { getRouterPath } from "@routes/PATH_ROUTE";
import { useQueryClient } from "@tanstack/react-query";
import { isBadRequestError } from "@utils/isBadRequestError";

import { useWorkspaceId } from "./model/useWorkspaceId";
import RecordingDocumentListEmpty from "./ui/RecordingDocumentListEmpty";
import RecordingDocumentListLoadFailed from "./ui/RecordingDocumentListLoadFailed";
import RecordingDocumentListSkeleton from "./ui/RecordingDocumentListSkeleton";
import RecordingDocumentRow from "./ui/RecordingDocumentRow";
import RecordingGroup from "./ui/RecordingGroup";

/**
 * 문서 탭 「녹음별」 탭의 목록. 문서가 1개 이상 나온 녹음을 녹음 시작 최신순으로 20개씩 이어 받아, 녹음마다 그 녹음에서 나온 문서를 모두 펼쳐 보여줘요(DOC-R21~R24).
 *
 * 제목 「문서」와 탭은 문서 탭 섹션(`DocumentArchive`)이 그리고, 여기서는 탭이 가리키는 목록만 그려요.
 *
 * 녹음 행을 누르면 그 녹음의 녹음 문서로, 문서 행을 누르면 그 문서의 문서 보기로 가요. 묶음과 순서는 서버가 준 그대로이고 여기서 다시 거르거나 정렬하지 않아요.
 *
 * 목록을 받지 못한 동안에는 상태 화면이 목록 자리를 대신 그려요.
 * - 불러오는 중: `RecordingDocumentListSkeleton`. 401 · 403 · 404로 다른 화면에 보내는 동안에도 이 화면이에요.
 * - 불러오기 실패: `RecordingDocumentListLoadFailed`. 「다시 시도」는 처음부터 다시 받아요.
 * - 문서가 나온 녹음 없음: `RecordingDocumentListEmpty`.
 *
 * 목록 끝까지 내리면 다음 20개를 이어 받아요. 다음 20개를 받지 못하면 이미 보인 녹음은 그대로 두고 목록 끝에서만 안내와 「다시 시도」를 보여줘요.
 * 커서가 거절(400)된 실패는 같은 커서로 다시 보내도 같아서, 그때의 「다시 시도」는 처음부터 다시 받아요.
 *
 * 주소의 워크스페이스 id는 숫자인지 확인하지 않고 그대로 요청해요. 잘못된 주소인지는 서버가 판단하고, 여기서는 그 응답에 따라 목록을 불러오지 못했다고 알려요.
 */
export default function RecordingDocumentList() {
  const { workspaceId } = useWorkspaceId();
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
  } = useDocumentRecordingsInfiniteQuery({ workspaceId });

  // 이 신규 경로의 미지원은 워크스페이스 접근 거부가 아니에요. 실제 인증·권한·자원 오류는 기존 가드가 처리해요.
  const { isUnsupported, isUnavailable } = useApiSupportState({
    queryKey: documentKeys.recordingList({ workspaceId }), error,
  });
  const { isRedirecting } = useWorkspaceAccessGuard({
    error: isUnsupported ? null : error,
  });

  const { sentinelRef } = useLoadMoreOnReachEnd({
    isEnabled:
      !isUnavailable && hasNextPage && !isFetchingNextPage && !isFetchNextPageError,
    onReachEnd: () => {
      fetchNextPage();
    },
  });

  // 화면을 새로고침하지 않고 목록만 다시 조회해요. 새로고침하면 브라우저 안에만 있는 진행 중인 녹음이 사라져요
  const handleRetry = () => {
    refetch();
  };

  const handleRetryNextPage = () => {
    // 커서가 거절됐으면 같은 커서를 다시 보내도 또 거절돼요. 받아 둔 페이지를 지우고 처음부터 다시 받아요
    if (isBadRequestError(error)) {
      queryClient.resetQueries({
        queryKey: documentKeys.recordingList({ workspaceId }),
      });
      return;
    }

    fetchNextPage();
  };

  // 받은 목록이 없고 실패했을 때 실패 안내를 보여줘요. 다른 화면으로 보내는 중이면 가는 동안 안내가 잠깐 보이지 않게 빼요
  const shouldShowLoadFailed = (data === undefined || isUnavailable) && isError && !isRedirecting;

  // 정상 캐시가 남아 있어도 현재 미지원이면 이전 링크를 실행시키지 않아요.
  if (isUnsupported) {
    return <UpdatePreparingNotice onCheck={handleRetry} />;
  }

  if (isRedirecting) return <RecordingDocumentListSkeleton />;

  if (shouldShowLoadFailed) {
    return <RecordingDocumentListLoadFailed onRetry={handleRetry} />;
  }

  if (data === undefined) {
    return <RecordingDocumentListSkeleton />;
  }

  const recordings = data.pages.flatMap(({ items }) => items);

  if (recordings.length === 0) return <RecordingDocumentListEmpty />;

  return (
    <Container>
      <Groups>
        {recordings.map(
          ({
            recordingId,
            title,
            startedAt,
            recordingDurationSeconds,
            documents,
          }) => (
            <RecordingGroup
              key={recordingId}
              recordingHref={getRouterPath({
                routeKey: "RECORDING_DOCUMENT",
                params: {
                  workspaceId: String(workspaceId),
                  recordingId: String(recordingId),
                },
              })}
              title={title}
              startedAt={startedAt}
              recordingDurationSeconds={recordingDurationSeconds}
            >
              {documents.map(({ id, title: documentTitle, summary }) => (
                <RecordingDocumentRow
                  key={id}
                  href={getRouterPath({
                    routeKey: "DOCUMENT",
                    params: {
                      workspaceId: String(workspaceId),
                      documentId: String(id),
                    },
                  })}
                  title={documentTitle}
                  summary={summary}
                />
              ))}
            </RecordingGroup>
          ),
        )}
      </Groups>
      {isFetchingNextPage && (
        <LoadMore>
          <LoadingIndicator
            label="다음 녹음을 불러오고 있어요"
            size="1.25rem"
          />
        </LoadMore>
      )}
      {isFetchNextPageError && (
        <LoadMore>
          <InlineError
            message="녹음을 더 불러오지 못했어요. 다시 시도해 주세요."
            onRetry={handleRetryNextPage}
            align="center"
            size="sm"
          />
        </LoadMore>
      )}
      {/* 목록 끝 표시. 화면에 들어오면 다음 페이지를 받아요 */}
      <Sentinel ref={sentinelRef} aria-hidden />
    </Container>
  );
}

const Container = styled.div`
  display: flex;
  flex-direction: column;
`;

/** 피그마 Recordings: 녹음 카드 사이 32px */
const Groups = styled.div`
  display: flex;
  flex-direction: column;
  gap: 2rem; /* 32px */
`;

/** 피그마 「다음 녹음 불러오는 중 · 실패」: 마지막 카드 아래 32px에 가운데 정렬 */
const LoadMore = styled.div`
  display: flex;
  justify-content: center;
  padding-top: 2rem; /* 32px */
`;

const Sentinel = styled.div`
  height: 1px;
`;
