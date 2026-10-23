import { isHttpError } from "@api/httpClient/error";
import useRetryRecordingTranscriptionMutation from "@api/mutations/useRetryRecordingTranscriptionMutation";
import type useRecordingQuery from "@api/queries/useRecordingQuery";
import { TOAST_MESSAGES } from "@constants/toastMessages";
import useRedirectToLoginOnUnauthorized from "@hooks/domain/auth/useRedirectToLoginOnUnauthorized";
import { useToast } from "@provider/context/toastContext";
import { isRetryRejectedError } from "@utils/isRetryRejectedError";
import { isUnauthorizedError } from "@utils/isUnauthorizedError";
import { useState } from "react";

interface UseTranscriptionRetryParams {
  workspaceId: number;
  recordingId: number;
  /** 지금 화면이 아는 녹음의 전사 시도 id. 녹음 상태를 아직 받지 못했으면 undefined예요 */
  transcriptionAttemptId: string | null | undefined;
  /** 정리 화면의 녹음 조회를 다시 하는 함수. 거절 뒤 서버의 녹음 상태를 확인할 때 써요 */
  refetchRecording: ReturnType<typeof useRecordingQuery>["refetch"];
}

/**
 * 전사가 실패한 녹음의 전사를 다시 접수하는 훅(STT-R22).
 *
 * 실패 코드와 녹음 방식(PCM · WebM)에 관계없이 전사부터 다시 접수해요.
 * 접수되면 녹음 상태를 다시 받아 전사 중, 곧 정리 중으로 돌아가요. 그 조회가 끝날 때까지 다시 시도 중(`isTranscriptionRetrying`)이에요.
 * 거절은 종류를 가리지 않고 콘솔에 오류 코드를 남겨요.
 * 409 · 404는 더 시도할 수 없다는 응답이라 녹음 상태를 다시 조회하고,
 * 같은 전사 시도로 여전히 실패면 그 시도를 거절 기록에 남겨 `retryTranscription`을 없애요. 조회에 실패해도 남겨요.
 * 이 조회가 끝날 때까지도 다시 시도 중이에요. 서버에서 이미 전사 중이면 정리 중으로 바뀌고 거절 기록을 남기지 않아요.
 * 그래서 그 전사가 또 실패하면, 새 시도든 서버가 이어서 돌린 같은 시도든 다시 시도할 수 있어요.
 * 서버 오류 · 네트워크 문제는 잠깐의 문제일 수 있어 버튼을 유지하고, 오류 토스트로 알려요. 로그인이 풀렸으면(401) 로그인 화면으로 보내요.
 */
export const useTranscriptionRetry = ({
  workspaceId,
  recordingId,
  transcriptionAttemptId,
  refetchRecording,
}: UseTranscriptionRetryParams) => {
  const {
    mutate: requestTranscriptionRetry,
    isPending: isTranscriptionRetryPending,
    error: transcriptionRetryError,
  } = useRetryRecordingTranscriptionMutation();
  // 거절 뒤 서버의 녹음 상태를 다시 조회하는 중인지. 그동안 버튼이 풀리지 않게 다시 시도 중으로 둬요
  const [isCheckingRejection, setIsCheckingRejection] = useState(false);
  // 다시 시도가 거절된(409 · 404) 전사 시도의 id예요. 거절된 적이 없으면 undefined예요.
  // 녹음이 아니라 시도로 기록해, 다른 곳에서 다시 시도한 새 시도가 또 실패하면 그 시도는 가리지 않아요
  const [rejectedAttemptId, setRejectedAttemptId] = useState<string | null>();
  useRedirectToLoginOnUnauthorized({ error: transcriptionRetryError });
  const { show } = useToast();

  // 지금 실패한 전사 시도의 다시 시도가 거절됐어요. 더 시도할 수 없어 「다시 시도」를 없애요
  const isRejected =
    rejectedAttemptId !== undefined &&
    rejectedAttemptId === transcriptionAttemptId;

  const retryTranscription = () => {
    const attemptId = transcriptionAttemptId ?? null;

    requestTranscriptionRetry(
      { workspaceId, recordingId },
      {
        onError: async (retryError) => {
          console.error("전사를 다시 시도하지 못했어요", {
            code: isHttpError(retryError) ? retryError.code : undefined,
          });

          if (!isRetryRejectedError(retryError)) {
            if (!isUnauthorizedError(retryError)) {
              show(TOAST_MESSAGES.RETRY_REQUEST_FAILED);
            }
            return;
          }

          // 다른 곳에서 먼저 다시 시도해 서버에서는 이미 전사 중일 수 있어요. 실패 상태에서는 3초 조회가 멈춰 있어 직접 다시 조회해요.
          // 거절 기록은 조회가 끝난 뒤에 남겨요. 먼저 남기면 조회하는 동안 「홈으로」가 보였다가 정리 중으로 바뀔 수 있어요
          setIsCheckingRejection(true);
          const { data: latestRecording } = await refetchRecording();

          // 같은 시도로 여전히 실패일 때만 남겨요. 결과를 알 수 없던 시도는 서버가 새 시도를 만들지 않고 같은 시도를 이어서 돌려서,
          // 전사 중이어도 남기면 그 시도가 또 실패했을 때 「다시 시도」가 숨겨져요.
          // 조회에 실패하면 앞서 받은 녹음이 그대로라 남아요. 같은 시도로는 다시 눌러도 같은 거절을 받아요
          const isStillSameFailure =
            latestRecording?.transcriptionStatus === "FAILED" &&
            latestRecording.transcriptionAttemptId === attemptId;
          if (isStillSameFailure) setRejectedAttemptId(attemptId);
          setIsCheckingRejection(false);
        },
      },
    );
  };

  return {
    retryTranscription: isRejected ? undefined : retryTranscription,
    isTranscriptionRetrying: isTranscriptionRetryPending || isCheckingRejection,
  };
};
