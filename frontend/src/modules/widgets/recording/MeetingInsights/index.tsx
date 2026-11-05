import { MEDIA_QUERY } from "@constants/breakpoint";
import styled from "@emotion/styled";
import { useMemo, useState } from "react";

import { ChartTooltipProvider } from "./context/chartTooltipContext";
import { useMeetingInsightsDataset } from "./model/useMeetingInsightsDataset";
import MeetingPicker from "./ui/MeetingPicker";
import MeetingResultSection from "./ui/MeetingResultSection";
import SpeakerSection from "./ui/SpeakerSection";
import WorkspaceSection from "./ui/WorkspaceSection";
import { calculateMeetingMetrics } from "./utils/calculateMeetingMetrics";
import { calculateSpeakerMetrics } from "./utils/calculateSpeakerMetrics";
import { calculateWorkspaceTrends } from "./utils/calculateWorkspaceTrends";
import { pickDefaultMeeting } from "./utils/pickDefaultMeeting";
import { toKstDateParts } from "./utils/toKstDateParts";

/**
 * 회의 인포그래픽 시안.
 *
 * 지금 저장된 값으로 그릴 수 있는 항목을 한 페이지에 모아 팀이 보고 기획하는 화면이에요.
 * 제품 화면이 아니어서 로그인과 GNB 없이 주소로 바로 열어요.
 */
const MeetingInsights = () => {
  const { dataset, isPending } = useMeetingInsightsDataset();
  const [selectedRecordingId, setSelectedRecordingId] = useState<number | null>(
    null,
  );
  const meetings = useMemo(() => dataset?.meetings ?? [], [dataset]);
  const meeting =
    meetings.find(({ recordingId }) => recordingId === selectedRecordingId) ??
    pickDefaultMeeting(meetings);
  const meetingMetrics = useMemo(
    () => (meeting === undefined ? null : calculateMeetingMetrics(meeting)),
    [meeting],
  );
  const speakerMetrics = useMemo(
    () =>
      meeting === undefined
        ? null
        : calculateSpeakerMetrics(
            meeting.segments,
            meeting.recordingDurationSeconds,
          ),
    [meeting],
  );
  const trends = useMemo(() => calculateWorkspaceTrends(meetings), [meetings]);

  if (isPending) {
    return <Notice>회의 데이터를 불러오고 있어요</Notice>;
  }

  if (
    dataset === undefined ||
    meeting === undefined ||
    meetingMetrics === null ||
    speakerMetrics === null
  ) {
    return <Notice>보여 줄 회의가 없어요</Notice>;
  }

  const { workspaceName, sourceLabel, exportedAt, memberCount } = dataset;

  return (
    <ChartTooltipProvider>
      <Container>
        <Header>
          <Title>회의 인포그래픽</Title>
          <Lead>
            새 AI 처리 없이 지금 저장된 값으로 그릴 수 있는 항목을 모았어요.
            카드마다 계산 방법과 쓴 데이터가 있어요.
          </Lead>
          <SourceText>
            {`${workspaceName} 워크스페이스, ${sourceLabel}, 회의 ${meetings.length}개, ${toKstDateParts(exportedAt).dateKey} 기준`}
          </SourceText>
        </Header>

        <StickyBar>
          <MeetingPicker
            meetings={meetings}
            selectedRecordingId={meeting.recordingId}
            onSelect={setSelectedRecordingId}
          />
          <SectionNav aria-label="구역 이동">
            <a href="#meeting-result">회의 결과</a>
            <a href="#speakers">사람과 말하기</a>
            <a href="#workspace">여러 회의 비교</a>
          </SectionNav>
        </StickyBar>

        <Section id="meeting-result" aria-labelledby="meeting-result-title">
          <SectionTitle id="meeting-result-title">회의 결과</SectionTitle>
          <SectionLead>
            화자 번호와 관계없이 고른 회의에서 계산했어요.
          </SectionLead>
          <CardGrid>
            <MeetingResultSection
              meeting={meeting}
              metrics={meetingMetrics}
              memberCount={memberCount}
            />
          </CardGrid>
        </Section>

        <Section id="speakers" aria-labelledby="speakers-title">
          <SectionTitle id="speakers-title">사람과 말하기</SectionTitle>
          <SectionLead>
            화자 번호로 계산해서 번호가 틀리면 값도 틀려요. 먼저 품질 카드를
            확인해 주세요.
          </SectionLead>
          <CardGrid>
            <SpeakerSection
              metrics={speakerMetrics}
              durationSeconds={meeting.recordingDurationSeconds}
              memberCount={memberCount}
            />
          </CardGrid>
        </Section>

        <Section id="workspace" aria-labelledby="workspace-title">
          <SectionTitle id="workspace-title">여러 회의 비교</SectionTitle>
          <SectionLead>
            회의를 시간 순서로 놓고 워크스페이스 전체를 비교했어요.
          </SectionLead>
          <CardGrid>
            <WorkspaceSection
              trends={trends}
              selectedRecordingId={meeting.recordingId}
              memberCount={memberCount}
            />
          </CardGrid>
        </Section>
      </Container>
    </ChartTooltipProvider>
  );
};

export default MeetingInsights;

const Notice = styled.p`
  ${({ theme }) => theme.text.body01}
  padding: 3rem 1rem; /* 48px 16px */
  color: ${({ theme }) => theme.neutral[600]};
  text-align: center;
`;

const Container = styled.div`
  display: flex;
  flex-direction: column;
  gap: 2rem; /* 32px */
  width: 100%;
  max-width: 75rem; /* 1200px */
  margin: 0 auto;
  padding: 2.5rem 1.5rem 5rem; /* 40px 24px 80px */

  ${MEDIA_QUERY.mobile} {
    padding: 1.5rem 1rem 3rem; /* 24px 16px 48px */
  }
`;

const Header = styled.header`
  display: flex;
  flex-direction: column;
  gap: 0.5rem; /* 8px */
`;

const Title = styled.h1`
  ${({ theme }) => theme.text.title02}
  color: ${({ theme }) => theme.neutral[900]};
`;

const Lead = styled.p`
  ${({ theme }) => theme.text.body01}
  color: ${({ theme }) => theme.neutral[700]};
`;

const SourceText = styled.p`
  ${({ theme }) => theme.text.caption02}
  color: ${({ theme }) => theme.neutral[500]};
`;

const StickyBar = styled.div`
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem 1.5rem; /* 12px 24px */
  align-items: center;
  justify-content: space-between;
  padding: 0.75rem 0; /* 12px 0 */
  background: ${({ theme }) => theme.neutral[50]};
  border-bottom: 1px solid ${({ theme }) => theme.neutral[200]};
`;

const SectionNav = styled.nav`
  ${({ theme }) => theme.text.caption02}
  display: flex;
  flex: none;
  gap: 0.75rem; /* 12px */

  a {
    color: ${({ theme }) => theme.neutral[700]};
    text-decoration: none;
  }

  a:hover {
    color: ${({ theme }) => theme.neutral[900]};
    text-decoration: underline;
  }
`;

const Section = styled.section`
  display: flex;
  flex-direction: column;
  gap: 0.5rem; /* 8px */
  scroll-margin-top: 4.5rem; /* 72px */
`;

const SectionTitle = styled.h2`
  ${({ theme }) => theme.text.heading02}
  color: ${({ theme }) => theme.neutral[900]};
`;

const SectionLead = styled.p`
  ${({ theme }) => theme.text.body02}
  margin-bottom: 0.5rem; /* 8px */
  color: ${({ theme }) => theme.neutral[600]};
`;

const CardGrid = styled.div`
  display: grid;
  grid-template-columns: repeat(12, minmax(0, 1fr));
  gap: 1rem; /* 16px */
`;
