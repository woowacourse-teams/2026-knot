import { HTTP_ERROR_TYPE, isHttpError } from "@api/httpClient/error";
import useConfirmDocumentMutation from "@api/mutations/useConfirmDocumentMutation";
import type usePendingDocumentsQuery from "@api/queries/usePendingDocumentsQuery";
import { documentKeys } from "@api/queryKey/document";
import { TOAST_MESSAGES } from "@constants/toastMessages";
import useNavigateToLogin from "@hooks/domain/auth/useNavigateToLogin";
import { useToast } from "@provider/context/toastContext";
import { useQueryClient } from "@tanstack/react-query";
import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { isUnauthorizedError } from "@utils/isUnauthorizedError";

interface UseConfirmDocumentParams {
  workspaceId: number;
  documentId: number;
  /** 문서가 나온 녹음의 ID. 문서를 아직 받지 못했으면 `undefined`이고, 그동안은 모두 확인했는지 보지 않아요 */
  recordingSessionId?: number;
}

type PendingDocuments = ReturnType<typeof usePendingDocumentsQuery>["data"];

/**
 * 문서를 확인했다고 기록하는 동작과 그 진행 상태를 주는 도메인 훅.
 *
 * 요청이 끝나면 뮤테이션 훅이 문서와 내 확인 대기 문서 목록을 다시 불러와요. `isConfirming`은 그 다시 불러오기까지 끝나야 `false`가 돼요.
 * 다시 받은 확인 대기 목록에 같은 녹음의 문서가 남지 않으면 「문서를 모두 확인했어요」 토스트를 띄워요(CONF-R4).
 * 하나씩 확인하는 동안에는 띄우지 않고, 목록을 받지 못했으면 모두 확인했는지 알 수 없어 띄우지 않아요.
 * 확인 대상이 아니라는 409도 같은 흐름으로 문서를 다시 불러와요.
 * 그 밖의 실패는 아무것도 바꾸지 않아 다시 누를 수 있고, 오류 토스트로 알려요.
 * 로그인이 풀렸으면(401) 로그인 화면으로 보내요.
 *
 * 서버가 응답하면 그 결과를 사용 기록(`document_confirmed`)으로 보내요. 확인되면 모두 확인했는지를 토스트와 같은 기준으로 함께 보내고,
 * 알 수 없으면(문서나 목록을 받지 못함) 빼요. 409도 이 확인이 기록되지 않은 것이라 실패로 보내요. 로그인이 풀린 401은 결과가 아니라 보내지 않아요.
 */
const useConfirmDocument = ({
  workspaceId,
  documentId,
  recordingSessionId,
}: UseConfirmDocumentParams) => {
  const queryClient = useQueryClient();
  const { mutate: requestConfirmation, isPending: isConfirming } =
    useConfirmDocumentMutation();
  const { navigateToLogin } = useNavigateToLogin();
  const { show } = useToast();

  // 다시 받은 확인 대기 목록에 같은 녹음의 문서가 남지 않았는지. 녹음 ID나 목록이 없어 알 수 없으면 `undefined`예요
  const getIsAllConfirmed = () => {
    if (recordingSessionId === undefined) return undefined;

    const pendingListKey = documentKeys.pendingList({ workspaceId });
    if (queryClient.getQueryState(pendingListKey)?.status !== "success") {
      return undefined;
    }

    const pendingDocuments =
      queryClient.getQueryData<PendingDocuments>(pendingListKey);
    const hasPendingInRecording = pendingDocuments?.items.some(
      (document) => document.recordingSessionId === recordingSessionId,
    );

    return hasPendingInRecording === undefined
      ? undefined
      : !hasPendingInRecording;
  };

  const trackConfirmResult = (
    properties:
      | { result: "confirmed"; is_all_confirmed?: boolean }
      | { result: "failed" },
  ) => {
    captureAnalyticsEvent({
      event: "document_confirmed",
      properties: {
        ...properties,
        document_id: documentId,
        workspace_id: workspaceId,
      },
    });
  };

  const handleConfirmed = () => {
    const isAllConfirmed = getIsAllConfirmed();

    trackConfirmResult({
      result: "confirmed",
      ...(isAllConfirmed !== undefined && { is_all_confirmed: isAllConfirmed }),
    });

    if (isAllConfirmed) show(TOAST_MESSAGES.ALL_DOCUMENTS_CONFIRMED);
  };

  const confirmDocument = () => {
    requestConfirmation(
      { workspaceId, documentId },
      {
        onSuccess: handleConfirmed,
        onError: (error) => {
          if (isUnauthorizedError(error)) {
            navigateToLogin({ replace: true });
            return;
          }

          trackConfirmResult({ result: "failed" });

          if (isHttpError(error, HTTP_ERROR_TYPE.conflict)) return;

          show(TOAST_MESSAGES.DOCUMENT_CONFIRM_FAILED);
        },
      },
    );
  };

  return { confirmDocument, isConfirming };
};

export default useConfirmDocument;
