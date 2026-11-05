import styled from "@emotion/styled";

import { useChartTooltip } from "../context/chartTooltipContext";
import type { SpeakerGroup, SpeakerMetrics } from "../types/metrics";
import { formatPercent } from "../utils/formatMetric";
import { Swatch } from "./DonutChart";

interface PhaseBarsProps {
  phases: SpeakerMetrics["phases"];
  groups: SpeakerGroup[];
  colorByKey: Map<string, string>;
}

/** 회의를 셋으로 나눠 구간마다 누가 말했는지 쌓아 보여 줘요 */
const PhaseBars = ({ phases, groups, colorByKey }: PhaseBarsProps) => {
  const { bindTooltip } = useChartTooltip();
  const labelByKey = new Map(groups.map(({ key, label }) => [key, label]));

  return (
    <Container>
      {phases.map(({ label, shares }) => (
        <PhaseRow key={label}>
          <PhaseLabel>{label}</PhaseLabel>
          <Stack>
            {shares.map(({ key, ratio }) => (
              <Piece
                key={key}
                style={{
                  flexGrow: ratio,
                  background: colorByKey.get(key),
                }}
                {...bindTooltip({
                  value: formatPercent(ratio),
                  label: labelByKey.get(key) ?? key,
                  detail: `회의 ${label} 구간`,
                  color: colorByKey.get(key),
                })}
              />
            ))}
          </Stack>
        </PhaseRow>
      ))}
      <Legend>
        {groups.map(({ key, label }) => (
          <LegendItem key={key}>
            <Swatch style={{ background: colorByKey.get(key) }} />
            {label}
          </LegendItem>
        ))}
      </Legend>
    </Container>
  );
};

export default PhaseBars;

const Container = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.5rem; /* 8px */
`;

const PhaseRow = styled.div`
  display: grid;
  grid-template-columns: 2.25rem 1fr;
  gap: 0.5rem; /* 8px */
  align-items: center;
`;

const PhaseLabel = styled.span`
  ${({ theme }) => theme.text.caption02}
  color: ${({ theme }) => theme.neutral[700]};
`;

const Stack = styled.div`
  display: flex;
  gap: 2px;
  height: 1.25rem; /* 20px */
`;

const Piece = styled.div`
  flex-basis: 0;
  min-width: 2px;

  &:first-of-type {
    border-radius: 0.25rem 0 0 0.25rem; /* 4px 0 0 4px */
  }

  &:last-of-type {
    border-radius: 0 0.25rem 0.25rem 0; /* 0 4px 4px 0 */
  }
`;

const Legend = styled.div`
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem 0.75rem; /* 4px 12px */
  margin-top: 0.25rem; /* 4px */
`;

const LegendItem = styled.span`
  ${({ theme }) => theme.text.caption01}
  display: inline-flex;
  gap: 0.25rem; /* 4px */
  align-items: center;
  color: ${({ theme }) => theme.neutral[700]};
`;
