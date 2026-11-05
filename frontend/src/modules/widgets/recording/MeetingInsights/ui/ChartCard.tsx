import { MEDIA_QUERY } from "@constants/breakpoint";
import styled from "@emotion/styled";
import type { ReactNode } from "react";

type ChartStatus = "ready" | "speaker";

const STATUS_LABEL: Record<ChartStatus, string> = {
  ready: "지금 가능",
  speaker: "화자 확인 뒤",
};

interface ChartCardProps {
  title: string;
  status: ChartStatus;
  /** 값을 어떻게 계산했는지 */
  method: string;
  /** 어떤 저장 값을 썼는지 */
  dataFields: string;
  /** 12칸 중 차지하는 칸 수 */
  span?: 3 | 4 | 5 | 6 | 7 | 8 | 12;
  /** 그림의 값을 글자로 읽을 수 있는 표 */
  table?: { headers: string[]; rows: (string | number)[][] };
  children: ReactNode;
}

/** 그림 하나와 그 그림의 계산 방법, 쓴 데이터, 상태를 함께 보여 줘요. 팀이 항목별로 판단하는 단위예요 */
const ChartCard = ({
  title,
  status,
  method,
  dataFields,
  span = 6,
  table,
  children,
}: ChartCardProps) => (
  <Card $span={span}>
    <CardHeader>
      <CardTitle>{title}</CardTitle>
      <StatusTag $status={status}>{STATUS_LABEL[status]}</StatusTag>
    </CardHeader>
    <CardBody>{children}</CardBody>
    <CardNote>
      <NoteRow>
        <dt>계산</dt>
        <dd>{method}</dd>
      </NoteRow>
      <NoteRow>
        <dt>데이터</dt>
        <dd>{dataFields}</dd>
      </NoteRow>
    </CardNote>
    {table !== undefined && (
      <TableDetails>
        <summary>표로 보기</summary>
        <TableScroll>
          <table>
            <thead>
              <tr>
                {table.headers.map((header) => (
                  <th key={header} scope="col">
                    {header}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {table.rows.map((row, rowIndex) => (
                <tr key={rowIndex}>
                  {row.map((cell, cellIndex) => (
                    <td key={cellIndex}>{cell}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </TableScroll>
      </TableDetails>
    )}
  </Card>
);

export default ChartCard;

const Card = styled.article<{ $span: number }>`
  grid-column: span ${({ $span }) => $span};
  display: flex;
  flex-direction: column;
  gap: 1rem; /* 16px */
  min-width: 0;
  padding: 1.25rem; /* 20px */
  background: ${({ theme }) => theme.neutral[0]};
  border: 1px solid ${({ theme }) => theme.neutral[200]};
  border-radius: 1rem; /* 16px */

  ${MEDIA_QUERY.belowDesktop} {
    grid-column: span ${({ $span }) => ($span <= 4 ? 6 : 12)};
  }

  ${MEDIA_QUERY.mobile} {
    grid-column: span 12;
    padding: 1rem; /* 16px */
  }
`;

const CardHeader = styled.header`
  display: flex;
  gap: 0.5rem; /* 8px */
  align-items: flex-start;
  justify-content: space-between;
`;

const CardTitle = styled.h3`
  ${({ theme }) => theme.text.heading04}
  color: ${({ theme }) => theme.neutral[900]};
`;

const StatusTag = styled.span<{ $status: ChartStatus }>`
  ${({ theme }) => theme.text.caption01}
  flex: none;
  padding: 0.125rem 0.5rem; /* 2px 8px */
  color: ${({ theme, $status }) =>
    $status === "ready" ? theme.sub.accent[700] : theme.sub.caution[700]};
  background: ${({ theme, $status }) =>
    $status === "ready" ? theme.sub.accent[100] : theme.neutral[100]};
  border-radius: 999px;
`;

const CardBody = styled.div`
  flex: 1;
  min-width: 0;
`;

const CardNote = styled.dl`
  ${({ theme }) => theme.text.caption01}
  display: flex;
  flex-direction: column;
  gap: 0.25rem; /* 4px */
  padding-top: 0.75rem; /* 12px */
  color: ${({ theme }) => theme.neutral[600]};
  border-top: 1px solid ${({ theme }) => theme.neutral[100]};
`;

const NoteRow = styled.div`
  display: flex;
  gap: 0.5rem; /* 8px */

  dt {
    flex: none;
    width: 2.5rem; /* 40px */
    color: ${({ theme }) => theme.neutral[500]};
  }
`;

const TableDetails = styled.details`
  ${({ theme }) => theme.text.caption01}
  color: ${({ theme }) => theme.neutral[600]};

  summary {
    cursor: pointer;
  }
`;

const TableScroll = styled.div`
  max-height: 16rem; /* 256px */
  margin-top: 0.5rem; /* 8px */
  overflow: auto;

  table {
    width: 100%;
    border-collapse: collapse;
  }

  th,
  td {
    padding: 0.25rem 0.5rem; /* 4px 8px */
    text-align: left;
    white-space: nowrap;
    border-bottom: 1px solid ${({ theme }) => theme.neutral[100]};
  }

  th {
    position: sticky;
    top: 0;
    background: ${({ theme }) => theme.neutral[0]};
  }
`;
