import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";

import type {
  RecordingAudioMode,
  RecordingEndCause,
} from "@/shared/types/recording";

interface TrackRecordingAutoEndedParams {
  workspaceId: number;
  recordingId: number;
  audioMode: RecordingAudioMode;
  /** 녹음이 끝난 이유. `useRecordingControl`이 Sentry 지표(`recording.end`)로 세는 값과 같아요 */
  reason: RecordingEndCause;
  /** 음성을 서버로 넘겼는지. PCM은 남은 조각을 올리기 시작했으면 `true`예요 */
  isUploaded: boolean;
}

const REASON_BY_END_CAUSE = {
  connectionExpired: "connection_expired",
  maxDuration: "max_duration",
  endedByServer: "ended_by_server",
  discarded: "discarded",
  unreachable: "unreachable",
  controlLost: "control_lost",
} as const;

/**
 * @description 서버가 녹음을 끝낸 결과를 사용 기록(`recording_auto_ended`)으로 보내요
 * @param params - 녹음 세션의 워크스페이스 · 녹음 id와 녹음 방식, 끝난 이유, 음성을 넘겼는지
 * @example
 * trackRecordingAutoEnded({ ...session, reason: "connectionExpired", isUploaded: true });
 *
 * 사용자가 끝낸 녹음(`userEnded`)은 끝낸 곳과 함께 `recording_ended`로 보내므로 여기서는 보내지 않아요.
 */
export const trackRecordingAutoEnded = ({
  workspaceId,
  recordingId,
  audioMode,
  reason,
  isUploaded,
}: TrackRecordingAutoEndedParams) => {
  if (reason === "userEnded") return;

  captureAnalyticsEvent({
    event: "recording_auto_ended",
    properties: {
      reason: REASON_BY_END_CAUSE[reason],
      is_uploaded: isUploaded,
      audio_mode: audioMode,
      recording_id: recordingId,
      workspace_id: workspaceId,
    },
  });
};
