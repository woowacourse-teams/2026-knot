import styled from "@emotion/styled";

interface TutorialBarProps {
  onSkip: () => void;
}

/**
 * 튜토리얼 2~4단계에 화면 위쪽 가운데에 띄우는 바. 예시 대화라 대화 목록에 남지 않는다고 알리고 건너뛰기를 둬요.
 */
export default function TutorialBar({ onSkip }: TutorialBarProps) {
  return (
    <Container>
      <Label>탐색 둘러보기</Label>
      <Description>예시 대화라 대화 목록에 남지 않아요</Description>
      <SkipButton type="button" onClick={onSkip}>
        건너뛰기
      </SkipButton>
    </Container>
  );
}

const Container = styled.div`
  position: fixed;
  top: 5rem; /* 80px, GNB 아래 */
  left: 50%;
  display: flex;
  align-items: center;
  gap: 0.75rem; /* 12px */
  max-width: calc(100vw - 2rem);
  padding: 0.5rem 0.5rem 0.5rem 1rem; /* 8px 8px 8px 16px */
  border-radius: 999px;
  background-color: ${({ theme }) => theme.neutral[800]};
  transform: translateX(-50%);
  pointer-events: auto;
`;

const Label = styled.span`
  flex-shrink: 0;
  ${({ theme }) => theme.text.caption02};
  font-weight: 500;
  color: ${({ theme }) => theme.neutral[0]};
`;

const Description = styled.span`
  overflow: hidden;
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[300]};
  white-space: nowrap;
  text-overflow: ellipsis;
`;

const SkipButton = styled.button`
  flex-shrink: 0;
  padding: 0.375rem 0.75rem; /* 6px 12px */
  border-radius: 999px;
  ${({ theme }) => theme.text.caption02};
  font-weight: 500;
  color: ${({ theme }) => theme.neutral[0]};
  background-color: rgba(255, 255, 255, 0.14);
  cursor: pointer;

  &:hover {
    background-color: rgba(255, 255, 255, 0.24);
  }

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.neutral[0]};
    outline-offset: 2px;
  }
`;
