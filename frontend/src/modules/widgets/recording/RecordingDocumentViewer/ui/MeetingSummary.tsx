import styled from "@emotion/styled";
import DocumentBody from "@primitives/ui/DocumentBody";
import InlineError from "@primitives/ui/InlineError";
import Spinner from "@primitives/ui/Spinner";

import { toMeetingSummaryMarkdown } from "../utils/toMeetingSummaryMarkdown";

/** 화면이 그리는 데 필요한 회의 전체 요약 값. 녹음 문서 조회의 `meetingSummary`와 같은 모양이에요 */
interface MeetingSummaryValue {
  status: "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED";
  content: string | null;
  isRetryable: boolean;
}

interface MeetingSummaryProps {
  /** 회의 전체 요약. 요약 기능을 켜기 전에 끝난 녹음이면 null이에요 */
  meetingSummary: MeetingSummaryValue | null;
  /** 실패한 요약의 「다시 시도」를 눌렀을 때 */
  onRetry: () => void;
  /** 다시 시도 요청의 응답을 기다리는 중인지. 그동안 버튼을 누를 수 없어요 */
  isRetrying: boolean;
}

/**
 * 녹음 문서의 회의 전체 요약 자리(STT-R38). 요약 상태마다 다른 것을 그려요.
 *
 * - 만드는 중(`QUEUED` · `RUNNING`): 회전 표시와 「회의 전체 요약을 만들고 있어요」
 * - 완료(`SUCCEEDED`): 요약 본문. 주제별 문서 본문과 같은 글꼴로 그리고, 마지막 줄 회의 핵심 결론은 제목과 문장으로 나눠요
 * - 실패(`FAILED`): 「요약을 만들지 못했어요」. 서버가 다시 시도할 수 있다고 하면(`isRetryable`) 「다시 시도」를 둬요
 * - 요약 기록 없음(null): 실패나 만드는 중으로 보이지 않게, 요약이 없는 까닭만 회색으로 한 줄 알려요
 *
 * 요약 실패는 이 자리에서만 알리고 토스트를 띄우지 않아요.
 */
export default function MeetingSummary({
  meetingSummary,
  onRetry,
  isRetrying,
}: MeetingSummaryProps) {
  if (meetingSummary === null) {
    return (
      <NoSummaryNotice>
        이 녹음은 회의 전체 요약 기능이 생기기 전에 끝나 요약이 없어요.
      </NoSummaryNotice>
    );
  }

  const { status, content, isRetryable } = meetingSummary;

  if (status === "SUCCEEDED" && content !== null) {
    return <DocumentBody content={toMeetingSummaryMarkdown(content)} />;
  }

  if (status === "FAILED") {
    return (
      <StateBlock>
        <InlineError
          align="center"
          message="요약을 만들지 못했어요"
          onRetry={isRetryable ? onRetry : undefined}
          isRetrying={isRetrying}
        />
      </StateBlock>
    );
  }

  return (
    <StateBlock>
      <Drafting role="status">
        <Spinner size="1rem" />
        회의 전체 요약을 만들고 있어요
      </Drafting>
    </StateBlock>
  );
}

/** 피그마 Doc/MeetingSummary 상태=실패 · 만드는 중: 위아래 32px 안에 가운데 */
const StateBlock = styled.div`
  display: flex;
  justify-content: center;
  padding: 2rem 0; /* 32px */
`;

const Drafting = styled.p`
  display: flex;
  align-items: center;
  gap: 0.5rem; /* 8px */
  ${({ theme }) => theme.text.body01};
  color: ${({ theme }) => theme.neutral[600]};
`;

const NoSummaryNotice = styled.p`
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[500]};
`;
