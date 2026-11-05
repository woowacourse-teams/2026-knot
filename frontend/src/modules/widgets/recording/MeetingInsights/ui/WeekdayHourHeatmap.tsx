import styled from "@emotion/styled";

import { SEQUENTIAL_COLORS } from "../constants/chartColors";
import { useChartTooltip } from "../context/chartTooltipContext";
import type { WorkspaceTrends } from "../types/metrics";

interface WeekdayHourHeatmapProps {
  cells: WorkspaceTrends["weekdayHours"];
}

const WEEKDAY_LABELS = ["월", "화", "수", "목", "금", "토", "일"];

/** 요일과 시작 시간대별 회의 수. 칸이 진할수록 회의가 많아요 */
const WeekdayHourHeatmap = ({ cells }: WeekdayHourHeatmapProps) => {
  const { bindTooltip } = useChartTooltip();
  const hours = cells.map(({ hour }) => hour);
  const firstHour = Math.min(...hours);
  const lastHour = Math.max(...hours);
  const hourList = Array.from(
    { length: lastHour - firstHour + 1 },
    (_, index) => firstHour + index,
  );
  const countOf = (weekday: number, hour: number) =>
    cells.find((cell) => cell.weekday === weekday && cell.hour === hour)
      ?.count ?? 0;

  return (
    <Scroll>
      <Grid
        style={{
          gridTemplateColumns: `1.5rem repeat(${hourList.length}, minmax(1.25rem, 1fr))`,
        }}
      >
        <span />
        {hourList.map((hour) => (
          <HourLabel key={hour}>{hour}</HourLabel>
        ))}
        {WEEKDAY_LABELS.map((weekdayLabel, weekday) => (
          <Row key={weekdayLabel}>
            <WeekdayLabel>{weekdayLabel}</WeekdayLabel>
            {hourList.map((hour) => {
              const count = countOf(weekday, hour);

              return (
                <Cell
                  key={hour}
                  style={{
                    background:
                      count === 0
                        ? undefined
                        : SEQUENTIAL_COLORS[
                            Math.min(count, SEQUENTIAL_COLORS.length) - 1
                          ],
                  }}
                  {...(count > 0 &&
                    bindTooltip({
                      value: `${count}회`,
                      label: `${weekdayLabel}요일 ${hour}시`,
                    }))}
                />
              );
            })}
          </Row>
        ))}
      </Grid>
    </Scroll>
  );
};

export default WeekdayHourHeatmap;

const Scroll = styled.div`
  overflow-x: auto;
`;

const Grid = styled.div`
  display: grid;
  gap: 2px;
`;

const Row = styled.div`
  display: contents;
`;

const HourLabel = styled.span`
  ${({ theme }) => theme.text.caption01}
  color: ${({ theme }) => theme.neutral[500]};
  text-align: center;
`;

const WeekdayLabel = styled.span`
  ${({ theme }) => theme.text.caption01}
  color: ${({ theme }) => theme.neutral[600]};
`;

const Cell = styled.span`
  height: 1.5rem; /* 24px */
  background: ${({ theme }) => theme.neutral[50]};
  border-radius: 2px;
`;
