import useRetryDocumentGenerationJobsMutation from "@api/mutations/useRetryDocumentGenerationJobsMutation";
import useRetryRecordingTranscriptionMutation from "@api/mutations/useRetryRecordingTranscriptionMutation";
import { recordingKeys } from "@api/queryKey/recording";
import { TOAST_MESSAGES } from "@constants/toastMessages";
import useRedirectToLoginOnUnauthorized from "@hooks/domain/auth/useRedirectToLoginOnUnauthorized";
import { useToast } from "@provider/context/toastContext";
import { useQueryClient } from "@tanstack/react-query";
import { isRetryRejectedError } from "@utils/isRetryRejectedError";
import { isUnauthorizedError } from "@utils/isUnauthorizedError";
import { trackRecordingRetryResult } from "@utils/trackRecordingRetryResult";
import { useRef, useState } from "react";

import type { CurrentRecordingStatus } from "@/shared/types/recording";

type FailedRecordingStatus = Extract<
  CurrentRecordingStatus,
  "transcriptionFailed" | "documentFailed"
>;

interface UseRetryFailedRecordingParams {
  /** 다시 시도를 누른 곳. 응답을 사용 기록(`recording_retry_result`)으로 보낼 때 함께 보내요 */
  surface: Parameters<typeof trackRecordingRetryResult>[0]["surface"];
  workspaceId: number;
  recordingId: number | null;
  failedStatus: FailedRecordingStatus | null;
  isRetryable: boolean;
  retryableJobIds: number[];
}

/**
 * 홈 상태 응답이 지정한 재시도 가능한 실패만 접수해요.
 * 접수·거절·응답 유실 후에는 상태를 다시 받아, 성공한 작업을 다음 접수에 섞지 않아요.
 * 서버가 응답하면 다시 시도를 누른 곳과 함께 결과를 사용 기록(`recording_retry_result`)으로 보내요.
 */
const useRetryFailedRecording = ({
  surface,
  workspaceId,
  recordingId,
  failedStatus,
  isRetryable,
  retryableJobIds,
}: UseRetryFailedRecordingParams) => {
  const queryClient = useQueryClient();
  const { show } = useToast();
  const { mutateAsync: requestTranscriptionRetry } =
    useRetryRecordingTranscriptionMutation();
  const { mutateAsync: requestDocumentRetry } =
    useRetryDocumentGenerationJobsMutation();
  const [isRetrying, setIsRetrying] = useState(false);
  const [retryError, setRetryError] = useState<unknown>(null);
  // 같은 렌더 안에서 연속으로 눌러도 상태 갱신을 기다리지 않고 차단해요.
  const isRequestInFlight = useRef(false);

  useRedirectToLoginOnUnauthorized({ error: retryError });

  const retry = async () => {
    if (
      recordingId === null ||
      failedStatus === null ||
      !isRetryable ||
      isRequestInFlight.current
    )
      return;

    isRequestInFlight.current = true;
    setIsRetrying(true);
    setRetryError(null);
    let isAccepted = false;
    // 사용 기록에 남길 요청마다의 오류. 접수된 요청은 null이에요
    let requestErrors: unknown[] = [];

    try {
      if (failedStatus === "transcriptionFailed") {
        await requestTranscriptionRetry({ workspaceId, recordingId });
        isAccepted = true;
        requestErrors = [null];
        return;
      }

      const results = await requestDocumentRetry({
        workspaceId,
        jobIds: [...new Set(retryableJobIds)],
      });
      isAccepted = results.some((result) => result.isAccepted);
      requestErrors = results.map(({ error }) => error);
      const errors = results.flatMap(({ error }) =>
        error === null ? [] : [error],
      );
      const unauthorizedError = errors.find(isUnauthorizedError);
      if (unauthorizedError) {
        setRetryError(unauthorizedError);
        return;
      }
      if (!isAccepted && errors.some((error) => !isRetryRejectedError(error))) {
        show(TOAST_MESSAGES.RETRY_REQUEST_FAILED);
      }
    } catch (error) {
      requestErrors = [error];
      setRetryError(error);
      if (!isUnauthorizedError(error) && !isRetryRejectedError(error)) {
        show(TOAST_MESSAGES.RETRY_REQUEST_FAILED);
      }
    } finally {
      trackRecordingRetryResult({
        surface,
        stage:
          failedStatus === "transcriptionFailed"
            ? "transcription"
            : "document_generation",
        errors: requestErrors,
        workspaceId,
        recordingId,
      });

      try {
        // 접수 성공은 mutation이 재조회까지 기다려요. 실패·응답 유실 때도 서버 상태를 확인해요.
        if (!isAccepted)
          await queryClient.invalidateQueries({ queryKey: recordingKeys.all });
      } finally {
        isRequestInFlight.current = false;
        setIsRetrying(false);
      }
    }
  };

  return {
    handleRetry: () => {
      void retry();
    },
    isRetrying,
  };
};

export default useRetryFailedRecording;
