import type { Theme } from "@emotion/react";
import styled from "@emotion/styled";
import Chip from "@primitives/ui/Chip";
import LinkTo from "@primitives/ui/LinkTo";
import { getRouterPath } from "@routes/PATH_ROUTE";
import { formatDate } from "@utils/formatDate";
import { formatDurationFromSeconds } from "@utils/formatDurationFromSeconds";
import { useId } from "react";

import ChevronRightIcon from "@/assets/icons/chevronRight.svg";
import MicIcon from "@/assets/icons/mic.svg";

import { PROCESSING_STATUS_LABEL } from "../constants/recordingStatus";
import type {
  RecordingHistoryItem,
  RecordingProcessingStatus,
} from "../types/recordingHistory";
import RetryRecordingButton from "./RetryRecordingButton";

interface RecordingHistoryRowProps {
  workspaceId: number;
  recording: RecordingHistoryItem;
}

/**
 * 녹음 기록 한 행. 마이크 배지, 녹음 제목, 그 아래 줄과 오른쪽 버튼으로 이뤄져요(STT-R32 · R51).
 *
 * 아래 줄은 완료 행이면 녹음 날짜와 녹음 길이 칩, 그 밖의 행이면 처리 상태예요.
 * 오른쪽은 다시 시도할 수 있는 실패 행이면 「다시 시도」, 그 밖에는 그 녹음의 녹음 문서로 가는 「녹음 보기」예요.
 * 문서 정리 중 행의 「녹음 보기」는 누를 수 없어요(STT-R34).
 * 제목은 한 줄로 자르고, 마우스를 올리면 브라우저 기본 툴팁으로 전체 제목을 보여 줘요.
 */
export default function RecordingHistoryRow({
  workspaceId,
  recording: {
    recordingId,
    title,
    startedAt,
    recordingDurationSeconds,
    processingStatus,
    isRetryable,
    retryableJobIds,
  },
}: RecordingHistoryRowProps) {
  const titleId = useId();
  const isFailed =
    processingStatus === "TRANSCRIPTION_FAILED" ||
    processingStatus === "DOCUMENT_FAILED";

  return (
    <Root>
      <Badge aria-hidden $isCompleted={processingStatus === "COMPLETED"}>
        <MicIcon size={18} />
      </Badge>

      <Info>
        <Title id={titleId} title={title}>
          {title}
        </Title>
        {processingStatus === "COMPLETED" ? (
          <Meta>
            <StartedDate dateTime={startedAt}>
              {formatDate(startedAt)}
            </StartedDate>
            <Chip>{formatDurationFromSeconds(recordingDurationSeconds)}</Chip>
          </Meta>
        ) : (
          <Status $processingStatus={processingStatus}>
            <StatusDot aria-hidden $processingStatus={processingStatus} />
            <StatusText>{PROCESSING_STATUS_LABEL[processingStatus]}</StatusText>
          </Status>
        )}
      </Info>

      {isFailed && isRetryable ? (
        <RetryRecordingButton
          workspaceId={workspaceId}
          recordingId={recordingId}
          processingStatus={processingStatus}
          retryableJobIds={retryableJobIds}
          describedBy={titleId}
        />
      ) : processingStatus === "PROCESSING" ? (
        // 정리가 끝나야 녹음 문서를 열 수 있어 누를 수 없게 둬요
        <ViewRecording
          as="span"
          role="link"
          aria-disabled="true"
          aria-describedby={titleId}
          $isDisabled
        >
          녹음 보기
          <ChevronRightIcon size={12} aria-hidden />
        </ViewRecording>
      ) : (
        <ViewRecording
          as={LinkTo}
          href={getRouterPath({
            routeKey: "RECORDING_DOCUMENT",
            params: {
              workspaceId: String(workspaceId),
              recordingId: String(recordingId),
            },
          })}
          aria-describedby={titleId}
        >
          녹음 보기
          <ChevronRightIcon size={12} aria-hidden />
        </ViewRecording>
      )}
    </Root>
  );
}

type RowStatus = Exclude<RecordingProcessingStatus, "COMPLETED">;

/** 처리 상태 줄의 글자색과 점 색. 정리 중은 청록, 실패는 어두운 빨강, 정리할 내용 없음은 경고색 없이 회색이에요(STT-R16) */
const statusColors = (theme: Theme) =>
  ({
    PROCESSING: { text: theme.sub.accent[700], dot: theme.sub.accent[500] },
    TRANSCRIPTION_FAILED: {
      text: theme.sub.warning[700],
      dot: theme.sub.warning[700],
    },
    DOCUMENT_FAILED: {
      text: theme.sub.warning[700],
      dot: theme.sub.warning[700],
    },
    NO_CONTENT: { text: theme.neutral[600], dot: theme.neutral[400] },
  }) satisfies Record<RowStatus, { text: string; dot: string }>;

/** 피그마 Row/Recording: 안쪽 여백 20px 24px 18px, 배지 · 글 묶음 · 오른쪽 버튼 사이 12px */
const Root = styled.li`
  display: flex;
  align-items: center;
  gap: 0.75rem; /* 12px */
  padding: 1.25rem 1.5rem 1.125rem; /* 20px 24px 18px */
`;

/** 피그마 Badge: 36px 동그라미 안에 18px 마이크. 바탕 #F0EEEA는 토큰에 없어 가장 가까운 Neutral/100을 써요 */
const Badge = styled.span<{ $isCompleted: boolean }>`
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 2.25rem; /* 36px */
  height: 2.25rem;
  border-radius: 50%;
  background-color: ${({ theme }) => theme.neutral[100]};
  color: ${({ theme, $isCompleted }) =>
    $isCompleted ? theme.neutral[700] : theme.neutral[500]};
`;

/** 피그마 Info: 제목과 아래 줄 사이 4px. 남는 폭을 모두 써요 */
const Info = styled.div`
  display: flex;
  flex: 1 0 0;
  flex-direction: column;
  gap: 0.25rem; /* 4px */
  /* 제목이 길어도 오른쪽 버튼을 밀어내지 않고 말줄임돼요 */
  min-width: 0;
`;

const Title = styled.p`
  ${({ theme }) => theme.text.heading04};
  overflow: hidden;
  color: ${({ theme }) => theme.neutral[900]};
  white-space: nowrap;
  text-overflow: ellipsis;
`;

/** 피그마 Meta: 날짜와 녹음 길이 칩 사이 8px */
const Meta = styled.div`
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem; /* 8px */
`;

const StartedDate = styled.time`
  ${({ theme }) => theme.text.caption01};
  color: ${({ theme }) => theme.neutral[500]};
`;

/** 피그마 Status: 점과 글자 사이 6px */
const Status = styled.p<{ $processingStatus: RowStatus }>`
  ${({ theme }) => theme.text.caption01};
  display: flex;
  align-items: center;
  gap: 0.375rem; /* 6px */
  min-width: 0;
  color: ${({ theme, $processingStatus }) =>
    statusColors(theme)[$processingStatus].text};
`;

const StatusDot = styled.span<{ $processingStatus: RowStatus }>`
  flex-shrink: 0;
  width: 0.5rem; /* 8px */
  height: 0.5rem;
  border-radius: 50%;
  background-color: ${({ theme, $processingStatus }) =>
    statusColors(theme)[$processingStatus].dot};
`;

const StatusText = styled.span`
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
`;

/** 피그마 Link/녹음 보기: 글자와 화살표 사이 2px. 누를 수 없으면 글자와 화살표를 흐리게 둬요 */
const ViewRecording = styled.a<{ $isDisabled?: boolean }>`
  ${({ theme }) => theme.text.caption02};
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  gap: 0.125rem; /* 2px */
  color: ${({ theme, $isDisabled }) =>
    $isDisabled ? theme.neutral[300] : theme.neutral[600]};
  white-space: nowrap;

  & > svg {
    flex-shrink: 0;
    color: ${({ theme, $isDisabled }) =>
      $isDisabled ? theme.neutral[300] : theme.neutral[400]};
  }

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: 2px;
  }
`;
