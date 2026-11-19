import useCancelSearchWriteCardMutation from "@api/mutations/useCancelSearchWriteCardMutation";
import useExecuteSearchWriteCardMutation from "@api/mutations/useExecuteSearchWriteCardMutation";
import useConversationId from "@hooks/domain/search/useConversationId";
import { logRequestError } from "@utils/logRequestError";
import { useRef } from "react";
import { useParams } from "react-router";

interface UseWriteCardActionsParams {
  cardId: number;
}

/**
 * 지금 대화의 쓰기 확인 카드 하나를 실행하거나 취소하는 훅.
 *
 * 두 요청 모두 끝나면 대화 메시지를 다시 받아, 카드는 다시 받은 상태로 그려져요.
 * 요청 하나가 끝나기 전에는 다시 누른 실행과 취소를 보내지 않아요(SEARCH-R43).
 * 실패한 요청은 안내 없이 기록만 하고, 카드는 다시 받은 지금 상태로 돌아가요.
 */
const useWriteCardActions = ({ cardId }: UseWriteCardActionsParams) => {
  const { workspaceId } = useParams();
  const { conversationId } = useConversationId();
  const { mutate: executeCard, isPending: isExecuting } =
    useExecuteSearchWriteCardMutation();
  const { mutate: cancelCard, isPending: isCanceling } =
    useCancelSearchWriteCardMutation();
  // isPending은 다음 렌더에야 바뀌어, 빠르게 두 번 누르면 둘째 누름이 그 전에 들어와요
  const isRequestingRef = useRef(false);

  const variables =
    conversationId === undefined
      ? null
      : { workspaceId: Number(workspaceId), conversationId, cardId };

  const release = () => {
    isRequestingRef.current = false;
  };

  const execute = () => {
    if (!variables || isRequestingRef.current) return;

    isRequestingRef.current = true;
    executeCard(variables, {
      onError: (error) =>
        logRequestError({ feature: "search", action: "쓰기 확인 카드 실행", error }),
      onSettled: release,
    });
  };

  const cancel = () => {
    if (!variables || isRequestingRef.current) return;

    isRequestingRef.current = true;
    cancelCard(variables, {
      onError: (error) =>
        logRequestError({ feature: "search", action: "쓰기 확인 카드 취소", error }),
      onSettled: release,
    });
  };

  return { execute, cancel, isExecuting, isCanceling };
};

export default useWriteCardActions;
