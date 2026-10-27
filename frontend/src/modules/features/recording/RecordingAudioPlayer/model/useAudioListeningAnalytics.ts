import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { type RefObject, useEffect } from "react";
import { useParams } from "react-router";

interface UseAudioListeningAnalyticsParams {
  /** 플레이어의 `<audio>`. `useAudioPlayback`이 돌려준 것을 그대로 받아요 */
  audioRef: RefObject<HTMLAudioElement | null>;
  /** 전체 길이(ms). 서버가 준 녹음 길이예요 */
  durationMillis: number;
}

/** 재생하며 한 번의 시간 이벤트 사이에 흘렀다고 볼 수 있는 가장 긴 길이(초). 더 벌어지면 듣지 않고 건너뛴 것으로 봐요 */
const MAX_PLAYED_STEP_SECONDS = 2;

/** 이만큼(ms) 안에 이어진 위치 이동은 막대를 끄는 동안의 이동이라 한 번으로 세요 */
const SEEK_MERGE_MS = 1000;

/** 사용 기록의 초 값은 소수 첫째 자리까지 보내요 */
const toTenths = (seconds: number) => Math.round(seconds * 10) / 10;

/**
 * 녹음 음성을 들은 것을 사용 기록으로 보내는 훅. `<audio>`의 이벤트를 지켜보기만 하고 재생 동작은 바꾸지 않아요.
 *
 * - 첫 재생(`recording_audio_played`): 실제로 재생이 시작되면(`playing`) 이 플레이어에서 처음 한 번만 보내요.
 * - 들은 시간(`recording_audio_listened`): 화면을 떠나 플레이어가 사라지거나 탭을 닫을 때(`pagehide`) 한 번 보내요. 재생한 적이 없으면 보내지 않아요.
 *   재생 중 시간 이벤트 사이에 흐른 만큼만 들은 시간으로 더해, 위치를 옮겨 건너뛴 구간은 세지 않아요.
 *   탭을 닫는 순간에는 화면이 사라지지 않아 정리 함수가 불리지 않을 수 있어서 `pagehide`에서도 보내요.
 */
export const useAudioListeningAnalytics = ({
  audioRef,
  durationMillis,
}: UseAudioListeningAnalyticsParams) => {
  const params = useParams();
  const workspaceId = Number(params.workspaceId);
  const recordingId = Number(params.recordingId);

  useEffect(() => {
    const audio = audioRef.current;
    if (audio === null) return;

    const durationSeconds = toTenths(durationMillis / 1000);
    let hasPlayed = false;
    let isListenedSent = false;
    let listenedSeconds = 0;
    let lastPosition = audio.currentTime;
    let maxPosition = 0;
    let seekCount = 0;
    let lastSeekAt = -Infinity;
    let hasReachedEnd = false;

    const handlePlaying = () => {
      lastPosition = audio.currentTime;
      if (hasPlayed) return;

      hasPlayed = true;
      captureAnalyticsEvent({
        event: "recording_audio_played",
        properties: {
          position_seconds: toTenths(audio.currentTime),
          duration_seconds: durationSeconds,
          recording_id: recordingId,
          workspace_id: workspaceId,
        },
      });
    };

    const handleTimeUpdate = () => {
      const position = audio.currentTime;
      const playedStep = position - lastPosition;
      lastPosition = position;
      if (!hasPlayed || audio.paused) return;

      if (playedStep > 0 && playedStep <= MAX_PLAYED_STEP_SECONDS) {
        listenedSeconds += playedStep;
      }
      maxPosition = Math.max(maxPosition, position);
    };

    // 위치를 옮기면 옮긴 자리부터 다시 재요. 브라우저는 `seeking`을 시간 이벤트보다 먼저 보내요
    const handleSeeking = () => {
      lastPosition = audio.currentTime;
      const now = Date.now();
      if (now - lastSeekAt > SEEK_MERGE_MS) seekCount += 1;
      lastSeekAt = now;
    };

    const handleEnded = () => {
      hasReachedEnd = true;
      maxPosition = Math.max(maxPosition, audio.currentTime);
    };

    const sendListened = () => {
      if (!hasPlayed || isListenedSent) return;

      isListenedSent = true;
      captureAnalyticsEvent({
        event: "recording_audio_listened",
        properties: {
          listened_seconds: toTenths(listenedSeconds),
          duration_seconds: durationSeconds,
          max_position_seconds: toTenths(maxPosition),
          seek_count: seekCount,
          reached_end: hasReachedEnd,
          recording_id: recordingId,
          workspace_id: workspaceId,
        },
      });
    };

    audio.addEventListener("playing", handlePlaying);
    audio.addEventListener("timeupdate", handleTimeUpdate);
    audio.addEventListener("seeking", handleSeeking);
    audio.addEventListener("ended", handleEnded);
    window.addEventListener("pagehide", sendListened);

    return () => {
      audio.removeEventListener("playing", handlePlaying);
      audio.removeEventListener("timeupdate", handleTimeUpdate);
      audio.removeEventListener("seeking", handleSeeking);
      audio.removeEventListener("ended", handleEnded);
      window.removeEventListener("pagehide", sendListened);
      sendListened();
    };
  }, [audioRef, durationMillis, recordingId, workspaceId]);
};
