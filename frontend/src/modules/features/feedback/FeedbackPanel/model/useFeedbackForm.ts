import useSendFeedbackMutation from "@api/mutations/useSendFeedbackMutation";
import { FEEDBACK_TYPE_OPTIONS } from "@constants/feedback";
import { TOAST_MESSAGES } from "@constants/toastMessages";
import { useToast } from "@provider/context/toastContext";
import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import {
  clearFeedbackDraft,
  readFeedbackDraft,
  saveFeedbackDraft,
  type FeedbackDraft,
} from "@utils/feedbackDraft";
import { getAnalyticsReplayUrl } from "@utils/getAnalyticsReplayUrl";
import { useState } from "react";

import type { FeedbackType } from "@/shared/types/feedback";

interface UseFeedbackFormParams {
  /** 보내기에 성공하면 불러요. 패널을 닫아요 */
  onSent: () => void;
}

/**
 * 피드백 패널의 고른 유형 · 적은 내용과 보내기.
 *
 * - `type`, `content`: 고른 유형(없으면 `null`)과 적은 내용. 바꿀 때마다 이 탭에 남겨, 닫았다 열거나 새로고침해도 그대로예요(FEEDBACK-R6)
 * - `isSending`, `isFailed`: 보내는 중인지, 마지막으로 보내지 못했는지(FEEDBACK-R8)
 * - `send`: 유형을 골랐을 때만 보내요. 보고 있는 화면 주소와 PostHog 리플레이 주소를 함께 보내요
 *
 * 저장에 성공해야만 남긴 내용을 지우고, PostHog `survey sent`를 보내고(FEEDBACK-R7), 토스트를 띄운 뒤 `onSent`를 불러요.
 * 실패하면 적은 내용을 그대로 두고, 실패는 MutationCache가 Sentry에 남겨요.
 */
const useFeedbackForm = ({ onSent }: UseFeedbackFormParams) => {
  const [draft, setDraft] = useState(readFeedbackDraft);
  const { mutate, isPending, isError } = useSendFeedbackMutation();
  const { show } = useToast();

  const changeDraft = (next: FeedbackDraft) => {
    setDraft(next);
    saveFeedbackDraft(next);
  };

  const changeType = (type: FeedbackType) => changeDraft({ ...draft, type });

  const changeContent = (content: string) => changeDraft({ ...draft, content });

  const send = () => {
    const option = FEEDBACK_TYPE_OPTIONS.find(
      ({ type }) => type === draft.type,
    );
    // 유형을 고르지 않았으면 보내지 않아요(FEEDBACK-R5)
    if (!option) return;

    const content = draft.content.trim();

    mutate(
      {
        type: option.type,
        content,
        pageUrl: window.location.href,
        replayUrl: getAnalyticsReplayUrl(),
      },
      {
        onSuccess: () => {
          clearFeedbackDraft();
          captureAnalyticsEvent({
            event: "survey sent",
            properties: {
              feedback_type: option.label,
              feedback_score: option.score,
              ...(content && { feedback_content: content }),
            },
          });
          show(TOAST_MESSAGES.FEEDBACK_SENT);
          onSent();
        },
      },
    );
  };

  return {
    type: draft.type,
    content: draft.content,
    isSending: isPending,
    isFailed: isError,
    changeType,
    changeContent,
    send,
  };
};

export default useFeedbackForm;
