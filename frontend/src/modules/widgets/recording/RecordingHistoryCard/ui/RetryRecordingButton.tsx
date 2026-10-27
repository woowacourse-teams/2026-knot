import styled from "@emotion/styled";
import useRetryFailedRecording from "@hooks/domain/recording/useRetryFailedRecording";

import SyncIcon from "@/assets/icons/sync.svg";

interface RetryRecordingButtonProps {
  workspaceId: number;
  recordingId: number;
  processingStatus: "TRANSCRIPTION_FAILED" | "DOCUMENT_FAILED";
  /** 문서 생성 실패에서 다시 접수할 작업 ID. 전사 실패는 빈 배열이에요 */
  retryableJobIds: number[];
  /** 버튼이 어느 녹음의 것인지 알리는 제목 요소의 id */
  describedBy: string;
}

/**
 * 다시 시도할 수 있는 실패 행의 「다시 시도」(STT-R34). 녹음 문서의 다시 시도와 같은 요청을 보내요.
 *
 * 전사 실패면 전사 다시 시도를 한 번, 문서 생성 실패면 작업마다 문서 생성 작업 재시도를 보내요.
 * 접수 · 일부 접수 · 거절 · 응답 유실 뒤에는 목록을 다시 받아 행 상태를 맞추고, 요청을 저절로 다시 보내지 않아요.
 * 응답을 기다리는 동안에는 다시 누를 수 없어요.
 */
export default function RetryRecordingButton({
  workspaceId,
  recordingId,
  processingStatus,
  retryableJobIds,
  describedBy,
}: RetryRecordingButtonProps) {
  const { handleRetry, isRetrying } = useRetryFailedRecording({
    surface: "recording_history",
    workspaceId,
    recordingId,
    failedStatus:
      processingStatus === "TRANSCRIPTION_FAILED"
        ? "transcriptionFailed"
        : "documentFailed",
    isRetryable: true,
    retryableJobIds,
  });

  return (
    <Root
      type="button"
      disabled={isRetrying}
      aria-busy={isRetrying}
      aria-describedby={describedBy}
      onClick={handleRetry}
    >
      <SyncIcon size={14} aria-hidden />
      다시 시도
    </Root>
  );
}

/** 피그마 Button/다시 시도: 알약 모양, 안쪽 6px 12px 6px 10px, 아이콘과 글자 사이 4px */
const Root = styled.button`
  ${({ theme }) => theme.text.caption02};
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  gap: 0.25rem; /* 4px */
  padding: 0.375rem 0.75rem 0.375rem 0.625rem; /* 6px 12px 6px 10px */
  border-radius: 999px;
  background-color: ${({ theme }) => theme.neutral[100]};
  color: ${({ theme }) => theme.neutral[700]};
  white-space: nowrap;

  &:disabled {
    cursor: default;
    opacity: 0.6;
  }

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: 2px;
  }
`;
