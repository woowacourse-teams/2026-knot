import FeedbackPanel from "@features/feedback/FeedbackPanel";
import styled from "@emotion/styled";
import useKeyDown from "@hooks/common/useKeyDown";
import useIsSendingFeedback from "@hooks/domain/feedback/useIsSendingFeedback";
import Dim from "@primitives/ui/Dim";

interface FeedbackSheetProps {
  /** 시트를 닫아요 */
  onClose: () => void;
}

/**
 * 휴대폰에서 피드백 패널을 화면 아래 시트로 띄워요(FEEDBACK-R2). 흐린 바깥이나 ESC로 닫혀요(FEEDBACK-R4).
 *
 * 보내는 동안에는 바깥과 ESC로 닫지 않아요(FEEDBACK-R8). 닫히면 시트를 연 「피드백」으로 포커스가 돌아가요(`DialogProvider`).
 */
export default function FeedbackSheet({ onClose }: FeedbackSheetProps) {
  const { isSendingFeedback } = useIsSendingFeedback();

  useKeyDown({
    key: "Escape",
    isEnabled: !isSendingFeedback,
    onKeyDown: onClose,
  });

  return (
    <Dim
      onClick={() => {
        if (!isSendingFeedback) onClose();
      }}
    >
      <SheetWrapper onClick={(e) => e.stopPropagation()}>
        <FeedbackPanel onClose={onClose} />
      </SheetWrapper>
    </Dim>
  );
}

// 패널 안을 누른 것은 바깥 누름으로 보지 않게 막기만 하고, 배치는 패널이 정해요
const SheetWrapper = styled.div`
  display: contents;
`;
