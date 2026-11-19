import styled from "@emotion/styled";

import type { WriteCard } from "../types/writeCard";
import { formatDatabaseFieldValue } from "../utils/formatDatabaseFieldValue";
import WriteCardBody from "./WriteCardBody";

interface WriteCardFieldsProps {
  card: WriteCard;
}

/**
 * 카드가 만들 페이지의 위치, 제목, 내용, DB 칸, 근거를 보기 전용으로 보여주는 항목 목록(SEARCH-R48, R49).
 *
 * DB 칸은 DB 아래에 만들 때만 보여요. DB 속성을 확인하지 못한 카드는 값 대신 「확인하는 중」을 흐리게 보여요.
 */
export default function WriteCardFields({ card }: WriteCardFieldsProps) {
  const { location, title, body, databaseFields, sourceDocument, executable } =
    card;
  const isDatabase = location.kind === "DATABASE";

  return (
    <List>
      <Row>
        <Name>위치</Name>
        <Value>{isDatabase ? `${location.path} DB` : location.path}</Value>
      </Row>

      <Row>
        <Name>제목</Name>
        <Value>{title}</Value>
      </Row>

      <BodyRow>
        <BodyName>내용</BodyName>
        <Detail>
          <WriteCardBody body={body} />
        </Detail>
      </BodyRow>

      {isDatabase && (
        <Row>
          <Name>DB 칸</Name>
          {executable ? (
            <Value>
              {(databaseFields ?? []).map(({ name, value }) => (
                <DatabaseField key={name}>
                  {`${name}  ${formatDatabaseFieldValue(value)}`}
                </DatabaseField>
              ))}
            </Value>
          ) : (
            <Value $isMuted>확인하는 중</Value>
          )}
        </Row>
      )}

      <Row>
        <Name>근거</Name>
        <Detail>
          <SourceChip>
            <SourceKind>문서</SourceKind>
            <SourceTitle>{sourceDocument.title}</SourceTitle>
          </SourceChip>
        </Detail>
      </Row>
    </List>
  );
}

const List = styled.dl`
  display: flex;
  flex-direction: column;
  gap: 0.875rem; /* 14px */
`;

const Row = styled.div`
  display: flex;
  align-items: center;
  gap: 0.75rem; /* 12px */
`;

const BodyRow = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.375rem; /* 6px */
`;

const Name = styled.dt`
  flex-shrink: 0;
  width: 5rem; /* 80px */
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[500]};
`;

/** 내용은 이름이 왼쪽 칸이 아니라 미리보기 위에 놓여요 */
const BodyName = styled(Name)`
  width: auto;
`;

/** 브라우저 기본 들여쓰기 없이 이름 칸 바로 옆(내용은 바로 아래)에 놓여요 */
const Detail = styled.dd`
  min-width: 0;
  margin: 0;
`;

const Value = styled(Detail)<{ $isMuted?: boolean }>`
  overflow-wrap: anywhere;
  ${({ theme }) => theme.text.body01};
  color: ${({ theme, $isMuted }) =>
    $isMuted ? theme.neutral[500] : theme.neutral[900]};
`;

const DatabaseField = styled.span`
  display: block;
  white-space: pre-wrap;
`;

const SourceChip = styled.span`
  display: inline-flex;
  align-items: center;
  gap: 0.25rem; /* 4px */
  padding: 0.375rem 0.75rem; /* 6px 12px */
  border: 1px solid ${({ theme }) => theme.neutral[300]};
  border-radius: 999px;
  background-color: ${({ theme }) => theme.neutral[0]};
  white-space: nowrap;
`;

const SourceKind = styled.span`
  ${({ theme }) => theme.text.caption02};
  font-weight: 600;
  color: ${({ theme }) => theme.neutral[700]};
`;

const SourceTitle = styled.span`
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[600]};
`;
