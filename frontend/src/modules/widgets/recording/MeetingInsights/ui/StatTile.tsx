import styled from "@emotion/styled";

interface StatTileProps {
  value: string | number;
  label: string;
  /** 값 아래 덧붙이는 설명 */
  note?: string;
}

/** 이보다 긴 값은 칸 안에서 꺾이지 않게 글자를 줄여요 */
const LONG_VALUE_LENGTH = 6;

/** 값 하나를 크게 보여 주는 칸 */
const StatTile = ({ value, label, note }: StatTileProps) => (
  <Tile>
    <TileLabel>{label}</TileLabel>
    <TileValue $isLong={String(value).length >= LONG_VALUE_LENGTH}>
      {value}
    </TileValue>
    {note !== undefined && <TileNote>{note}</TileNote>}
  </Tile>
);

export default StatTile;

export const StatRow = styled.div`
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(7rem, 1fr));
  gap: 1rem; /* 16px */
`;

const Tile = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.125rem; /* 2px */
  min-width: 0;
`;

const TileLabel = styled.span`
  ${({ theme }) => theme.text.caption02}
  color: ${({ theme }) => theme.neutral[600]};
`;

const TileValue = styled.strong<{ $isLong: boolean }>`
  ${({ theme, $isLong }) =>
    $isLong ? theme.text.heading02 : theme.text.title02}
  color: ${({ theme }) => theme.neutral[900]};
`;

const TileNote = styled.span`
  ${({ theme }) => theme.text.caption01}
  color: ${({ theme }) => theme.neutral[500]};
`;
