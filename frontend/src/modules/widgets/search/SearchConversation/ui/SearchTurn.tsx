import styled from "@emotion/styled";
import useOpenedEvidenceMessage from "@hooks/domain/search/useOpenedEvidenceMessage";
import ChatBubble from "@primitives/ui/ChatBubble";
import EvidenceButton from "@primitives/ui/EvidenceButton";
import InlineError from "@primitives/ui/InlineError";

import type { SearchTurn as SearchTurnData } from "../types/searchTurn";

interface SearchTurnProps {
  turn: SearchTurnData;

  /** 실패한 답변의 `다시 시도`를 눌렀을 때 실행할 동작 */
  onRetryAnswer: () => void;
}

/**
 * 질문 하나와 그에 대한 답변 하나를 묶어 보여주는 대화 단위.
 *
 * 질문은 오른쪽 말풍선, 답변은 왼쪽 평문 문단으로 그려요. 저장된 답변과 생성 중인 답변을 같은 모양으로 그려요.
 * 접수된 뒤 첫 답변 텍스트가 오기 전에는 답변 자리에 진행 문구를 두고, 텍스트가 흐르기 시작하면 텍스트가 그 자리를 이어받아요.
 * 답변에 근거가 있으면 그 아래 「기록 N개에서 찾았어요」 버튼으로 찾은 기록을 열어요.
 * 답변이 실패했으면 받은 글자는 그리지 않고 그 자리에 실패 안내와 `다시 시도`만 둬요(SEARCH-R5).
 */
export default function SearchTurn({ turn, onRetryAnswer }: SearchTurnProps) {
  const { question, answer } = turn;
  const { openedMessageId, openEvidenceMessage } = useOpenedEvidenceMessage();

  // TODO: BE가 답변 content를 마크다운으로 줘요
  // 마크다운 렌더러로 바꾸면 줄 단위 split과 Paragraph는 빠져요.
  const paragraphs =
    answer?.content.split("\n").filter((line) => line.trim() !== "") ?? [];
  const evidenceCount = answer?.evidences.length ?? 0;
  const isSearching = answer?.status === "STREAMING" && paragraphs.length === 0;

  return (
    <Container>
      <QuestionRow>
        <ChatBubble>{question}</ChatBubble>
      </QuestionRow>

      {answer?.status === "FAILED" && (
        <InlineError
          message="답변을 만들지 못했어요. 잠시 후 다시 시도해 주세요."
          onRetry={onRetryAnswer}
        />
      )}

      {answer && answer.status !== "FAILED" && (
        <Answer>
          {isSearching && <SearchingText>문서에서 찾고 있어요…</SearchingText>}

          {paragraphs.map((paragraph, index) => (
            <Paragraph key={index}>{paragraph}</Paragraph>
          ))}

          {evidenceCount > 0 && (
            <EvidenceButtonWrapper>
              <EvidenceButton
                count={evidenceCount}
                isOpen={openedMessageId === answer.id}
                onClick={() => openEvidenceMessage(answer.id)}
              />
            </EvidenceButtonWrapper>
          )}
        </Answer>
      )}
    </Container>
  );
}

const Container = styled.article`
  display: flex;
  flex-direction: column;
  gap: 1rem; /* 16px */
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
  overflow-wrap: anywhere;
  ${({ theme }) => theme.text.body01};
  color: ${({ theme }) => theme.neutral[900]};
`;

const SearchingText = styled.p`
  ${({ theme }) => theme.text.body01};
  color: ${({ theme }) => theme.neutral[600]};
`;

/** 버튼이 답변 폭만큼 늘어나지 않고 문구 길이에 맞도록 감싸요 */
const EvidenceButtonWrapper = styled.div`
  display: flex;
`;
