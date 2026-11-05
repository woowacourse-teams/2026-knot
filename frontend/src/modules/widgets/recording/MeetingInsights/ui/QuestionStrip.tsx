import styled from "@emotion/styled";

import { SINGLE_SERIES_COLOR } from "../constants/chartColors";
import { useChartTooltip } from "../context/chartTooltipContext";
import type { QuestionMark } from "../types/metrics";
import { formatClock, getTimeTicks } from "../utils/formatMetric";

interface QuestionStripProps {
  questions: QuestionMark[];
  durationSeconds: number;
}

/** 질문이 회의의 어디쯤 나왔는지 점으로 보여 줘요. 구간 시작 시각이라 위치는 대략이에요 */
const QuestionStrip = ({ questions, durationSeconds }: QuestionStripProps) => {
  const { bindTooltip } = useChartTooltip();
  const durationMillis = Math.max(durationSeconds * 1000, 1);
  const toPercent = (millis: number) => `${(millis / durationMillis) * 100}%`;

  return (
    <Container>
      <Line>
        {questions.map(({ startMillis, speakerNumber, text }, index) => (
          <Hit
            key={`${startMillis}-${index}`}
            style={{ left: toPercent(startMillis) }}
            {...bindTooltip(
              {
                value: formatClock(startMillis),
                label:
                  speakerNumber === null ? "미상" : `참여자 ${speakerNumber}`,
                detail: text,
              },
              { isFocusable: false },
            )}
          >
            <Dot />
          </Hit>
        ))}
      </Line>
      <Axis>
        {getTimeTicks(durationSeconds).map((tick) => (
          <AxisTick key={tick} style={{ left: toPercent(tick) }}>
            {formatClock(tick)}
          </AxisTick>
        ))}
      </Axis>
    </Container>
  );
};

export default QuestionStrip;

const Container = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.25rem; /* 4px */
  margin-top: 0.75rem; /* 12px */
`;

const Line = styled.div`
  position: relative;
  height: 1.5rem; /* 24px */
  border-bottom: 1px solid ${({ theme }) => theme.neutral[200]};
`;

const Hit = styled.div`
  position: absolute;
  bottom: -0.75rem; /* -12px */
  display: flex;
  align-items: center;
  justify-content: center;
  width: 1.5rem; /* 24px */
  height: 1.5rem; /* 24px */
  transform: translateX(-50%);
`;

const Dot = styled.span`
  width: 0.5rem; /* 8px */
  height: 0.5rem; /* 8px */
  background: ${SINGLE_SERIES_COLOR};
  border: 1px solid ${({ theme }) => theme.neutral[0]};
  border-radius: 50%;
`;

const Axis = styled.div`
  position: relative;
  height: 1.5rem; /* 24px */
  margin-top: 0.5rem; /* 8px */
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
