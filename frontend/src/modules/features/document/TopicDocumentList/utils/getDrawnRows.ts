interface GetDrawnRowsParams {
  /** `getRowOffsets`가 돌려준 행 위치 */
  offsets: number[];
  /** 화면 근처라서 그릴 첫 행 */
  start: number;
  /** 화면 근처라서 그릴 행이 끝난 다음 행. 그리지 않아요 */
  end: number;
  /** 화면 근처가 아니어도 지우지 않을 행. 포커스가 있는 행이에요. 없으면 null이에요 */
  keptIndex: number | null;
}

/**
 * 카드에 그릴 행과, 그린 행 사이에 비워 둘 높이를 차례대로 구해요.
 *
 * 지우지 않을 행이 범위 밖에 있으면 그 행도 제자리에 그리고, 그 행과 범위 사이는 행 대신 같은 높이의 빈 칸으로 채워요.
 * 행 위치가 행을 모두 그린 것과 같아서, 범위가 바뀌어도 그린 행이 움직이지 않아요.
 */
export const getDrawnRows = ({
  offsets,
  start,
  end,
  keptIndex,
}: GetDrawnRowsParams) => {
  const row = (index: number) => ({ type: "row" as const, index });
  const rangeRows = Array.from({ length: end - start }, (_, k) =>
    row(start + k),
  );
  const isKeptOutside =
    keptIndex !== null && (keptIndex < start || keptIndex >= end);

  if (!isKeptOutside) return { firstRowTop: offsets[start], items: rangeRows };

  if (start === end)
    return { firstRowTop: offsets[keptIndex], items: [row(keptIndex)] };

  const gap = (height: number) =>
    height > 0 ? [{ type: "gap" as const, height }] : [];

  if (keptIndex < start) {
    return {
      firstRowTop: offsets[keptIndex],
      items: [
        row(keptIndex),
        ...gap(offsets[start] - offsets[keptIndex + 1]),
        ...rangeRows,
      ],
    };
  }

  return {
    firstRowTop: offsets[start],
    items: [
      ...rangeRows,
      ...gap(offsets[keptIndex] - offsets[end]),
      row(keptIndex),
    ],
  };
};
