import styled from "@emotion/styled";

import DocumentEmptyIllustration from "@/assets/illustrations/documentEmpty.svg";

import StateMessage from "./StateMessage";

/**
 * 정리할 내용이 없던 녹음(STT-R42 정리할 내용 없음). 오류가 아니라 결과라서 경고색과 「다시 시도」를 두지 않아요(STT-R16).
 */
export default function NoContentState() {
  return (
    <StateMessage
      illustration={<EmptyIllustration size={48} />}
      descriptionLines={["변환된 음성 기록이 없어요."]}
    />
  );
}

const EmptyIllustration = styled(DocumentEmptyIllustration)`
  color: ${({ theme }) => theme.primary};
`;
