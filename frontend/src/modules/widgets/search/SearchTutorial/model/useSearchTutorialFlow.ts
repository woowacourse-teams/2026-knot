import useMeQuery from "@api/queries/useMeQuery";
import { SEARCH_TUTORIAL_MOCK_CONVERSATION, type SearchTutorialQuestionKind } from "@constants/searchTutorial";
import useNavigateToNewChat from "@hooks/domain/chat/useNavigateToNewChat";
import useSearchAnswerControl from "@hooks/domain/search/useSearchAnswerControl";
import { useSearchAnswerStore } from "@store/searchAnswerStore";
import { useSearchTutorialStore, type SearchTutorialStep } from "@store/searchTutorialStore";
import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { useEffect, useRef } from "react";
import { useParams } from "react-router";

import {
  SEARCH_TUTORIAL_SEND_DELAY_MS,
  SEARCH_TUTORIAL_TYPING_INTERVAL_MS,
} from "../constants/searchTutorial";
import { toLocalDate, writeSearchTutorialRecord } from "../utils/searchTutorialRecord";
import { useSearchTutorialScreen } from "./useSearchTutorialScreen";

type EndResult =
  | { result: "completed"; via: "question_kind"; questionKind: SearchTutorialQuestionKind }
  | { result: "completed"; via: "typed" | "finish_button" }
  | { result: "skipped" | "snoozed" | "closed" };

const prefersReducedMotion = () =>
  typeof window.matchMedia === "function" &&
  window.matchMedia("(prefers-reduced-motion: reduce)").matches;

/**
 * 탐색 튜토리얼의 단계를 넘기고 끝내는 동작과, 단계마다 저절로 일어나는 일을 맡아요.
 *
 * - 1단계에서 밝힌 곳이나 「보여 주세요」를 누르면 빈 새 탐색 대화로 옮겨 2단계를 시작해요.
 * - 2단계는 예시 질문을 독 입력창에 한 글자씩 넣은 뒤 자동으로 보내고 3단계로 넘어가요. 서버에는 보내지 않아요.
 * - 3단계의 예시 답변은 대화 칸이 그리고, 다 그리면 안내 카드가 「다음」을 보여 줘요.
 * - 4단계에서 사용자가 독으로 직접 질문을 보내면 튜토리얼을 끝내요.
 * - 2~4단계에 탐색 화면을 벗어나면 건너뛴 것으로 봐요.
 *
 * 끝날 때 멤버의 기록을 남겨요. 미루면 오늘 날짜를, 그 밖에는 다 본 것으로 남겨 다시 띄우지 않아요.
 * 움직임 줄이기 설정이면 예시 질문을 한 번에 채워요.
 */
export const useSearchTutorialFlow = () => {
  const { workspaceId } = useParams();
  const { isChatScreen } = useSearchTutorialScreen();
  const { data: me } = useMeQuery();
  const { navigateToNewChat } = useNavigateToNewChat();
  const { sendQuestion } = useSearchAnswerControl();
  const step = useSearchTutorialStore((state) => state.step);
  const goTo = useSearchTutorialStore((state) => state.goTo);
  const setDraft = useSearchTutorialStore((state) => state.setDraft);
  const endTutorial = useSearchTutorialStore((state) => state.end);
  const answerStatus = useSearchAnswerStore((state) => state.status);

  const numericWorkspaceId = Number(workspaceId);

  const end = (endResult: EndResult) => {
    if (step === null) return;

    if (me) {
      writeSearchTutorialRecord(
        me.memberId,
        endResult.result === "snoozed"
          ? { state: "snoozed", at: toLocalDate(new Date()) }
          : { state: "done" },
      );
    }

    captureAnalyticsEvent({
      event: "search_tutorial_ended",
      properties: {
        result: endResult.result,
        step,
        via: "via" in endResult ? endResult.via : undefined,
        question_kind: "questionKind" in endResult ? endResult.questionKind : undefined,
        workspace_id: numericWorkspaceId,
      },
    });
    endTutorial();
  };

  // 단계를 보여 줄 때마다 사용 기록을 남겨요
  useEffect(() => {
    if (step === null) return;

    captureAnalyticsEvent({
      event: "search_tutorial_step_viewed",
      properties: { step, workspace_id: numericWorkspaceId },
    });
  }, [step, numericWorkspaceId]);

  // 2단계: 예시 질문을 한 글자씩 넣고, 다 넣으면 잠깐 뒤 보내요
  useEffect(() => {
    if (step !== 2) return;

    const { question } = SEARCH_TUTORIAL_MOCK_CONVERSATION;
    let length = prefersReducedMotion() ? question.length : 0;
    let sendTimer: number | undefined;

    setDraft(question.slice(0, length));

    const typingTimer = window.setInterval(() => {
      if (length < question.length) {
        length += 1;
        setDraft(question.slice(0, length));
        return;
      }

      window.clearInterval(typingTimer);
      sendTimer = window.setTimeout(() => goTo(3), SEARCH_TUTORIAL_SEND_DELAY_MS);
    }, SEARCH_TUTORIAL_TYPING_INTERVAL_MS);

    return () => {
      window.clearInterval(typingTimer);
      window.clearTimeout(sendTimer);
    };
  }, [step, setDraft, goTo]);

  // 4단계에서 독으로 직접 보낸 질문은 실제 질문이에요. 보내기 시작하면 튜토리얼을 끝내요
  const previousAnswerStatusRef = useRef(answerStatus);
  useEffect(() => {
    const previous = previousAnswerStatusRef.current;
    previousAnswerStatusRef.current = answerStatus;

    if (step === 4 && previous !== "SENDING" && answerStatus === "SENDING") {
      end({ result: "completed", via: "typed" });
    }
    // end는 렌더마다 새로 만들어지지만 상태가 바뀔 때만 보면 돼요
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [answerStatus, step]);

  // 1단계에서 탐색으로 옮기는 중인지. 주소가 바뀌기 전에 2단계가 되어도 화면을 벗어난 것으로 보지 않아요
  const isMovingToChatRef = useRef(false);

  // 2~4단계는 탐색 화면에서 이어져요. 다른 화면으로 나가면 건너뛴 것으로 봐요
  useEffect(() => {
    if (isChatScreen) isMovingToChatRef.current = false;
    if (step === null || step === 1 || isChatScreen || isMovingToChatRef.current) return;

    end({ result: "skipped" });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isChatScreen, step]);

  const proceedFromFirst = () => {
    if (!workspaceId) return;

    isMovingToChatRef.current = true;
    navigateToNewChat(workspaceId);
    goTo(2);
  };

  const chooseQuestionKind = async (kind: SearchTutorialQuestionKind, question: string) => {
    end({ result: "completed", via: "question_kind", questionKind: kind });
    await sendQuestion({
      workspaceId: numericWorkspaceId,
      conversationId: undefined,
      content: question,
      entry: "tutorial",
    });
  };

  return {
    step,
    proceedFromFirst,
    /** 1단계의 보조 버튼. 처음이면 미루고, 두 번째로 뜬 것이면 닫아요 */
    dismissFirst: (isAgain: boolean) => end({ result: isAgain ? "closed" : "snoozed" }),
    goToNext: (next: SearchTutorialStep) => goTo(next),
    skip: () => end({ result: "skipped" }),
    finish: () => end({ result: "completed", via: "finish_button" }),
    chooseQuestionKind,
  };
};
