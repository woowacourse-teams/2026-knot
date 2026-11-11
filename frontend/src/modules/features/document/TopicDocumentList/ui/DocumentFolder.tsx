import styled from "@emotion/styled";
import { getRouterPath } from "@routes/PATH_ROUTE";
import { useId, useRef } from "react";

import { useVisibleRowRange } from "../model/useVisibleRowRange";
import DocumentRow from "./DocumentRow";

interface FolderDocument {
  id: number;
  title: string;
  /** 한 줄 요약. 없으면 null이에요 */
  summary: string | null;
  /** 문서 생성 시각(ISO 8601) */
  createdAt: string;
  /** 녹음 길이(초) */
  recordingDurationSeconds: number;
}

interface DocumentFolderProps {
  /** 행을 누르면 갈 문서 보기 주소의 워크스페이스 id */
  workspaceId: number;
  /** 폴더 이름. AI가 분류한 주제예요 */
  topic: string;
  /** 이 폴더의 전체 문서 수. 아래에 그린 행을 센 값이 아니라 서버가 준 값이에요 */
  documentCount: number;
  /** 폴더의 문서. 화면에 그리는 순서 그대로예요 */
  documents: FolderDocument[];
}

/**
 * 문서 목록의 폴더 하나. 폴더 이름과 문서 수 아래에, 그 폴더의 문서 행을 카드 하나로 묶어 보여줘요.
 *
 * 문서가 많아도 카드 안에는 화면 근처의 행만 그려요. 카드는 행을 모두 그린 높이를 갖고, 그린 행은 위쪽 여백으로 제자리에 놓아요.
 */
export default function DocumentFolder({
  workspaceId,
  topic,
  documentCount,
  documents,
}: DocumentFolderProps) {
  const topicId = useId();
  const cardRef = useRef<HTMLUListElement>(null);
  const { start, end, cardHeight, firstRowTop } = useVisibleRowRange({
    cardRef,
    documents,
  });
  const visibleDocuments = documents.slice(start, end);

  return (
    <Container aria-labelledby={topicId}>
      <TopicHeader>
        <Topic id={topicId}>{topic}</Topic>
        <DocumentCount>{documentCount}</DocumentCount>
      </TopicHeader>
      {/* 스크롤할 때마다 바뀌는 값이라 Emotion 클래스를 새로 만들지 않게 인라인 스타일로 넣어요 */}
      <Card
        ref={cardRef}
        style={{ height: cardHeight, paddingTop: firstRowTop }}
      >
        {visibleDocuments.map(
          ({ id, title, summary, createdAt, recordingDurationSeconds }) => (
            <DocumentRow
              key={id}
              href={getRouterPath({
                routeKey: "DOCUMENT",
                params: {
                  workspaceId: String(workspaceId),
                  documentId: String(id),
                },
              })}
              title={title}
              summary={summary}
              createdAt={createdAt}
              recordingDurationSeconds={recordingDurationSeconds}
            />
          ),
        )}
      </Card>
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
