import {
  PostFeedbackRequestDto,
  type PostFeedbackRequestInput,
} from "@api/dto/feedback";
import { sendFeedbackApi } from "@api/fetch/relay/f";
import { feedbackMutationKeys } from "@api/queryKey/feedback";
import { useMutation } from "@tanstack/react-query";

/**
 * 피드백을 Notion 접수함에 보내는 뮤테이션 훅. 무효화할 쿼리는 없어요.
 *
 * 보내는 동안 패널을 닫지 못하게, 패널을 띄운 쪽이 `mutationKey`로 보내는 중인지 봐요(`useIsSendingFeedback`).
 */
const useSendFeedbackMutation = () => {
  return useMutation({
    mutationKey: feedbackMutationKeys.send(),
    mutationFn: (input: PostFeedbackRequestInput) =>
      sendFeedbackApi(new PostFeedbackRequestDto(input)),
  });
};

export default useSendFeedbackMutation;
