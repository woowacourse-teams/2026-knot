import { ROW_DIVIDER_PX, ROW_HEIGHT_REM } from "../constants/rowHeight";

interface RowDocument {
  /** 한 줄 요약. 없으면 null이고, 그때는 `DocumentRow`가 요약 줄을 그리지 않아요 */
  summary: string | null;
}

interface GetRowOffsetsParams {
  /** 폴더 안의 문서. 화면에 그리는 순서 그대로예요 */
  documents: RowDocument[];
  /** 1rem이 몇 px인지. 브라우저 글자 크기 설정에 따라 달라져요 */
  remPx: number;
}

/**
 * 폴더 카드 안에서 행마다 위쪽 끝이 카드 맨 위에서 몇 px 아래에 있는지 구해요.
 *
 * 돌려주는 배열은 행 수보다 하나 길어요. `offsets[i]`는 i번째 행의 위쪽 끝이고, 마지막 값은 카드 전체 높이예요.
 * 행 높이는 요약이 있는지로 정하고, 두 번째 행부터는 위쪽 구분선 1px을 더해요.
 *
 * @example
 * getRowOffsets({ documents: [{ summary: null }, { summary: null }], remPx: 16 });
 * // [0, 64, 129]
 */
export const getRowOffsets = ({ documents, remPx }: GetRowOffsetsParams) => {
  const offsets = [0];

  documents.forEach(({ summary }, index) => {
    const rowHeightRem =
      summary === null
        ? ROW_HEIGHT_REM.withoutSummary
        : ROW_HEIGHT_REM.withSummary;
    const dividerPx = index === 0 ? 0 : ROW_DIVIDER_PX;

    offsets.push(offsets[index] + rowHeightRem * remPx + dividerPx);
  });

  return offsets;
};
