import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";

import type { SearchQuestionEntry } from "@/shared/types/searchAnswer";

interface CreateSearchAnswerResultTrackerParams {
  /** 보낸 질문 글 */
  question: string;
  /** 실패한 답변을 다시 요청한 것인지 */
  isRetry: boolean;
  /** 새 대화의 첫 질문인지 */
  isFirstQuestion: boolean;
  /** 질문을 보낸 곳 */
  entry: SearchQuestionEntry;
  /** 받은 답변 글을 읽는 함수. 답변은 끝나야 다 모여서, 보낼 때 읽어요 */
  getAnswer: () => string;
  /** 서버가 알려 준 실패 원인 코드를 읽는 함수. 실패해야 알 수 있어, 실패로 끝났을 때만 읽어요. 코드가 없으면 `null`이에요 */
  getFailureCode: () => string | null;
}

/**
 * 답변이 끝난 방식. 접수 전에 실패했으면 `ACCEPT_FAILED`,
 * 끊긴 답변을 이어 받다가 서버가 그 답변을 더는 주지 않으면(4xx) `UNAVAILABLE`이에요
 */
type SearchAnswerOutcome =
  | "COMPLETED"
  | "STOPPED"
  | "FAILED"
  | "ACCEPT_FAILED"
  | "UNAVAILABLE";

const RESULT_BY_OUTCOME = {
  COMPLETED: "completed",
  STOPPED: "stopped",
  FAILED: "failed",
  ACCEPT_FAILED: "accept_failed",
  UNAVAILABLE: "unavailable",
} as const;

/**
 * @description 질문을 보낼 때 만들어 두고, 답변이 끝나면 끝난 방식만 넘겨 사용 기록(`search_answer_result`)을 보내는 함수를 돌려줘요
 * @param params - 질문 글, 다시 시도 · 첫 질문 여부, 질문을 보낸 곳, 받은 답변 글 · 실패 코드를 읽는 함수
 * @returns 끝난 방식을 받아 보내는 함수
 * @example
 * const trackAnswerResult = createSearchAnswerResultTracker({ question, isRetry, isFirstQuestion, entry, getAnswer, getFailureCode });
 * trackAnswerResult("COMPLETED");
 *
 * 걸린 시간은 만든 때(질문을 보낸 때)부터 보낼 때까지를 0.1초 단위로 반올림해요.
 * 실패 코드는 실패(`FAILED`)로 끝났고 서버가 코드를 알려 줬을 때만 넣어요.
 */
export const createSearchAnswerResultTracker = ({
  question,
  isRetry,
  isFirstQuestion,
  entry,
  getAnswer,
  getFailureCode,
}: CreateSearchAnswerResultTrackerParams) => {
  const sentAt = Date.now();

  return (outcome: SearchAnswerOutcome) => {
    const failureCode = outcome === "FAILED" ? getFailureCode() : null;

    captureAnalyticsEvent({
      event: "search_answer_result",
      properties: {
        result: RESULT_BY_OUTCOME[outcome],
        is_retry: isRetry,
        entry,
        ...(failureCode !== null && { failure_code: failureCode }),
        is_first_question: isFirstQuestion,
        answer_seconds: Math.round((Date.now() - sentAt) / 100) / 10,
        question,
        answer: getAnswer(),
      },
    });
  };
};
