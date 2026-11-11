import styled from "@emotion/styled";
import MeetingDocumentList from "@features/document/MeetingDocumentList";
import RecordingAudioPlayer from "@features/recording/RecordingAudioPlayer";
import useRedirectToLoginOnUnauthorized from "@hooks/domain/auth/useRedirectToLoginOnUnauthorized";
import useRecordingDocumentStatus from "@hooks/domain/recording/useRecordingDocumentStatus";
import useRetryFailedRecording from "@hooks/domain/recording/useRetryFailedRecording";
import Chip from "@primitives/ui/Chip";
import Divider from "@primitives/ui/Divider";
import UpdatePreparingNotice from "@primitives/ui/UpdatePreparingNotice";
import { formatDate } from "@utils/formatDate";
import { formatDurationFromSeconds } from "@utils/formatDurationFromSeconds";
import { useId } from "react";
import { useParams } from "react-router";

import { useMeetingSummaryRetry } from "./model/useMeetingSummaryRetry";
import { useRecordingDocumentAnalytics } from "./model/useRecordingDocumentAnalytics";
import MeetingSummary from "./ui/MeetingSummary";
import NoContentState from "./ui/NoContentState";
import OrganizingState from "./ui/OrganizingState";
import ProcessingFailedState from "./ui/ProcessingFailedState";
import RecordingDocumentCopyButton from "./ui/RecordingDocumentCopyButton";
import RecordingDocumentLoadFailed from "./ui/RecordingDocumentLoadFailed";
import RecordingDocumentSkeleton from "./ui/RecordingDocumentSkeleton";

/** 전사·전체 요약·주제별 문서가 준비되는 대로 각각 표시하는 녹음 상세. */
export default function RecordingDocumentViewer() {
  const titleId = useId();
  const params = useParams();
  const workspaceId = Number(params.workspaceId);
  const recordingId = Number(params.recordingId);
  const { status, recordingDocument, error, retryLoad, isTranscriptAvailable } =
    useRecordingDocumentStatus({ workspaceId, recordingId, isPolling: true });
  useRedirectToLoginOnUnauthorized({ error });
  const failedStatus =
    status === "transcriptionFailed"
      ? "transcriptionFailed"
      : status === "topicFailed"
        ? "documentFailed"
        : null;
  const { handleRetry, isRetrying } = useRetryFailedRecording({
    surface: "recording_document",
    workspaceId,
    recordingId,
    failedStatus,
    isRetryable: recordingDocument?.isRetryable ?? false,
    retryableJobIds: recordingDocument?.retryableJobIds ?? [],
  });
  const { retryMeetingSummary, isMeetingSummaryRetrying } =
    useMeetingSummaryRetry({ workspaceId, recordingId });
  const { trackMeetingSummaryCopied } = useRecordingDocumentAnalytics({
    workspaceId,
    recordingId,
    status,
    recordingDocument,
  });

  if (status === "unsupported") {
    return <UpdatePreparingNotice onCheck={retryLoad} />;
  }

  if (status === "loadFailed") {
    return <RecordingDocumentLoadFailed onRetry={retryLoad} />;
  }

  if (status === "loading" || recordingDocument === undefined) {
    return (
      <Container aria-busy="true" aria-label="녹음 문서를 불러오고 있어요">
        <RecordingDocumentSkeleton />
      </Container>
    );
  }

  const {
    title,
    startedAt,
    recordingDurationSeconds,
    isRetryable,
    meetingSummary,
  } = recordingDocument;
  const hasDocuments =
    recordingDocument.topicClassificationStatus === "SUCCEEDED" ||
    status === "completed" ||
    status === "documentFailed";
  const hasPlayer =
    isTranscriptAvailable || hasDocuments || status === "noContent";
  const isEmptyTranscript = recordingDocument.transcriptStatus === "EMPTY";
  // 요약이 완료일 때만 복사할 요약이 있어요. 요약 자리가 없는 처리 상태에서는 요약이 와도 버튼을 두지 않아요
  const copyableSummary =
    meetingSummary?.status === "SUCCEEDED" ? meetingSummary.content : null;

  return (
    // 다른 녹음 문서로 옮기면 「복사됨」 같은 앞 녹음의 표시가 남지 않게, 녹음마다 새로 그려요
    <Container key={recordingId} aria-labelledby={titleId}>
      <TitleBlock>
        <TitleRow>
          <Title id={titleId}>{title}</Title>
          {copyableSummary !== null && (
            <RecordingDocumentCopyButton
              title={title}
              content={copyableSummary}
              onCopied={trackMeetingSummaryCopied}
            />
          )}
        </TitleRow>
        <Meta>
          <StartedDate dateTime={startedAt}>
            {formatDate(startedAt)}
          </StartedDate>
          <Chip>{formatDurationFromSeconds(recordingDurationSeconds)}</Chip>
        </Meta>
      </TitleBlock>

      {status === "organizing" && <OrganizingState />}

      {failedStatus !== null && (
        <ProcessingFailedState
          isTranscriptionFailed={status === "transcriptionFailed"}
          onRetry={isRetryable ? handleRetry : undefined}
          isRetrying={isRetrying}
        />
      )}

      {hasPlayer && <RecordingAudioPlayer />}

      {isEmptyTranscript && <NoContentState />}

      {!isEmptyTranscript && (meetingSummary !== null || hasDocuments) && (
        <MeetingSummary
          meetingSummary={meetingSummary}
          onRetry={retryMeetingSummary}
          isRetrying={isMeetingSummaryRetrying}
        />
      )}
      {status === "noContent" && !isEmptyTranscript && (
        <p>이 녹음에서 생성된 주제별 문서는 없어요.</p>
      )}

      {hasDocuments && (
        <>
          <Divider />
          <MeetingDocumentList />
        </>
      )}
    </Container>
  );
}

/** 피그마 Content: 폭 720px, 묶음 사이 40px */
const Container = styled.section`
  display: flex;
  flex-direction: column;
  gap: 2.5rem; /* 40px */
  width: 100%;
  max-width: 45rem; /* 720px */
`;

/** 피그마 TitleBlock: 제목 줄과 날짜 줄 사이 4px */
const TitleBlock = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.25rem; /* 4px */
`;

/** 피그마 TitleRow: 왼쪽에 제목, 오른쪽 끝에 복사 버튼. 둘 사이 16px, 세로 가운데 */
const TitleRow = styled.div`
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem; /* 16px */
  min-height: 2.5rem; /* 40px, 복사 버튼이 없어도 같은 높이 */
`;

/** 제목이 길면 버튼 자리를 남기고 줄을 바꿔요 */
const Title = styled.h2`
  flex: 1;
  min-width: 0;
  ${({ theme }) => theme.text.heading02};
  color: ${({ theme }) => theme.neutral[900]};
  overflow-wrap: break-word;
`;

/** 피그마 Meta: 날짜와 녹음 길이 칩 사이 8px */
const Meta = styled.div`
  display: flex;
  align-items: center;
  gap: 0.5rem; /* 8px */
`;

const StartedDate = styled.time`
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[500]};
`;
