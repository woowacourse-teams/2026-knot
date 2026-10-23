/**
 * 피드백 패널에서 고르는 유형. 화면 문구와 점수는 `shared/constants/feedback`에 있어요.
 *
 * 프론트 Worker가 이 값을 Notion 접수함의 「종류」로 바꾸므로, 바꾸면 `src/worker/relay`의 `FEEDBACK_KIND`도 함께 바꿔요.
 */
export type FeedbackType = "inconvenience" | "bug" | "idea" | "praise";
