import useRetryMeetingSummaryMutation from "@api/mutations/useRetryMeetingSummaryMutation";
import { TOAST_MESSAGES } from "@constants/toastMessages";
import useRedirectToLoginOnUnauthorized from "@hooks/domain/auth/useRedirectToLoginOnUnauthorized";
import { useToast } from "@provider/context/toastContext";
import { isRetryRejectedError } from "@utils/isRetryRejectedError";
import { isUnauthorizedError } from "@utils/isUnauthorizedError";

interface UseMeetingSummaryRetryParams {
  workspaceId: number;
  recordingId: number;
}

/**
 * 녹음 문서에서 실패한 회의 전체 요약을 다시 접수하는 훅(STT-R38).
 *
 * 접수되든 거절되든 녹음 문서를 다시 받아, 서버가 알려 준 요약 상태(만드는 중 · 실패 · 다시 시도 가능 여부)를 그대로 보여 줘요.
 * 다시 받을 때까지 다시 시도 중(`isMeetingSummaryRetrying`)이라 같은 요청이 두 번 가지 않아요.
 * 거절(409 · 404)은 다시 받은 상태가 알려 주므로 따로 알리지 않고, 네트워크 · 서버 문제는 오류 토스트로 알려요. 로그인이 풀렸으면(401) 로그인 화면으로 보내요.
 */
export const useMeetingSummaryRetry = ({
  workspaceId,
  recordingId,
}: UseMeetingSummaryRetryParams) => {
  const {
    mutate: requestRetry,
    isPending: isMeetingSummaryRetrying,
    error: retryError,
  } = useRetryMeetingSummaryMutation();
  const { show } = useToast();
  useRedirectToLoginOnUnauthorized({ error: retryError });

  const retryMeetingSummary = () => {
    if (isMeetingSummaryRetrying) return;

    requestRetry(
      { workspaceId, recordingId },
      {
        onError: (error) => {
          if (isRetryRejectedError(error) || isUnauthorizedError(error)) return;

          show(TOAST_MESSAGES.RETRY_REQUEST_FAILED);
        },
      },
    );
  };

  return { retryMeetingSummary, isMeetingSummaryRetrying };
};
