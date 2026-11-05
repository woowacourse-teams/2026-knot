import { formatDurationFromSeconds } from "@utils/formatDurationFromSeconds";
import { formatRecordingTime } from "@utils/formatRecordingTime";

export const formatPercent = (ratio: number) => `${Math.round(ratio * 100)}%`;

export const formatMinutes = (seconds: number) =>
  formatDurationFromSeconds(seconds);

/** 회의 안의 위치(ms)를 `분:초`로 바꿔요 */
export const formatClock = (millis: number) =>
  formatRecordingTime(millis / 1000);

export const formatSeconds = (seconds: number) =>
  seconds >= 60 ? formatRecordingTime(seconds) : `${Math.round(seconds)}초`;

const NICE_STEPS = [1, 2, 2.5, 5, 10];

/** 그림 위쪽 값을 읽기 쉬운 수로 올려요 */
export const getNiceMax = (value: number) => {
  if (value <= 0) return 1;

  const scale = 10 ** Math.floor(Math.log10(value));
  const step = NICE_STEPS.find((nice) => nice * scale >= value) ?? 10;

  return step * scale;
};

const TIME_TICK_MINUTES = [1, 2, 5, 10, 15, 30, 60];

/** 회의 길이에 맞는 시간 눈금(ms)을 5개 안팎으로 돌려줘요 */
export const getTimeTicks = (durationSeconds: number) => {
  const minutes = durationSeconds / 60;
  const step = TIME_TICK_MINUTES.find((tick) => minutes / tick <= 6) ?? 120;

  return Array.from(
    { length: Math.floor(minutes / step) + 1 },
    (_, index) => index * step * 60_000,
  );
};
