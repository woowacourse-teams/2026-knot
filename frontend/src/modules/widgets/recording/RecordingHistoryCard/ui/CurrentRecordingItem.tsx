import type { Theme } from "@emotion/react";
import { MEDIA_QUERY } from "@constants/breakpoint";
import styled from "@emotion/styled";
import Button from "@primitives/ui/Button";

import MicIcon from "@/assets/icons/mic.svg";
import type { CurrentRecordingStatus } from "@/shared/types/recording";

import {
  CURRENT_RECORDING_COPY,
  OTHER_TAB_CONNECTION_LOST_HINT,
  OTHER_TAB_RECORDING_HINT,
  RELOADED_RECORDING_COPY,
} from "../constants/recordingStatus";

type CurrentRecordingItemStatus = Extract<
  CurrentRecordingStatus,
  "recording" | "paused"
>;

interface CurrentRecordingItemProps {
  recordingStatus: CurrentRecordingItemStatus;
  /** 녹음 이름. 예: `유월 님의 녹음` */
  title: string;
  /** 녹음한 시간. 예: `12:48` */
  elapsedTime: string;
  /** 이 탭의 녹음이면 넘겨요. 없으면 다른 탭의 녹음이라 「녹음 화면으로」를 누를 수 없어요(STT-R28) */
  onOpenRecording?: () => void;
  /** 이 탭을 새로고침해 아직 이어 받지 않은 녹음인지(STT-R26) */
  isReloaded?: boolean;
  /** 다른 탭의 녹음에서 녹음 탭의 연결이 끊겼는지(STT-R28) */
  isConnectionLost?: boolean;
}

/**
 * 녹음 기록 카드 맨 위의 내 녹음 중 · 일시정지 녹음 한 줄. 녹음 이름 / 상태 줄(점 + 상태 · 시간) / 안내 한 줄과 오른쪽 「녹음 화면으로」로 이뤄져요(STT-R24).
 * 다른 탭의 녹음이면 「녹음 화면으로」를 누를 수 없고, 안내는 줄을 바꿔 다 보여 줘요. 끝내기는 독의 ■로 해요.
 *
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=6061-44336 다른 탭 평소
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=6061-44533 다른 탭 끊김
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=6061-44922 다른 탭 모바일
 */
export default function CurrentRecordingItem({
  recordingStatus,
  title,
  elapsedTime,
  onOpenRecording,
  isReloaded = false,
  isConnectionLost = false,
}: CurrentRecordingItemProps) {
  const { label, hint } = CURRENT_RECORDING_COPY[recordingStatus];
  const isOtherTab = onOpenRecording === undefined;
  // 새로고침 뒤 서버는 녹음 중이어도 소리를 받지 않아 일시정지처럼 보여 줘요
  const visibleStatus = isReloaded ? "paused" : recordingStatus;
  const visibleLabel =
    isReloaded && recordingStatus === "recording"
      ? RELOADED_RECORDING_COPY.label
      : label;

  const getHint = () => {
    if (isReloaded) return RELOADED_RECORDING_COPY.hint;
    if (!isOtherTab) return hint;

    return isConnectionLost
      ? OTHER_TAB_CONNECTION_LOST_HINT
      : OTHER_TAB_RECORDING_HINT;
  };
  const visibleHint = getHint();

  return (
    <Container>
      <IconChip>
        <MicIcon size={24} />
      </IconChip>

      <Info>
        <Title title={title}>{title}</Title>
        <Status $recordingStatus={visibleStatus}>
          <StatusDot aria-hidden="true" $recordingStatus={visibleStatus} />
          <StatusText>{`${visibleLabel} · ${elapsedTime}`}</StatusText>
        </Status>
        {visibleHint && (
          <Hint $isOtherTab={isOtherTab} $isCaution={isConnectionLost}>
            {visibleHint}
          </Hint>
        )}
      </Info>

      <Button
        size="sm"
        variant="filled"
        disabled={isOtherTab}
        onClick={onOpenRecording}
      >
        녹음 화면으로
      </Button>
    </Container>
  );
}

/** 상태 줄의 글자색과 점 색. 녹음 중은 빨강, 일시정지는 회색이에요 */
const statusColors = (theme: Theme) =>
  ({
    recording: { text: theme.sub.warning[600], dot: theme.sub.warning[600] },
    paused: { text: theme.neutral[600], dot: theme.neutral[400] },
  }) satisfies Record<
    CurrentRecordingItemStatus,
    { text: string; dot: string }
  >;

/** 피그마 「기존 확정 · 녹음 중」: 안쪽 20px, 아이콘 · 글 묶음 · 버튼 사이 16px */
const Container = styled.div`
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 1rem; /* 16px */
  width: 100%;
  padding: 1.25rem; /* 20px */
  border-radius: 1rem; /* 16px */
  background-color: ${({ theme }) => theme.neutral[100]};

  /* 모바일은 버튼을 아래 줄로 내리고 폭을 꽉 채워요(RESP-R5) */
  ${MEDIA_QUERY.mobile} {
    flex-wrap: wrap;
    align-items: flex-start;

    && > button {
      flex-basis: 100%;
      height: 2.75rem; /* 44px */
    }
  }
`;

const IconChip = styled.span`
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 3.25rem; /* 52px */
  height: 3.25rem;
  border-radius: 0.875rem; /* 14px */
  background-color: ${({ theme }) => theme.neutral[0]};
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

const Status = styled.p<{ $recordingStatus: CurrentRecordingItemStatus }>`
  display: flex;
  align-items: center;
  gap: 0.375rem; /* 6px */
  color: ${({ theme, $recordingStatus }) =>
    statusColors(theme)[$recordingStatus].text};
  ${({ theme }) => theme.text.caption01};
`;

const StatusDot = styled.span<{ $recordingStatus: CurrentRecordingItemStatus }>`
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

const Hint = styled.p<{ $isOtherTab: boolean; $isCaution: boolean }>`
  overflow: hidden;
  color: ${({ theme, $isCaution }) =>
    $isCaution ? theme.sub.caution[700] : theme.neutral[500]};
  text-overflow: ellipsis;
  white-space: ${({ $isOtherTab }) => ($isOtherTab ? "normal" : "nowrap")};
  ${({ theme }) => theme.text.caption01};

  ${MEDIA_QUERY.mobile} {
    white-space: normal;
  }
`;
