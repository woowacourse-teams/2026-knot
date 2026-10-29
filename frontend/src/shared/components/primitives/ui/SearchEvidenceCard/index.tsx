import styled from "@emotion/styled";
import { formatDate } from "@utils/formatDate";

interface SearchEvidenceCardProps {
  /** 문서 제목 */
  title: string;
  /** 출처(근거가 나온 곳의 종류, v2는 `문서`만) */
  sourceType: string;
  /** 문서 날짜(ISO). 없으면 날짜 자리를 비워요 */
  createdAt?: string;
}

/**
 * 답변의 근거가 된 문서 하나를 보여주는 카드.
 *
 * 첫 줄에 문서 제목, 둘째 줄에 출처와 날짜를 그려요. 날짜가 없으면 출처만 그려요.
 * 제목은 두 줄까지 보이고 넘치면 말줄임돼요.
 */
export default function SearchEvidenceCard({
  title,
  sourceType,
  createdAt,
}: SearchEvidenceCardProps) {
  return (
    <Container>
      <Title>{title}</Title>
      <Meta>
        <span>{sourceType}</span>
        {createdAt && <span>{formatDate(createdAt)}</span>}
      </Meta>
    </Container>
  );
}

const Container = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.625rem; /* 10px */
  padding: 1rem 1.25rem; /* 16px 20px */
  border-radius: 0.75rem; /* 12px */
  background-color: ${({ theme }) => theme.neutral[50]};
`;

const Title = styled.p`
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow-wrap: anywhere;
  ${({ theme }) => theme.text.label01};
  color: ${({ theme }) => theme.neutral[900]};
`;

const Meta = styled.p`
  display: flex;
  gap: 0.75rem; /* 12px */
  ${({ theme }) => theme.text.caption01};
  color: ${({ theme }) => theme.neutral[600]};
`;
