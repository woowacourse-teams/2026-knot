interface GetVisibleRangeParams {
  /** `getRowOffsets`가 돌려준 행 위치. 행 수보다 하나 길어요 */
  offsets: number[];
  /** 화면 위쪽 끝이 카드 맨 위에서 몇 px 아래에 있는지. 카드가 화면 위쪽 끝보다 아래에 있으면 음수예요 */
  viewStart: number;
  /** 화면 아래쪽 끝이 카드 맨 위에서 몇 px 아래에 있는지 */
  viewEnd: number;
  /** 화면 위아래로 넓혀서 미리 그릴 거리(px) */
  overscanPx: number;
}

/**
 * 폴더 카드 안에서 그릴 행의 범위를 구해요.
 *
 * 화면 위아래를 `overscanPx`만큼 넓힌 구간에 조금이라도 걸친 행을 골라요.
 * 카드가 화면 밖에 있어도 넓힌 구간에 걸치면 그 행을 미리 그려서, 카드가 화면에 들어올 때 빈 칸이 보이지 않게 해요.
 * `end`는 범위에 들어가지 않는 첫 행이라서 `documents.slice(start, end)`로 그릴 문서를 고를 수 있어요.
 * 넓힌 구간에 걸친 행이 없으면 `start`와 `end`가 모두 0이에요.
 *
 * @example
 * getVisibleRange({ offsets: [0, 64, 157, 222, 287], viewStart: 287, viewEnd: 900, overscanPx: 100 });
 * // { start: 2, end: 4 }
 */
export const getVisibleRange = ({
  offsets,
  viewStart,
  viewEnd,
  overscanPx,
}: GetVisibleRangeParams) => {
  const rangeStart = viewStart - overscanPx;
  const rangeEnd = viewEnd + overscanPx;
  const rowCount = offsets.length - 1;
  let firstInRange = -1;
  let lastInRange = -1;

  for (let index = 0; index < rowCount; index += 1) {
    const isInRange =
      offsets[index + 1] > rangeStart && offsets[index] < rangeEnd;

    if (isInRange) {
      if (firstInRange === -1) firstInRange = index;
      lastInRange = index;
    }
  }

  if (firstInRange === -1) return { start: 0, end: 0 };

  return { start: firstInRange, end: lastInRange + 1 };
};
