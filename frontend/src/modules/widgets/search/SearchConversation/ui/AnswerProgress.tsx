import styled from "@emotion/styled";

import type { SearchAnswerStage } from "@/shared/types/searchAnswer";

/** 진행 단계마다 보여 줄 글자. 어느 앱을 읽는 중인지는 보여 주지 않아요(SEARCH-R38) */
const STAGE_LABEL: Record<SearchAnswerStage, string> = {
  SEARCHING: "찾는 중",
  GENERATING: "작성 중",
};

interface AnswerProgressProps {
  /** 지금 진행 단계. progress를 받기 전이면 `null`이고 「찾는 중」으로 보여요 */
  stage: SearchAnswerStage | null;
}

/** 답변 텍스트가 오기 전 답변 칸 맨 위에 지금 진행 단계를 점과 글자로 보여주는 줄 */
export default function AnswerProgress({ stage }: AnswerProgressProps) {
  return (
    <Container role="status">
      <Dot />
      <Label>{STAGE_LABEL[stage ?? "SEARCHING"]}</Label>
    </Container>
  );
}

const Container = styled.p`
  display: flex;
  align-items: center;
  gap: 0.625rem; /* 10px */
`;

const Dot = styled.span`
  flex-shrink: 0;
  width: 0.5rem; /* 8px */
  height: 0.5rem; /* 8px */
  border-radius: 50%;
  background-color: ${({ theme }) => theme.neutral[800]};
`;

const Label = styled.span`
  ${({ theme }) => theme.text.body01};
  color: ${({ theme }) => theme.neutral[500]};
`;
