import styled from "@emotion/styled";

import {
  SEQUENTIAL_COLORS,
  SINGLE_SERIES_COLOR,
} from "../constants/chartColors";
import { useChartTooltip } from "../context/chartTooltipContext";
import { getNiceMax } from "../utils/formatMetric";

export interface ColumnItem {
  key: string;
  value: number;
  valueLabel: string;
  label: string;
}

interface ColumnChartProps {
  items: ColumnItem[];
  /** 왼쪽 눈금 값을 글자로 바꿔요 */
  formatTick?: (value: number) => string;
  /** 아래 양 끝에 쓰는 글자 */
  startLabel?: string;
  endLabel?: string;
  /** 값을 주면 이 막대만 진하게 보여 줘요 */
  selectedKey?: string;
}

const CHART_HEIGHT = 160;

/** 시간 순서로 값을 비교하는 세로 막대 */
const ColumnChart = ({
  items,
  formatTick = String,
  startLabel,
  endLabel,
  selectedKey,
}: ColumnChartProps) => {
  const { bindTooltip } = useChartTooltip();
  const max = getNiceMax(Math.max(...items.map(({ value }) => value), 0));

  return (
    <Chart>
      <Plot style={{ height: CHART_HEIGHT }}>
        <Ticks>
          <span>{formatTick(max)}</span>
          <span>{formatTick(max / 2)}</span>
          <span>{formatTick(0)}</span>
        </Ticks>
        <Columns>
          <GridLine style={{ top: 0 }} />
          <GridLine style={{ top: "50%" }} />
          <GridLine style={{ bottom: 0 }} />
          {items.map(({ key, value, valueLabel, label }) => (
            <ColumnHit key={key} {...bindTooltip({ value: valueLabel, label })}>
              <Column
                style={{
                  height: `${(value / max) * 100}%`,
                  background:
                    selectedKey === undefined || selectedKey === key
                      ? SINGLE_SERIES_COLOR
                      : SEQUENTIAL_COLORS[1],
                }}
              />
            </ColumnHit>
          ))}
        </Columns>
      </Plot>
      <EdgeLabels>
        <span>{startLabel}</span>
        <span>{endLabel}</span>
      </EdgeLabels>
    </Chart>
  );
};

export default ColumnChart;

const Chart = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.25rem; /* 4px */
`;

const Plot = styled.div`
  display: flex;
  gap: 0.5rem; /* 8px */
`;

const Ticks = styled.div`
  ${({ theme }) => theme.text.caption01}
  display: flex;
  flex: none;
  flex-direction: column;
  justify-content: space-between;
  min-width: 1.75rem; /* 28px */
  color: ${({ theme }) => theme.neutral[500]};
  text-align: right;
`;

const Columns = styled.div`
  position: relative;
  display: flex;
  flex: 1;
  gap: 2px;
  align-items: flex-end;
  min-width: 0;
`;

const GridLine = styled.div`
  position: absolute;
  right: 0;
  left: 0;
  height: 1px;
  background: ${({ theme }) => theme.neutral[100]};
`;

const ColumnHit = styled.div`
  position: relative;
  display: flex;
  flex: 1;
  align-items: flex-end;
  justify-content: center;
  min-width: 0;
  height: 100%;

  &:hover > div {
    filter: brightness(1.15);
  }
`;

const Column = styled.div`
  width: 100%;
  min-height: 1px;
  max-width: 1.5rem; /* 24px */
  border-radius: 0.25rem 0.25rem 0 0; /* 4px 4px 0 0 */
`;

const EdgeLabels = styled.div`
  ${({ theme }) => theme.text.caption01}
  display: flex;
  justify-content: space-between;
  padding-left: 2.25rem; /* 36px */
  color: ${({ theme }) => theme.neutral[500]};
`;
