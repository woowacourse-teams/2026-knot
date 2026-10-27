import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { useEffect, useRef } from "react";
import { useParams } from "react-router";

type AudioUnavailableReason =
  "no_audio" | "preparing" | "load_failed" | "media_error";

interface UseAudioUnavailableAnalyticsParams {
  /** 지금 음성을 들을 수 없는 까닭. 들을 수 있거나 아직 모르면 `null`이에요 */
  reason: AudioUnavailableReason | null;
}

/**
 * 녹음 음성을 들을 수 없었던 것을 사용 기록(`recording_audio_unavailable`)으로 보내는 훅.
 *
 * 까닭이 정해지면 이 플레이어에서 까닭마다 한 번 보내요. 「다시 시도」 뒤에 같은 까닭으로 또 실패해도 다시 보내지 않아요.
 * 녹음 문서는 녹음마다 플레이어를 새로 그려서, 화면에 들어올 때마다 다시 세요.
 */
export const useAudioUnavailableAnalytics = ({
  reason,
}: UseAudioUnavailableAnalyticsParams) => {
  const params = useParams();
  const workspaceId = Number(params.workspaceId);
  const recordingId = Number(params.recordingId);
  const trackedReasonsRef = useRef(new Set<AudioUnavailableReason>());

  useEffect(() => {
    const trackedReasons = trackedReasonsRef.current;
    if (reason === null || trackedReasons.has(reason)) return;

    trackedReasons.add(reason);
    captureAnalyticsEvent({
      event: "recording_audio_unavailable",
      properties: {
        reason,
        recording_id: recordingId,
        workspace_id: workspaceId,
      },
    });
  }, [reason, recordingId, workspaceId]);
};
