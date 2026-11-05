import styled from "@emotion/styled";

interface OutcomeListsProps {
  directions: string[];
  undecided: string[];
}

const ITEM_LIMIT = 8;

const OutcomeColumn = ({
  title,
  items,
}: {
  title: string;
  items: string[];
}) => (
  <Column>
    <ColumnTitle>{`${title} ${items.length}개`}</ColumnTitle>
    {items.length === 0 ? (
      <EmptyText>나온 항목이 없어요</EmptyText>
    ) : (
      <Items>
        {items.slice(0, ITEM_LIMIT).map((item) => (
          <li key={item}>{item}</li>
        ))}
        {items.length > ITEM_LIMIT && (
          <MoreItem>{`외 ${items.length - ITEM_LIMIT}개`}</MoreItem>
        )}
      </Items>
    )}
  </Column>
);

/** 회의에서 정한 것과 못 정한 것을 나란히 보여 줘요 */
const OutcomeLists = ({ directions, undecided }: OutcomeListsProps) => (
  <Columns>
    <OutcomeColumn title="정한 방향" items={directions} />
    <OutcomeColumn title="아직 결정하지 못한 것" items={undecided} />
  </Columns>
);

export default OutcomeLists;

const Columns = styled.div`
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(16rem, 1fr));
  gap: 1.25rem; /* 20px */
`;

const Column = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.5rem; /* 8px */
  min-width: 0;
`;

const ColumnTitle = styled.h4`
  ${({ theme }) => theme.text.label01}
  color: ${({ theme }) => theme.neutral[800]};
`;

const EmptyText = styled.p`
  ${({ theme }) => theme.text.caption02}
  color: ${({ theme }) => theme.neutral[500]};
`;

const Items = styled.ul`
  ${({ theme }) => theme.text.caption02}
  display: flex;
  flex-direction: column;
  gap: 0.25rem; /* 4px */
  padding-left: 1rem; /* 16px */
  color: ${({ theme }) => theme.neutral[700]};
  list-style: disc;
`;

const MoreItem = styled.li`
  color: ${({ theme }) => theme.neutral[500]};
  list-style: none;
`;
