import {
  SEARCH_TUTORIAL_QUESTION_KINDS,
  type SearchTutorialQuestionKind,
} from "@constants/searchTutorial";
import styled from "@emotion/styled";
import type { CSSProperties } from "react";

import SpeechBubbleIcon from "@/assets/icons/speechBubble.svg";

import { SEARCH_TUTORIAL_TARGET } from "../constants/searchTutorial";

interface QuestionKindCardsProps {
  style?: CSSProperties;
  onChoose: (kind: SearchTutorialQuestionKind, question: string) => void;
}

/**
 * 튜토리얼 마지막 단계에서 독 입력창 위에 띄우는 질문 종류 카드 3개. 누르면 그 질문을 실제로 보내요.
 */
export default function QuestionKindCards({ style, onChoose }: QuestionKindCardsProps) {
  return (
    <List data-search-tutorial={SEARCH_TUTORIAL_TARGET.questionKinds} aria-label="이렇게 물어볼 수 있어요" style={style}>
      {SEARCH_TUTORIAL_QUESTION_KINDS.map(({ kind, question, label }) => (
        <li key={kind}>
          <Card type="button" onClick={() => onChoose(kind, question)}>
            <Question>
              <SpeechBubbleIcon size={18} aria-hidden="true" />
              <span>{question}</span>
            </Question>
            <Kind>{label}</Kind>
          </Card>
        </li>
      ))}
    </List>
  );
}

const List = styled.ul`
  position: fixed;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0.75rem; /* 12px */
  pointer-events: auto;

  /* 좁은 화면에서는 세로로 쌓아요 */
  @media (max-width: 767px) {
    grid-template-columns: 1fr;
  }
`;

const Card = styled.button`
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: 0.5rem; /* 8px */
  width: 100%;
  height: 100%;
  padding: 1rem 1.125rem; /* 16px 18px */
  border: 1px solid transparent;
  border-radius: 1rem; /* 16px */
  background-color: ${({ theme }) => theme.neutral[0]};
  box-shadow: ${({ theme }) => theme.shadow02};
  text-align: left;
  cursor: pointer;

  &:hover {
    border-color: ${({ theme }) => theme.neutral[300]};
  }

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: 2px;
  }
`;

const Question = styled.span`
  display: flex;
  gap: 0.5rem; /* 8px */
  ${({ theme }) => theme.text.label01};
  color: ${({ theme }) => theme.neutral[900]};

  & > svg {
    flex-shrink: 0;
    margin-top: 0.125rem; /* 2px */
    color: ${({ theme }) => theme.neutral[700]};
  }

  & > span {
    display: -webkit-box;
    overflow: hidden;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }
`;

const Kind = styled.span`
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[600]};
`;
