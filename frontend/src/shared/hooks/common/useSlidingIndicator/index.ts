import useIndicatorDroplet from "@hooks/common/useIndicatorDroplet";
import { useEffect, useLayoutEffect, useRef } from "react";

interface UseSlidingIndicatorParams {
  /** 탭 순서에서 지금 탭의 자리. 고른 탭이 없으면 -1이에요 */
  activeTabIndex: number;
  /** 탭 칸들 중 지금 탭을 찾는 선택자. 내비 링크는 `[aria-current="page"]`, 탭 목록은 `[aria-selected="true"]`예요 */
  activeTabSelector: string;
}

interface PlaceIndicatorParams {
  container: HTMLElement;
  indicator: HTMLElement;
  activeTabSelector: string;
  /** 이전 탭 자리에서 미끄러져 올지. 아니면 모션 없이 제자리에 놓아요 */
  isSliding: boolean;
}

/** 지금 탭 칸의 자리와 크기로 표시를 옮겨요 */
const placeIndicator = ({
  container,
  indicator,
  activeTabSelector,
  isSliding,
}: PlaceIndicatorParams) => {
  const activeTab = container.querySelector<HTMLElement>(activeTabSelector);
  if (!activeTab) return;

  indicator.dataset.sliding = String(isSliding);
  indicator.style.width = `${activeTab.offsetWidth}px`;
  indicator.style.height = `${activeTab.offsetHeight}px`;
  // transform 대신 translate로 옮겨요. transform에 자리를 두면 물방울 모양(scale)이 그 거리까지 키워 표시가 옆으로 밀려요
  indicator.style.translate = `${activeTab.offsetLeft}px ${activeTab.offsetTop}px`;
};

/**
 * 알약 모양 탭 묶음의 진한 표시 하나를 지금 탭 칸 아래에 놓아요. GNB 내비 필과 문서 탭이 같은 모션을 써요.
 *
 * 탭이 바뀌면 이전 탭 자리에서 새 탭 자리로 미끄러지고, 폭도 새 칸에 맞춰 함께 바뀌어요.
 * 미끄러지는 중에 다시 바뀌면 지금 있는 자리에서 이어서 움직여요. CSS 전환이 그 자리에서 다시 출발하기 때문이에요.
 * 처음 그릴 때, 고른 탭이 없다가 생길 때, 창 크기나 글꼴이 바뀌어 칸 크기가 달라질 때는 모션 없이 제자리에 놓아요.
 * 처음 자리가 정해지기 전에 미끄러지면 묶음의 왼쪽 끝에서 날아오는 것처럼 보여서예요.
 * 미끄러질 때만 표시가 물방울처럼 늘어났다가 맺혀요(`useIndicatorDroplet`).
 */
const useSlidingIndicator = <Container extends HTMLElement = HTMLElement>({
  activeTabIndex,
  activeTabSelector,
}: UseSlidingIndicatorParams) => {
  const containerRef = useRef<Container>(null);
  const indicatorRef = useRef<HTMLSpanElement>(null);
  /** 표시가 마지막으로 놓인 탭의 자리. 아직 놓이지 않았거나 숨었으면 -1이에요 */
  const placedTabIndexRef = useRef(-1);
  const { playSlideDroplet } = useIndicatorDroplet({
    containerRef,
    indicatorRef,
    activeTabSelector,
  });

  // 화면에 그리기 전에 옮겨야 표시가 이전 자리에 한 프레임 남지 않아요
  useLayoutEffect(() => {
    const container = containerRef.current;
    const indicator = indicatorRef.current;
    if (!container || !indicator) return;

    const placedTabIndex = placedTabIndexRef.current;
    placedTabIndexRef.current = activeTabIndex;
    if (activeTabIndex === -1) return;

    const isSliding =
      placedTabIndex !== -1 && placedTabIndex !== activeTabIndex;
    placeIndicator({ container, indicator, activeTabSelector, isSliding });
    if (isSliding) playSlideDroplet();
  }, [activeTabIndex, activeTabSelector, playSlideDroplet]);

  useEffect(() => {
    const container = containerRef.current;
    const indicator = indicatorRef.current;
    if (!container || !indicator || typeof ResizeObserver === "undefined") {
      return;
    }

    const observer = new ResizeObserver(() =>
      placeIndicator({
        container,
        indicator,
        activeTabSelector,
        isSliding: false,
      }),
    );
    // 글꼴이 늦게 불러와지면 칸마다 폭이 달라지므로 묶음과 칸을 함께 봐요.
    // 표시는 미끄러지는 동안 폭이 바뀌어, 표시까지 보면 모션을 스스로 끊어요
    observer.observe(container);
    container
      .querySelectorAll("button")
      .forEach((tab) => observer.observe(tab));

    return () => observer.disconnect();
  }, [activeTabSelector]);

  return { containerRef, indicatorRef };
};

export default useSlidingIndicator;
