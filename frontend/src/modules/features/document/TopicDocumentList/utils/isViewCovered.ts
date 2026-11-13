interface IsViewCoveredParams {
  /** `getRowOffsets`가 돌려준 행 위치 */
  offsets: number[];
  /** 지금 그린 첫 행 */
  start: number;
  /** 지금 그린 행이 끝난 다음 행 */
  end: number;
  /** 화면 위쪽 끝이 카드 맨 위에서 몇 px 아래에 있는지 */
  viewStart: number;
  /** 화면 아래쪽 끝이 카드 맨 위에서 몇 px 아래에 있는지 */
  viewEnd: number;
}

/**
 * 지금 그린 행이 화면에 보이는 카드 부분을 모두 덮는지 알려 줘요.
 *
 * 덮지 못하면 이 화면을 그대로 그릴 때 행 없이 카드 바탕만 보이는 곳이 생겨요.
 */
export const isViewCovered = ({
  offsets,
  start,
  end,
  viewStart,
  viewEnd,
}: IsViewCoveredParams) => {
  const cardHeight = offsets[offsets.length - 1];
  const visibleTop = Math.max(viewStart, 0);
  const visibleBottom = Math.min(viewEnd, cardHeight);

  if (visibleBottom <= visibleTop) return true;
  if (start === end) return false;

  return offsets[start] <= visibleTop && visibleBottom <= offsets[end];
};
