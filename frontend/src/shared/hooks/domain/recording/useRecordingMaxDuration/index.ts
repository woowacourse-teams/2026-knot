import { MAX_RECORDING_DURATION_MS } from "@constants/recording";
import { useRecordingStore } from "@store/recordingStore";
import { useEffect, useRef } from "react";

interface UseRecordingMaxDurationParams {
  /** 상한보다 이만큼 앞서 알려요. 기본값은 `0`으로 상한에 닿는 순간이에요 */
  beforeMs?: number;
  /** 녹음한 시간이 상한(에서 `beforeMs`를 뺀 시간)에 닿았을 때 실행할 콜백 함수 */
  onReached: () => void;
}

// TODO: 콜백 받을꺼면 공통 훅으로 만들기, 안받을꺼면 도메닝 훅으로 만들기

/**
 * 녹음한 시간이 상한(2시간)에 닿는 순간을 알려 주는 도메인 훅.
 * `beforeMs`를 주면 상한보다 그만큼 앞선 순간을 알려요. 예: 15분 전 안내
 *
 * 일시정지 중에는 시간이 흐르지 않아 기다리지 않고, 이어서 녹음하거나 녹음한 시간이 서버 값으로 맞춰지면
 * 남은 시간으로 다시 기다려요. 알릴 시점을 이미 지난 시간으로 맞춰지거나 이어서 녹음하면 바로 다시 알리므로,
 * 한 번만 알리려면 부르는 쪽이 막아요. 상한을 채운 시간으로 맞춰지면 알리지 않아요.
 *
 * 콜백은 항상 가장 최근에 전달된 함수가 실행돼요.
 */
const useRecordingMaxDuration = ({
  beforeMs = 0,
  onReached,
}: UseRecordingMaxDurationParams) => {
  const accumulatedMs = useRecordingStore((state) => state.accumulatedMs);
  const resumedAt = useRecordingStore((state) => state.resumedAt);
  const onReachedRef = useRef(onReached);

  useEffect(() => {
    onReachedRef.current = onReached;
  }, [onReached]);

  useEffect(() => {
    // 녹음을 정지/중지 하면 타이머를 열지 않는다. (정지, 중지 되면 null값이 들어온다.)
    // TODO: 너무 useRecordingStore의 내부 로직에 의존적인 코드임. 수정 필요함.
    if (resumedAt === null) return;

    // 상한을 이미 채운 시간으로 맞춰졌다면 기다리지 않아요. 바로 알리면 알림과 시간 맞추기가 쉬지 않고 되풀이돼요
    if (accumulatedMs >= MAX_RECORDING_DURATION_MS) return;

    const reachedAtMs = MAX_RECORDING_DURATION_MS - beforeMs;
    const remainingMs = reachedAtMs - accumulatedMs - (Date.now() - resumedAt);

    // 남은 시간이 딱 지날 때 콜백을 실행시키도록 한다.
    // 서버 시간으로 맞춰 알릴 시점을 이미 넘겼거나, 맞춘 뒤 이 Effect가 늦게 실행돼 넘겼다면 바로 알려요
    const timer = setTimeout(
      () => onReachedRef.current(),
      Math.max(remainingMs, 0),
    );

    return () => clearTimeout(timer);
  }, [accumulatedMs, beforeMs, resumedAt]);
};

export default useRecordingMaxDuration;
