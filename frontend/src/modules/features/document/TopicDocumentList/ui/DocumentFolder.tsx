import styled from "@emotion/styled";
import { type ReactNode, useId } from "react";

interface DocumentFolderProps {
  /** 폴더 이름. AI가 분류한 주제예요 */
  topic: string;
  /** 이 폴더의 전체 문서 수. 아래에 그린 행을 센 값이 아니라 서버가 준 값이에요 */
  documentCount: number;
  /** 폴더에 담을 행(`DocumentRow`) */
  children: ReactNode;
}

/**
 * 문서 목록의 폴더 하나. 폴더 이름과 문서 수 아래에, 그 폴더의 문서 행을 카드 하나로 묶어 보여줘요.
 */
export default function DocumentFolder({
  topic,
  documentCount,
  children,
}: DocumentFolderProps) {
  const topicId = useId();

  return (
    <Container aria-labelledby={topicId}>
      <TopicHeader>
        <Topic id={topicId}>{topic}</Topic>
        <DocumentCount>{documentCount}</DocumentCount>
      </TopicHeader>
      <Card>{children}</Card>
    </Container>
  );
}

/** 피그마 Folder: 폴더 이름 줄과 카드 사이 12px */
const Container = styled.section`
  display: flex;
  flex-direction: column;
  gap: 0.75rem; /* 12px */
`;

/** 피그마 TopicHeader: 왼쪽 여백 4px, 이름과 문서 수 사이 6px */
const TopicHeader = styled.div`
  display: flex;
  align-items: center;
  gap: 0.375rem; /* 6px */
  padding-left: 0.25rem; /* 4px */
`;

const Topic = styled.h3`
  ${({ theme }) => theme.text.label01};
  color: ${({ theme }) => theme.neutral[700]};
  overflow-wrap: anywhere;
`;

const DocumentCount = styled.span`
  ${({ theme }) => theme.text.caption02};
  flex-shrink: 0;
  color: ${({ theme }) => theme.neutral[500]};
`;

/** 피그마 Card/DocGroup: 흰 바탕, 모서리 20px, Shadow02. 행의 모서리가 카드 밖으로 나가지 않게 잘라요 */
const Card = styled.ul`
  overflow: hidden;
  border-radius: 1.25rem; /* 20px */
  background-color: ${({ theme }) => theme.neutral[0]};
  box-shadow: ${({ theme }) => theme.shadow02};
`;
