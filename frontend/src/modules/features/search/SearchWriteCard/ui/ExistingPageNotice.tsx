import styled from "@emotion/styled";
import Button from "@primitives/ui/Button";
import { formatDate } from "@utils/formatDate";

interface ExistingPageNoticeProps {
  /** 이미 만든 노션 페이지 주소 */
  url: string;
  /** 만든 시각(ISO 8601) */
  createdAt: string;
}

/**
 * 같은 대화에서 같은 회의 문서로 이미 만든 페이지를 알리는 안내(SEARCH-R51). [노션에서 열기]로 그 페이지를 새 탭에 띄워요.
 *
 * 만든 이력일 뿐이라 만들기는 막지 않아요.
 */
export default function ExistingPageNotice({
  url,
  createdAt,
}: ExistingPageNoticeProps) {
  const handleOpenClick = () => {
    window.open(url, "_blank", "noopener,noreferrer");
  };

  return (
    <Container>
      <Texts>
        <Title>이미 만든 페이지가 있어요</Title>
        <CreatedAt>{formatDate(createdAt)}</CreatedAt>
      </Texts>
      <Button size="sm" variant="outline" onClick={handleOpenClick}>
        노션에서 열기
      </Button>
    </Container>
  );
}

const Container = styled.div`
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem; /* 12px */
  padding: 0.75rem; /* 12px */
  border-radius: 0.5rem; /* 8px */
  background-color: ${({ theme }) => theme.neutral[100]};
`;

const Texts = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.125rem; /* 2px */
  min-width: 0;
`;

const Title = styled.p`
  ${({ theme }) => theme.text.caption02};
  font-weight: 600;
  color: ${({ theme }) => theme.neutral[900]};
`;

const CreatedAt = styled.p`
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[500]};
`;
