import styled from "@emotion/styled";

import FeedbackIcon from "@/assets/icons/feedback.svg";
import NewsIcon from "@/assets/icons/news.svg";

interface NewsMenuProps {
  /** 「새 소식」을 누르면 불러요 */
  onNewsSelect: () => void;
  /** 「피드백 보내기」를 누르면 불러요 */
  onFeedbackSelect: () => void;
}

/**
 * 「소식과 피드백」 버튼이 여는 메뉴. 고르면 메뉴가 닫히고 같은 자리에 그 패널이 열려요(NEWS-R3, FEEDBACK-R3).
 *
 * 메뉴를 열면 첫 항목으로 포커스를 옮겨, 키보드로 연 사람이 바로 고를 수 있어요.
 */
export default function NewsMenu({
  onNewsSelect,
  onFeedbackSelect,
}: NewsMenuProps) {
  return (
    <Container aria-label="새 소식과 피드백">
      {/* 메뉴를 열면 바로 고를 수 있게 첫 항목으로 포커스를 옮겨요 */}
      <MenuItem type="button" onClick={onNewsSelect} autoFocus>
        <NewsIcon size={18} />새 소식
      </MenuItem>
      <MenuItem type="button" onClick={onFeedbackSelect}>
        <FeedbackIcon size={18} />
        피드백 보내기
      </MenuItem>
    </Container>
  );
}

/** 피그마 Menu/소식과 피드백: 폭 240px · 안쪽 6px */
const Container = styled.nav`
  display: flex;
  flex-direction: column;
  width: 15rem; /* 240px */
  padding: 0.375rem; /* 6px */
  border: 1px solid ${({ theme }) => theme.neutral[200]};
  border-radius: 1rem; /* 16px */
  background-color: ${({ theme }) => theme.neutral[0]};
  box-shadow: ${({ theme }) => theme.shadow03};
`;

const MenuItem = styled.button`
  display: flex;
  align-items: center;
  gap: 0.625rem; /* 10px */
  padding: 0.625rem 0.75rem; /* 10px 12px */
  border-radius: 0.5rem; /* 8px */
  color: ${({ theme }) => theme.neutral[900]};
  ${({ theme }) => theme.text.caption02};
  font-weight: 500;
  text-align: left;

  & > svg {
    flex-shrink: 0;
    color: ${({ theme }) => theme.neutral[600]};
  }

  &:hover {
    background-color: ${({ theme }) => theme.neutral[100]};
  }

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: -2px;
  }
`;
