import useRecordingState from "@hooks/domain/recording/useRecordingState";
import type { MeetingAudioConnectResult } from "@store/recordingStore";
import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";

import type { MeetingLocation } from "../types/meetingSource";

/** 연결 안내에서 고른 곳과 사용 기록의 회의를 연 곳 값 */
const LOCATION_BY_MEETING_LOCATION = {
  browserTab: "browser_tab",
  meetingApp: "meeting_app",
} as const satisfies Record<MeetingLocation, string>;

/** 회의 소리 연결의 결과와 사용 기록의 결과 값. 연결하지 않은 경우(`notConnected`)는 공유 창의 결과가 아니라 보내지 않아요 */
const RESULT_BY_CONNECT_RESULT = {
  connected: "connected",
  noAudio: "no_audio",
  cancelled: "share_cancelled",
} as const satisfies Record<
  Exclude<MeetingAudioConnectResult, "notConnected">,
  string
>;

interface TrackConnectResultParams {
  /** 공유 창의 결과. 연결 안내에서 취소했으면 `dialogCancelled`예요 */
  result: MeetingAudioConnectResult | "dialogCancelled";
  /** 연결 안내에서 고른 회의를 연 곳 */
  location: MeetingLocation;
  /** 「다시 연결하기」로 연 것인지 */
  isReconnect: boolean;
}

/**
 * 녹음 화면의 회의 소리 연결 결과를 사용 기록(`meeting_audio_connect_result`)으로 보내는 훅.
 *
 * 연결 안내에서 취소했거나 공유 창의 결과(연결 · 소리 없음 · 공유 창 닫음)가 정해지면 보내요.
 * 함께 녹음하다 공유가 멈춘 것은 녹음 화면을 떠나 있어도 일어나서, 화면이 아니라 녹음 저장소가 보내요.
 */
export const useMeetingAudioConnectAnalytics = () => {
  const { session } = useRecordingState();

  const trackConnectResult = ({
    result,
    location,
    isReconnect,
  }: TrackConnectResultParams) => {
    if (session === null || result === "notConnected") return;

    captureAnalyticsEvent({
      event: "meeting_audio_connect_result",
      properties: {
        result:
          result === "dialogCancelled"
            ? "dialog_cancelled"
            : RESULT_BY_CONNECT_RESULT[result],
        location: LOCATION_BY_MEETING_LOCATION[location],
        is_reconnect: isReconnect,
        recording_id: session.recordingId,
        workspace_id: session.workspaceId,
      },
    });
  };

  return { trackConnectResult };
};
