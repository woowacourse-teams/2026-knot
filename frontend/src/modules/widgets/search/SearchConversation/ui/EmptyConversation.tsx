import { MEDIA_QUERY } from "@constants/breakpoint";
import styled from "@emotion/styled";
import { useSearchTutorialStore } from "@store/searchTutorialStore";
import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { useParams } from "react-router";

/**
 * 질문이 아직 없는 빈 대화에서 대화 칸 가운데에 띄우는 안내 문구.
 *
 * 첫 질문을 보내기 전까지만 보이며, 무엇을 할 수 있는 화면인지 알려 줘요.
 * 아래 「탐색 사용법 보기」로 탐색 튜토리얼을 언제든 다시 볼 수 있어요(SEARCH-R22). 튜토리얼이 떠 있는 동안에는 숨겨요.
 */
export default function EmptyConversation() {
  const { workspaceId } = useParams();
  const isTutorialActive = useSearchTutorialStore((state) => state.step !== null);
  const startTutorial = useSearchTutorialStore((state) => state.start);

  const handleReplay = () => {
    startTutorial({ source: "replay", variant: "default" });
    captureAnalyticsEvent({
      event: "search_tutorial_shown",
      properties: { source: "replay", is_again: false, workspace_id: Number(workspaceId) },
    });
  };

  return (
    <Container>
      <Title>무엇을 찾고 있나요?</Title>
      <Description>모아 둔 문서에서 찾아 근거와 함께 답해요.</Description>
      {!isTutorialActive && (
        <ReplayButton type="button" onClick={handleReplay}>
          탐색 사용법 보기
        </ReplayButton>
      )}
    </Container>
  );
}

const Container = styled.div`
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.5rem; /* 8px */
  height: 100%;
  text-align: center;
`;

const Title = styled.h2`
  color: ${({ theme }) => theme.neutral[800]};
  ${({ theme }) => theme.text.title01};
  /* 모바일은 제목을 40px에서 26px로 줄여요 */
  ${MEDIA_QUERY.mobile} {
    ${({ theme }) => theme.text.heading02};
  }
`;

const Description = styled.p`
  color: ${({ theme }) => theme.neutral[600]};
  ${({ theme }) => theme.text.body01};
`;

const ReplayButton = styled.button`
  color: ${({ theme }) => theme.neutral[600]};
  ${({ theme }) => theme.text.caption02};
  text-decoration: underline;
  text-underline-offset: 3px;
  cursor: pointer;

  &:hover {
    color: ${({ theme }) => theme.neutral[800]};
  }

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: 2px;
  }
`;
