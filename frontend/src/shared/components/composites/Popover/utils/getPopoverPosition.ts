import type { PopoverPlacement } from "../types/popover";

/** 트리거와 팝오버 사이 간격 */
const TRIGGER_GAP = 8;

/** 화면 가장자리에서 팝오버가 떨어져 있어야 하는 최소 거리 */
const VIEWPORT_MARGIN = 16;

interface GetPopoverPositionParams {
  /** 트리거의 화면 기준 위치. `getBoundingClientRect()` 결과를 그대로 넘겨요 */
  triggerRect: Pick<DOMRect, "top" | "right" | "bottom" | "left">;
  /** 그려진 팝오버의 크기 */
  popoverSize: Pick<DOMRect, "width" | "height">;
  /** 화면(뷰포트) 크기 */
  viewport: Pick<DOMRect, "width" | "height">;
  /** 트리거의 어느 가장자리에 맞출지 */
  placement: PopoverPlacement;
}

/**
 * 팝오버를 화면 어디에 띄울지 계산해요. 결과는 `position: fixed` 기준 좌표예요.
 *
 * 피그마의 팝오버 배치 규칙을 따라요.
 * - 트리거 바로 아래 8px에 띄우고, `placement`에 따라 트리거의 왼쪽이나 오른쪽 끝에 맞춰요.
 * - 아래 공간이 간격 8 + 팝오버 높이 + 16보다 작으면, 곧 아래에 띄웠을 때 화면 아래 끝까지
 *   16px이 남지 않으면 트리거 위로 뒤집어요.
 *   다만 위쪽이 아래쪽보다 좁으면 뒤집을수록 더 많이 잘리므로 아래에 그대로 둬요.
 * - 가로는 화면 가장자리에서 16px 안쪽에 머물러요. 넘치면 정렬은 두고 안쪽으로만 밀어요.
 */
export const getPopoverPosition = ({
  triggerRect,
  popoverSize,
  viewport,
  placement,
}: GetPopoverPositionParams) => {
  const spaceBelow = viewport.height - triggerRect.bottom;
  const spaceAbove = triggerRect.top;
  const shouldFlip =
    spaceBelow < TRIGGER_GAP + popoverSize.height + VIEWPORT_MARGIN &&
    spaceAbove > spaceBelow;

  const alignedTop = shouldFlip
    ? triggerRect.top - TRIGGER_GAP - popoverSize.height
    : triggerRect.bottom + TRIGGER_GAP;

  // 긴 이름 편집 폼도 버튼이 화면 밖으로 밀리지 않게 세로 위치를 제한해요.
  const maxTop = viewport.height - VIEWPORT_MARGIN - popoverSize.height;
  const top = Math.max(VIEWPORT_MARGIN, Math.min(alignedTop, maxTop));

  const alignedLeft =
    placement === "bottom-start"
      ? triggerRect.left
      : triggerRect.right - popoverSize.width;
  const maxLeft = viewport.width - VIEWPORT_MARGIN - popoverSize.width;
  // 화면이 팝오버보다 좁아 maxLeft가 더 작아져도 왼쪽 여백은 지켜요
  const left = Math.max(VIEWPORT_MARGIN, Math.min(alignedLeft, maxLeft));

  return { top, left };
};
