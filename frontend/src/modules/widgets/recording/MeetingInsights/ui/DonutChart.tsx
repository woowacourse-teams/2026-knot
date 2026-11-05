import { useTheme } from "@emotion/react";
import styled from "@emotion/styled";

import { useChartTooltip } from "../context/chartTooltipContext";

export interface DonutItem {
  key: string;
  label: string;
  ratio: number;
  valueLabel: string;
  color: string;
}

interface DonutChartProps {
  items: DonutItem[];
  centerValue: string;
  centerLabel: string;
}

const SIZE = 200;
const STROKE = 26;
const RADIUS = (SIZE - STROKE) / 2;
const CIRCUMFERENCE = 2 * Math.PI * RADIUS;
/** 조각 사이의 흰 틈 */
const GAP = 2;

/** 전체에서 각자가 차지하는 몫. 값은 옆 범례에도 글자로 써요 */
const DonutChart = ({ items, centerValue, centerLabel }: DonutChartProps) => {
  const theme = useTheme();
  const { bindTooltip } = useChartTooltip();
  let offset = 0;

  return (
    <Layout>
      <svg
        width={SIZE}
        height={SIZE}
        viewBox={`0 0 ${SIZE} ${SIZE}`}
        role="img"
        aria-label={`${centerLabel} ${centerValue}`}
      >
        <g transform={`rotate(-90 ${SIZE / 2} ${SIZE / 2})`}>
          {items.map(({ key, label, ratio, valueLabel, color }) => {
            const length = ratio * CIRCUMFERENCE;
            const visible = Math.max(length - GAP, 1);
            const dashOffset = -offset;

            offset += length;

            return (
              <circle
                key={key}
                cx={SIZE / 2}
                cy={SIZE / 2}
                r={RADIUS}
                fill="none"
                stroke={color}
                strokeWidth={STROKE}
                strokeDasharray={`${visible} ${CIRCUMFERENCE - visible}`}
                strokeDashoffset={dashOffset}
                {...bindTooltip({ value: valueLabel, label, color })}
              />
            );
          })}
        </g>
        <CenterValue x={SIZE / 2} y={SIZE / 2 + 2} textAnchor="middle">
          {centerValue}
        </CenterValue>
        <CenterLabel
          x={SIZE / 2}
          y={SIZE / 2 + 22}
          textAnchor="middle"
          fill={theme.neutral[600]}
        >
          {centerLabel}
        </CenterLabel>
      </svg>
      <Legend>
        {items.map(({ key, label, valueLabel, color }) => (
          <LegendRow key={key}>
            <Swatch style={{ background: color }} />
            <LegendLabel>{label}</LegendLabel>
            <LegendValue>{valueLabel}</LegendValue>
          </LegendRow>
        ))}
      </Legend>
    </Layout>
  );
};

export default DonutChart;

const Layout = styled.div`
  display: flex;
  flex-wrap: wrap;
  gap: 1.25rem; /* 20px */
  align-items: center;
  justify-content: center;
`;

const CenterValue = styled.text`
  ${({ theme }) => theme.text.heading03}
  fill: ${({ theme }) => theme.neutral[900]};
`;

const CenterLabel = styled.text`
  ${({ theme }) => theme.text.caption01}
`;

const Legend = styled.ul`
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 0.25rem; /* 4px */
  min-width: 10rem; /* 160px */
`;

const LegendRow = styled.li`
  ${({ theme }) => theme.text.caption02}
  display: flex;
  gap: 0.5rem; /* 8px */
  align-items: center;
  color: ${({ theme }) => theme.neutral[700]};
`;

export const Swatch = styled.span`
  flex: none;
  width: 0.625rem; /* 10px */
  height: 0.625rem; /* 10px */
  border-radius: 2px;
`;

const LegendLabel = styled.span`
  flex: 1;
`;

const LegendValue = styled.span`
  color: ${({ theme }) => theme.neutral[900]};
`;
