import styled from "@emotion/styled";

import { SEQUENTIAL_COLORS } from "../constants/chartColors";
import { useChartTooltip } from "../context/chartTooltipContext";
import type { WorkspaceTrends } from "../types/metrics";
import { formatMinutes } from "../utils/formatMetric";
import { toWeekday } from "../utils/toKstDateParts";

interface MeetingCalendarProps {
  calendar: WorkspaceTrends["calendar"];
}

const WEEKDAY_LABELS = ["월", "화", "수", "목", "금", "토", "일"];

/** 칸 색 단계. 회의 수가 많을수록 진해요 */
const getCellColor = (count: number) =>
  SEQUENTIAL_COLORS[Math.min(count, SEQUENTIAL_COLORS.length) - 1];

const listMonths = (calendar: WorkspaceTrends["calendar"]) => [
  ...new Set(calendar.map(({ dateKey }) => dateKey.slice(0, 7))),
];

const listDays = (month: string) => {
  const [year, monthNumber] = month.split("-").map(Number);
  const lastDay = new Date(Date.UTC(year, monthNumber, 0)).getUTCDate();

  return Array.from(
    { length: lastDay },
    (_, index) => `${month}-${String(index + 1).padStart(2, "0")}`,
  );
};

/** 회의가 있었던 날을 달력 칸의 색으로 보여 줘요 */
const MeetingCalendar = ({ calendar }: MeetingCalendarProps) => {
  const { bindTooltip } = useChartTooltip();
  const dayByKey = new Map(calendar.map((day) => [day.dateKey, day]));

  return (
    <Months>
      {listMonths(calendar).map((month) => {
        const days = listDays(month);

        return (
          <Month key={month}>
            <MonthTitle>{`${Number(month.slice(5))}월`}</MonthTitle>
            <Grid>
              {WEEKDAY_LABELS.map((label) => (
                <WeekdayLabel key={label}>{label}</WeekdayLabel>
              ))}
              {Array.from({ length: toWeekday(days[0]) }, (_, index) => (
                <span key={index} />
              ))}
              {days.map((dateKey) => {
                const day = dayByKey.get(dateKey);
                const isDark = day !== undefined && day.count >= 3;

                return (
                  <Cell
                    key={dateKey}
                    style={{
                      background:
                        day === undefined ? undefined : getCellColor(day.count),
                      color: isDark ? "#FFFFFF" : undefined,
                    }}
                    {...(day !== undefined &&
                      bindTooltip({
                        value: `${day.count}회`,
                        label: dateKey,
                        detail: `회의 시간 ${formatMinutes(day.minutes * 60)}`,
                      }))}
                  >
                    {Number(dateKey.slice(8))}
                  </Cell>
                );
              })}
            </Grid>
          </Month>
        );
      })}
    </Months>
  );
};

export default MeetingCalendar;

const Months = styled.div`
  display: flex;
  flex-wrap: wrap;
  gap: 1.5rem; /* 24px */
`;

const Month = styled.div`
  flex: 1;
  min-width: 14rem; /* 224px */
`;

const MonthTitle = styled.h4`
  ${({ theme }) => theme.text.label01}
  margin-bottom: 0.5rem; /* 8px */
  color: ${({ theme }) => theme.neutral[800]};
`;

const Grid = styled.div`
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 2px;
`;

const WeekdayLabel = styled.span`
  ${({ theme }) => theme.text.caption01}
  color: ${({ theme }) => theme.neutral[500]};
  text-align: center;
`;

const Cell = styled.span`
  ${({ theme }) => theme.text.caption01}
  display: flex;
  align-items: center;
  justify-content: center;
  aspect-ratio: 1;
  color: ${({ theme }) => theme.neutral[700]};
  background: ${({ theme }) => theme.neutral[50]};
  border-radius: 0.25rem; /* 4px */
`;
