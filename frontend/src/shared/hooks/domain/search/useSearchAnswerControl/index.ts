import { useCallback } from "react";
import { SseRequestError } from "@api/sse/requestSseStream";
import {
  SearchAnswerDisconnectedError,
} from "@api/fetch/api/v1/workspaces/[workspaceId]/search/conversations";
import useCreateSearchConversationMutation from "@api/mutations/useCreateSearchConversationMutation";
import useCreateSearchQuestionMutation from "@api/mutations/useCreateSearchQuestionMutation";
import useResumeSearchAnswerMutation from "@api/mutations/useResumeSearchAnswerMutation";
import useRetrySearchAnswerMutation from "@api/mutations/useRetrySearchAnswerMutation";
import { searchKeys } from "@api/queryKey/search";
import useNavigateToChatSession from "@hooks/domain/chat/useNavigateToChatSession";
import { PATH_ROUTE } from "@routes/PATH_ROUTE";
import {
  type SearchAnswerResumeTarget,
  type SearchAnswerRetryTarget,
  useSearchAnswerStore,
} from "@store/searchAnswerStore";
import { useQueryClient } from "@tanstack/react-query";
import { logRequestError } from "@utils/logRequestError";
import { useMatch } from "react-router";

import type { SearchAnswerStreamEvent } from "@/shared/types/searchAnswer";

interface SendQuestionParams {
  workspaceId: number;

  /** 이어 가는 대화 ID. 없으면 첫 질문으로 새 대화를 시작해요 */
  conversationId?: number;

  content: string;
}

interface RetryAnswerParams extends SearchAnswerRetryTarget {
  workspaceId: number;

  conversationId: number;

  /** 실패한 답변의 질문 내용. 다시 받는 동안 그 턴의 질문 말풍선에 그대로 보여요 */
  question: string;
}

interface ResumeAnswerParams extends SearchAnswerResumeTarget {
  workspaceId: number;
}

/** 첫 질문·후속 질문·재시도 요청이 함께 받는 값 */
interface AnswerRequest {
  requestId: string;
  signal: AbortSignal;
  onEvent: (event: SearchAnswerStreamEvent) => void;
}

interface RunAnswerParams {
  workspaceId: number;

  /** 이어 가는 대화 ID. 없으면 첫 질문이라 accepted로 받은 대화 주소로 옮겨 가요 */
  conversationId?: number;

  question: string;

  retryOf?: SearchAnswerRetryTarget;

  /** 공통 값을 받아 이번 요청의 뮤테이션을 불러요. 끝난 결과는 `relaySearchAnswerUntilEnd`와 같아요 */
  requestAnswer: (
    request: AnswerRequest,
  ) => Promise<{ status: "COMPLETED" | "STOPPED" } | null>;
}

interface FollowAnswerParams {
  workspaceId: number;

  conversationId: number;

  answerMessageId: number;

  /** 이어 받는 답변의 저장소 컨트롤러. 다시 연결해도 같은 컨트롤러로 이벤트를 반영하고 끊어요 */
  controller: AbortController;
}

/** 끝 이벤트 없이 끊긴 답변에 다시 연결하는 최대 횟수. 넘으면 이어 받기를 그만두고 저장된 메시지로 그려요 */
const MAX_RESUME_ATTEMPTS = 3;

/** 다시 연결한 스트림이 또 끊겼을 때 다음 연결 전에 쉬는 시간(ms). 처음 끊겼을 때는 바로 다시 연결해요 */
const RESUME_RETRY_DELAY_MS = 1000;

/** 접수된 질문은 다시 보내지 않고 조회 스트림만 제한 재연결해요. */
const canReconnectAnswer = (error: unknown) =>
  error instanceof SearchAnswerDisconnectedError ||
  error instanceof TypeError ||
  (error instanceof SseRequestError && error.status >= 500);

const isUnavailableAnswer = (error: unknown) =>
  error instanceof SseRequestError && [400, 401, 403, 404, 409].includes(error.status);

const wait = (ms: number) =>
  new Promise((resolve) => {
    setTimeout(resolve, ms);
  });

/**
 * 탐색 질문을 보내거나 실패한 답변을 다시 요청하고, 도착하는 답변을 전역 저장소에 넘기는 도메인 훅.
 *
 * 저장소가 할 수 없는 일(뮤테이션 호출, 주소 이동)만 맡고, 상태를 어떻게 바꿀지는 저장소가 정해요.
 * - 보내기: 대화 ID가 없으면 첫 질문, 있으면 후속 질문으로 보내요. requestId는 보낼 때마다 새로 만들어요.
 *   접수 대기·생성 중이면 저장소가 시작을 거절해 보내지 않아요(SEARCH-R6).
 * - 질문이 접수(accepted)되면 대화 목록 조회를 무효화해요. 첫 질문은 서버가 이때 대화를 만들어 목록에 넣고,
 *   후속 질문은 그 대화가 맨 위로 올라가요. 답변이 끝나기 전에 목록에 보이도록 접수 때 다시 받아요(COMMON-01).
 * - 첫 질문은 accepted로 받은 대화 주소로 옮겨 가요. 탐색 새 대화 화면에서 보냈으면 뒤로 가기로 입력 전 화면에
 *   돌아오지 않도록 바꿔치기하고, 홈처럼 다른 화면에서 보냈으면 뒤로 가기로 그 화면에 돌아오도록 쌓아요.
 * - 끝난 결과와 실패는 저장소에 넘기고, 화면은 저장소 상태로 읽어요.
 * - 완료·중지로 끝나면 그 대화의 메시지 조회를 무효화해요. 저장소가 다음 질문까지 끝난 답변을 들고 있어
 *   다시 받는 동안 화면은 바뀌지 않고, 다음 질문을 보낼 때 저장된 답변이 그 자리를 이어받아요.
 * - accepted 뒤에 실패해도 메시지 조회를 무효화해요. 다시 시도가 접수 전에 실패하면 저장소가 비워지고 저장된 턴이
 *   그 자리를 이어받는데, 그때 조회 결과가 생성 중으로 낡아 있으면 실패 안내 대신 진행 문구가 남기 때문이에요.
 * - accepted 전에 실패하면 `{ isAcceptFailed: true }`를 돌려줘요. 질문을 보낸 독이 그 자리에서 입력창을 되돌리고
 *   안내해요(SEARCH-R4). 한 번 일어난 일이라 저장소에 두지 않고 보낸 쪽에만 돌려줘요.
 * - 다시 시도: 실패한 답변의 질문으로 답변을 다시 받아요(SEARCH-R5). 대화가 이미 있으니 주소는 옮기지 않고,
 *   접수되면 첫 질문이 실패해 목록에서 빠져 있던 대화도 목록에 다시 나타나요.
 *   접수 전에 실패하면 저장된 실패 답변이 그대로 보여 다시 누를 수 있어요.
 * - 끊김: 접수된 답변이 끝 이벤트 없이 끊기면 실패로 그리지 않고 재연결 API로 이어 받아요(SEARCH-R8).
 *   서버는 연결이 끊겨도 답변을 계속 만들기 때문이에요. 다시 끊기면 잠시 쉬고 `MAX_RESUME_ATTEMPTS`번까지 다시 연결하고,
 *   그래도 끝을 받지 못하면 안내 문단으로 대화를 이어가게 하고 메시지를 다시 조회해요.
 * - 이어 받기: 화면이 받고 있지 않은, 서버에서 만들고 있는 답변을 재연결 API로 끝까지 받아요(SEARCH-R8).
 *   다른 대화에 다녀오거나 새로고침한 뒤 대화 화면이 `useResumeSearchAnswer`로 불러요. 끊김과 같은 방식으로 다시 연결해요.
 * - 중단: 받기만 끊고 처음 상태로 돌아가요. 서버는 답변을 계속 만들어요(SEARCH-R8).
 */
const useSearchAnswerControl = () => {
  const queryClient = useQueryClient();
  const { navigateToChatSession } = useNavigateToChatSession();
  const isNewConversationScreen = useMatch(PATH_ROUTE.CHAT) !== null;
  const { mutateAsync: createSearchConversation } =
    useCreateSearchConversationMutation();
  const { mutateAsync: createSearchQuestion } =
    useCreateSearchQuestionMutation();

  const { mutateAsync: retrySearchAnswer } = useRetrySearchAnswerMutation();
  const { mutateAsync: resumeSearchAnswer } = useResumeSearchAnswerMutation();

  const followAnswer = useCallback(
    async ({
      workspaceId,
      conversationId,
      answerMessageId,
      controller,
    }: FollowAnswerParams) => {
      const { applyEvent, settle, fail, release } =
        useSearchAnswerStore.getState();

      for (let attempt = 1; ; attempt += 1) {
        try {
          const result = await resumeSearchAnswer({
            workspaceId,
            conversationId,
            answerId: answerMessageId,
            signal: controller.signal,
            onEvent: (event) => applyEvent(controller, event),
          });

          // 끊었으면 결과가 없고, 저장소는 abort가 이미 비웠어요
          if (!result) return;

          settle(controller, result.status);
        } catch (error) {
          const canReconnect =
            canReconnectAnswer(error) && attempt < MAX_RESUME_ATTEMPTS;

          if (canReconnect) {
            await wait(RESUME_RETRY_DELAY_MS);
            if (controller.signal.aborted) return;
            continue;
          }

          logRequestError({
            feature: "search",
            action: "탐색 답변 이어 받기",
            error,
          });

          if (isUnavailableAnswer(error)) {
            release(controller);
          } else {
            fail(controller, error);
          }
        }

        queryClient.invalidateQueries({
          queryKey: searchKeys.messages({ workspaceId, conversationId }),
        });
        return;
      }
    },
    [queryClient, resumeSearchAnswer],
  );

  const runAnswer = useCallback(
    async ({
      workspaceId,
      conversationId,
      question,
      retryOf,
      requestAnswer,
    }: RunAnswerParams) => {
      const { start, applyEvent, settle, fail } =
        useSearchAnswerStore.getState();

      const controller = start({
        question,
        conversationId: conversationId ?? null,
        retryOf,
      });
      if (!controller) return { isAcceptFailed: false };

      // 첫 질문은 accepted가 와야 대화 ID를 알아요
      let acceptedConversationId = conversationId;
      let acceptedAnswerMessageId: number | undefined;

      const invalidateMessages = () => {
        if (acceptedConversationId === undefined) return;

        queryClient.invalidateQueries({
          queryKey: searchKeys.messages({
            workspaceId,
            conversationId: acceptedConversationId,
          }),
        });
      };

      try {
        const result = await requestAnswer({
          requestId: crypto.randomUUID(),
          signal: controller.signal,
          onEvent: (event) => {
            applyEvent(controller, event);

            if (event.event !== "accepted") return;

            acceptedConversationId = event.data.conversationId;
            acceptedAnswerMessageId = event.data.answerMessageId;
            queryClient.invalidateQueries({
              queryKey: searchKeys.conversations(workspaceId),
            });

            if (conversationId === undefined) {
              navigateToChatSession({
                workspaceId: String(workspaceId),
                sessionId: String(event.data.conversationId),
                replace: isNewConversationScreen,
              });
            }
          },
        });

        // 끊었으면 결과가 없고, 저장소는 abort가 이미 비웠어요
        if (result) {
          settle(controller, result.status);
          invalidateMessages();
        }

        return { isAcceptFailed: false };
      } catch (error) {
        if (
          canReconnectAnswer(error) &&
          acceptedConversationId !== undefined &&
          acceptedAnswerMessageId !== undefined
        ) {
          await followAnswer({
            workspaceId,
            conversationId: acceptedConversationId,
            answerMessageId: acceptedAnswerMessageId,
            controller,
          });

          return { isAcceptFailed: false };
        }

        logRequestError({ feature: "search", action: "탐색 질문", error });
        invalidateMessages();

        return { isAcceptFailed: fail(controller, error) };
      }
    },
    [followAnswer, isNewConversationScreen, navigateToChatSession, queryClient],
  );

  const sendQuestion = useCallback(
    ({ workspaceId, conversationId, content }: SendQuestionParams) =>
      runAnswer({
        workspaceId,
        conversationId,
        question: content,
        requestAnswer: (request) =>
          conversationId === undefined
            ? createSearchConversation({ ...request, workspaceId, content })
            : createSearchQuestion({
                ...request,
                workspaceId,
                conversationId,
                content,
              }),
      }),
    [createSearchConversation, createSearchQuestion, runAnswer],
  );

  const retryAnswer = useCallback(
    async ({
      workspaceId,
      conversationId,
      question,
      questionMessageId,
      answerMessageId,
    }: RetryAnswerParams) => {
      await runAnswer({
        workspaceId,
        conversationId,
        question,
        retryOf: { questionMessageId, answerMessageId },
        requestAnswer: (request) =>
          retrySearchAnswer({
            ...request,
            workspaceId,
            conversationId,
            questionId: questionMessageId,
          }),
      });
    },
    [retrySearchAnswer, runAnswer],
  );

  const resumeAnswer = useCallback(
    async ({ workspaceId, ...target }: ResumeAnswerParams) => {
      const controller = useSearchAnswerStore.getState().resume(target);
      if (!controller) return;

      await followAnswer({
        workspaceId,
        conversationId: target.conversationId,
        answerMessageId: target.answerMessageId,
        controller,
      });
    },
    [followAnswer],
  );

  const abortAnswer = useCallback(
    () => useSearchAnswerStore.getState().abort(),
    [],
  );

  return { sendQuestion, retryAnswer, resumeAnswer, abortAnswer };
};

export default useSearchAnswerControl;
