import styled from "@emotion/styled";
import type { CSSProperties, ReactNode } from "react";

import { COACH_CARD_WIDTH, SEARCH_TUTORIAL_STEP_COUNT } from "../constants/searchTutorial";

interface CoachCardProps {
  step: number;
  title: string;
  description: string;
  /** 동작 버튼 아래에 두는 작은 진행 안내(예: 질문을 입력하는 중…) */
  footnote?: string;
  /** 오른쪽 아래 동작 버튼들 */
  actions?: ReactNode;
  /** 화면 안 위치. 밝힌 대상 옆에 놓여요 */
  style?: CSSProperties;
}

/**
 * 탐색 튜토리얼의 안내 카드. 진행 점과 「n / 4」, 제목, 설명, 동작 버튼을 담아요.
 *
 * 놓일 자리는 밝힌 대상에 맞춰 튜토리얼이 정해요.
 */
export default function CoachCard({ step, title, description, footnote, actions, style }: CoachCardProps) {
  return (
    <Container role="dialog" aria-modal="false" aria-labelledby="search-tutorial-title" style={style}>
      <Progress>
        {Array.from({ length: SEARCH_TUTORIAL_STEP_COUNT }, (_, index) => (
          <Dot key={index} $isCurrent={index + 1 === step} aria-hidden="true" />
        ))}
        <StepText>
          {step} / {SEARCH_TUTORIAL_STEP_COUNT}
        </StepText>
      </Progress>

      <Text>
        <Title id="search-tutorial-title">{title}</Title>
        <Description>{description}</Description>
      </Text>

      {footnote && <Footnote aria-live="polite">{footnote}</Footnote>}
      {actions && <Actions>{actions}</Actions>}
    </Container>
  );
}

const Container = styled.div`
  position: fixed;
  display: flex;
  flex-direction: column;
  gap: 0.75rem; /* 12px */
  width: min(${COACH_CARD_WIDTH / 16}rem, calc(100vw - 2rem));
  padding: 1.25rem; /* 20px */
  border-radius: 1rem; /* 16px */
  background-color: ${({ theme }) => theme.neutral[0]};
  box-shadow:
    0 1px 2px rgba(31, 30, 28, 0.06),
    0 12px 32px -6px rgba(31, 30, 28, 0.18);
  pointer-events: auto;
`;

const Progress = styled.div`
  display: flex;
  align-items: center;
  gap: 0.375rem; /* 6px */
`;

const Dot = styled.span<{ $isCurrent: boolean }>`
  width: ${({ $isCurrent }) => ($isCurrent ? "1rem" : "0.375rem")}; /* 16px : 6px */
  height: 0.375rem; /* 6px */
  border-radius: 0.1875rem; /* 3px */
  background-color: ${({ theme, $isCurrent }) => ($isCurrent ? theme.neutral[800] : theme.neutral[300])};
`;

const StepText = styled.span`
  ${({ theme }) => theme.text.caption01};
  color: ${({ theme }) => theme.neutral[600]};
`;

const Text = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.375rem; /* 6px */
`;

const Title = styled.h2`
  ${({ theme }) => theme.text.heading04};
  color: ${({ theme }) => theme.neutral[900]};
`;

const Description = styled.p`
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[600]};
`;

const Footnote = styled.p`
  ${({ theme }) => theme.text.caption01};
  color: ${({ theme }) => theme.neutral[600]};
`;

const Actions = styled.div`
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 0.25rem; /* 4px */
`;

/** 안내 카드의 동작 버튼. 주 버튼은 먹색 알약, 보조 버튼은 글자만 있어요 */
export const CoachButton = styled.button<{ $variant: "primary" | "text" }>`
  padding: 0.5625rem 1rem; /* 9px 16px */
  border-radius: 999px;
  ${({ theme }) => theme.text.caption02};
  font-weight: 500;
  color: ${({ theme, $variant }) => ($variant === "primary" ? theme.neutral[0] : theme.neutral[600])};
  background-color: ${({ theme, $variant }) => ($variant === "primary" ? theme.neutral[800] : "transparent")};
  cursor: pointer;

  &:hover {
    background-color: ${({ theme, $variant }) => ($variant === "primary" ? theme.neutral[900] : theme.neutral[100])};
  }

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: 2px;
  }
`;
