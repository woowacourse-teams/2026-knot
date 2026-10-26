import styled from "@emotion/styled";
import LinkTo from "@primitives/ui/LinkTo";

import ChevronRightIcon from "@/assets/icons/chevronRight.svg";
import FileIcon from "@/assets/icons/file.svg";

interface MeetingDocumentCardProps {
  /** 카드를 누르면 갈 문서 보기 주소 */
  href: string;
  title: string;
  /** 한 줄 요약. 없으면 null이고, 그때는 제목만 보여줘요 */
  summary: string | null;
}

/**
 * 「이 회의에서 나온 문서」의 문서 카드 하나. 제목과 한 줄 요약을 담고, 누르면 그 문서의 문서 보기로 가요(STT-R52). 피그마 Card/RelatedDoc 상태=기본이에요.
 *
 * 주제 · 날짜 · 녹음 길이 · 확인 상태는 보여주지 않아요. 제목과 요약은 한 줄을 넘으면 말줄임해요.
 */
export default function MeetingDocumentCard({
  href,
  title,
  summary,
}: MeetingDocumentCardProps) {
  return (
    <li>
      <CardLink href={href}>
        <TopRow>
          <FileIcon size={16} aria-hidden />
          <Title>{title}</Title>
          <ChevronRightIcon size={12} aria-hidden />
        </TopRow>
        {summary !== null && <Summary>{summary}</Summary>}
      </CardLink>
    </li>
  );
}

/** 피그마 Card/RelatedDoc: 흰 카드, 모서리 16px, 안쪽 여백 16px 20px, 줄 사이 6px, 그림자 Shadow02 */
const CardLink = styled(LinkTo)`
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 0.375rem; /* 6px */
  height: 100%;
  min-height: 5rem; /* 80px */
  padding: 1rem 1.25rem; /* 16px 20px */
  border-radius: 1rem; /* 16px */
  background-color: ${({ theme }) => theme.neutral[0]};
  box-shadow: ${({ theme }) => theme.shadow02};

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: 2px;
  }
`;

/** 피그마 Top: 파일 아이콘 · 제목 · 화살표 사이 8px */
const TopRow = styled.div`
  display: flex;
  align-items: center;
  gap: 0.5rem; /* 8px */

  & > svg {
    flex-shrink: 0;
    color: ${({ theme }) => theme.neutral[400]};
  }

  /* 화살표는 아이콘보다 한 단계 진해요 */
  & > svg:last-of-type {
    color: ${({ theme }) => theme.neutral[500]};
  }
`;

const Title = styled.p`
  flex: 1;
  min-width: 0;
  ${({ theme }) => theme.text.label01};
  overflow: hidden;
  color: ${({ theme }) => theme.neutral[900]};
  white-space: nowrap;
  text-overflow: ellipsis;
`;

const Summary = styled.p`
  ${({ theme }) => theme.text.caption02};
  overflow: hidden;
  color: ${({ theme }) => theme.neutral[600]};
  white-space: nowrap;
  text-overflow: ellipsis;
`;
