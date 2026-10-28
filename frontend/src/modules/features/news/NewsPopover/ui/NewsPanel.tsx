import styled from "@emotion/styled";

import NewsIcon from "@/assets/icons/news.svg";
import type { ReleaseNote } from "@/shared/types/releaseNote";

import { formatReleaseDate } from "../utils/formatReleaseDate";
import { groupNotesByDate } from "../utils/groupNotesByDate";

import NewsItem from "./NewsItem";

interface NewsPanelProps {
  /** 보여 줄 노트. 최신 노트가 맨 앞이에요 */
  notes: ReleaseNote[];
  /** NEW 칩을 붙일 안 읽은 노트의 id */
  newNoteIds: string[];
}

/**
 * 새 소식 버튼이 여는 패널. 머리 아래에 노트를 배포 날짜별로 묶어 보여 주고, 노트가 없으면 빈 상태를 보여 줘요(NEWS-R4).
 *
 * 안 읽은 노트의 항목에는 NEW 칩을 붙여요. 같은 날 낸 노트가 한 묶음에 모여도 칩은 노트마다 따로 정해요.
 * 화면 높이를 넘으면 머리는 그대로 두고 목록만 패널 안에서 스크롤해요.
 */
export default function NewsPanel({ notes, newNoteIds }: NewsPanelProps) {
  const dateGroups = groupNotesByDate(
    notes.map(({ id, releasedAt, items }) => ({
      releasedAt,
      items: items.map((item) => ({ ...item, isNew: newNoteIds.includes(id) })),
    })),
  );

  return (
    <Container>
      <Header>
        <Title>새 소식</Title>
        <Description>새로 생기거나 달라진 점을 모아 둬요</Description>
      </Header>

      {dateGroups.length === 0 ? (
        <Empty>
          <EmptyIconWrapper>
            <NewsIcon size={18} />
          </EmptyIconWrapper>
          <EmptyTitle>아직 새 소식이 없어요</EmptyTitle>
          <EmptyDescription>
            바뀐 점이 생기면 여기에 모아 둘게요
          </EmptyDescription>
        </Empty>
      ) : (
        <Body>
          {dateGroups.map(({ releasedAt, items }) => (
            <DateGroup key={releasedAt}>
              <DateHeading>{formatReleaseDate(releasedAt)}</DateHeading>
              <ItemList>
                {items.map((item) => (
                  <NewsItem key={item.title} {...item} />
                ))}
              </ItemList>
            </DateGroup>
          ))}
        </Body>
      )}
    </Container>
  );
}

const Container = styled.div`
  display: flex;
  flex-direction: column;
  overflow: hidden;
  width: 22.5rem; /* 360px */
  /* 좁은 화면에서도 팝오버가 지키는 좌우 16px 여백 안에 들어가요 */
  max-width: calc(100vw - 2rem);
  /* GNB(44px) 가운데 버튼 아래 8px에서 열려 위로 48px, 화면 아래 여백 16px을 빼요 */
  max-height: calc(100dvh - 4rem);
  border: 1px solid ${({ theme }) => theme.neutral[200]};
  border-radius: 1rem; /* 16px */
  background-color: ${({ theme }) => theme.neutral[0]};
  box-shadow: ${({ theme }) => theme.shadow03};
`;

const Header = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.125rem; /* 2px */
  padding: 1.25rem 1.25rem 0.875rem; /* 20px 20px 14px */
  border-bottom: 1px solid ${({ theme }) => theme.neutral[200]};
`;

const Title = styled.h2`
  margin: 0;
  color: ${({ theme }) => theme.neutral[900]};
  ${({ theme }) => theme.text.label01};
`;

const Description = styled.p`
  margin: 0;
  color: ${({ theme }) => theme.neutral[500]};
  ${({ theme }) => theme.text.caption01};
`;

const Body = styled.div`
  display: flex;
  flex-direction: column;
  gap: 1.75rem; /* 28px */
  overflow-y: auto;
  padding: 1rem 1.25rem 1.5rem; /* 16px 20px 24px */
`;

const DateGroup = styled.section`
  display: flex;
  flex-direction: column;
  gap: 0.75rem; /* 12px */
`;

const DateHeading = styled.h3`
  margin: 0;
  color: ${({ theme }) => theme.neutral[500]};
  ${({ theme }) => theme.text.caption01};
`;

const ItemList = styled.ul`
  display: flex;
  flex-direction: column;
  gap: 1.25rem; /* 20px */
  margin: 0;
  padding: 0;
  list-style: none;
`;

const Empty = styled.div`
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.375rem; /* 6px */
  padding: 2.5rem 1.25rem 3rem; /* 40px 20px 48px */
  text-align: center;
`;

const EmptyIconWrapper = styled.div`
  display: flex;
  padding: 0.6875rem; /* 11px */
  /* 피그마의 4px 간격 틀과 위아래 6px 간격을 합쳐 제목까지 16px을 띄워요 */
  margin-bottom: 0.625rem; /* 10px */
  border-radius: 62.4375rem; /* 999px */
  background-color: ${({ theme }) => theme.neutral[100]};
  color: ${({ theme }) => theme.neutral[500]};
`;

const EmptyTitle = styled.p`
  margin: 0;
  color: ${({ theme }) => theme.neutral[800]};
  ${({ theme }) => theme.text.caption02};
  font-weight: 500;
`;

const EmptyDescription = styled.p`
  margin: 0;
  color: ${({ theme }) => theme.neutral[500]};
  ${({ theme }) => theme.text.caption01};
`;
