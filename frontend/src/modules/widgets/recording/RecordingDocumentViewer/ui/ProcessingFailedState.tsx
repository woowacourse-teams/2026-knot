import styled from "@emotion/styled";
import Button from "@primitives/ui/Button";

import DocumentFailedIllustration from "@/assets/illustrations/documentFailed.svg";

import StateMessage from "./StateMessage";

interface ProcessingFailedStateProps {
  /** 전사(녹음을 글로 옮기기) 단계에서 실패했는지. 아니면 주제 분류 단계의 실패예요 */
  isTranscriptionFailed: boolean;
  /** 「다시 시도」를 눌렀을 때. 서버가 다시 시도할 수 없다고 하면 넘기지 않아요 */
  onRetry?: () => void;
  /** 다시 시도 요청의 응답을 기다리는 중인지. 그동안 버튼을 누를 수 없어요 */
  isRetrying: boolean;
}

/**
 * 전사나 주제 분류가 실패한 녹음(STT-R42). 제목은 정리 화면의 실패(STT-R22)와 같아요.
 *
 * 전사 실패는 「녹음을 글로 옮기지 못했어요」, 주제 분류 실패는 「문서를 만들지 못했어요」예요. 그림 · 설명 · 버튼은 두 실패가 같아요.
 * 다시 시도할 수 있으면 녹음이 보관되어 있다고 알리고 「다시 시도」를 둬요. 다시 시도할 수 없으면 같은 제목만 둬요.
 * 서버가 다시 시도할 수 없는 이유를 나눠 주지 않아 이유를 짐작해 쓰지 않아요.
 */
export default function ProcessingFailedState({
  isTranscriptionFailed,
  onRetry,
  isRetrying,
}: ProcessingFailedStateProps) {
  const title = isTranscriptionFailed
    ? "녹음을 글로 옮기지 못했어요"
    : "문서를 만들지 못했어요";

  if (onRetry === undefined) {
    return (
      <StateMessage
        illustration={<FailedIllustration size={48} />}
        title={title}
      />
    );
  }

  return (
    <StateMessage
      illustration={<FailedIllustration size={48} />}
      title={title}
      descriptionLines={[
        "녹음은 보관해 두었어요.",
        "다시 시도하거나, 홈의 진행 중인 녹음에서 나중에 다시 시도할 수 있어요.",
      ]}
      button={
        <Button size="sm" isLoading={isRetrying} onClick={onRetry}>
          다시 시도
        </Button>
      }
    />
  );
}

const FailedIllustration = styled(DocumentFailedIllustration)`
  color: ${({ theme }) => theme.primary};
`;
