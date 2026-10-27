import useDocumentsByRecordingQuery from "@api/queries/useDocumentsByRecordingQuery";
import useRecordingDocumentQuery from "@api/queries/useRecordingDocumentQuery";
import styled from "@emotion/styled";
import useRedirectToLoginOnUnauthorized from "@hooks/domain/auth/useRedirectToLoginOnUnauthorized";
import useRetryFailedRecording from "@hooks/domain/recording/useRetryFailedRecording";
import InlineError from "@primitives/ui/InlineError";
import Skeleton from "@primitives/ui/Skeleton";
import { getRouterPath } from "@routes/PATH_ROUTE";
import { useId } from "react";
import { useParams } from "react-router";

import DocumentFailedCard from "./ui/DocumentFailedCard";
import MeetingDocumentCard from "./ui/MeetingDocumentCard";
import { sortByCreatedAt } from "./utils/sortByCreatedAt";

/**
 * 녹음 문서의 「이 회의에서 나온 문서」(STT-R52). 그 녹음에서 나온 주제별 문서를 두 열 카드로 보여 주고, 카드를 누르면 문서 보기로 가요.
 *
 * 보여 주는 문서와 순서는 문서 탭 「녹음별」 탭에서 그 녹음 아래에 보이는 문서와 같아요. 「녹음별」 탭처럼 만든 순서로 늘어놓아요.
 * 머리의 「이 회의에서 나온 문서」 옆에 만들어진 문서 수를 둬요. 실패 카드는 세지 않고, 만들어진 문서가 없으면 수를 두지 않아요.
 *
 * 문서 생성이 실패한 녹음(`DOCUMENT_FAILED`)이면 문서 카드 뒤에 「문서를 만들지 못했어요」 카드를 하나 둬요.
 * 서버가 다시 시도할 수 있다고 하면(`isRetryable`) 그 카드의 「다시 시도」가 실패한 문서 작업을 모두 다시 접수하고, 접수되면 녹음 문서가 정리 중으로 바뀌어요.
 *
 * 목록을 받지 못한 동안에는 이 자리만 상태를 그려요. 불러오는 중은 카드 모양 덩어리, 불러오기 실패는 안내와 「다시 시도」예요.
 * 제목 · 회의 전체 요약 · 전사 · 녹음 음성은 그대로 읽을 수 있어요.
 *
 * 어느 처리 상태에서 이 자리를 둘지(완료 · 문서 생성 실패)는 이 목록을 놓는 쪽이 정해요. 녹음 문서는 이 목록이 있어도 주제별 문서가 아니에요(STT-R40).
 */
export default function MeetingDocumentList() {
  const titleId = useId();
  const params = useParams();
  const workspaceId = Number(params.workspaceId);
  const recordingId = Number(params.recordingId);
  // 녹음 문서 섹션과 같은 쿼리 키라 요청이 더 나가지 않아요
  const { data: recordingDocument } = useRecordingDocumentQuery({
    workspaceId,
    recordingId,
  });
  const {
    data: documentList,
    error,
    isError,
    refetch,
  } = useDocumentsByRecordingQuery({
    workspaceId,
    recordingSessionId: recordingId,
  });
  const { isUnauthorized } = useRedirectToLoginOnUnauthorized({ error });
  const isDocumentFailed =
    recordingDocument?.processingStatus === "DOCUMENT_FAILED";
  const isRetryable = recordingDocument?.isRetryable ?? false;
  const { handleRetry, isRetrying } = useRetryFailedRecording({
    surface: "recording_document",
    workspaceId,
    recordingId,
    failedStatus: isDocumentFailed ? "documentFailed" : null,
    isRetryable,
    retryableJobIds: recordingDocument?.retryableJobIds ?? [],
  });

  const documents =
    documentList === undefined
      ? undefined
      : sortByCreatedAt(documentList.items);

  // 화면을 새로고침하지 않고 목록만 다시 조회해요. 새로고침하면 브라우저 안에만 있는 진행 중인 녹음이 사라져요
  const handleRetryLoad = () => {
    refetch();
  };

  const renderContent = () => {
    if (documents === undefined) {
      if (isError && !isUnauthorized) {
        return (
          <InlineError
            message="목록을 불러오지 못했어요. 다시 시도해 주세요."
            onRetry={handleRetryLoad}
          />
        );
      }

      return (
        <Grid aria-hidden>
          {/* 피그마 Card/RelatedDoc: 높이 80px, 모서리 16px */}
          <Skeleton height={5} radius={1} />
          <Skeleton height={5} radius={1} />
        </Grid>
      );
    }

    return (
      <Grid>
        {documents.map(({ id, title, summary }) => (
          <MeetingDocumentCard
            key={id}
            href={getRouterPath({
              routeKey: "DOCUMENT",
              params: {
                workspaceId: String(workspaceId),
                documentId: String(id),
              },
            })}
            title={title}
            summary={summary}
          />
        ))}
        {isDocumentFailed && (
          <DocumentFailedCard
            onRetry={isRetryable ? handleRetry : undefined}
            isRetrying={isRetrying}
          />
        )}
      </Grid>
    );
  };

  return (
    <Root aria-labelledby={titleId}>
      <Heading>
        <Title id={titleId}>이 회의에서 나온 문서</Title>
        {documents !== undefined && documents.length > 0 && (
          <Count>{documents.length}</Count>
        )}
      </Heading>
      {renderContent()}
    </Root>
  );
}

/** 피그마 이 회의에서 나온 문서: 머리와 카드 사이 16px */
const Root = styled.section`
  display: flex;
  flex-direction: column;
  gap: 1rem; /* 16px */
`;

/** 피그마 Heading: 제목과 문서 수 사이 6px, 글자 아래 줄 맞춤 */
const Heading = styled.div`
  display: flex;
  align-items: baseline;
  gap: 0.375rem; /* 6px */
`;

const Title = styled.h3`
  ${({ theme }) => theme.text.heading04};
  color: ${({ theme }) => theme.neutral[900]};
`;

const Count = styled.span`
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[500]};
`;

/** 피그마 Grid: 두 열, 열 사이 20px, 줄 사이 16px. 좁은 화면에서는 한 열로 쌓아요 */
const Grid = styled.ul`
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(17.5rem, 100%), 1fr));
  gap: 1rem 1.25rem; /* 16px 20px */
`;
