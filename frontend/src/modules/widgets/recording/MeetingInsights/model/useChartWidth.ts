import { useEffect, useRef, useState } from "react";

const DEFAULT_WIDTH = 640;

/** 그림을 픽셀 단위로 그리려고 칸 너비를 재요. SVG를 늘리면 글자까지 늘어나서 직접 재요 */
export const useChartWidth = () => {
  const containerRef = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(DEFAULT_WIDTH);

  useEffect(() => {
    const container = containerRef.current;

    if (container === null || typeof ResizeObserver === "undefined") return;

    const observer = new ResizeObserver(([entry]) => {
      setWidth(entry.contentRect.width);
    });

    observer.observe(container);

    return () => observer.disconnect();
  }, []);

  return { containerRef, width };
};
