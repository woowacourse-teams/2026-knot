import type { PostFeedbackRequestDto } from "@api/dto/feedback";
import axios from "axios";

/**
 * 피드백을 보내는 우리 주소의 중계 경로. 프론트 Worker(`src/worker/relay`)가 Notion 접수함에 넣어요(ADR 930).
 * Worker의 `FEEDBACK_RELAY_PATH`와 같아야 하고, wrangler.jsonc의 `run_worker_first`(`/relay/*`) 안에 있어야 해요
 */
export const FEEDBACK_RELAY_PATH = "/relay/f";

const TIMEOUT_DURATION = 10_000;

/**
 * @description 피드백을 프론트 Worker로 보내 Notion 「사용자 피드백 접수함」에 넣습니다
 * @param body - 고른 유형 · 적은 내용 · 화면 주소 · 리플레이 주소
 * @returns 없음
 * @example
 * await sendFeedbackApi(new PostFeedbackRequestDto({ type: "bug", content, pageUrl }));
 */
export const sendFeedbackApi = async (body: PostFeedbackRequestDto) => {
  // 우리 API가 아니라 같은 주소의 Worker라, httpClient의 API 주소 · CSRF 헤더 · 로그인 갱신을 거치지 않아요.
  // Notion이 거절하면 Worker가 그 응답 코드를 그대로 돌려줘, MutationCache가 적은 내용 없이 코드만 Sentry에 남겨요
  await axios.post(FEEDBACK_RELAY_PATH, body, { timeout: TIMEOUT_DURATION });
};
