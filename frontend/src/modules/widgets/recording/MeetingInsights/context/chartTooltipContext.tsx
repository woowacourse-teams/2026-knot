import styled from "@emotion/styled";
import {
  createContext,
  use,
  useMemo,
  useState,
  type FocusEvent,
  type PointerEvent,
  type ReactNode,
} from "react";

export interface ChartTooltipContent {
  /** 먼저 읽히는 값 */
  value: string;
  label: string;
  /** 값 아래 덧붙이는 설명 */
  detail?: string;
  color?: string;
}

interface TooltipState extends ChartTooltipContent {
  x: number;
  y: number;
}

interface ChartTooltipActions {
  show: (content: ChartTooltipContent, x: number, y: number) => void;
  hide: () => void;
}

const TOOLTIP_OFFSET = 14;
const TOOLTIP_WIDTH = 280;

const chartTooltipContext = createContext<ChartTooltipActions | null>(null);

/** 페이지의 모든 그림이 함께 쓰는 툴팁을 하나만 그려요. 그림은 값이 바뀌어도 다시 그려지지 않아요 */
export const ChartTooltipProvider = ({ children }: { children: ReactNode }) => {
  const [tooltip, setTooltip] = useState<TooltipState | null>(null);
  const actions = useMemo<ChartTooltipActions>(
    () => ({
      show: (content, x, y) => setTooltip({ ...content, x, y }),
      hide: () => setTooltip(null),
    }),
    [],
  );

  return (
    <chartTooltipContext.Provider value={actions}>
      {children}
      {tooltip !== null && (
        <TooltipBox
          role="status"
          style={{
            left: Math.min(
              tooltip.x + TOOLTIP_OFFSET,
              window.innerWidth - TOOLTIP_WIDTH - TOOLTIP_OFFSET,
            ),
            top: tooltip.y + TOOLTIP_OFFSET,
          }}
        >
          <TooltipValue>{tooltip.value}</TooltipValue>
          <TooltipLabel>
            {tooltip.color !== undefined && (
              <TooltipKey style={{ background: tooltip.color }} />
            )}
            {tooltip.label}
          </TooltipLabel>
          {tooltip.detail !== undefined && (
            <TooltipDetail>{tooltip.detail}</TooltipDetail>
          )}
        </TooltipBox>
      )}
    </chartTooltipContext.Provider>
  );
};

/** 그림 요소에 펼쳐 넣을 툴팁 속성을 만들어요. 구간이 많은 그림은 `isFocusable`을 꺼서 탭 순서를 지켜요 */
export const useChartTooltip = () => {
  const actions = use(chartTooltipContext);

  if (actions === null) {
    throw new Error("ChartTooltipProvider 안에서 써야 해요");
  }

  const bindTooltip = (
    content: ChartTooltipContent,
    { isFocusable = true }: { isFocusable?: boolean } = {},
  ) => ({
    onPointerEnter: ({ clientX, clientY }: PointerEvent) =>
      actions.show(content, clientX, clientY),
    onPointerMove: ({ clientX, clientY }: PointerEvent) =>
      actions.show(content, clientX, clientY),
    onPointerLeave: actions.hide,
    ...(isFocusable && {
      tabIndex: 0,
      "aria-label": `${content.label} ${content.value}`,
      onFocus: ({ currentTarget }: FocusEvent<Element>) => {
        const { left, bottom } = currentTarget.getBoundingClientRect();

        actions.show(content, left, bottom);
      },
      onBlur: actions.hide,
    }),
  });

  return { bindTooltip, showTooltip: actions.show, hideTooltip: actions.hide };
};

const TooltipBox = styled.div`
  position: fixed;
  z-index: 100;
  display: flex;
  flex-direction: column;
  gap: 0.125rem; /* 2px */
  max-width: 17.5rem; /* 280px */
  padding: 0.5rem 0.75rem; /* 8px 12px */
  pointer-events: none;
  background: ${({ theme }) => theme.neutral[0]};
  border: 1px solid ${({ theme }) => theme.neutral[200]};
  border-radius: 0.5rem; /* 8px */
  box-shadow: ${({ theme }) => theme.shadow02};
`;

const TooltipValue = styled.strong`
  ${({ theme }) => theme.text.label01}
  color: ${({ theme }) => theme.neutral[900]};
`;

const TooltipLabel = styled.span`
  ${({ theme }) => theme.text.caption01}
  display: flex;
  gap: 0.375rem; /* 6px */
  align-items: center;
  color: ${({ theme }) => theme.neutral[600]};
`;

const TooltipKey = styled.span`
  flex: none;
  width: 0.75rem; /* 12px */
  height: 0.125rem; /* 2px */
  border-radius: 1px;
`;

const TooltipDetail = styled.span`
  ${({ theme }) => theme.text.caption01}
  color: ${({ theme }) => theme.neutral[600]};
`;
