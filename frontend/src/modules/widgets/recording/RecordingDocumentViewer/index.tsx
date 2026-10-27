import styled from "@emotion/styled";
import MeetingDocumentList from "@features/document/MeetingDocumentList";
import RecordingAudioPlayer from "@features/recording/RecordingAudioPlayer";
import useRedirectToLoginOnUnauthorized from "@hooks/domain/auth/useRedirectToLoginOnUnauthorized";
import useRecordingDocumentStatus from "@hooks/domain/recording/useRecordingDocumentStatus";
import useRetryFailedRecording from "@hooks/domain/recording/useRetryFailedRecording";
import Chip from "@primitives/ui/Chip";
import Divider from "@primitives/ui/Divider";
import { formatDate } from "@utils/formatDate";
import { formatDurationFromSeconds } from "@utils/formatDurationFromSeconds";
import { useId } from "react";
import { useParams } from "react-router";

import { useMeetingSummaryRetry } from "./model/useMeetingSummaryRetry";
import MeetingSummary from "./ui/MeetingSummary";
import NoContentState from "./ui/NoContentState";
import OrganizingState from "./ui/OrganizingState";
import ProcessingFailedState from "./ui/ProcessingFailedState";
import RecordingDocumentCopyButton from "./ui/RecordingDocumentCopyButton";
import RecordingDocumentLoadFailed from "./ui/RecordingDocumentLoadFailed";
import RecordingDocumentSkeleton from "./ui/RecordingDocumentSkeleton";

/**
 * 녹음 문서 섹션(STT-R36). 녹음 하나의 제목 · 녹음 음성 · 회의 전체 요약 · 이 회의에서 나온 문서를 위에서부터 한 열로 보여줘요.
 * 전사는 이 섹션이 아니라 오른쪽 원문 패널이 보여 주고, 패널을 둘지는 레이아웃이 같은 처리 상태로 정해요(STT-R39).
 * 어느 녹음 문서를 보고 있는지는 GNB의 위치 표시가 `워크스페이스 이름 › 문서 › 녹음 제목`으로 알려요.
 *
 * 제목은 서버가 준 그대로예요. AI 녹음 제목이 없으면 서버가 「OO 님의 녹음」으로 채워 줘요(STT-R37).
 * 제목 아래에는 녹음을 시작한 날과 녹음 길이를 둬요.
 * 회의 전체 요약이 완료일 때만 제목 줄 오른쪽 끝에 녹음 제목과 요약을 복사하는 버튼을 둬요(STT-R38).
 *
 * 그 아래는 처리 상태(`useRecordingDocumentStatus`)마다 달라요(STT-R42).
 * - 문서 정리 중: 정리 중 안내만. 처리가 끝나면 녹음 문서를 다시 받아 저절로 바뀌어요. 3초마다 다시 묻는 일은 이 섹션만 해요
 * - 전사 실패 · 주제 분류 실패: 실패 안내. 서버가 다시 시도할 수 있다고 하면 「다시 시도」로 전사나 주제 분류부터 다시 접수하고, 접수되면 정리 중으로 바뀌어요
 * - 정리할 내용 없음: 녹음 음성과 「변환된 음성 기록이 없어요.」
 * - 완료 · 문서 생성 실패: 녹음 음성, 회의 전체 요약, 구분선, 이 회의에서 나온 문서. 문서 생성 실패의 다시 시도는 이 회의에서 나온 문서의 실패 카드에 있어요
 *
 * 녹음 음성은 정리를 마친 녹음(완료 · 문서 생성 실패 · 정리할 내용 없음)에만 둬요(STT-R50). 다시 시도 버튼은 현재 멤버 누구에게나 보여요.
 *
 * 주소의 두 id는 숫자인지 확인하지 않고 그대로 요청해요. 잘못된 주소인지는 서버가 판단하고, 여기서는 그 응답에 따라 녹음을 불러오지 못했다고 알려요.
 * 로그인이 풀렸으면(401) 로그인 화면으로 보내요.
 */
export default function RecordingDocumentViewer() {
  const titleId = useId();
  const params = useParams();
  const workspaceId = Number(params.workspaceId);
  const recordingId = Number(params.recordingId);
  const { status, recordingDocument, error, retryLoad } =
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
  const hasDocuments = status === "completed" || status === "documentFailed";
  const hasPlayer = hasDocuments || status === "noContent";
  // 요약이 완료일 때만 복사할 요약이 있어요. 요약 자리가 없는 처리 상태에서는 요약이 와도 버튼을 두지 않아요
  const copyableSummary =
    hasDocuments && meetingSummary?.status === "SUCCEEDED"
      ? meetingSummary.content
      : null;

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

      {status === "noContent" && <NoContentState />}

      {hasDocuments && (
        <>
          <MeetingSummary
            meetingSummary={meetingSummary}
            onRetry={retryMeetingSummary}
            isRetrying={isMeetingSummaryRetrying}
          />
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
