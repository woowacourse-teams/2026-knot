import styled from "@emotion/styled";
import LinkTo from "@primitives/ui/LinkTo";
import type { ReactNode } from "react";

import ChevronRightIcon from "@/assets/icons/chevronRight.svg";

interface RelatedCardProps {
  /** 카드를 누르면 갈 주소 */
  href: string;
  /** 제목 앞 16px 아이콘. 장식이라 스크린리더에는 읽히지 않게 넘겨요 */
  icon: ReactNode;
  /** 제목. 한 줄을 넘으면 말줄임해요. 아직 받지 못했으면 스켈레톤 같은 자리 표시를 넘겨요 */
  title: ReactNode;
  /** 제목 아래 한 줄 설명. 없으면 null이고, 그때는 제목만 보여줘요 */
  description: string | null;
}

/**
 * 다른 화면으로 이어지는 흰 카드 하나. 아이콘 · 제목 · 화살표 아래에 한 줄 설명을 두고, 누르면 그 주소로 가요. 피그마 Card/RelatedDoc 상태=기본이에요.
 *
 * 녹음 문서의 「이 회의에서 나온 문서」(STT-R52)와 문서 보기의 「이 문서가 나온 녹음」(DOC-R25)이 같은 모양을 써요.
 */
export default function RelatedCard({
  href,
  icon,
  title,
  description,
}: RelatedCardProps) {
  return (
    <CardLink href={href}>
      <TopRow>
        {icon}
        <Title>{title}</Title>
        <ChevronRightIcon size={12} aria-hidden />
      </TopRow>
      {description !== null && <Description>{description}</Description>}
    </CardLink>
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

/** 피그마 Top: 아이콘 · 제목 · 화살표 사이 8px */
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

const Description = styled.p`
  ${({ theme }) => theme.text.caption02};
  overflow: hidden;
  color: ${({ theme }) => theme.neutral[600]};
  white-space: nowrap;
  text-overflow: ellipsis;
`;
