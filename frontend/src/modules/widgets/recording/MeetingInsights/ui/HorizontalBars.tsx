import styled from "@emotion/styled";

import { SINGLE_SERIES_COLOR } from "../constants/chartColors";
import { useChartTooltip } from "../context/chartTooltipContext";

export interface BarItem {
  key: string;
  label: string;
  value: number;
  valueLabel: string;
  color?: string;
  /** 툴팁에 덧붙이는 설명 */
  detail?: string;
}

interface HorizontalBarsProps {
  items: BarItem[];
}

/** 값이 많은 것부터 비교하는 가로 막대. 막대 끝에 값을 함께 써요 */
const HorizontalBars = ({ items }: HorizontalBarsProps) => {
  const { bindTooltip } = useChartTooltip();
  const max = Math.max(...items.map(({ value }) => value), 1);

  return (
    <List>
      {items.map(({ key, label, value, valueLabel, color, detail }) => (
        <Row
          key={key}
          {...bindTooltip({
            value: valueLabel,
            label,
            detail,
            color: color ?? SINGLE_SERIES_COLOR,
          })}
        >
          <RowLabel title={label}>{label}</RowLabel>
          <BarTrack>
            <Bar
              style={{
                width: `${(value / max) * 100}%`,
                background: color ?? SINGLE_SERIES_COLOR,
              }}
            />
          </BarTrack>
          <RowValue>{valueLabel}</RowValue>
        </Row>
      ))}
    </List>
  );
};

export default HorizontalBars;

const List = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.375rem; /* 6px */
`;

const Row = styled.div`
  display: grid;
  grid-template-columns: minmax(4.5rem, 34%) 1fr auto;
  gap: 0.5rem; /* 8px */
  align-items: center;
  border-radius: 0.25rem; /* 4px */

  &:hover {
    background: ${({ theme }) => theme.neutral[50]};
  }
`;

const RowLabel = styled.span`
  ${({ theme }) => theme.text.caption02}
  overflow: hidden;
  color: ${({ theme }) => theme.neutral[700]};
  text-overflow: ellipsis;
  white-space: nowrap;
`;

const BarTrack = styled.div`
  min-width: 0;
`;

const Bar = styled.div`
  min-width: 2px;
  height: 0.875rem; /* 14px */
  border-radius: 0 0.25rem 0.25rem 0; /* 0 4px 4px 0 */
`;

const RowValue = styled.span`
  ${({ theme }) => theme.text.caption02}
  color: ${({ theme }) => theme.neutral[800]};
`;
