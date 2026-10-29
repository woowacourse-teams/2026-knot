import styled from "@emotion/styled";
import IllustratedMessageLayout from "@primitives/layout/IllustratedMessageLayout";
import type { ReactNode } from "react";

interface StateMessageProps {
  /** 맨 위에 놓을 그림 */
  illustration: ReactNode;
  /** 지금 상태를 알리는 제목. 없으면 그리지 않아요 */
  title?: string;
  /** 제목 아래 설명. 항목 하나가 한 줄이에요. 비워 두면 설명을 그리지 않아요 */
  descriptionLines?: string[];
  /** 설명 아래 놓을 버튼. 없으면 그리지 않아요 */
  button?: ReactNode;
}

/**
 * 녹음 문서에서 처리 상태(정리 중 · 실패 · 정리할 내용 없음)를 알리는 틀.
 *
 * 녹음 제목 아래, 회의 전체 요약 자리를 대신해 놓여요. 그림 · 제목 · 설명 · 버튼의 배치는 `IllustratedMessageLayout`이 맡고,
 * 여기서는 묶음의 폭 · 위아래 여백과 제목 · 설명의 글꼴과 색만 정해요. 글꼴은 정리 화면의 상태 화면과 같아요.
 */
export default function StateMessage({
  illustration,
  title,
  descriptionLines = [],
  button,
}: StateMessageProps) {
  return (
    <Root>
      <IllustratedMessageLayout
        illustration={illustration}
        title={title !== undefined && <Title>{title}</Title>}
        description={
          descriptionLines.length > 0 && (
            <Description $hasTitle={title !== undefined}>
              {descriptionLines.map((line) => (
                <p key={line}>{line}</p>
              ))}
            </Description>
          )
        }
        button={button}
      />
    </Root>
  );
}

/** 피그마 State/Drafting · State/DocNotCreated: 폭 518px 묶음. 녹음 제목 아래에서 위아래 32px을 띄워요 */
const Root = styled.div`
  width: 100%;
  max-width: 32.375rem; /* 518px */
  margin: 0 auto;
  padding: 2rem 0; /* 32px */
`;

const Title = styled.h2`
  ${({ theme }) => theme.text.heading02};
  color: ${({ theme }) => theme.primary};
`;

/** 제목이 없으면(정리할 내용 없음) 설명이 안내의 전부라 한 단계 작고 흐리게 그려요 */
const Description = styled.div<{ $hasTitle: boolean }>`
  ${({ theme, $hasTitle }) =>
    $hasTitle ? theme.text.body02 : theme.text.body01};
  color: ${({ theme, $hasTitle }) =>
    $hasTitle ? theme.neutral[700] : theme.neutral[600]};
`;
