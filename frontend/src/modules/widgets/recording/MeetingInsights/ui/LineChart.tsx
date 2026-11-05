import { useTheme } from "@emotion/react";
import styled from "@emotion/styled";
import { useState, type PointerEvent } from "react";

import { SINGLE_SERIES_COLOR } from "../constants/chartColors";
import { useChartTooltip } from "../context/chartTooltipContext";
import { useChartWidth } from "../model/useChartWidth";
import { getNiceMax } from "../utils/formatMetric";

export interface LinePoint {
  key: string;
  value: number;
  valueLabel: string;
  label: string;
}

interface LineChartProps {
  points: LinePoint[];
  /** 값 아래를 옅게 칠해요 */
  isArea?: boolean;
  formatTick?: (value: number) => string;
  startLabel?: string;
  endLabel?: string;
}

const HEIGHT = 180;
const MARGIN = { top: 8, right: 8, bottom: 22, left: 36 };

/** 변화를 보여 주는 선. 마우스가 있는 곳의 가장 가까운 점을 찾아 값을 보여 줘요 */
const LineChart = ({
  points,
  isArea = false,
  formatTick = String,
  startLabel,
  endLabel,
}: LineChartProps) => {
  const theme = useTheme();
  const { containerRef, width } = useChartWidth();
  const { showTooltip, hideTooltip } = useChartTooltip();
  const [activeIndex, setActiveIndex] = useState<number | null>(null);

  const plotWidth = Math.max(width - MARGIN.left - MARGIN.right, 1);
  const plotHeight = HEIGHT - MARGIN.top - MARGIN.bottom;
  const max = getNiceMax(Math.max(...points.map(({ value }) => value), 0));
  const xOf = (index: number) =>
    MARGIN.left +
    (points.length <= 1 ? 0 : (index / (points.length - 1)) * plotWidth);
  const yOf = (value: number) =>
    MARGIN.top + plotHeight - (value / max) * plotHeight;
  const linePath = points
    .map(
      ({ value }, index) =>
        `${index === 0 ? "M" : "L"}${xOf(index)},${yOf(value)}`,
    )
    .join(" ");
  const areaPath = `${linePath} L${xOf(points.length - 1)},${yOf(0)} L${xOf(0)},${yOf(0)} Z`;
  const active = activeIndex === null ? undefined : points[activeIndex];

  const handlePointerMove = (event: PointerEvent<SVGSVGElement>) => {
    const { left } = event.currentTarget.getBoundingClientRect();
    const ratio = (event.clientX - left - MARGIN.left) / plotWidth;
    const index = Math.min(
      Math.max(Math.round(ratio * (points.length - 1)), 0),
      points.length - 1,
    );
    const { valueLabel, label } = points[index];

    setActiveIndex(index);
    showTooltip(
      { value: valueLabel, label, color: SINGLE_SERIES_COLOR },
      event.clientX,
      event.clientY,
    );
  };

  const handlePointerLeave = () => {
    setActiveIndex(null);
    hideTooltip();
  };

  return (
    <Wrapper ref={containerRef}>
      <svg
        width={width}
        height={HEIGHT}
        role="img"
        aria-label="시간에 따른 값의 변화"
        onPointerMove={handlePointerMove}
        onPointerLeave={handlePointerLeave}
      >
        {[0, 0.5, 1].map((tick) => (
          <g key={tick}>
            <line
              x1={MARGIN.left}
              x2={width - MARGIN.right}
              y1={yOf(max * tick)}
              y2={yOf(max * tick)}
              stroke={theme.neutral[100]}
            />
            <AxisText
              x={MARGIN.left - 6}
              y={yOf(max * tick) + 4}
              textAnchor="end"
            >
              {formatTick(max * tick)}
            </AxisText>
          </g>
        ))}
        {isArea && (
          <path d={areaPath} fill={SINGLE_SERIES_COLOR} fillOpacity={0.1} />
        )}
        <path
          d={linePath}
          fill="none"
          stroke={SINGLE_SERIES_COLOR}
          strokeWidth={2}
          strokeLinejoin="round"
          strokeLinecap="round"
        />
        {activeIndex !== null && active !== undefined && (
          <>
            <line
              x1={xOf(activeIndex)}
              x2={xOf(activeIndex)}
              y1={MARGIN.top}
              y2={MARGIN.top + plotHeight}
              stroke={theme.neutral[300]}
            />
            <circle
              cx={xOf(activeIndex)}
              cy={yOf(active.value)}
              r={4}
              fill={SINGLE_SERIES_COLOR}
              stroke={theme.neutral[0]}
              strokeWidth={2}
            />
          </>
        )}
        <AxisText x={MARGIN.left} y={HEIGHT - 6}>
          {startLabel}
        </AxisText>
        <AxisText x={width - MARGIN.right} y={HEIGHT - 6} textAnchor="end">
          {endLabel}
        </AxisText>
      </svg>
    </Wrapper>
  );
};

export default LineChart;

const Wrapper = styled.div`
  width: 100%;
  min-width: 0;
`;

const AxisText = styled.text`
  ${({ theme }) => theme.text.caption01}
  fill: ${({ theme }) => theme.neutral[500]};
`;
