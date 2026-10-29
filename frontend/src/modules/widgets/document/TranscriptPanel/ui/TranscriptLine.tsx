import styled from "@emotion/styled";
import { formatRecordingTime } from "@utils/formatRecordingTime";

const MILLIS_PER_SECOND = 1000;

interface TranscriptLineProps {
  /** 녹음한 오디오의 시작을 0으로 한 발화 시작 위치(ms) */
  startMillis: number;
  /** 원문 안에서만 쓰는 익명 화자 번호. 화자를 알 수 없으면 null */
  speakerNumber: number | null;
  /** 발화 문장 */
  text: string;
}

/**
 * 원문의 발화 한 줄. 왼쪽에 말하기 시작한 시각, 오른쪽에 말한 사람과 문장을 보여줘요.
 *
 * 말한 사람은 이름 대신 서버가 준 번호로 「참여자 N」이라고 적어요(DOC-R7). 번호가 없으면 문장만 보여줘요.
 */
export default function TranscriptLine({
  startMillis,
  speakerNumber,
  text,
}: TranscriptLineProps) {
  return (
    <Container>
      <Time>{formatRecordingTime(startMillis / MILLIS_PER_SECOND)}</Time>
      <Text>
        {speakerNumber !== null && `참여자 ${speakerNumber}: `}
        {text}
      </Text>
    </Container>
  );
}

/** 피그마 Line: 시각과 문장 사이 10px, 안쪽 여백 6px 8px, 모서리 8px. 시각은 문장의 첫 줄 위쪽에 맞춰요 */
const Container = styled.li`
  display: flex;
  align-items: flex-start;
  gap: 0.625rem; /* 10px */
  padding: 0.375rem 0.5rem; /* 6px 8px */
  border-radius: 0.5rem; /* 8px */
`;

/** `02:40`의 폭(32px)을 기본으로 두고, 한 시간이 넘어 `1:02:03`이 되면 글자만큼 늘어나요 */
const Time = styled.span`
  flex-shrink: 0;
  min-width: 2rem; /* 32px */
  ${({ theme }) => theme.text.caption01};
  color: ${({ theme }) => theme.neutral[600]};
  font-variant-numeric: tabular-nums;
`;

const Text = styled.p`
  flex: 1 0 0;
  min-width: 0;
  ${({ theme }) => theme.text.body01};
  color: ${({ theme }) => theme.neutral[800]};
  overflow-wrap: anywhere;
`;
