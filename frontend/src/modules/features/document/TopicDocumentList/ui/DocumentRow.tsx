import styled from "@emotion/styled";
import Chip from "@primitives/ui/Chip";
import LinkTo from "@primitives/ui/LinkTo";
import { formatDate } from "@utils/formatDate";
import { formatDurationFromSeconds } from "@utils/formatDurationFromSeconds";

import ChevronRightIcon from "@/assets/icons/chevronRight.svg";

interface DocumentRowProps {
  /** 행을 누르면 갈 문서 보기 주소 */
  href: string;
  title: string;
  /** 한 줄 요약. 없으면 null이고, 그때는 요약 줄을 그리지 않아요 */
  summary: string | null;
  /** 문서 생성 시각(ISO 8601) */
  createdAt: string;
  /** 녹음 길이(초) */
  recordingDurationSeconds: number;
}

/**
 * 문서 목록의 행 하나. 왼쪽에 제목과 한 줄 요약, 오른쪽에 만든 날짜와 녹음 길이를 보여주고, 누르면 그 문서 보기로 가요.
 *
 * 확인 여부와 문서 상태는 보여주지 않아요(DOC-R10 · DOC-R11).
 */
export default function DocumentRow({
  href,
  title,
  summary,
  createdAt,
  recordingDurationSeconds,
}: DocumentRowProps) {
  return (
    <Item>
      <RowLink href={href}>
        <TextBlock>
          <Title>{title}</Title>
          {summary !== null && <Summary>{summary}</Summary>}
        </TextBlock>
        <Meta>
          <CreatedDate dateTime={createdAt}>
            {formatDate(createdAt)}
          </CreatedDate>
          <Chip>{formatDurationFromSeconds(recordingDurationSeconds)}</Chip>
        </Meta>
        <ChevronRightIcon size={12} aria-hidden />
      </RowLink>
    </Item>
  );
}

/** 피그마 Divider: 행 사이에만 1px 선을 그어요 */
const Item = styled.li`
  & + & {
    border-top: 1px solid ${({ theme }) => theme.neutral[200]};
  }
`;

/** 피그마 Doc 행: 안쪽 여백 20px 24px, 글 묶음 · 날짜 묶음 · 화살표 사이 16px */
const RowLink = styled(LinkTo)`
  display: flex;
  align-items: center;
  gap: 1rem; /* 16px */
  padding: 1.25rem 1.5rem; /* 20px 24px */

  & > svg {
    flex-shrink: 0;
    color: ${({ theme }) => theme.neutral[500]};
  }

  /* 카드가 모서리 밖을 잘라 내므로 테두리를 안쪽에 그려요 */
  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: -2px;
  }
`;

/** 피그마 Summary: 제목과 요약 사이 4px. 남는 폭을 모두 써요 */
const TextBlock = styled.div`
  display: flex;
  flex: 1 0 0;
  flex-direction: column;
  gap: 0.25rem; /* 4px */
  /* 글이 길어도 날짜 묶음을 밀어내지 않고 말줄임돼요 */
  min-width: 0;
`;

const Title = styled.p`
  ${({ theme }) => theme.text.label01};
  overflow: hidden;
  color: ${({ theme }) => theme.neutral[900]};
  white-space: nowrap;
  text-overflow: ellipsis;
`;

const Summary = styled.p`
  ${({ theme }) => theme.text.body01};
  overflow: hidden;
  color: ${({ theme }) => theme.neutral[800]};
  white-space: nowrap;
  text-overflow: ellipsis;
`;

/** 피그마 Meta: 날짜와 녹음 길이 칩 사이 8px */
const Meta = styled.div`
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 0.5rem; /* 8px */
`;

const CreatedDate = styled.time`
  ${({ theme }) => theme.text.caption01};
  color: ${({ theme }) => theme.neutral[500]};
`;
