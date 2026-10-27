import styled from "@emotion/styled";
import InlineError from "@primitives/ui/InlineError";
import { formatRecordingTime } from "@utils/formatRecordingTime";
import type { ChangeEvent, CSSProperties, KeyboardEvent } from "react";

import PauseIcon from "@/assets/icons/pause.svg";
import PlayIcon from "@/assets/icons/play.svg";

import { useAudioListeningAnalytics } from "../model/useAudioListeningAnalytics";
import { useAudioPlayback } from "../model/useAudioPlayback";
import { useAudioUnavailableAnalytics } from "../model/useAudioUnavailableAnalytics";

/** 위치 막대를 키보드로 옮길 때 한 번에 움직이는 길이(ms) */
const SEEK_STEP_MS = 1000;

/** 위치 막대를 옮기는 방향키. 앞으로는 1, 뒤로는 -1이에요 */
const SEEK_KEY_DIRECTIONS: Partial<Record<string, 1 | -1>> = {
  ArrowRight: 1,
  ArrowUp: 1,
  ArrowLeft: -1,
  ArrowDown: -1,
};

interface AudioPlaybackBarProps {
  /** 재생할 음성의 서명 URL */
  playbackUrl: string;
  /** URL 만료 시각(ISO 8601). 서버가 주지 않으면 null이에요 */
  expiresAt: string | null;
  /** 전체 길이(ms). 일시정지를 뺀 녹음 길이예요 */
  durationMillis: number;
  /** 새 서명 URL을 받아요 */
  refreshPlaybackUrl: () => Promise<unknown>;
}

/**
 * 녹음 음성을 듣는 막대. 재생 · 일시정지 버튼, 지금 위치, 위치 막대, 전체 길이를 한 줄에 둬요(STT-R50).
 *
 * 전체 길이는 음성 파일이 아니라 서버가 준 녹음 길이를 써요. MediaRecorder로 만든 WebM은 파일에 길이가 없어 브라우저가 알 수 없을 때가 있어서예요.
 * 위치 막대는 브라우저의 범위 입력이라 끌거나 누르거나 키보드 방향키로 옮길 수 있고, 낭독기에는 「지금 위치 / 전체 길이」로 읽혀요.
 * 막대에는 단위(step)를 두지 않아요. 단위가 있으면 브라우저가 값을 가까운 단위로 반올림해 점이 진한 구간보다 앞서 가서예요. 방향키는 1초씩 직접 옮겨요.
 * 음성을 불러오지 못하면 막대 대신 그 자리에 안내와 「다시 시도」를 둬요. `<audio>`는 남겨 두어 다시 불러올 때 써요.
 */
export default function AudioPlaybackBar({
  playbackUrl,
  expiresAt,
  durationMillis,
  refreshPlaybackUrl,
}: AudioPlaybackBarProps) {
  const {
    audioRef,
    isPlaying,
    currentMillis,
    hasLoadError,
    togglePlay,
    seek,
    retryLoad,
  } = useAudioPlayback({ playbackUrl, expiresAt, refreshPlaybackUrl });
  useAudioListeningAnalytics({ audioRef, durationMillis });
  useAudioUnavailableAnalytics({
    reason: hasLoadError ? "media_error" : null,
  });

  const positionMillis = Math.min(currentMillis, durationMillis);
  const progressPercent =
    durationMillis > 0 ? (positionMillis / durationMillis) * 100 : 0;
  const currentTime = formatRecordingTime(positionMillis / 1000);
  const totalTime = formatRecordingTime(durationMillis / 1000);

  const handleSeek = (e: ChangeEvent<HTMLInputElement>) => {
    seek(Number(e.target.value));
  };

  // 단위가 없는 막대에서 브라우저는 방향키 한 번에 전체 길이의 1%를 옮겨요. 녹음 길이와 상관없이 1초씩 옮기려고 직접 받아요
  const handleSeekKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    const direction = SEEK_KEY_DIRECTIONS[e.key];
    if (direction === undefined) return;

    e.preventDefault();
    seek(
      Math.min(
        Math.max(positionMillis + direction * SEEK_STEP_MS, 0),
        durationMillis,
      ),
    );
  };

  return (
    <>
      {/* 화면에 보이지 않는 재생기예요. 버튼과 막대가 이 요소를 움직여요 */}
      <audio ref={audioRef} src={playbackUrl} preload="metadata" />
      {hasLoadError ? (
        <InlineError
          message="음성을 불러오지 못했어요. 다시 시도해 주세요."
          onRetry={retryLoad}
        />
      ) : (
        <Controls>
          <PlayButton
            type="button"
            aria-label={isPlaying ? "일시정지" : "재생"}
            onClick={togglePlay}
          >
            {isPlaying ? (
              <PauseIcon size={14} aria-hidden />
            ) : (
              <PlayIcon size={14} aria-hidden />
            )}
          </PlayButton>
          <Time aria-hidden>{currentTime}</Time>
          <PositionSlider
            type="range"
            aria-label="재생 위치"
            aria-valuetext={`${currentTime} / ${totalTime}`}
            min={0}
            max={durationMillis}
            step="any"
            value={positionMillis}
            onChange={handleSeek}
            onKeyDown={handleSeekKeyDown}
            style={
              { "--playback-progress": `${progressPercent}%` } as CSSProperties
            }
          />
          <Time aria-hidden>{totalTime}</Time>
        </Controls>
      )}
    </>
  );
}

/** 피그마 Player/RecordingAudio 정지 · 재생 중: 버튼 · 시각 · 막대 · 시각을 가운데 맞춰 한 줄로 */
const Controls = styled.div`
  display: flex;
  align-items: center;
  gap: 0.75rem; /* 12px */
`;

/** 피그마 재생 버튼: 지름 36px 진한 원 안의 흰 아이콘 14px */
const PlayButton = styled.button`
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 2.25rem; /* 36px */
  height: 2.25rem;
  border-radius: 62.4375rem; /* 999px */
  background-color: ${({ theme }) => theme.neutral[800]};
  color: ${({ theme }) => theme.neutral[0]};

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: 2px;
  }
`;

/** 시각은 숫자 폭을 맞춰, 재생하는 동안 막대가 흔들리지 않게 해요 */
const Time = styled.span`
  flex-shrink: 0;
  ${({ theme }) => theme.text.caption01};
  color: ${({ theme }) => theme.neutral[600]};
  font-variant-numeric: tabular-nums;
`;

/**
 * 피그마 위치 막대: 높이 4px 연한 선 위에 지나온 구간만 진하게, 지금 위치에 지름 10px 진한 점.
 * 지나온 구간은 배경 그라데이션의 경계로 그려요. 경계는 재생하는 동안 화면을 그릴 때마다 바뀌어서,
 * 값마다 스타일 클래스를 새로 만들지 않게 CSS 변수 `--playback-progress`로 받아요
 */
const PositionSlider = styled.input`
  flex: 1;
  min-width: 0;
  height: 1rem; /* 16px, 누르기 쉬운 높이 */
  margin: 0;
  background: transparent;
  cursor: pointer;
  appearance: none;

  &::-webkit-slider-runnable-track {
    height: 0.25rem; /* 4px */
    border-radius: 62.4375rem; /* 999px */
    background: ${({ theme }) =>
      `linear-gradient(to right, ${theme.neutral[800]} var(--playback-progress), ${theme.neutral[200]} var(--playback-progress))`};
  }

  &::-moz-range-track {
    height: 0.25rem; /* 4px */
    border-radius: 62.4375rem; /* 999px */
    background: ${({ theme }) =>
      `linear-gradient(to right, ${theme.neutral[800]} var(--playback-progress), ${theme.neutral[200]} var(--playback-progress))`};
  }

  &::-webkit-slider-thumb {
    width: 0.625rem; /* 10px */
    height: 0.625rem;
    /* 4px 선 가운데에 점을 놓아요 */
    margin-top: -0.1875rem; /* -3px */
    border: none;
    border-radius: 62.4375rem; /* 999px */
    background-color: ${({ theme }) => theme.neutral[800]};
    appearance: none;
  }

  &::-moz-range-thumb {
    width: 0.625rem; /* 10px */
    height: 0.625rem;
    border: none;
    border-radius: 62.4375rem; /* 999px */
    background-color: ${({ theme }) => theme.neutral[800]};
  }

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: 2px;
    border-radius: 0.25rem; /* 4px */
  }
`;
