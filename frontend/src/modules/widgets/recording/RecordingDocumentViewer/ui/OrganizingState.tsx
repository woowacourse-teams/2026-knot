import WritingAnimation from "@primitives/ui/WritingAnimation";

import StateMessage from "./StateMessage";

/**
 * 문서를 정리하는 중인 녹음(STT-R42 문서 정리 중). 정리 화면과 같은 그림과 문구를 두고 버튼은 두지 않아요.
 */
export default function OrganizingState() {
  return (
    <StateMessage
      illustration={<WritingAnimation />}
      title="회의 내용을 바탕으로 문서를 정리하고 있어요"
      descriptionLines={[
        "주제별로 문서를 나누는 중이에요.",
        "조금만 기다려 주세요.",
      ]}
    />
  );
}
