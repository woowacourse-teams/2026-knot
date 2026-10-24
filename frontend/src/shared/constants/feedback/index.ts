import type { FeedbackType } from "@/shared/types/feedback";

/**
 * 피드백 유형의 화면 문구와 점수. 패널의 칩은 이 순서대로 놓여요(FEEDBACK-R5).
 *
 * 점수는 사용자에게 보여 주지 않고 PostHog `survey sent`의 `feedback_score`로만 보내요(FEEDBACK-R7).
 * 칩 순서와 달리 문구로 매겨요. 오류가 있어요 1, 불편해요 2, 아이디어가 있어요 3, 좋았어요 4.
 */
export const FEEDBACK_TYPE_OPTIONS = [
  { type: "inconvenience", label: "불편해요", score: 2 },
  { type: "bug", label: "오류가 있어요", score: 1 },
  { type: "idea", label: "아이디어가 있어요", score: 3 },
  { type: "praise", label: "좋았어요", score: 4 },
] as const satisfies { type: FeedbackType; label: string; score: number }[];

/** 적을 수 있는 내용의 최대 길이. Notion 글 칸 한 덩어리의 한도이고, 프론트 Worker도 같은 길이로 막아요 */
export const MAX_FEEDBACK_CONTENT_LENGTH = 2000;
