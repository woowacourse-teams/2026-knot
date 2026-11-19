import useCancelSearchWriteCardMutation from "@api/mutations/useCancelSearchWriteCardMutation";
import useExecuteSearchWriteCardMutation from "@api/mutations/useExecuteSearchWriteCardMutation";
import useConversationId from "@hooks/domain/search/useConversationId";
import { logRequestError } from "@utils/logRequestError";
import { useCallback, useEffect, useRef } from "react";
import { useParams } from "react-router";

import type { WriteCard } from "../types/writeCard";

/** 자기 요청 없이 실행 중 카드를 봤을 때 실행 API로 지금 상태를 받기 전 기다리는 시간(ms) */
const RUNNING_CARD_CHECK_DELAY = 2000;

interface UseWriteCardActionsParams {
  cardId: number;
  /** 서버가 준 카드의 지금 상태 */
  status: WriteCard["status"];
}

/**
 * 지금 대화의 쓰기 확인 카드 하나를 실행하거나 취소하는 훅.
 *
 * 두 요청 모두 끝나면 대화 메시지를 다시 받아, 카드는 다시 받은 상태로 그려져요.
 * 요청 하나가 끝나기 전에는 다시 누른 실행과 취소를 보내지 않아요(SEARCH-R43).
 * 실패한 요청은 안내 없이 기록만 하고, 카드는 다시 받은 지금 상태로 돌아가요.
 * 새로고침이나 다른 탭처럼 자기 요청 없이 실행 중 카드를 보면 실행 API로 지금 상태를 받아요(SEARCH-R43, R50).
 * 대기가 아닌 카드의 실행 요청은 노션에 다시 쓰지 않고 지금 상태를 주고, 서버가 실행 중에 멈춰 남은 카드는 이 요청이 와야 정리되기 때문이에요.
 */
const useWriteCardActions = ({ cardId, status }: UseWriteCardActionsParams) => {
  const { workspaceId } = useParams();
  const { conversationId } = useConversationId();
  const {
    mutate: executeCard,
    isPending: isExecuting,
    isError: hasExecuteFailed,
  } = useExecuteSearchWriteCardMutation();
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

  const execute = useCallback(() => {
    if (conversationId === undefined || isRequestingRef.current) return;

    isRequestingRef.current = true;
    executeCard(
      { workspaceId: Number(workspaceId), conversationId, cardId },
      {
        onError: (error) =>
          logRequestError({ feature: "search", action: "쓰기 확인 카드 실행", error }),
        onSettled: () => {
          isRequestingRef.current = false;
        },
      },
    );
  }, [workspaceId, conversationId, cardId, executeCard]);

  useEffect(() => {
    // 실패하면 같은 요청을 되풀이하지 않아요
    if (status !== "RUNNING" || isExecuting || hasExecuteFailed) return;

    const timer = setTimeout(execute, RUNNING_CARD_CHECK_DELAY);

    return () => clearTimeout(timer);
  }, [status, isExecuting, hasExecuteFailed, execute]);

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
