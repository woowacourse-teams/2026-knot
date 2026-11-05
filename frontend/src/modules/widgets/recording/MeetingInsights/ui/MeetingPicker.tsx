import styled from "@emotion/styled";

import type { MeetingInsightsMeeting } from "../types/dataset";
import { formatMinutes } from "../utils/formatMetric";
import { getMeetingLabel } from "../utils/getMeetingLabel";

interface MeetingPickerProps {
  meetings: MeetingInsightsMeeting[];
  selectedRecordingId: number;
  onSelect: (recordingId: number) => void;
}

/** 회의 단위 그림이 보여 줄 회의를 골라요. 최근 회의가 위에 와요 */
const MeetingPicker = ({
  meetings,
  selectedRecordingId,
  onSelect,
}: MeetingPickerProps) => (
  <Label>
    <span>회의 고르기</span>
    <Select
      value={selectedRecordingId}
      onChange={({ target }) => onSelect(Number(target.value))}
    >
      {[...meetings].reverse().map((meeting) => (
        <option key={meeting.recordingId} value={meeting.recordingId}>
          {`${getMeetingLabel(meeting)} (${formatMinutes(meeting.recordingDurationSeconds)})`}
        </option>
      ))}
    </Select>
  </Label>
);

export default MeetingPicker;

const Label = styled.label`
  ${({ theme }) => theme.text.caption02}
  display: flex;
  flex: 1 1 18rem; /* 좁은 화면에서는 구역 이동과 줄을 나눠요 */
  gap: 0.5rem; /* 8px */
  align-items: center;
  min-width: 0;
  color: ${({ theme }) => theme.neutral[600]};

  > span {
    flex: none;
  }
`;

const Select = styled.select`
  ${({ theme }) => theme.text.label01}
  flex: 1;
  min-width: 0;
  max-width: 36rem; /* 576px */
  padding: 0.5rem 0.75rem; /* 8px 12px */
  color: ${({ theme }) => theme.neutral[900]};
  background: ${({ theme }) => theme.neutral[0]};
  border: 1px solid ${({ theme }) => theme.neutral[300]};
  border-radius: 0.5rem; /* 8px */
`;
