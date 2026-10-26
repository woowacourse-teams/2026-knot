import type { Theme } from "@emotion/react";
import { MEDIA_QUERY } from "@constants/breakpoint";
import styled from "@emotion/styled";
import Button from "@primitives/ui/Button";

import MicIcon from "@/assets/icons/mic.svg";
import type { CurrentRecordingStatus } from "@/shared/types/recording";

import { RECORDING_STATUS_COPY } from "../constants/recordingStatus";

interface CurrentRecordingItemProps {
  recordingStatus: CurrentRecordingStatus;
  /** 녹음 이름. 예: `유월 님의 녹음` */
  title: string;
  /** 녹음한 시간. 예: `12:48`. 문서 정리 중에는 넘기지 않아 상태 줄에 시간이 없어요 */
  elapsedTime?: string;
  /** 넘기면 녹음 화면으로 가는 「녹음 화면으로」 버튼을 보여 줘요 */
  onOpenRecording?: () => void;
  /** 넘기면 문서 정리 화면으로 가는 「보기」 버튼을 보여 줘요 */
  onOpenRecordingDocuments?: () => void;
  /** 넘기면 실패한 정리를 다시 접수하는 「다시 시도」 버튼을 보여 줘요 */
  onRetry?: () => void;
  /** 다시 시도 요청의 응답을 기다리는 중인지. 그동안 「다시 시도」를 누를 수 없어요 */
  isRetrying?: boolean;
}

/**
 * 진행 중인 녹음 한 칸. 상태 줄(점 + 상태 · 시간) / 녹음 이름 / 안내 한 줄과 오른쪽 버튼으로 이뤄져요.
 */
export default function CurrentRecordingItem({
  recordingStatus,
  title,
  elapsedTime,
  onOpenRecording,
  onOpenRecordingDocuments,
  onRetry,
  isRetrying = false,
}: CurrentRecordingItemProps) {
  const { label, hint } = RECORDING_STATUS_COPY[recordingStatus];
  const isFailed =
    recordingStatus === "transcriptionFailed" ||
    recordingStatus === "documentFailed";

  return (
    <Container>
      <IconChip>
        <MicIcon size={24} />
      </IconChip>

      <Info>
        <Status $recordingStatus={recordingStatus}>
          <StatusDot aria-hidden="true" $recordingStatus={recordingStatus} />
          <StatusText>
            {elapsedTime === undefined ? label : `${label} · ${elapsedTime}`}
          </StatusText>
        </Status>
        <Title>{title}</Title>
        {(!isFailed || onRetry) && <Hint>{hint}</Hint>}
      </Info>

      {onOpenRecording && (
        <Button size="sm" variant="filled" onClick={onOpenRecording}>
          녹음 화면으로
        </Button>
      )}
      {onOpenRecordingDocuments && (
        <Button size="sm" variant="outline" onClick={onOpenRecordingDocuments}>
          보기
        </Button>
      )}
      {onRetry && (
        <Button
          size="sm"
          variant="filled"
          isLoading={isRetrying}
          onClick={onRetry}
        >
          다시 시도
        </Button>
      )}
    </Container>
  );
}

/** 상태 줄의 글자색과 점 색. 녹음 중은 빨강, 일시정지는 회색, 문서 정리 중은 청록, 두 실패는 녹음 중보다 어두운 빨강(Sub/Warning-700)이에요 */
const statusColors = (theme: Theme) =>
  ({
    recording: { text: theme.sub.warning[600], dot: theme.sub.warning[600] },
    paused: { text: theme.neutral[600], dot: theme.neutral[400] },
    organizing: { text: theme.sub.accent[700], dot: theme.sub.accent[500] },
    transcriptionFailed: {
      text: theme.sub.warning[700],
      dot: theme.sub.warning[700],
    },
    documentFailed: {
      text: theme.sub.warning[700],
      dot: theme.sub.warning[700],
    },
  }) satisfies Record<CurrentRecordingStatus, { text: string; dot: string }>;

const Container = styled.div`
  display: flex;
  flex: 1;
  align-items: center;
  gap: 1rem; /* 16px */
  width: 100%;
  min-height: 0;
  padding: 1.25rem; /* 20px */
  border-radius: 1rem; /* 16px */
  background-color: ${({ theme }) => theme.neutral[100]};

  /* 모바일은 버튼을 아래 줄로 내리고 폭을 꽉 채워요(RESP-R5) */
  ${MEDIA_QUERY.mobile} {
    flex-wrap: wrap;
    align-items: flex-start;

    && > button {
      flex-basis: 100%;
      height: 2.75rem; /* 44px */
    }
  }
`;

const IconChip = styled.span`
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 3.25rem; /* 52px */
  height: 3.25rem;
  border-radius: 0.875rem; /* 14px */
  background-color: ${({ theme }) => theme.neutral[0]};
  color: ${({ theme }) => theme.neutral[500]};
`;

const Info = styled.div`
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 0.25rem; /* 4px */
  min-width: 0;
`;

const Status = styled.p<{ $recordingStatus: CurrentRecordingStatus }>`
  display: flex;
  align-items: center;
  gap: 0.375rem; /* 6px */
  color: ${({ theme, $recordingStatus }) =>
    statusColors(theme)[$recordingStatus].text};
  ${({ theme }) => theme.text.caption02};
`;

const StatusDot = styled.span<{ $recordingStatus: CurrentRecordingStatus }>`
  flex-shrink: 0;
  width: 0.5rem; /* 8px */
  height: 0.5rem;
  border-radius: 50%;
  background-color: ${({ theme, $recordingStatus }) =>
    statusColors(theme)[$recordingStatus].dot};
`;

const StatusText = styled.span`
  font-variant-numeric: tabular-nums; /* 시간이 바뀌어도 폭이 흔들리지 않아요 */
  white-space: nowrap;
`;

const Title = styled.p`
  overflow: hidden;
  color: ${({ theme }) => theme.neutral[900]};
  text-overflow: ellipsis;
  white-space: nowrap;
  ${({ theme }) => theme.text.heading04};
`;

const Hint = styled.p`
  overflow: hidden;
  color: ${({ theme }) => theme.neutral[500]};
  text-overflow: ellipsis;
  white-space: nowrap;
  ${({ theme }) => theme.text.caption02};

  ${MEDIA_QUERY.mobile} {
    white-space: normal;
  }
`;
