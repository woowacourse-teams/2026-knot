import styled from "@emotion/styled";

import AlertIcon from "@/assets/icons/alert.svg";

interface CardNoticeProps {
  title: string;
  description: string;
}

/**
 * 노션 앱 카드 안의 붉은 안내 상자. 연결 만료와 노션 게스트라 막힌 경우에 써요.
 */
export default function CardNotice({ title, description }: CardNoticeProps) {
  return (
    <Container role="status">
      <IconWrapper>
        <AlertIcon size={18} />
      </IconWrapper>
      <Text>
        <Title>{title}</Title>
        <Description>{description}</Description>
      </Text>
    </Container>
  );
}

/** 피그마 상태 안내: 안쪽 14px 16px, 아이콘과 글 사이 10px */
const Container = styled.div`
  display: flex;
  align-items: flex-start;
  gap: 0.625rem; /* 10px */
  padding: 0.875rem 1rem; /* 14px 16px */
  border-radius: 1rem; /* 16px */
  background-color: ${({ theme }) => theme.sub.warning[50]};
`;

/** 아이콘을 제목 첫 줄 가운데에 맞춰요 */
const IconWrapper = styled.span`
  display: flex;
  flex-shrink: 0;
  padding-top: 0.1875rem; /* 3px */
  color: ${({ theme }) => theme.neutral[800]};
`;

const Text = styled.p`
  display: flex;
  flex-direction: column;
  gap: 0.125rem; /* 2px */
  min-width: 0;
`;

const Title = styled.span`
  ${({ theme }) => theme.text.label01};
  color: ${({ theme }) => theme.sub.warning[700]};
`;

const Description = styled.span`
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[700]};
`;
