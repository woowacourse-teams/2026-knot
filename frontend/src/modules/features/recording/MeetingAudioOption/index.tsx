import { MEDIA_QUERY } from "@constants/breakpoint";
import styled from "@emotion/styled";
import Button from "@primitives/ui/Button";
import { useId } from "react";

import MicIcon from "@/assets/icons/mic.svg";
import MonitorIcon from "@/assets/icons/monitor.svg";

import { MEETING_AUDIO_COPY } from "./constants/meetingAudioCopy";
import { useMeetingAudioOption } from "./model/useMeetingAudioOption";
import type { MeetingSourceState } from "./types/meetingSource";
import SoundSourceCard, { type SoundSourceStatus } from "./ui/SoundSourceCard";

const { STATUS } = MEETING_AUDIO_COPY;

const MICROPHONE_STATUS = {
  recording: { label: STATUS.RECORDING, tone: "recording" },
  paused: { label: STATUS.PAUSED, tone: "paused" },
} as const satisfies Record<string, SoundSourceStatus>;

const MEETING_STATUS = {
  off: null,
  connecting: null,
  on: MICROPHONE_STATUS.recording,
  paused: MICROPHONE_STATUS.paused,
  noAudio: { label: STATUS.NO_AUDIO, tone: "caution" },
  stopped: { label: STATUS.STOPPED, tone: "caution" },
  unsupported: { label: STATUS.UNSUPPORTED, tone: "disabled" },
  mobile: { label: STATUS.MOBILE, tone: "disabled" },
} as const satisfies Record<MeetingSourceState, SoundSourceStatus | null>;

/** 버튼을 두는 상태와 그 문구. 공유 창에서 고르는 동안은 같은 버튼이 로딩으로 바뀌어요 */
const MEETING_ACTION: Partial<Record<MeetingSourceState, string>> = {
  off: MEETING_AUDIO_COPY.OPTION,
  connecting: MEETING_AUDIO_COPY.OPTION,
  noAudio: MEETING_AUDIO_COPY.RECONNECT,
  stopped: MEETING_AUDIO_COPY.RECONNECT,
};

/**
 * 녹음 화면의 「녹음하는 소리」 패널(STT-R44~R49). Figma `Recorder/SoundSource`(5404-3689)예요.
 *
 * 녹음은 독 마이크로 시작해 마이크만 녹음하고(STT-R43), 「온라인 회의」 줄의 「함께 녹음하기」로 공유한 탭, 화면의 소리를
 * 같은 녹음에 섞어요. Chrome 계열이 아닌 브라우저와 모바일에서는 「지원 안 함」만 보여 주고 마이크 녹음은 그대로예요.
 * 소리 없이 공유했거나 함께 녹음하다 공유가 멈추면 토스트 대신 줄 안에 계속 남겨(뒤에 있는 탭에서도 사라지지 않게)
 * 「다시 연결하기」를 둬요.
 */
export default function MeetingAudioOption() {
  const titleId = useId();
  const { isPaused, meetingState, isEnding, handleConnectClick } =
    useMeetingAudioOption();
  const actionLabel = MEETING_ACTION[meetingState];

  return (
    <Panel aria-labelledby={titleId}>
      <Title id={titleId}>{MEETING_AUDIO_COPY.PANEL_TITLE}</Title>
      <Sources>
        <SoundSourceCard
          icon={<MicIcon />}
          title={MEETING_AUDIO_COPY.MICROPHONE_TITLE}
          status={
            isPaused ? MICROPHONE_STATUS.paused : MICROPHONE_STATUS.recording
          }
          description={MEETING_AUDIO_COPY.MICROPHONE_DESCRIPTION}
        />
        <SoundSourceCard
          icon={<MonitorIcon />}
          title={MEETING_AUDIO_COPY.MEETING_TITLE}
          status={MEETING_STATUS[meetingState]}
          description={MEETING_AUDIO_COPY.MEETING_DESCRIPTION[meetingState]}
          isLiveRegion
          action={
            actionLabel &&
            !isEnding && (
              <Button
                size="sm"
                variant="outline"
                isLoading={meetingState === "connecting"}
                onClick={handleConnectClick}
              >
                {actionLabel}
              </Button>
            )
          }
        />
      </Sources>
    </Panel>
  );
}

const Panel = styled.section`
  display: flex;
  flex-direction: column;
  gap: 0.75rem; /* 12px */
  padding: 1.25rem 1.5rem; /* 20px 24px */
  border-radius: 1.25rem; /* 20px */
  background-color: ${({ theme }) => theme.neutral[0]};

  ${MEDIA_QUERY.mobile} {
    padding: 1.25rem; /* 20px */
  }
`;

const Title = styled.h2`
  ${({ theme }) => theme.text.label01};
  color: ${({ theme }) => theme.neutral[900]};
`;

/** 컴퓨터에서는 두 소리를 나란히, 모바일에서는 위아래로 놓아요 */
const Sources = styled.div`
  display: flex;
  gap: 0.75rem; /* 12px */

  ${MEDIA_QUERY.mobile} {
    flex-direction: column;
  }
`;
