import styled from "@emotion/styled";
import Chip from "@primitives/ui/Chip";
import LinkTo from "@primitives/ui/LinkTo";
import { formatDate } from "@utils/formatDate";
import { formatDurationFromSeconds } from "@utils/formatDurationFromSeconds";

import ChevronRightIcon from "@/assets/icons/chevronRight.svg";
import MicIcon from "@/assets/icons/mic.svg";

interface RecordingRowProps {
  /** 행을 누르면 갈 녹음 문서 주소 */
  href: string;
  /** 제목 요소의 id. 녹음 묶음의 `aria-labelledby`가 가리켜요 */
  titleId: string;
  /** 서버가 만든 표시 제목. AI 녹음 제목이 없으면 「OO 님의 녹음」이 와요 */
  title: string;
  /** 녹음 시작 시각(ISO 8601) */
  startedAt: string;
  /** 녹음 길이(초) */
  recordingDurationSeconds: number;
}

/**
 * 「녹음별」 탭 녹음 묶음의 머리 행. 마이크 배지, 표시 제목, 그 아래 녹음 시작 날짜 · 녹음 길이 칩을 보여주고, 오른쪽에 「녹음 보기」가 있어요(DOC-R22).
 *
 * 행 전체가 그 녹음의 녹음 문서로 가는 링크예요. 문서 수, 녹음한 사람, 참여자 수는 보여주지 않아요. 그 녹음의 문서는 행 아래 문서 항목으로 봐요.
 */
export default function RecordingRow({
  href,
  titleId,
  title,
  startedAt,
  recordingDurationSeconds,
}: RecordingRowProps) {
  return (
    <RowLink href={href}>
      <Badge aria-hidden>
        <MicIcon size={18} />
      </Badge>
      <Info>
        <Title id={titleId}>{title}</Title>
        <Meta>
          <StartedDate dateTime={startedAt}>
            {formatDate(startedAt)}
          </StartedDate>
          <Chip>{formatDurationFromSeconds(recordingDurationSeconds)}</Chip>
        </Meta>
      </Info>
      <ViewRecording>
        녹음 보기
        <ChevronRightIcon size={12} aria-hidden />
      </ViewRecording>
    </RowLink>
  );
}

/** 피그마 Row/Recording: 안쪽 여백 20px 24px 18px, 배지 · 글 묶음 · 「녹음 보기」 사이 12px */
const RowLink = styled(LinkTo)`
  display: flex;
  align-items: center;
  gap: 0.75rem; /* 12px */
  padding: 1.25rem 1.5rem 1.125rem; /* 20px 24px 18px */

  /* 카드가 모서리 밖을 잘라 내므로 테두리를 안쪽에 그려요 */
  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: -2px;
  }
`;

/** 피그마 Badge: 36px 동그라미 안에 18px 마이크. 바탕 #F0EEEA는 토큰에 없어 가장 가까운 Neutral/100을 써요 */
const Badge = styled.span`
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 2.25rem; /* 36px */
  height: 2.25rem;
  border-radius: 50%;
  background-color: ${({ theme }) => theme.neutral[100]};
  color: ${({ theme }) => theme.neutral[700]};
`;

/** 피그마 Info: 제목과 아래 줄 사이 4px. 남는 폭을 모두 써요 */
const Info = styled.span`
  display: flex;
  flex: 1 0 0;
  flex-direction: column;
  gap: 0.25rem; /* 4px */
  /* 제목이 길어도 「녹음 보기」를 밀어내지 않고 말줄임돼요 */
  min-width: 0;
`;

const Title = styled.span`
  ${({ theme }) => theme.text.label01};
  overflow: hidden;
  color: ${({ theme }) => theme.neutral[900]};
  white-space: nowrap;
  text-overflow: ellipsis;
`;

/** 피그마 Meta: 날짜와 녹음 길이 칩 사이 8px */
const Meta = styled.span`
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem; /* 8px */
`;

const StartedDate = styled.time`
  ${({ theme }) => theme.text.caption01};
  color: ${({ theme }) => theme.neutral[600]};
`;

/** 피그마 Link/녹음 보기: 글자와 화살표 사이 2px */
const ViewRecording = styled.span`
  ${({ theme }) => theme.text.caption02};
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  gap: 0.125rem; /* 2px */
  color: ${({ theme }) => theme.neutral[600]};
  white-space: nowrap;

  & > svg {
    flex-shrink: 0;
  }
`;
