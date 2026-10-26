import { retryMeetingSummaryApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/meetingSummary/retry";
import { recordingDocumentKeys } from "@api/queryKey/recordingDocument";
import { useMutation, useQueryClient } from "@tanstack/react-query";

interface RetryMeetingSummaryMutationVariables {
  workspaceId: number;
  recordingId: number;
}

/**
 * 실패한 회의 전체 요약을 다시 접수하는 뮤테이션 훅(STT-R38).
 *
 * 본문이 없어 요청 DTO가 없어요. 오류는 `HttpError`의 `code`로 넘겨요(409 `MEETING_SUMMARY_RETRY_NOT_ALLOWED`).
 * 접수(202)되든, 거절(409)되든, 응답을 받지 못하든 녹음 문서를 다시 조회해요. 화면은 서버가 다시 알려 준 요약 상태를 그대로 따르고, 저절로 다시 보내지 않아요.
 * 다시 조회가 끝날 때까지 `isPending`이 유지되므로, 그동안 버튼을 다시 누를 수 없게 할 수 있어요.
 */
const useRetryMeetingSummaryMutation = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (variables: RetryMeetingSummaryMutationVariables) =>
      retryMeetingSummaryApi(variables),
    onSettled: (_data, _error, { workspaceId, recordingId }) =>
      queryClient.invalidateQueries({
        queryKey: recordingDocumentKeys.detail({ workspaceId, recordingId }),
      }),
  });
};

export default useRetryMeetingSummaryMutation;
