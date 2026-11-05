import styled from "@emotion/styled";
import MeetingInsights from "@widgets/recording/MeetingInsights";

/**
 * 회의 인포그래픽 시안 화면 (`/meeting-insights`)
 *
 * 팀이 함께 보고 기획하려고 만든 시안이라 로그인과 GNB가 없어요.
 */
export default function MeetingInsightsPage() {
  return (
    <Root>
      <MeetingInsights />
    </Root>
  );
}

const Root = styled.main`
  min-height: 100vh;
  background: ${({ theme }) => theme.neutral[50]};
`;
