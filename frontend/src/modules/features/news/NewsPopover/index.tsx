import { RELEASE_NOTES } from "@constants/releaseNote";
import Popover from "@composites/Popover";
import styled from "@emotion/styled";

import LetterIcon from "@/assets/icons/letter.svg";
import type { ReleaseNote } from "@/shared/types/releaseNote";

import { useLastSeenNote } from "./model/useLastSeenNote";
import NewsPanel from "./ui/NewsPanel";

interface NewsPopoverProps {
  /** 패널에 보여 줄 노트. 기본은 `shared/constants/releaseNote`의 노트이고, 빈 상태를 보려면 빈 배열을 넘겨요 */
  notes?: ReleaseNote[];
}

/**
 * Knot 서비스의 새 소식(릴리즈 노트)을 여는 버튼. GNB 오른쪽 프로필 바로 왼쪽에 두는 버튼이에요(NEWS-R1).
 *
 * 누르면 버튼 아래에 새 소식 패널을 오른쪽 끝을 맞춰 열고, 다시 누르거나 바깥을 누르거나 ESC를 누르면 닫아요.
 * 열려 있는 동안 버튼은 채워진 모양이에요.
 *
 * 이 기기에서 새 소식을 한 번도 열지 않은 사용자에게는 처음 그릴 때 패널을 저절로 열어 줘요(NEWS-R6).
 */
export default function NewsPopover({
  notes = RELEASE_NOTES,
}: NewsPopoverProps) {
  const { isAutoOpen, markAsSeen } = useLastSeenNote({ notes });

  return (
    <Popover
      openOn="click"
      placement="bottom-end"
      isInitiallyOpen={isAutoOpen}
      onOpen={markAsSeen}
      content={<NewsPanel notes={notes} />}
    >
      {(triggerProps) => (
        <Trigger type="button" aria-label="새 소식과 피드백" {...triggerProps}>
          <LetterIcon size={18} />
        </Trigger>
      )}
    </Popover>
  );
}

/** 피그마 Btn/소식과 피드백: 36px 원 · 아이콘 18px */
const Trigger = styled.button`
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

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: 2px;
  }

  /* 손가락으로 누를 때는 보이는 크기(36px)는 그대로 두고 누르는 영역만 44px로 넓혀요(RESP-R9) */
  @media (pointer: coarse) {
    position: relative;

    &::after {
      position: absolute;
      inset: -0.25rem; /* 4px */
      content: "";
    }
  }
`;
