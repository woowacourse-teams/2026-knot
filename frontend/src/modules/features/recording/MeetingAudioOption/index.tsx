import Button from "@primitives/ui/Button";

import MonitorIcon from "@/assets/icons/monitor.svg";

import { MEETING_AUDIO_COPY } from "./constants/meetingAudioCopy";
import { useMeetingAudioOption } from "./model/useMeetingAudioOption";
import type { MeetingSourceState } from "./types/meetingSource";
import SoundSourceCard, { type SoundSourceStatus } from "./ui/SoundSourceCard";

const { STATUS } = MEETING_AUDIO_COPY;

const MEETING_STATUS = {
  off: null,
  connecting: null,
  on: { label: STATUS.RECORDING, tone: "recording" },
  paused: { label: STATUS.PAUSED, tone: "paused" },
  noAudio: { label: STATUS.NO_AUDIO, tone: "caution" },
  stopped: { label: STATUS.STOPPED, tone: "caution" },
  unsupported: { label: STATUS.UNSUPPORTED, tone: "disabled" },
  // 시안은 모바일에 「지원 안 함」 표시 없이 흐린 줄과 설명만 둬요
  mobile: { tone: "disabled" },
} as const satisfies Record<MeetingSourceState, SoundSourceStatus | null>;

/** 버튼을 두는 상태와 그 문구. 공유 창에서 고르는 동안은 같은 버튼이 로딩으로 바뀌어요 */
const MEETING_ACTION: Partial<Record<MeetingSourceState, string>> = {
  off: MEETING_AUDIO_COPY.OPTION,
  connecting: MEETING_AUDIO_COPY.OPTION,
  noAudio: MEETING_AUDIO_COPY.RECONNECT,
  stopped: MEETING_AUDIO_COPY.RECONNECT,
};

/**
 * 녹음 화면의 「화상 회의 상대방 목소리」 줄(STT-R44~R49). Figma `Recorder/SoundSource`(5404-3689)예요.
 *
 * 녹음은 독 마이크로 시작해 지금 나누는 대화만 녹음하고(STT-R43), 「함께 녹음하기」로 공유한 탭, 화면의 소리를
 * 같은 녹음에 섞어요. Chrome 계열이 아닌 브라우저와 모바일에서는 지원하지 않는다고만 알리고 마이크 녹음은 그대로예요.
 * 소리 없이 공유했거나 함께 녹음하다 공유가 멈추면 토스트 대신 줄 안에 계속 남겨(뒤에 있는 탭에서도 사라지지 않게)
 * 「다시 연결하기」를 둬요.
 */
export default function MeetingAudioOption() {
  const { meetingState, isEnding, handleConnectClick } =
    useMeetingAudioOption();
  const actionLabel = MEETING_ACTION[meetingState];

  return (
    <SoundSourceCard
      icon={<MonitorIcon />}
      title={MEETING_AUDIO_COPY.MEETING_TITLE}
      status={MEETING_STATUS[meetingState]}
      description={MEETING_AUDIO_COPY.MEETING_DESCRIPTION[meetingState]}
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
  );
}
