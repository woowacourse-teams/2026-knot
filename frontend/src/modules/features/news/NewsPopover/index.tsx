import useReleaseNotesQuery from "@api/queries/useReleaseNotesQuery";
import { SCREEN_CONDITION } from "@constants/breakpoint";
import Popover from "@composites/Popover";
import styled from "@emotion/styled";
import useMediaQuery from "@hooks/common/useMediaQuery";
import useIsSendingFeedback from "@hooks/domain/feedback/useIsSendingFeedback";
import type { ReactNode } from "react";

import LetterIcon from "@/assets/icons/letter.svg";

import { useLastSeenNote } from "./model/useLastSeenNote";
import NewsPopoverContent from "./ui/NewsPopoverContent";

interface RenderFeedbackPanelParams {
  /** 팝오버를 닫아요. 피드백 패널의 X와 보낸 뒤에 불러요 */
  onClose: () => void;
}

interface NewsPopoverProps {
  /**
   * 메뉴에서 「피드백 보내기」를 고르면 같은 자리에 그릴 피드백 패널.
   * 피드백은 다른 도메인이라 이 버튼이 직접 그리지 않고, 버튼을 놓는 레이아웃이 넘겨요
   */
  renderFeedbackPanel: (params: RenderFeedbackPanelParams) => ReactNode;
}

/**
 * Knot의 새 소식(릴리즈 노트)과 피드백 보내기를 여는 「소식과 피드백」 버튼. GNB 오른쪽 프로필 바로 왼쪽에 둬요(FEEDBACK-R1).
 *
 * 누르면 버튼 아래에 메뉴(새 소식, 피드백 보내기)를 오른쪽 끝을 맞춰 열고, 고르면 같은 자리에 그 패널을 열어요(NEWS-R3, FEEDBACK-R3).
 * 다시 누르거나 바깥을 누르거나 ESC를 누르면 닫고, 닫으면 이 버튼으로 포커스를 돌려줘요(FEEDBACK-R4).
 * 피드백을 보내는 동안에는 이 방법으로 닫지 않아요(FEEDBACK-R8).
 * 열려 있는 동안 버튼은 채워진 모양이고, 닫혀 있을 때 올리거나 포커스하면 툴팁 「새 소식과 피드백」을 보여 줘요.
 *
 * 노트는 프론트 Worker가 Notion 새 소식 DB에서 읽어 와요(ADR 956). 받지 못하면 새 소식 패널은 빈 상태를 보여 줘요.
 * 이 기기에서 새 소식을 한 번도 열지 않은 사용자에게는 노트를 받은 뒤 메뉴 없이 새 소식 패널을 저절로 열어 줘요(NEWS-R6).
 * 휴대폰에서는 피드백을 사이드바에서 열어서(FEEDBACK-R2), 누르면 메뉴 없이 새 소식 패널을 바로 열어요.
 */
export default function NewsPopover({ renderFeedbackPanel }: NewsPopoverProps) {
  const { data, isPending } = useReleaseNotesQuery();
  const notes = data?.notes ?? [];
  const { isAutoOpen, markAsSeen } = useLastSeenNote({ notes });
  const { isMatched: isMobile } = useMediaQuery(SCREEN_CONDITION.mobile);
  const { isSendingFeedback } = useIsSendingFeedback();
  const initialView = isAutoOpen || isMobile ? "news" : "menu";

  // 팝오버는 처음 그릴 때만 저절로 열지 봐서, 노트를 받기 전에는 버튼을 그리지 않아요
  if (isPending) return null;

  return (
    <Popover
      openOn="click"
      placement="bottom-end"
      isInitiallyOpen={isAutoOpen}
      isDismissible={!isSendingFeedback}
      onOpen={() => {
        if (initialView === "news") markAsSeen();
      }}
      content={({ close }) => (
        <NewsPopoverContent
          initialView={initialView}
          notes={notes}
          onNewsOpen={markAsSeen}
          feedbackPanel={renderFeedbackPanel({ onClose: close })}
        />
      )}
    >
      {(triggerProps) => (
        <Trigger type="button" aria-label="새 소식과 피드백" {...triggerProps}>
          <LetterIcon size={18} />
          <Tooltip aria-hidden>새 소식과 피드백</Tooltip>
        </Trigger>
      )}
    </Popover>
  );
}

/** 피그마 Tooltip: 버튼 8px 아래에 오른쪽 끝을 맞춰 떠요(FEEDBACK-R3) */
const Tooltip = styled.span`
  position: absolute;
  top: calc(100% + 0.5rem); /* 8px */
  right: 0;
  z-index: 30; /* 화면 위에 떠 있는 패널(20)보다 위에 떠요 */
  display: none;
  padding: 0.375rem 0.625rem; /* 6px 10px */
  border-radius: 0.375rem; /* 6px */
  background-color: ${({ theme }) => theme.neutral[900]};
  color: ${({ theme }) => theme.neutral[0]};
  white-space: nowrap;
  pointer-events: none;
  ${({ theme }) => theme.text.caption01};
`;

/** 피그마 Btn/소식과 피드백: 36px 원 · 아이콘 18px */
const Trigger = styled.button`
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0.5rem; /* 8px */
  border: 1px solid ${({ theme }) => theme.neutral[200]};
  border-radius: 62.4375rem; /* 999px */
  background-color: ${({ theme }) => theme.neutral[0]};
  color: ${({ theme }) => theme.neutral[600]};
  box-shadow: ${({ theme }) => theme.shadow01};
  transition:
    background-color 0.2s ease-in,
    color 0.2s ease-in;

  &[aria-expanded="true"] {
    border-color: ${({ theme }) => theme.neutral[700]};
    background-color: ${({ theme }) => theme.neutral[700]};
    color: ${({ theme }) => theme.neutral[0]};
  }

  /* 메뉴나 패널이 같은 자리에 뜨므로 열려 있는 동안에는 툴팁을 띄우지 않아요 */
  /* 툴팁은 버튼 안의 하나뿐인 span이에요 */
  &:focus-visible:not([aria-expanded="true"]) > span {
    display: block;
  }

  /* 손가락으로 누른 뒤 남는 호버로 툴팁이 뜨지 않게, 마우스를 올릴 수 있을 때만 띄워요 */
  @media (hover: hover) {
    &:hover:not([aria-expanded="true"]) > span {
      display: block;
    }
  }

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: 2px;
  }

  /* 손가락으로 누를 때는 보이는 크기(36px)는 그대로 두고 누르는 영역만 44px로 넓혀요(RESP-R9) */
  @media (pointer: coarse) {
    &::after {
      position: absolute;
      inset: -0.25rem; /* 4px */
      content: "";
    }
  }
`;
