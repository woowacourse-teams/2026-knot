import {
  type RefObject,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { flushSync } from "react-dom";

import { OVERSCAN_PX } from "../constants/overscan";
import { getDrawnRows } from "../utils/getDrawnRows";
import { getRowOffsets } from "../utils/getRowOffsets";
import { getVisibleRange } from "../utils/getVisibleRange";
import { isViewCovered } from "../utils/isViewCovered";

interface RowDocument {
  /** 한 줄 요약. 없으면 null이에요 */
  summary: string | null;
}

interface UseVisibleRowRangeParams {
  /** 행을 담는 폴더 카드 */
  cardRef: RefObject<HTMLUListElement | null>;
  /** 폴더의 문서. 화면에 그리는 순서 그대로예요 */
  documents: RowDocument[];
  /** 화면 근처가 아니어도 지우지 않을 행. 포커스가 있는 행이에요. 없으면 null이에요 */
  keptIndex: number | null;
}

interface RowRange {
  start: number;
  end: number;
}

// 브라우저 글자 크기 설정에 따라 1rem의 px 값이 달라져서, 행 위치를 계산할 때 화면에서 읽어요
const getRemPx = () =>
  parseFloat(getComputedStyle(document.documentElement).fontSize);

/**
 * 폴더 카드 안에서 화면 근처에 있어 그려야 할 행의 범위를 구해요.
 *
 * 스크롤 영역은 카드를 감싼 `main`이에요. 카드가 그려진 뒤와 `main`을 스크롤하거나 `main`의 크기가 바뀔 때마다 카드 위치를 재서 범위를 다시 구해요.
 * `main`을 찾지 못하면 위치를 잴 기준이 없어 행을 모두 그려요.
 * 지우지 않을 행이 범위 밖에 있으면 그 행도 그리고, 범위와의 사이는 빈 칸으로 채워요.
 * 카드 높이와 그린 첫 행의 위쪽 끝도 함께 돌려줘서, 행을 일부만 그려도 카드 높이와 행 위치가 행을 모두 그린 것과 같게 해요.
 */
export const useVisibleRowRange = ({
  cardRef,
  documents,
  keptIndex,
}: UseVisibleRowRangeParams) => {
  const offsets = useMemo(
    () => getRowOffsets({ documents, remPx: getRemPx() }),
    [documents],
  );
  // 카드 위치를 재기 전에는 행을 그리지 않아요. 첫 렌더링에서 행을 모두 만들면 가상화로 줄이려던 일을 그대로 하게 돼요
  const [range, setRange] = useState<RowRange>({ start: 0, end: 0 });
  // scroll 이벤트에서 지금 그린 행이 화면을 덮는지 보려고, 마지막으로 그린 범위를 함께 둬요
  const drawnRangeRef = useRef(range);

  useLayoutEffect(() => {
    drawnRangeRef.current = range;
  }, [range]);

  // 화면을 그리기 전에 범위를 구해서, 행이 없는 카드가 한 번 보이지 않게 해요
  useLayoutEffect(() => {
    const card = cardRef.current;

    if (card === null) return;

    // 범위가 그대로면 이전 값을 돌려줘서, 스크롤할 때마다 다시 렌더링하지 않게 해요
    const applyRange = (next: RowRange) => {
      setRange((previous) =>
        previous.start === next.start && previous.end === next.end
          ? previous
          : next,
      );
    };
    const main = card.closest("main");

    if (main === null) {
      applyRange({ start: 0, end: offsets.length - 1 });
      return;
    }

    const measureView = () => {
      const viewStart =
        main.getBoundingClientRect().top - card.getBoundingClientRect().top;

      return { viewStart, viewEnd: viewStart + main.clientHeight };
    };
    const getRange = (view: { viewStart: number; viewEnd: number }) =>
      getVisibleRange({ offsets, ...view, overscanPx: OVERSCAN_PX });

    // scroll 이벤트와 크기 변화 알림은 화면을 그리기 직전에 와요. 지금 그린 행이 화면을 덮으면 렌더링을 미뤄도 되지만,
    // 뒤로 가기로 스크롤 위치를 한 번에 되돌릴 때처럼 덮지 못하면 행 없는 화면이 한 프레임 보여서 이벤트 안에서 다시 렌더링해요
    const handleViewChange = () => {
      const view = measureView();
      const { start, end } = drawnRangeRef.current;

      if (isViewCovered({ offsets, start, end, ...view })) {
        applyRange(getRange(view));
        return;
      }
      flushSync(() => applyRange(getRange(view)));
    };

    applyRange(getRange(measureView()));
    main.addEventListener("scroll", handleViewChange, { passive: true });
    // 창 크기가 바뀌어 `main`이 높아지면 scroll 이벤트 없이 화면이 넓어져서, 크기가 바뀔 때도 범위를 다시 구해요
    const resizeObserver = new ResizeObserver(handleViewChange);

    resizeObserver.observe(main);

    return () => {
      main.removeEventListener("scroll", handleViewChange);
      resizeObserver.disconnect();
    };
  }, [cardRef, offsets]);

  const { firstRowTop, items } = getDrawnRows({
    offsets,
    start: range.start,
    end: range.end,
    keptIndex,
  });

  return {
    /** 행을 모두 그린 카드의 높이(px) */
    cardHeight: offsets[offsets.length - 1],
    /** 그린 첫 행의 위쪽 끝이 카드 맨 위에서 몇 px 아래에 있는지 */
    firstRowTop,
    /** 카드에 차례대로 그릴 행(row)과 행 사이의 빈 칸(gap) */
    drawnRows: items,
  };
};
