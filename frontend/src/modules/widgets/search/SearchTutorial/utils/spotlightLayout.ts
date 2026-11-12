import { COACH_CARD_WIDTH, SPOTLIGHT_PADDING } from "../constants/searchTutorial";
import type { SpotlightRect } from "../model/useSpotlightRects";

/** 화면 가장자리에서 띄울 최소 여백(px) */
const EDGE_GAP = 16;

/** 대상과 안내 카드 사이 간격(px) */
const CARD_GAP = 16;

/** 밝힐 영역. 대상보다 사방으로 조금 넓고, 모서리는 대상 높이의 절반까지 둥글어요 */
export interface SpotlightHole extends SpotlightRect {
  radius: number;
}

/**
 * @description 대상 위치를 밝힐 영역으로 바꿔요. 높이가 낮은 버튼·입력창은 알약 모양, 큰 영역은 20px 모서리예요
 * @param rect - 대상의 화면 위치
 * @returns 여백을 더한 영역과 모서리 반경
 */
export const toHole = (rect: SpotlightRect): SpotlightHole => {
  const height = rect.height + SPOTLIGHT_PADDING * 2;

  return {
    top: rect.top - SPOTLIGHT_PADDING,
    left: rect.left - SPOTLIGHT_PADDING,
    width: rect.width + SPOTLIGHT_PADDING * 2,
    height,
    radius: Math.min(height / 2, 20),
  };
};

/** 두 영역을 함께 덮는 가장 작은 영역 */
export const unionRect = (a: SpotlightRect, b: SpotlightRect): SpotlightRect => {
  const top = Math.min(a.top, b.top);
  const left = Math.min(a.left, b.left);

  return {
    top,
    left,
    width: Math.max(a.left + a.width, b.left + b.width) - left,
    height: Math.max(a.top + a.height, b.top + b.height) - top,
  };
};

const roundedRectPath = ({ top, left, width, height, radius }: SpotlightHole) => {
  const r = Math.max(0, Math.min(radius, width / 2, height / 2));
  const right = left + width;
  const bottom = top + height;

  return [
    `M${left + r} ${top}`,
    `H${right - r}`,
    `A${r} ${r} 0 0 1 ${right} ${top + r}`,
    `V${bottom - r}`,
    `A${r} ${r} 0 0 1 ${right - r} ${bottom}`,
    `H${left + r}`,
    `A${r} ${r} 0 0 1 ${left} ${bottom - r}`,
    `V${top + r}`,
    `A${r} ${r} 0 0 1 ${left + r} ${top}`,
    "Z",
  ].join(" ");
};

/**
 * @description 화면 전체에서 밝힐 영역만 뚫은 `clip-path` 값을 만들어요. 뚫린 곳은 클릭도 그 아래 화면으로 가요
 * @param viewport - 화면 크기
 * @param holes - 밝힐 영역들
 * @returns `path(evenodd, ...)` 값
 */
export const toDimClipPath = (viewport: { width: number; height: number }, holes: SpotlightHole[]) => {
  const outer = `M0 0 H${viewport.width} V${viewport.height} H0 Z`;

  return `path(evenodd, "${[outer, ...holes.map(roundedRectPath)].join(" ")}")`;
};

const clampLeft = (left: number, viewportWidth: number) => {
  const width = Math.min(COACH_CARD_WIDTH, viewportWidth - EDGE_GAP * 2);

  return Math.min(Math.max(left, EDGE_GAP), viewportWidth - width - EDGE_GAP);
};

/**
 * @description 안내 카드를 밝힌 영역 아래 가운데에 둬요
 * @returns `position: fixed` 위치
 */
export const placeBelowCenter = (hole: SpotlightRect, viewportWidth: number) => ({
  top: hole.top + hole.height + CARD_GAP,
  left: clampLeft(hole.left + hole.width / 2 - COACH_CARD_WIDTH / 2, viewportWidth),
});

/**
 * @description 안내 카드를 밝힌 영역 아래 왼쪽에 맞춰 둬요. 아래 자리가 모자라면 영역 안 아래쪽에 겹쳐 둬요
 * @returns `position: fixed` 위치
 */
export const placeBelowStart = (
  hole: SpotlightRect,
  viewport: { width: number; height: number },
  cardHeight = 200,
) => ({
  top: Math.min(hole.top + hole.height + CARD_GAP, viewport.height - cardHeight - EDGE_GAP),
  left: clampLeft(hole.left, viewport.width),
});

/**
 * @description 안내 카드를 밝힌 영역 위 왼쪽에 맞춰 둬요
 * @returns `position: fixed` 위치. 카드 아래쪽을 기준으로 잡아 카드 높이와 관계없이 영역 바로 위에 놓여요
 */
export const placeAboveStart = (hole: SpotlightRect, viewport: { width: number; height: number }) => ({
  bottom: viewport.height - hole.top + CARD_GAP,
  left: clampLeft(hole.left, viewport.width),
});
