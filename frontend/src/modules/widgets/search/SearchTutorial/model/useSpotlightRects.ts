import { useEffect, useState } from "react";

import type { SearchTutorialTarget } from "../constants/searchTutorial";

export interface SpotlightRect {
  top: number;
  left: number;
  width: number;
  height: number;
}

export interface SpotlightLayout {
  /** 대상별 화면 위치. 대상이 지금 화면에 없으면 빠져요 */
  rects: Partial<Record<SearchTutorialTarget, SpotlightRect>>;
  viewport: { width: number; height: number };
}

const findTarget = (name: SearchTutorialTarget) => {
  const candidates = document.querySelectorAll<HTMLElement>(`[data-search-tutorial="${name}"]`);

  // 같은 이름이 여럿이면(숨겨 둔 요소 등) 실제로 보이는 첫 요소를 써요
  return Array.from(candidates).find((element) => {
    const rect = element.getBoundingClientRect();

    return rect.width > 0 && rect.height > 0;
  });
};

const measure = (targets: readonly SearchTutorialTarget[]): SpotlightLayout => {
  const rects: SpotlightLayout["rects"] = {};

  for (const name of targets) {
    const element = findTarget(name);
    if (!element) continue;

    const { top, left, width, height } = element.getBoundingClientRect();
    rects[name] = { top, left, width, height };
  }

  return { rects, viewport: { width: window.innerWidth, height: window.innerHeight } };
};

const isSameLayout = (a: SpotlightLayout, b: SpotlightLayout) =>
  JSON.stringify(a) === JSON.stringify(b);

/**
 * 튜토리얼이 밝힐 대상들의 화면 위치를 따라가요.
 *
 * 대상은 독이 펼쳐지거나 탭 전환 모션이 끝나며 자리를 옮기므로, 떠 있는 동안 매 프레임 위치를 재고 바뀌었을 때만 다시 그려요.
 * 튜토리얼은 잠깐만 떠 있어 이 비용은 작아요.
 */
export const useSpotlightRects = (
  targets: readonly SearchTutorialTarget[],
  isEnabled: boolean,
): SpotlightLayout => {
  const [layout, setLayout] = useState<SpotlightLayout>(() => measure([]));
  const targetKey = targets.join(",");

  useEffect(() => {
    if (!isEnabled) return;

    const names = targetKey.split(",").filter(Boolean) as SearchTutorialTarget[];
    let frame = 0;

    const tick = () => {
      const next = measure(names);
      setLayout((previous) => (isSameLayout(previous, next) ? previous : next));
      frame = window.requestAnimationFrame(tick);
    };

    tick();

    return () => window.cancelAnimationFrame(frame);
  }, [targetKey, isEnabled]);

  return layout;
};
