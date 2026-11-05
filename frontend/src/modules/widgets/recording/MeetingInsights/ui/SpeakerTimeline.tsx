import styled from "@emotion/styled";

import { useChartTooltip } from "../context/chartTooltipContext";
import type { SpeakerGroup } from "../types/metrics";
import {
  formatClock,
  formatSeconds,
  getTimeTicks,
} from "../utils/formatMetric";

interface SpeakerTimelineProps {
  groups: SpeakerGroup[];
  colorByKey: Map<string, string>;
  durationSeconds: number;
}

/** 참여자별 가로 띠에 말한 구간을 시간 순서로 칠해요 */
const SpeakerTimeline = ({
  groups,
  colorByKey,
  durationSeconds,
}: SpeakerTimelineProps) => {
  const { bindTooltip } = useChartTooltip();
  const durationMillis = Math.max(durationSeconds * 1000, 1);
  const toPercent = (millis: number) => `${(millis / durationMillis) * 100}%`;

  return (
    <Timeline>
      {groups.map(({ key, label, intervals }) => (
        <TimelineRow key={key}>
          <RowLabel>{label}</RowLabel>
          <Track>
            {intervals.map(({ startMillis, endMillis }) => (
              <Interval
                key={startMillis}
                style={{
                  left: toPercent(startMillis),
                  width: toPercent(endMillis - startMillis),
                  background: colorByKey.get(key),
                }}
                {...bindTooltip(
                  {
                    value: formatSeconds((endMillis - startMillis) / 1000),
                    label,
                    detail: `${formatClock(startMillis)} ~ ${formatClock(endMillis)}`,
                    color: colorByKey.get(key),
                  },
                  { isFocusable: false },
                )}
              />
            ))}
          </Track>
        </TimelineRow>
      ))}
      <TimelineRow>
        <span />
        <Axis>
          {getTimeTicks(durationSeconds).map((tick) => (
            <AxisTick key={tick} style={{ left: toPercent(tick) }}>
              {formatClock(tick)}
            </AxisTick>
          ))}
        </Axis>
      </TimelineRow>
    </Timeline>
  );
};

export default SpeakerTimeline;

const Timeline = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.375rem; /* 6px */
`;

const TimelineRow = styled.div`
  display: grid;
  grid-template-columns: 4.5rem 1fr;
  gap: 0.5rem; /* 8px */
  align-items: center;
`;

const RowLabel = styled.span`
  ${({ theme }) => theme.text.caption01}
  color: ${({ theme }) => theme.neutral[700]};
  white-space: nowrap;
`;

const Track = styled.div`
  position: relative;
  height: 1.125rem; /* 18px */
  background: ${({ theme }) => theme.neutral[50]};
  border-radius: 2px;
`;

const Interval = styled.div`
  position: absolute;
  top: 0;
  bottom: 0;
  min-width: 2px;
  border-radius: 1px;

  &:hover {
    filter: brightness(1.15);
  }
`;

const Axis = styled.div`
  position: relative;
  height: 1.125rem; /* 18px */
`;

const AxisTick = styled.span`
  ${({ theme }) => theme.text.caption01}
  position: absolute;
  color: ${({ theme }) => theme.neutral[500]};
  transform: translateX(-50%);

  &:first-of-type {
    transform: none;
  }
`;
