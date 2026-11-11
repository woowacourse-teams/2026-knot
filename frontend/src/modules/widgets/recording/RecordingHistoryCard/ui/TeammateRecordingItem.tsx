import type { Theme } from "@emotion/react";
import styled from "@emotion/styled";

import MicIcon from "@/assets/icons/mic.svg";

import { CURRENT_RECORDING_COPY } from "../constants/recordingStatus";

type TeammateRecordingStatus = "recording" | "paused";

interface TeammateRecordingItemProps {
  recordingStatus: TeammateRecordingStatus;
  /** 녹음 이름. 예: `루덴스 님의 녹음` */
  title: string;
  /** 녹음한 시간. 예: `05:12` */
  elapsedTime: string;
}

/**
 * 녹음 기록 카드의 팀원 진행 중 녹음 한 줄(STT-R55). 끝난 녹음 행과 같은 모양에 녹음 이름 / 상태 줄(점 + 상태 · 시간)만 있어요.
 * 팀원의 녹음이라 녹음 화면으로 가거나 일시정지 · 끝낼 수 없어서 버튼과 안내가 없고, 눌러도 아무 데도 가지 않아요.
 * 상태 줄의 이름과 색은 내 녹음 줄(`CurrentRecordingItem`)과 같아요.
 *
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=6361-50630 Row/TeammateRecording
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=6361-53410 홈/팀원 녹음 중
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=6361-54428 홈/팀원 녹음 중 모바일
 */
export default function TeammateRecordingItem({
  recordingStatus,
  title,
  elapsedTime,
}: TeammateRecordingItemProps) {
  const { label } = CURRENT_RECORDING_COPY[recordingStatus];

  return (
    <Container>
      <Badge aria-hidden>
        <MicIcon size={18} />
      </Badge>

      <Info>
        <Title title={title}>{title}</Title>
        <Status $recordingStatus={recordingStatus}>
          <StatusDot aria-hidden="true" $recordingStatus={recordingStatus} />
          <StatusText>{`${label} · ${elapsedTime}`}</StatusText>
        </Status>
      </Info>
    </Container>
  );
}

/** 상태 줄의 글자색과 점 색. 내 녹음 줄과 같이 녹음 중은 빨강, 일시정지는 회색이에요 */
const statusColors = (theme: Theme) =>
  ({
    recording: { text: theme.sub.warning[600], dot: theme.sub.warning[600] },
    paused: { text: theme.neutral[600], dot: theme.neutral[400] },
  }) satisfies Record<TeammateRecordingStatus, { text: string; dot: string }>;

/** 끝난 녹음 행(Row/Recording)과 같은 여백 20px 24px 18px, 배지와 글 묶음 사이 12px */
const Container = styled.li`
  display: flex;
  align-items: center;
  gap: 0.75rem; /* 12px */
  padding: 1.25rem 1.5rem 1.125rem; /* 20px 24px 18px */
`;

/** 끝난 녹음 행과 같은 36px 동그라미 마이크 배지 */
const Badge = styled.span`
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 2.25rem; /* 36px */
  height: 2.25rem;
  border-radius: 50%;
  background-color: ${({ theme }) => theme.neutral[100]};
  color: ${({ theme }) => theme.neutral[500]};
`;

const Info = styled.div`
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 0.25rem; /* 4px */
  min-width: 0;
`;

const Title = styled.p`
  overflow: hidden;
  color: ${({ theme }) => theme.neutral[900]};
  text-overflow: ellipsis;
  white-space: nowrap;
  ${({ theme }) => theme.text.heading04};
`;

const Status = styled.p<{ $recordingStatus: TeammateRecordingStatus }>`
  display: flex;
  align-items: center;
  gap: 0.375rem; /* 6px */
  color: ${({ theme, $recordingStatus }) =>
    statusColors(theme)[$recordingStatus].text};
  ${({ theme }) => theme.text.caption01};
`;

const StatusDot = styled.span<{ $recordingStatus: TeammateRecordingStatus }>`
  flex-shrink: 0;
  width: 0.5rem; /* 8px */
  height: 0.5rem;
  border-radius: 50%;
  background-color: ${({ theme, $recordingStatus }) =>
    statusColors(theme)[$recordingStatus].dot};
`;

const StatusText = styled.span`
  font-variant-numeric: tabular-nums; /* 시간이 바뀌어도 폭이 흔들리지 않아요 */
  white-space: nowrap;
`;
