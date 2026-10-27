import {
  TAB_DROPLET_EASING,
  TAB_DROPLET_SETTLE_SCALE,
  TAB_DROPLET_SLIDE_DURATION_MS,
  TAB_DROPLET_SLIDE_TIMING,
  TAB_DROPLET_STRETCH_SCALE,
  TAB_DROPLET_SWELL_SCALE,
  TAB_DROPLET_TAP_DURATION_MS,
  TAB_DROPLET_TAP_TIMING,
  TAB_ENTER_EASING,
  TAB_PRESS_DURATION_MS,
  TAB_PRESS_SCALE,
} from "@constants/tabMotion";
import { useCallback, useEffect, useRef, type RefObject } from "react";

interface UseIndicatorDropletParams {
  /** 탭 칸들을 담은 내비 필. 지금 탭 칸을 누르고 떼는 것을 여기서 들어요 */
  containerRef: RefObject<HTMLElement | null>;
  /** 모양을 바꿀 진한 표시 */
  indicatorRef: RefObject<HTMLElement | null>;
  /** 탭 칸들 중 지금 탭을 찾는 선택자 */
  activeTabSelector: string;
}

interface MorphParams {
  /** 지금 모양 다음에 차례로 지나갈 모양들 */
  keyframes: Keyframe[];
  duration: number;
  /** 끝난 모양을 그대로 둘지. 누르는 동안처럼 손을 뗄 때까지 유지할 때만 써요 */
  isHeld?: boolean;
}

interface SettleKeyframesParams {
  /** 가장 부푸는 때(전체 시간 중 0에서 1 사이) */
  swellOffset: number;
  /** 되돌아 가장 작아지는 때(전체 시간 중 0에서 1 사이) */
  settleOffset: number;
}

const prefersReducedMotion = () =>
  window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;

/** 지금 그려진 모양. 모양 변화가 걸려 있지 않으면 계산된 값이 none이에요 */
const readScale = (element: HTMLElement) => {
  const { scale } = getComputedStyle(element);
  return scale === "none" ? "1" : scale;
};

const isOnActiveTab = (target: EventTarget | null, activeTabSelector: string) =>
  target instanceof Element &&
  target.closest(`button${activeTabSelector}`) !== null;

/** 부풀었다가 한 번 되돌아 제 크기로 자리잡는 모양들 */
const settleKeyframes = ({
  swellOffset,
  settleOffset,
}: SettleKeyframesParams) => [
  {
    offset: swellOffset,
    scale: String(TAB_DROPLET_SWELL_SCALE),
    easing: TAB_DROPLET_EASING,
  },
  {
    offset: settleOffset,
    scale: String(TAB_DROPLET_SETTLE_SCALE),
    easing: TAB_DROPLET_EASING,
  },
  { offset: 1, scale: "1" },
];

/**
 * 알약 모양 탭 묶음의 진한 표시를 물방울처럼 움직여요. 자리는 그대로 두고 모양(`scale`)만 바꿔요.
 *
 * 새 탭으로 미끄러질 때는 진행 방향으로 살짝 늘어났다가, 도착하며 한 번 부풀고 한 번 되돌아 자리잡아요.
 * 지금 탭 칸을 누르면 표시도 칸과 함께 눌리고, 떼면 제자리에서 톡 맺혀요. 키보드로 누를 때도 같아요.
 * 움직이는 중에 다시 바뀌면 지금 모양에서 이어서 새 모양으로 가요. 처음부터 다시 걸면 크기가 한 프레임 튀어 보여서예요.
 * 모양 변화는 언제나 하나만 걸어 둬서 연달아 눌러도 쌓이지 않아요.
 * 움직임 줄이기를 켜 두었거나 모션을 그릴 수 없는 브라우저에서는 모양을 바꾸지 않아요.
 */
const useIndicatorDroplet = ({
  containerRef,
  indicatorRef,
  activeTabSelector,
}: UseIndicatorDropletParams) => {
  /** 지금 표시에 걸린 모양 변화. 새 변화를 걸 때 이것만 지워요 */
  const dropletRef = useRef<Animation | null>(null);

  const morph = useCallback(
    ({ keyframes, duration, isHeld = false }: MorphParams) => {
      const indicator = indicatorRef.current;
      if (
        !indicator ||
        typeof indicator.animate !== "function" ||
        prefersReducedMotion()
      ) {
        return;
      }

      // 앞선 변화를 지우기 전에 읽어야 그 변화가 만든 지금 모양을 얻어요
      const currentScale = readScale(indicator);
      dropletRef.current?.cancel();
      dropletRef.current = indicator.animate(
        [{ scale: currentScale, easing: TAB_ENTER_EASING }, ...keyframes],
        { duration, fill: isHeld ? "forwards" : "none" },
      );
    },
    [indicatorRef],
  );

  /** 새 탭 자리로 미끄러지기 시작할 때 불러요 */
  const playSlideDroplet = useCallback(
    () =>
      morph({
        keyframes: [
          {
            offset: TAB_DROPLET_SLIDE_TIMING.stretch,
            scale: `${TAB_DROPLET_STRETCH_SCALE.x} ${TAB_DROPLET_STRETCH_SCALE.y}`,
            easing: TAB_DROPLET_EASING,
          },
          ...settleKeyframes({
            swellOffset: TAB_DROPLET_SLIDE_TIMING.swell,
            settleOffset: TAB_DROPLET_SLIDE_TIMING.settle,
          }),
        ],
        duration: TAB_DROPLET_SLIDE_DURATION_MS,
      }),
    [morph],
  );

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const playTapDroplet = () =>
      morph({
        keyframes: settleKeyframes({
          swellOffset: TAB_DROPLET_TAP_TIMING.swell,
          settleOffset: TAB_DROPLET_TAP_TIMING.settle,
        }),
        duration: TAB_DROPLET_TAP_DURATION_MS,
      });

    // 칸 밖으로 끌고 나가 떼도 눌린 채 남지 않도록 화면 어디서 떼든 들어요
    const handleRelease = () => {
      window.removeEventListener("pointerup", handleRelease);
      window.removeEventListener("pointercancel", handleRelease);
      playTapDroplet();
    };

    const handlePointerDown = (event: PointerEvent) => {
      if (
        event.button !== 0 ||
        !isOnActiveTab(event.target, activeTabSelector)
      ) {
        return;
      }

      morph({
        keyframes: [{ scale: String(TAB_PRESS_SCALE) }],
        duration: TAB_PRESS_DURATION_MS,
        isHeld: true,
      });
      window.addEventListener("pointerup", handleRelease);
      window.addEventListener("pointercancel", handleRelease);
    };

    // 포인터로 누른 것은 뗄 때 이미 맺혀서, 키보드로 누른 것(detail 0)만 여기서 맺혀요.
    // React가 화면을 옮기기 전에 불려 지금 탭인지 누르기 전 기준으로 봐요
    const handleClick = (event: MouseEvent) => {
      if (
        event.detail === 0 &&
        isOnActiveTab(event.target, activeTabSelector)
      ) {
        playTapDroplet();
      }
    };

    container.addEventListener("pointerdown", handlePointerDown);
    container.addEventListener("click", handleClick);

    return () => {
      container.removeEventListener("pointerdown", handlePointerDown);
      container.removeEventListener("click", handleClick);
      window.removeEventListener("pointerup", handleRelease);
      window.removeEventListener("pointercancel", handleRelease);
    };
  }, [containerRef, morph, activeTabSelector]);

  return { playSlideDroplet };
};

export default useIndicatorDroplet;
