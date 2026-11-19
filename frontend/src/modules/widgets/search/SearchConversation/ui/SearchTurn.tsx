import styled from "@emotion/styled";
import useOpenedEvidenceMessage from "@hooks/domain/search/useOpenedEvidenceMessage";
import ChatBubble from "@primitives/ui/ChatBubble";
import EvidenceButton from "@primitives/ui/EvidenceButton";
import NotionEvidenceButton from "@primitives/ui/NotionEvidenceButton";

import type { SearchTurn as SearchTurnData } from "../types/searchTurn";
import AnswerProgress from "./AnswerProgress";

interface SearchTurnProps {
  turn: SearchTurnData;
}

/**
 * 질문 하나와 그에 대한 답변 하나를 묶어 보여주는 대화 단위.
 *
 * 질문은 오른쪽 말풍선, 답변은 왼쪽 평문 문단으로 그려요. 저장된 답변과 생성 중인 답변을 같은 모양으로 그려요.
 * 접수된 뒤 첫 답변 텍스트가 오기 전에는 답변 자리에 「찾는 중」, 「작성 중」 진행 표시를 두고(SEARCH-R38),
 * 텍스트가 흐르기 시작하면 텍스트가 그 자리를 이어받아요.
 * 답변에 근거가 있으면 그 아래 「기록 N개에서 찾았어요」 버튼으로 찾은 기록을 열어요.
 * 답에 쓴 노션 페이지는 그 옆에 페이지마다 노션 버튼으로 두고, 넘치면 다음 줄로 이어요(SEARCH-R37).
 * 이전 실패 답변은 검증되지 않은 글자 대신 대화를 이어갈 수 있는 안내 문단으로 보여줘요(SEARCH-R5).
 */
export default function SearchTurn({ turn }: SearchTurnProps) {
  const { question, answer } = turn;
  const { openedMessageId, openEvidenceMessage } = useOpenedEvidenceMessage();

  // TODO: BE가 답변 content를 마크다운으로 줘요
  // 마크다운 렌더러로 바꾸면 줄 단위 split과 Paragraph는 빠져요.
  const paragraphs =
    answer?.content.split("\n").filter((line) => line.trim() !== "") ?? [];
  const evidenceCount = answer?.evidences.length ?? 0;
  const notionEvidences = answer?.notionEvidences ?? [];
  const isSearching = answer?.status === "STREAMING" && paragraphs.length === 0;

  return (
    <Container>
      <QuestionRow>
        <ChatBubble>{question}</ChatBubble>
      </QuestionRow>

      {answer?.status === "FAILED" && (
        <Answer>
          <Paragraph>지금은 답변을 정리하기 어려워요. 질문을 조금 더 구체적으로 이어서 해주시면 도와드릴게요.</Paragraph>
        </Answer>
      )}

      {answer && answer.status !== "FAILED" && (
        <Answer>
          {isSearching && <AnswerProgress stage={answer.stage} />}

          {paragraphs.map((paragraph, index) => (
            <Paragraph key={index}>{paragraph}</Paragraph>
          ))}

          {(evidenceCount > 0 || notionEvidences.length > 0) && (
            <EvidenceRow>
              {evidenceCount > 0 && (
                <EvidenceButton
                  count={evidenceCount}
                  isOpen={openedMessageId === answer.id}
                  onClick={() => openEvidenceMessage(answer.id)}
                />
              )}
              {notionEvidences.map(({ title, url }, index) => (
                // 같은 페이지가 두 번 와도 겹치지 않게 순서를 함께 써요
                <NotionEvidenceButton key={`${index}-${url}`} title={title} href={url} />
              ))}
            </EvidenceRow>
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

/** 근거 버튼들이 답변 폭만큼 늘어나지 않고 문구 길이에 맞게 놓이며, 넘치면 다음 줄 왼쪽부터 이어요 */
const EvidenceRow = styled.div`
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem; /* 8px */
`;
