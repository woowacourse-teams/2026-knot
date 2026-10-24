import { feedbackMutationKeys } from "@api/queryKey/feedback";
import { useIsMutating } from "@tanstack/react-query";

/**
 * 피드백을 보내는 중인지 알려 주는 도메인 훅.
 *
 * 보내는 동안에는 패널을 닫지 않아요(FEEDBACK-R8). 패널은 GNB 팝오버와 휴대폰 시트가 띄우는데,
 * 보내기는 패널 안에서 일어나서 띄운 쪽이 이 값으로 바깥 클릭 · ESC 닫기를 막아요.
 */
const useIsSendingFeedback = () => {
  const isSendingFeedback =
    useIsMutating({ mutationKey: feedbackMutationKeys.send() }) > 0;

  return { isSendingFeedback };
};

export default useIsSendingFeedback;
