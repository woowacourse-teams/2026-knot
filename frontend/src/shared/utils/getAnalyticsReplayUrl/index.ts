import posthog from "posthog-js";

/** 리플레이를 보내기 몇 초 전부터 열지. 무엇을 하다가 보냈는지 보이게 조금 앞에서 시작해요 */
const REPLAY_LOOK_BACK_SECONDS = 30;

/**
 * @description 지금 세션의 PostHog 리플레이 주소를 돌려줘요. 피드백 접수함의 「원문 링크」로 넣어요
 * @returns 보내기 30초 전부터 보는 리플레이 주소. PostHog가 꺼져 있거나 세션이 없으면 `undefined`
 * @example
 * const replayUrl = getAnalyticsReplayUrl(); // "https://us.posthog.com/project/…/replay/…?t=…"
 *
 * `src/index.tsx`는 PostHog 키가 있을 때만 PostHog를 켜요. 키가 없는 로컬 · 테스트에서는 주소가 없어요.
 */
export const getAnalyticsReplayUrl = () => {
  if (!process.env.POSTHOG_KEY) return undefined;

  return (
    posthog.get_session_replay_url({
      withTimestamp: true,
      timestampLookBack: REPLAY_LOOK_BACK_SECONDS,
    }) || undefined
  );
};
