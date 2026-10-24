/**
 * 피드백 DTO. 우리 API가 아니라 프론트 Worker가 받아 Notion 「사용자 피드백 접수함」에 넣어요(ADR 930)
 *
 * - POST /relay/f
 */

/** 고른 유형. Worker가 접수함의 「종류」로 바꿔요(`src/worker/relay`의 `FEEDBACK_KIND`) */
type FeedbackTypeValue = "inconvenience" | "bug" | "idea" | "praise";

// POST /relay/f

/** 피드백을 보낼 때 앱이 넘기는 값 */
export interface PostFeedbackRequestInput {
  type: FeedbackTypeValue;
  content: string;
  pageUrl: string;
  replayUrl?: string;
}

/** 피드백 요청 본문. 성공하면 Worker가 본문 없이 201을 돌려줘요 */
export class PostFeedbackRequestDto {
  /** 고른 유형. 예: `bug`(오류가 있어요) */
  type: FeedbackTypeValue;
  /** 적은 내용. 적지 않았으면 빈 문자열이고, 최대 2000자예요 */
  content: string;
  /** 보낼 때 보고 있던 화면의 절대 주소. 우리 주소여야 하고, 접수함의 「사용 상황」에 들어가요 */
  pageUrl: string;
  /** 그 세션의 PostHog 리플레이 주소(`https://us.posthog.com/…`). 접수함의 「원문 링크」에 들어가고, PostHog가 꺼져 있으면 없어요 */
  replayUrl?: string;

  constructor({ type, content, pageUrl, replayUrl }: PostFeedbackRequestInput) {
    this.type = type;
    this.content = content;
    this.pageUrl = pageUrl;
    this.replayUrl = replayUrl;
  }
}
