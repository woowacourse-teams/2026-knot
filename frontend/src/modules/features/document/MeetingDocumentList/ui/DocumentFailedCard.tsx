import styled from "@emotion/styled";
import Spinner from "@primitives/ui/Spinner";

import AlertIcon from "@/assets/icons/alert.svg";

interface DocumentFailedCardProps {
  /** 「다시 시도」를 눌렀을 때. 서버가 다시 시도할 수 없다고 하면 넘기지 않고, 그때는 「다시 시도」를 그리지 않아요 */
  onRetry?: () => void;
  /** 다시 시도 요청의 응답을 기다리는 중인지. 그동안 「다시 시도」를 누를 수 없어요 */
  isRetrying: boolean;
}

/**
 * 「이 회의에서 나온 문서」에서 만들지 못한 문서 자리를 알리는 카드(STT-R52 · R42). 피그마 Card/RelatedDoc 상태=만들지 못함이에요.
 *
 * 실패한 문서 작업이 여러 개여도 카드는 하나예요. 「다시 시도」는 실패한 문서 작업을 모두 다시 접수해요.
 * 무엇을 다시 접수할지는 이 카드를 놓는 쪽이 정해요.
 */
export default function DocumentFailedCard({
  onRetry,
  isRetrying,
}: DocumentFailedCardProps) {
  return (
    <Card>
      <TopRow>
        <AlertIcon size={16} aria-hidden />
        <Title>문서를 만들지 못했어요</Title>
      </TopRow>
      <BottomRow>
        <Description>녹음은 보관해 두었어요.</Description>
        {onRetry !== undefined && (
          <RetryButton
            type="button"
            disabled={isRetrying}
            aria-busy={isRetrying}
            onClick={onRetry}
          >
            {isRetrying && <Spinner size="0.75rem" />}
            다시 시도
          </RetryButton>
        )}
      </BottomRow>
    </Card>
  );
}

/** 피그마 Card/RelatedDoc 상태=만들지 못함: Neutral/100 바탕, 1px 점선 테두리, 모서리 16px, 안쪽 여백 16px 20px, 줄 사이 6px */
const Card = styled.li`
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 0.375rem; /* 6px */
  min-height: 5rem; /* 80px */
  padding: 1rem 1.25rem; /* 16px 20px */
  border: 1px dashed ${({ theme }) => theme.neutral[300]};
  border-radius: 1rem; /* 16px */
  background-color: ${({ theme }) => theme.neutral[100]};
`;

/** 경고색은 아이콘에만 써요(에러 표시 기준) */
const TopRow = styled.div`
  display: flex;
  align-items: center;
  gap: 0.5rem; /* 8px */

  & > svg {
    flex-shrink: 0;
    color: ${({ theme }) => theme.sub.warning[700]};
  }
`;

const Title = styled.p`
  ${({ theme }) => theme.text.label01};
  color: ${({ theme }) => theme.neutral[900]};
`;

const BottomRow = styled.div`
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem; /* 8px */
`;

const Description = styled.p`
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[600]};
`;

/** 피그마 「다시 시도」: 밑줄 친 진한 글자 버튼 */
const RetryButton = styled.button`
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 0.25rem; /* 4px */
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[900]};
  font-weight: 600;
  text-decoration: underline;
  text-underline-offset: 0.1875rem; /* 3px */

  &:disabled {
    cursor: default;
    opacity: 0.6;
  }

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: 2px;
  }
`;
