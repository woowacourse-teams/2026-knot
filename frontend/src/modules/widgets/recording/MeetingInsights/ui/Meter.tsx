import styled from "@emotion/styled";

import { SINGLE_SERIES_COLOR } from "../constants/chartColors";

interface MeterProps {
  /** 0부터 1까지의 값 */
  ratio: number;
  label: string;
}

/** 한 비율을 전체 길이에 대어 보여 줘요 */
const Meter = ({ ratio, label }: MeterProps) => (
  <Track
    role="meter"
    aria-label={label}
    aria-valuemin={0}
    aria-valuemax={100}
    aria-valuenow={Math.round(ratio * 100)}
  >
    <Fill style={{ width: `${Math.min(ratio, 1) * 100}%` }} />
  </Track>
);

export default Meter;

const Track = styled.div`
  height: 0.75rem; /* 12px */
  margin-top: 0.75rem; /* 12px */
  overflow: hidden;
  background: ${({ theme }) => theme.neutral[100]};
  border-radius: 0.25rem; /* 4px */
`;

const Fill = styled.div`
  height: 100%;
  background: ${SINGLE_SERIES_COLOR};
  border-radius: 0 0.25rem 0.25rem 0; /* 0 4px 4px 0 */
`;
