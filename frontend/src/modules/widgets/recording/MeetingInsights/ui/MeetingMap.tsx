import styled from "@emotion/styled";

import type { SummaryTopic } from "../utils/parseMeetingSummary";

interface MeetingMapProps {
  rootLabel: string;
  topics: SummaryTopic[];
}

const POINT_LIMIT = 4;

/** 회의 하나를 가운데 줄기에서 주제별 가지로 펼쳐 보여 줘요 */
const MeetingMap = ({ rootLabel, topics }: MeetingMapProps) => (
  <Map>
    <Root>{rootLabel}</Root>
    <Branches>
      {topics.map(({ title, points, closing }) => (
        <Branch key={title}>
          <BranchTitle>{title}</BranchTitle>
          {points.length > 0 && (
            <Points>
              {points.slice(0, POINT_LIMIT).map((point) => (
                <li key={point}>{point}</li>
              ))}
              {points.length > POINT_LIMIT && (
                <MorePoints>{`외 ${points.length - POINT_LIMIT}개`}</MorePoints>
              )}
            </Points>
          )}
          {closing !== null && <Closing>{closing}</Closing>}
        </Branch>
      ))}
    </Branches>
  </Map>
);

export default MeetingMap;

const Map = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.75rem; /* 12px */
`;

const Root = styled.p`
  ${({ theme }) => theme.text.label01}
  padding: 0.75rem 1rem; /* 12px 16px */
  color: ${({ theme }) => theme.neutral[0]};
  background: ${({ theme }) => theme.neutral[800]};
  border-radius: 0.75rem; /* 12px */
`;

const Branches = styled.div`
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(16rem, 1fr));
  gap: 0.75rem; /* 12px */
  padding-left: 0.75rem; /* 12px */
  border-left: 2px solid ${({ theme }) => theme.neutral[200]};
`;

const Branch = styled.section`
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 0.375rem; /* 6px */
  padding: 0.75rem; /* 12px */
  background: ${({ theme }) => theme.neutral[50]};
  border-radius: 0.5rem; /* 8px */
`;

const BranchTitle = styled.h4`
  ${({ theme }) => theme.text.label01}
  color: ${({ theme }) => theme.neutral[900]};
`;

const Points = styled.ul`
  ${({ theme }) => theme.text.caption02}
  display: flex;
  flex-direction: column;
  gap: 0.25rem; /* 4px */
  padding-left: 1rem; /* 16px */
  color: ${({ theme }) => theme.neutral[700]};
  list-style: disc;
`;

const MorePoints = styled.li`
  color: ${({ theme }) => theme.neutral[500]};
  list-style: none;
`;

const Closing = styled.p`
  ${({ theme }) => theme.text.caption02}
  padding-top: 0.375rem; /* 6px */
  color: ${({ theme }) => theme.sub.accent[700]};
  border-top: 1px solid ${({ theme }) => theme.neutral[200]};
`;
