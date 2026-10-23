import { HTTP_ERROR_TYPE, isHttpError } from "@api/httpClient/error";
import useRetryDocumentGenerationJobsMutation from "@api/mutations/useRetryDocumentGenerationJobsMutation";
import useRetryRecordingTranscriptionMutation from "@api/mutations/useRetryRecordingTranscriptionMutation";
import useRecordingQuery from "@api/queries/useRecordingQuery";
import { recordingKeys } from "@api/queryKey/recording";
import { TOAST_MESSAGES } from "@constants/toastMessages";
import useRedirectToLoginOnUnauthorized from "@hooks/domain/auth/useRedirectToLoginOnUnauthorized";
import { useToast } from "@provider/context/toastContext";
import { useQueryClient } from "@tanstack/react-query";
import { isUnauthorizedError } from "@utils/isUnauthorizedError";
import { useState } from "react";

import type { CurrentRecordingStatus } from "@/shared/types/recording";

import { useWorkspaceId } from "./useWorkspaceId";

/** 정리에 실패해 홈 카드에서 다시 시도할 수 있는 상태 */
type FailedRecordingStatus = Extract<
  CurrentRecordingStatus,
  "transcriptionFailed" | "documentFailed"
>;

interface UseRetryFailedRecordingParams {
  /** 실패한 녹음의 ID. 실패한 녹음이 없으면 `null` */
  recordingId: number | null;
  /** 어느 단계에서 실패했는지. 실패가 아니면 `null` */
  failedStatus: FailedRecordingStatus | null;
}

/** 더 시도할 수 없다는 응답. 보관 기간이 지남 · 횟수를 다 씀 · 이미 전사 중(409), 없는 녹음 · 작업(404) */
const isRetryRejectedError = (error: unknown) =>
  isHttpError(error, HTTP_ERROR_TYPE.conflict) ||
  isHttpError(error, HTTP_ERROR_TYPE.notFound);

/**
 * 홈 카드에서 정리에 실패한 녹음을 다시 접수하는 핸들러(STT-R24 · STT-R22).
 *
 * 전사 실패는 그 녹음의 전사를 다시 접수해요. 문서 생성 실패는 현재 녹음 조회에 실패한 작업 ID가 없어,
 * 누를 때 그 녹음의 상세를 조회해 `failedJobIds`를 받아 모두 다시 접수해요.
 * 하나라도 접수되면 재시도 뮤테이션이 녹음 쿼리를 무효화해 현재 녹음을 다시 받고, 카드는 「문서 정리 중」으로 돌아가요.
 *
 * 거절(409 · 404)은 더 시도할 수 없다는 뜻이라 알리지 않고 현재 녹음을 다시 조회해요.
 * 서버가 다시 시도할 수 없다고 답하면 카드는 녹음이 없는 모습으로 돌아가요. 홈 카드는 정리 화면과 달리 거절된 시도를 기억하지 않아요.
 * 다시 시도할 작업이 없을 때(상세를 받지 못했거나 실패 작업이 비었을 때)도 요청 없이 현재 녹음을 다시 조회해요.
 * 네트워크 · 서버 오류는 정리 화면과 같은 오류 토스트로 알리고 버튼을 유지해요. 로그인이 풀렸으면(401) 로그인 화면으로 보내요.
 * 요청과 그 뒤의 다시 조회가 끝날 때까지 `isRetrying`이라 버튼을 다시 누를 수 없어요.
 */
export const useRetryFailedRecording = ({
  recordingId,
  failedStatus,
}: UseRetryFailedRecordingParams) => {
  const workspaceId = Number(useWorkspaceId());
  const queryClient = useQueryClient();
  const { show } = useToast();
  // 실패한 작업 ID는 「다시 시도」를 누를 때 상세를 받아 써요. 저절로 조회하지 않아 녹음이 없을 때의 ID(0)로는 요청이 나가지 않아요
  const { refetch: refetchFailedRecording } = useRecordingQuery({
    workspaceId,
    recordingId: recordingId ?? 0,
    isEnabled: false,
  });
  const {
    mutate: requestTranscriptionRetry,
    isPending: isTranscriptionRetryPending,
    error: transcriptionRetryError,
  } = useRetryRecordingTranscriptionMutation();
  const {
    mutate: requestDocumentRetry,
    isPending: isDocumentRetryPending,
    data: documentRetryResults = [],
  } = useRetryDocumentGenerationJobsMutation();
  // 거절이나 빈 작업 뒤 현재 녹음을 다시 조회하는 동안. 그동안 버튼이 풀리면 같은 요청이 또 갈 수 있어요
  const [isRefreshing, setIsRefreshing] = useState(false);

  useRedirectToLoginOnUnauthorized({ error: transcriptionRetryError });
  useRedirectToLoginOnUnauthorized({
    error: documentRetryResults.find(({ error }) => isUnauthorizedError(error))
      ?.error,
  });

  const refreshCurrentRecording = async () => {
    setIsRefreshing(true);
    await queryClient.invalidateQueries({ queryKey: recordingKeys.all });
    setIsRefreshing(false);
  };

  const retryTranscription = (failedRecordingId: number) => {
    requestTranscriptionRetry(
      { workspaceId, recordingId: failedRecordingId },
      {
        onError: (error) => {
          console.error("전사를 다시 시도하지 못했어요", {
            code: isHttpError(error) ? error.code : undefined,
          });

          if (isRetryRejectedError(error)) {
            void refreshCurrentRecording();
            return;
          }
          if (!isUnauthorizedError(error)) {
            show(TOAST_MESSAGES.RETRY_REQUEST_FAILED);
          }
        },
      },
    );
  };

  const retryDocumentGeneration = async () => {
    // 누른 시점의 실패 작업을 받아요. 다른 곳에서 먼저 다시 시도했으면 목록이 줄어 있어요
    setIsRefreshing(true);
    const { data: failedRecording } = await refetchFailedRecording();
    setIsRefreshing(false);
    const jobIds = failedRecording?.documentGeneration?.failedJobIds ?? [];

    // 상세를 받지 못했거나 다시 시도할 작업이 없으면 서버의 지금 상태에 맞춰요
    if (jobIds.length === 0) {
      void refreshCurrentRecording();
      return;
    }

    requestDocumentRetry(
      { workspaceId, jobIds },
      {
        onSuccess: (results) => {
          if (results.some(({ isAccepted }) => isAccepted)) return;

          const errors = results.map(({ error }) => error);
          const hasRetryableFailure = errors.some(
            (error) => error !== null && !isRetryRejectedError(error),
          );

          if (!hasRetryableFailure) {
            void refreshCurrentRecording();
            return;
          }
          if (!errors.some(isUnauthorizedError)) {
            show(TOAST_MESSAGES.RETRY_REQUEST_FAILED);
          }
        },
      },
    );
  };

  const handleRetry = () => {
    if (recordingId === null || failedStatus === null) return;

    if (failedStatus === "transcriptionFailed") {
      retryTranscription(recordingId);
      return;
    }
    void retryDocumentGeneration();
  };

  return {
    handleRetry,
    isRetrying:
      isTranscriptionRetryPending || isDocumentRetryPending || isRefreshing,
  };
};
