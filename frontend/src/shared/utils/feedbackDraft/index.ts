import { FEEDBACK_TYPE_OPTIONS } from "@constants/feedback";

import type { FeedbackType } from "@/shared/types/feedback";

/** 피드백 패널에 적던 내용. 패널을 닫거나 새로고침해도 같은 탭에서는 남아요(FEEDBACK-R6) */
const FEEDBACK_DRAFT_KEY = "knot.feedback.draft";

/** 피드백 패널에서 고른 유형과 적은 내용 */
export interface FeedbackDraft {
  /** 고른 유형. 고르지 않았으면 `null`이에요 */
  type: FeedbackType | null;
  content: string;
}

const EMPTY_DRAFT: FeedbackDraft = { type: null, content: "" };

const isFeedbackType = (value: unknown): value is FeedbackType =>
  FEEDBACK_TYPE_OPTIONS.some(({ type }) => type === value);

// 비공개 창·저장소 차단처럼 sessionStorage를 못 쓰면 예외를 삼켜, 남기지 못해도 패널은 그대로 써요

/** 적던 내용을 돌려줘요. 없거나 읽을 수 없으면 빈 내용이에요 */
export const readFeedbackDraft = (): FeedbackDraft => {
  try {
    const saved: unknown = JSON.parse(
      sessionStorage.getItem(FEEDBACK_DRAFT_KEY) ?? "null",
    );
    if (typeof saved !== "object" || saved === null) return EMPTY_DRAFT;

    const { type, content } = saved as Record<string, unknown>;

    return {
      type: isFeedbackType(type) ? type : null,
      content: typeof content === "string" ? content : "",
    };
  } catch {
    return EMPTY_DRAFT;
  }
};

/** 고른 유형과 적은 내용을 이 탭에 남겨요 */
export const saveFeedbackDraft = (draft: FeedbackDraft) => {
  try {
    sessionStorage.setItem(FEEDBACK_DRAFT_KEY, JSON.stringify(draft));
  } catch {
    // 남기지 못하면 패널을 닫을 때 적던 내용이 사라져요
  }
};

/** 보내기에 성공하면 불러 남긴 내용을 지워요 */
export const clearFeedbackDraft = () => {
  try {
    sessionStorage.removeItem(FEEDBACK_DRAFT_KEY);
  } catch {
    // 지우지 못하면 다음에 열 때 보낸 내용이 남아 있어요
  }
};
