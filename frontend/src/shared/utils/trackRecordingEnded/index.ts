import type { MeetingAudioStatus } from "@store/recordingStore";
import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";

import type { RecordingAudioMode } from "@/shared/types/recording";

interface TrackRecordingEndedParams {
  workspaceId: number;
  recordingId: number;
  audioMode: RecordingAudioMode;
  /** 끝내기 직전의 회의 소리 함께 녹음 상태. 끝내면 저장소가 비워져서 끝내기 전에 읽은 값이에요 */
  meetingAudioStatus: MeetingAudioStatus;
  /** 이 브라우저가 회의 소리를 받을 수 있는지(Chrome 계열) */
  isMeetingAudioSupported: boolean;
  /** 끝내기 직전까지 녹음한 시간(ms). 일시정지한 동안은 빠져요 */
  elapsedMs: number;
  /** 끝낸 곳. 녹음 바 · 독의 녹음 칩 · 로그아웃 확인 창 · 워크스페이스를 옮기기 전 확인 창이에요 */
  surface: "recorder_bar" | "dock" | "logout" | "workspace_move";
  /** 녹음 끝내기의 결과. `useRecordingControl`의 `endRecordingWithoutConfirm`이 돌려주는 값이에요 */
  result: "uploaded" | "notUploaded" | "notEnded";
}

const RESULT_BY_END_RESULT = {
  uploaded: "uploaded",
  notUploaded: "not_uploaded",
} as const;

/** 함께 녹음한 적이 있는 회의 소리 상태와 사용 기록의 값. 마이크만(`off`)은 브라우저가 받을 수 있는지에 따라 달라요 */
const MEETING_AUDIO_BY_STATUS = {
  on: "on",
  stopped: "stopped",
  noAudio: "no_audio",
} as const;

/**
 * @description 녹음을 끝낸 결과를 사용 기록(`recording_ended`)으로 보내요
 * @param params - 녹음 세션의 워크스페이스 · 녹음 id와 녹음 방식, 끝내기 직전의 회의 소리 상태와 녹음한 시간, 끝낸 곳, 끝내기 결과
 * @example
 * trackRecordingEnded({ ...session, meetingAudioStatus, isMeetingAudioSupported: true, elapsedMs: 754_240, surface: "dock", result });
 *
 * 마이크만 녹음했으면 회의 소리를 받을 수 없는 브라우저는 `unsupported`, 받을 수 있는데 고르지 않았으면 `off`로 보내요.
 * 녹음한 시간은 초로 바꿔 소수 첫째 자리까지 반올림해요.
 * 끝내지 못했으면(`notEnded`) 녹음이 이어지므로 보내지 않아요.
 */
export const trackRecordingEnded = ({
  workspaceId,
  recordingId,
  audioMode,
  meetingAudioStatus,
  isMeetingAudioSupported,
  elapsedMs,
  surface,
  result,
}: TrackRecordingEndedParams) => {
  if (result === "notEnded") return;

  captureAnalyticsEvent({
    event: "recording_ended",
    properties: {
      result: RESULT_BY_END_RESULT[result],
      audio_mode: audioMode,
      meeting_audio:
        meetingAudioStatus === "off"
          ? isMeetingAudioSupported
            ? "off"
            : "unsupported"
          : MEETING_AUDIO_BY_STATUS[meetingAudioStatus],
      duration_seconds: Math.round(elapsedMs / 100) / 10,
      surface,
      recording_id: recordingId,
      workspace_id: workspaceId,
    },
  });
};
