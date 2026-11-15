import useConversationId from "@hooks/domain/search/useConversationId";
import { useSearchAnswerStore } from "@store/searchAnswerStore";
import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { createSearchAnswerResultTracker } from "@utils/createSearchAnswerResultTracker";
import { useEffect, useLayoutEffect, useRef } from "react";

type SearchAnswerState = ReturnType<typeof useSearchAnswerStore.getState>;

/** 떠난 답변. 그 대화로 돌아오면 떠나 있던 시간을 재요 */
interface LeftAnswer {
  conversationId: number;
  /** 떠난 시각(ms) */
  leftAt: number;
}

const isAnswering = ({ status }: SearchAnswerState) =>
  status === "SENDING" || status === "STREAMING";

/**
 * 답변이 끝난 방식. 받기를 끊었으면 결과가 아니라 `null`이에요.
 * 끊음 · 접수 전 실패 · 이어 받을 수 없음은 모두 처음 상태로 돌아가요. 끊을 때는 직전 요청을 abort하므로 그것으로 가려요.
 * 끊지 않았는데 접수 대기에서 돌아왔으면 접수 전 실패, 생성 중에서 돌아왔으면 끊긴 답변을 이어 받다가
 * 서버가 그 답변을 더는 주지 않은 것(4xx, `useSearchAnswerControl`이 저장소의 `release`를 불러요)이에요
 */
const getOutcome = (state: SearchAnswerState, previous: SearchAnswerState) => {
  if (state.status === "IDLE") {
    if (previous.controller?.signal.aborted) return null;

    return previous.status === "SENDING" ? "ACCEPT_FAILED" : "UNAVAILABLE";
  }

  return state.status === "SENDING" || state.status === "STREAMING"
    ? null
    : state.status;
};

/** 답변을 받는 중이고, 그 답변의 대화 주소에 있는지. 접수 전에는 답변의 대화가 없어(null) 주소와 같을 수 없어요 */
const isOnAnswer = (
  state: SearchAnswerState,
  routeConversationId: number | undefined,
) => isAnswering(state) && routeConversationId === state.conversationId;

/** 0.1초 단위로 반올림한 초 */
const toSeconds = (ms: number) => Math.round(ms / 100) / 10;

interface CaptureAnswerLeftParams {
  /** 질문을 보낸 시각(ms) */
  sentAt: number | null;

  /** 떠난 시각(ms) */
  leftAt: number;
}

const captureAnswerLeft = ({ sentAt, leftAt }: CaptureAnswerLeftParams) =>
  captureAnalyticsEvent({
    event: "search_answer_left",
    properties: { waited_seconds: toSeconds(leftAt - (sentAt ?? leftAt)) },
  });

/**
 * 탐색 답변에서 일어난 일을 사용 기록으로 보내는 도메인 훅. 답변 저장소와 주소를 지켜보기만 하고, 탐색 동작은 바꾸지 않아요.
 * 워크스페이스 레이아웃을 감싼 `WorkspaceAnalytics`에서 한 번만 불러요.
 *
 * - 답변 결과(`search_answer_result`): 질문을 보낸 뒤 완료 · 중지 · 실패 · 접수 전 실패 · 이어 받을 수 없음으로 끝나면 보내요.
 *   홈 · 문서로 옮겨도 답변은 뒤에서 계속 받아 끝나면 보내요. 질문을 보낸 곳(독 · 튜토리얼)은 저장소가 질문과 함께 들고 있어요.
 *   이어 받을 수 없음은 끝 이벤트 없이 끊긴 답변을 이어 받다가 서버가 그 답변을 더는 주지 않은(4xx) 경우예요.
 *   받기를 끊었으면(다른 대화를 열거나 워크스페이스를 나감) 결과가 없어 보내지 않아요.
 *   돌아와서 서버의 답변을 이어 받은 것도 보낸 질문이 아니라서 보내지 않아요.
 * - 떠남(`search_answer_left`): 답변이 끝나기 전에 그 답변의 대화 주소에서 다른 주소로 옮기면, 질문을 보낸 뒤 기다린 시간을 보내요.
 *   다른 워크스페이스로 옮기거나 워크스페이스 밖으로 나가는 것도 떠남이에요.
 *   접수 전에는 대화 주소가 없어 세지 않아요. 접수되면 앱이 그 대화로 옮겨 가요.
 * - 돌아옴(`search_answer_returned`): 떠났던 그 대화 주소로 같은 탭에서 돌아오면, 떠나 있던 시간과 답변이 끝나 있었는지를 보내요.
 *   그사이 답변이 끊겼거나 새 질문을 보냈으면 돌아올 답변이 없어 보내지 않아요. 새로고침하거나 탭을 닫은 뒤 돌아오면 세지 않아요.
 *
 * 다른 대화를 열거나 워크스페이스를 옮기거나 나가면 레이아웃의 `useAbortSearchAnswerOnLeave`가 `useEffect`에서 답변을 끊어요.
 * 떠남은 끊기 전의 상태로 알아야 해서, 주소를 보는 effect는 `useLayoutEffect`로 둬요. `useLayoutEffect`는 컴포넌트가 놓인 위치와
 * 관계없이 같은 화면 변경의 모든 `useEffect`보다 먼저 실행돼서, 이 훅을 레이아웃 바깥에서 불러도 끊기 전의 상태를 읽어요.
 */
const useSearchAnswerAnalytics = () => {
  const { conversationId: routeConversationId } = useConversationId();
  // 지금 답변의 질문을 보낸 시각(ms)
  const sentAtRef = useRef<number | null>(null);
  const previousRouteConversationIdRef = useRef(routeConversationId);
  const leftAnswerRef = useRef<LeftAnswer | null>(null);

  useEffect(() => {
    let trackAnswerResult: ReturnType<
      typeof createSearchAnswerResultTracker
    > | null = null;

    return useSearchAnswerStore.subscribe((state, previous) => {
      if (isAnswering(state) && !isAnswering(previous)) {
        // 서버에서 만들고 있던 답변을 이어 받으면 접수 대기 없이 바로 생성 중이 돼요. 보낸 질문이 아니라 결과를 세지 않아요
        if (state.status === "STREAMING") {
          trackAnswerResult = null;
          return;
        }

        // 다시 시도는 보낼 때부터 질문 메시지 id가 있고, 첫 질문은 아직 대화 id가 없어요
        sentAtRef.current = Date.now();
        trackAnswerResult = createSearchAnswerResultTracker({
          question: state.question,
          isRetry: state.questionMessageId !== null,
          isFirstQuestion: state.conversationId === null,
          entry: state.entry,
          getAnswer: () => useSearchAnswerStore.getState().text,
          getFailureCode: () => useSearchAnswerStore.getState().failedCode,
        });
        return;
      }

      if (!isAnswering(previous) || isAnswering(state)) return;

      const outcome = getOutcome(state, previous);
      if (outcome) trackAnswerResult?.(outcome);
      trackAnswerResult = null;
    });
  }, []);

  useLayoutEffect(() => {
    const previousRouteConversationId = previousRouteConversationIdRef.current;
    previousRouteConversationIdRef.current = routeConversationId;

    const state = useSearchAnswerStore.getState();
    const { conversationId } = state;
    const now = Date.now();

    if (leftAnswerRef.current?.conversationId !== conversationId) {
      leftAnswerRef.current = null;
    }

    const isLeavingAnswer =
      conversationId !== null &&
      isOnAnswer(state, previousRouteConversationId) &&
      routeConversationId !== conversationId;

    if (isLeavingAnswer) {
      captureAnswerLeft({ sentAt: sentAtRef.current, leftAt: now });
      leftAnswerRef.current = { conversationId, leftAt: now };
      return;
    }

    const leftAnswer = leftAnswerRef.current;
    if (leftAnswer && routeConversationId === leftAnswer.conversationId) {
      captureAnalyticsEvent({
        event: "search_answer_returned",
        properties: {
          away_seconds: toSeconds(now - leftAnswer.leftAt),
          is_answer_ready: !isAnswering(state),
        },
      });
      leftAnswerRef.current = null;
    }
  }, [routeConversationId]);

  // 워크스페이스 밖으로 나가면 주소 effect가 다시 실행되지 않아, 화면에서 지워질 때 떠남을 보내요.
  // 다른 워크스페이스로 옮길 때는 주소 effect가 보내므로 여기서는 보내지 않아요. 답변이 끊기므로 돌아옴은 재지 않아요
  useLayoutEffect(
    () => () => {
      const state = useSearchAnswerStore.getState();
      if (!isOnAnswer(state, previousRouteConversationIdRef.current)) return;

      captureAnswerLeft({ sentAt: sentAtRef.current, leftAt: Date.now() });
    },
    [],
  );
};

export default useSearchAnswerAnalytics;
