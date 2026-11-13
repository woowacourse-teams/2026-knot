import { SEARCH_TUTORIAL_MOCK_CONVERSATION } from "@constants/searchTutorial";
import styled from "@emotion/styled";
import ChatBubble from "@primitives/ui/ChatBubble";
import SearchEvidenceCard from "@primitives/ui/SearchEvidenceCard";
import { useSearchTutorialStore } from "@store/searchTutorialStore";
import { useEffect, useState } from "react";

/** 예시 답변을 한 글자 보여 주는 간격(ms) */
const ANSWER_INTERVAL_MS = 18;

const prefersReducedMotion = () =>
  typeof window.matchMedia === "function" &&
  window.matchMedia("(prefers-reduced-motion: reduce)").matches;

/**
 * 탐색 튜토리얼 3단계의 예시 대화. 서버에 보내지 않은 목 데이터로 질문과 답변 한 턴을 그려요.
 *
 * 답변은 실제 답변처럼 한 글자씩 써 나가고, 다 쓰면 근거 문서 카드를 보여 준 뒤 튜토리얼에 알려 안내 카드를 띄우게 해요.
 * 근거 문서는 예시라 누를 수 없어요. 👍👎와 찾은 기록 패널도 두지 않아요.
 * 움직임 줄이기 설정이면 답변을 한 번에 보여 줘요.
 */
export default function TutorialTurn() {
  const { question, answer, evidences } = SEARCH_TUTORIAL_MOCK_CONVERSATION;
  const markAnswerShown = useSearchTutorialStore((state) => state.markAnswerShown);
  const [length, setLength] = useState(() => (prefersReducedMotion() ? answer.length : 0));

  const isDone = length >= answer.length;

  useEffect(() => {
    if (isDone) {
      markAnswerShown();
      return;
    }

    const timer = window.setTimeout(() => setLength((prev) => prev + 1), ANSWER_INTERVAL_MS);

    return () => window.clearTimeout(timer);
  }, [isDone, length, markAnswerShown]);

  return (
    <Container data-search-tutorial="conversation" aria-label="예시 대화">
      <QuestionRow>
        <ChatBubble>{question}</ChatBubble>
      </QuestionRow>

      <Answer>
        <Paragraph aria-live="polite">{answer.slice(0, length)}</Paragraph>

        {isDone && (
          <Evidences>
            {evidences.map(({ title, createdAt }) => (
              <SearchEvidenceCard key={title} title={title} sourceType="문서" createdAt={createdAt} />
            ))}
          </Evidences>
        )}
      </Answer>
    </Container>
  );
}

const Container = styled.article`
  display: flex;
  flex-direction: column;
  gap: 1rem; /* 16px */
  padding-bottom: 1rem; /* 16px, 밝힌 영역이 카드 그림자까지 담도록 */
`;

const QuestionRow = styled.div`
  display: flex;
  justify-content: flex-end;
`;

const Answer = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.75rem; /* 12px */
`;

const Paragraph = styled.p`
  ${({ theme }) => theme.text.body01};
  color: ${({ theme }) => theme.neutral[900]};
  white-space: pre-wrap;
`;

const Evidences = styled.div`
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(13rem, 1fr));
  gap: 0.75rem; /* 12px */

  /* 근거 카드는 흰 패널 위에 놓이도록 만들어져 있어, 화면 바탕 위에서는 흰 카드로 띄워요(Figma 탐색 답변 형식) */
  & > * {
    border: 1px solid ${({ theme }) => theme.neutral[200]};
    background-color: ${({ theme }) => theme.neutral[0]};
  }
`;
