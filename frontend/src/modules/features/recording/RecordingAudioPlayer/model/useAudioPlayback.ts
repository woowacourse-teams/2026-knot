import { useEffect, useRef, useState } from "react";

interface UseAudioPlaybackParams {
  /** 재생할 음성의 서명 URL */
  playbackUrl: string;
  /** URL 만료 시각(ISO 8601). 서버가 주지 않으면 null이에요 */
  expiresAt: string | null;
  /** 새 서명 URL을 받았으면 true예요. 조회 실패·미지원이면 이전 주소로 재생하지 않아요 */
  refreshPlaybackUrl: () => Promise<boolean>;
}

/** 이미 만료됐거나 곧 만료될 URL인지. 재생을 시작하는 사이에 끊기지 않게 10초 앞당겨 봐요 */
const EXPIRY_MARGIN_MS = 10_000;

const isExpired = (expiresAt: string | null) =>
  expiresAt !== null && Date.parse(expiresAt) - EXPIRY_MARGIN_MS <= Date.now();

/**
 * 녹음 음성 플레이어의 `<audio>`를 다루는 훅. 재생 · 일시정지 · 위치 이동과 지금 위치를 맡아요(STT-R50).
 *
 * 화면은 `audioRef`를 붙인 `<audio>`만 그리면 되고, 버튼과 막대는 돌려준 값과 함수로 그려요.
 * 지금 위치는 `<audio>`의 시간 이벤트로 밀리초 단위로 맞추고, 재생하는 동안에는 화면을 그릴 때마다 읽어 막대가 끊기지 않고 움직여요.
 *
 * 서명 URL은 만료 시각이 있어요. 만료된 URL로 재생을 누르면 먼저 새 URL을 받고, 새 음성을 불러오면 멈췄던 위치에서 이어서 재생해요.
 * 음성을 불러오지 못하면(`<audio>`의 error) `hasLoadError`가 `true`예요. `retryLoad`는 새 URL을 받아 같은 위치부터 다시 불러와요.
 */
export const useAudioPlayback = ({
  playbackUrl,
  expiresAt,
  refreshPlaybackUrl,
}: UseAudioPlaybackParams) => {
  const audioRef = useRef<HTMLAudioElement>(null);
  const [isPlaying, setIsPlaying] = useState(false);
  const [currentMillis, setCurrentMillis] = useState(0);
  const [hasLoadError, setHasLoadError] = useState(false);
  // 새 URL을 불러온 뒤 이어서 재생할 위치(초)와, 이어서 재생할지
  const resumeAtRef = useRef<number | null>(null);
  const shouldPlayAfterLoadRef = useRef(false);

  useEffect(() => {
    const audio = audioRef.current;
    if (audio === null) return;

    const handleTimeUpdate = () => setCurrentMillis(audio.currentTime * 1000);
    const handlePlay = () => setIsPlaying(true);
    const handlePause = () => setIsPlaying(false);
    const handleError = () => {
      setIsPlaying(false);
      setHasLoadError(true);
    };
    const handleLoadedMetadata = () => {
      if (resumeAtRef.current !== null) {
        audio.currentTime = resumeAtRef.current;
        resumeAtRef.current = null;
      }

      if (shouldPlayAfterLoadRef.current) {
        shouldPlayAfterLoadRef.current = false;
        // 재생을 막는 브라우저 정책 등으로 실패하면 멈춘 채로 둬요. 버튼을 다시 누르면 돼요
        audio.play().catch(() => {});
      }
    };

    audio.addEventListener("timeupdate", handleTimeUpdate);
    audio.addEventListener("play", handlePlay);
    audio.addEventListener("pause", handlePause);
    audio.addEventListener("ended", handlePause);
    audio.addEventListener("error", handleError);
    audio.addEventListener("loadedmetadata", handleLoadedMetadata);

    return () => {
      audio.removeEventListener("timeupdate", handleTimeUpdate);
      audio.removeEventListener("play", handlePlay);
      audio.removeEventListener("pause", handlePause);
      audio.removeEventListener("ended", handlePause);
      audio.removeEventListener("error", handleError);
      audio.removeEventListener("loadedmetadata", handleLoadedMetadata);
    };
  }, []);

  // 시간 이벤트는 0.25초쯤마다 와서 그것만 따르면 막대가 뚝뚝 끊겨 움직여요
  useEffect(() => {
    const audio = audioRef.current;
    if (!isPlaying || audio === null) return;

    let frameId = requestAnimationFrame(function followPlayback() {
      setCurrentMillis(audio.currentTime * 1000);
      frameId = requestAnimationFrame(followPlayback);
    });

    return () => cancelAnimationFrame(frameId);
  }, [isPlaying]);

  // 새 URL을 받은 뒤 그 음성을 처음부터 다시 불러와요. 이어서 재생할 위치는 불러온 뒤(loadedmetadata) 맞춰요
  const reloadWithNewUrl = async ({ shouldPlay }: { shouldPlay: boolean }) => {
    const audio = audioRef.current;
    if (audio === null) return;

    resumeAtRef.current = audio.currentTime;
    shouldPlayAfterLoadRef.current = shouldPlay;
    const isRefreshed = await refreshPlaybackUrl();
    if (audioRef.current !== audio) return;
    if (!isRefreshed) {
      shouldPlayAfterLoadRef.current = false;
      setHasLoadError(true);
      return;
    }
    // URL이 그대로여도 다시 불러오도록 직접 불러요
    audio.load();
  };

  const togglePlay = () => {
    const audio = audioRef.current;
    if (audio === null) return;

    if (!audio.paused) {
      audio.pause();
      return;
    }

    if (isExpired(expiresAt)) {
      reloadWithNewUrl({ shouldPlay: true });
      return;
    }

    audio.play().catch(() => {});
  };

  const seek = (millis: number) => {
    const audio = audioRef.current;
    if (audio === null) return;

    audio.currentTime = millis / 1000;
    setCurrentMillis(millis);
  };

  const retryLoad = () => {
    setHasLoadError(false);
    reloadWithNewUrl({ shouldPlay: false });
  };

  return {
    audioRef,
    isPlaying,
    currentMillis,
    hasLoadError,
    togglePlay,
    seek,
    retryLoad,
  };
};
