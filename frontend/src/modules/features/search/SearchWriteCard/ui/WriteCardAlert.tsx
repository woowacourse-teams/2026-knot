import styled from "@emotion/styled";

import Alert from "@/assets/icons/alert.svg";

interface WriteCardAlertProps {
  /** 경고 색으로 보여 줄 짧은 제목 */
  title: string;
  /** 제목 아래 회색 설명 */
  description: string;
}

/** 실패하거나 연결이 필요해 멈춘 카드의 경고 제목과 설명(SEARCH-R52, R53) */
export default function WriteCardAlert({
  title,
  description,
}: WriteCardAlertProps) {
  return (
    <Container>
      <TitleRow>
        <AlertIcon size={18} />
        <Title>{title}</Title>
      </TitleRow>
      <Description>{description}</Description>
    </Container>
  );
}

const Container = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.25rem; /* 4px */
  min-width: 0;
`;

const TitleRow = styled.p`
  display: flex;
  align-items: center;
  gap: 0.375rem; /* 6px */
`;

const AlertIcon = styled(Alert)`
  flex-shrink: 0;
  color: ${({ theme }) => theme.neutral[800]};
`;

const Title = styled.span`
  ${({ theme }) => theme.text.caption02};
  font-weight: 600;
  color: ${({ theme }) => theme.sub.warning[700]};
`;

const Description = styled.p`
  overflow-wrap: anywhere;
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[500]};
`;
