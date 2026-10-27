import {
  TAB_ENTER_EASING,
  TAB_INDICATOR_DURATION_MS,
  TAB_POP_EASING,
  TAB_PRESS_DURATION_MS,
  TAB_PRESS_SCALE,
  TAB_RELEASE_DURATION_MS,
} from "@constants/tabMotion";
import styled from "@emotion/styled";
import useSlidingIndicator from "@hooks/common/useSlidingIndicator";

import { useTabsKeyboard } from "./model/useTabsKeyboard";

interface TabItem<Value extends string> {
  /** 탭을 가리키는 값. 같은 목록 안에서 겹치지 않아요 */
  value: Value;
  /** 탭에 보여줄 글자 */
  label: string;
}

interface TabsProps<Value extends string> {
  /**
   * 탭 요소 id의 앞부분. 탭 하나의 id는 `${id}-${value}`라, 탭이 가리키는 영역(`role="tabpanel"`)이
   * `aria-labelledby`로 이 id를 가리킬 수 있어요
   */
  id: string;
  /** 탭 목록. 적힌 순서대로 왼쪽부터 놓아요 */
  items: readonly TabItem<Value>[];
  /** 지금 고른 탭의 값 */
  value: Value;
  /** 다른 탭을 골랐을 때. 이미 고른 탭을 다시 눌러도 부르지 않아요 */
  onChange: (value: Value) => void;
  /** 탭이 가리키는 영역의 id. 모든 탭의 `aria-controls`가 돼요 */
  panelId: string;
  /** 탭 목록이 무엇을 고르는지 알리는 이름 */
  "aria-label": string;
}

/**
 * 알약 모양 탭 목록. 고른 탭은 진한 알약으로, 나머지는 글자만 보여요.
 *
 * 어느 탭을 골랐는지는 쓰는 쪽이 들고 있어요(`value` · `onChange`). 주소처럼 바깥에 있는 상태가 탭을 정하는 경우가 많아,
 * 이 컴포넌트는 고른 값을 기억하지 않고 그리기와 키보드 조작만 맡아요. 화살표 · Home · End로 옮기면 바로 골라요.
 *
 * 움직임은 GNB 내비 필과 같아요. 진한 알약 하나가 탭 사이를 미끄러지며 물방울처럼 늘어났다가 맺히고,
 * 누르는 동안 탭이 살짝 작아졌다가 떼면 통 튀어 돌아와요. 고른 탭을 다시 누르면 알약도 함께 눌렸다가 제자리에서 맺혀요.
 * 움직임 줄이기를 켜 두면 눌림, 미끄러짐, 물방울 없이 바로 바뀌어요.
 * 탭이 가리키는 영역은 쓰는 쪽이 `role="tabpanel"`로 그리고, `panelId`와 탭 id(`${id}-${value}`)로 서로 이어요.
 */
export default function Tabs<Value extends string>({
  id,
  items,
  value,
  onChange,
  panelId,
  "aria-label": ariaLabel,
}: TabsProps<Value>) {
  const { handleKeyDown } = useTabsKeyboard({
    values: items.map((item) => item.value),
    value,
    onChange,
  });
  const activeTabIndex = items.findIndex((item) => item.value === value);
  const { containerRef, indicatorRef } = useSlidingIndicator<HTMLDivElement>({
    activeTabIndex,
    activeTabSelector: '[aria-selected="true"]',
  });

  return (
    <TabList ref={containerRef} role="tablist" aria-label={ariaLabel}>
      <Indicator
        ref={indicatorRef}
        aria-hidden="true"
        hidden={activeTabIndex === -1}
      />
      {items.map((item) => {
        const isSelected = item.value === value;

        return (
          <Tab
            key={item.value}
            type="button"
            role="tab"
            id={`${id}-${item.value}`}
            data-value={item.value}
            aria-selected={isSelected}
            aria-controls={panelId}
            // 고른 탭만 Tab 키 순서에 두고, 나머지는 화살표로 옮겨요
            tabIndex={isSelected ? 0 : -1}
            $isSelected={isSelected}
            onClick={() => {
              if (!isSelected) onChange(item.value);
            }}
            onKeyDown={handleKeyDown}
          >
            {item.label}
          </Tab>
        );
      })}
    </TabList>
  );
}

/** 피그마 Tabs/문서 목록: 안쪽 여백 4px, 탭 사이 2px, 둥근 끝. 바탕 #F0EEEA는 토큰에 없어 가장 가까운 Neutral/100을 써요 */
const TabList = styled.div`
  position: relative; /* 알약이 이 자리를 기준으로 탭 아래에 놓여요 */
  display: inline-flex;
  /* 세로로 쌓는 부모가 늘려도 탭 묶음은 글자 폭만 차지해요 */
  width: fit-content;
  gap: 0.125rem; /* 2px */
  padding: 0.25rem; /* 4px */
  border-radius: 62.4375rem; /* 999px */
  background-color: ${({ theme }) => theme.neutral[100]};
`;

/**
 * 고른 탭 아래에 깔리는 Neutral/800 알약. 자리와 크기는 `useSlidingIndicator`가 탭을 재서 정해요.
 *
 * 탭이 바뀔 때만(`data-sliding="true"`) 미끄러지고, 처음 놓이거나 탭 크기가 바뀌면 바로 그 자리에 놓여요.
 * 모양(`scale`)은 `useIndicatorDroplet`이 바꿔서 여기서는 전환을 걸지 않아요. CSS 전환은 모양 애니메이션보다 앞서 그려져 둘을 함께 걸면 물방울이 끊겨요.
 */
const Indicator = styled.span`
  position: absolute;
  top: 0;
  left: 0;
  border-radius: 62.4375rem;
  background-color: ${({ theme }) => theme.neutral[800]};
  pointer-events: none;

  @media (prefers-reduced-motion: no-preference) {
    transition-property: translate, width;
    transition-duration: 0s;
    transition-timing-function: ${TAB_ENTER_EASING};

    &[data-sliding="true"] {
      transition-duration: ${TAB_INDICATOR_DURATION_MS}ms;
    }
  }
`;

/** 피그마 Tab: 안쪽 여백 6px 16px, 둥근 끝. 고른 탭은 알약 위에 흰 글자 */
const Tab = styled.button<{ $isSelected: boolean }>`
  position: relative; /* 먼저 놓인 알약보다 위에 글자를 그려요 */
  ${({ theme }) => theme.text.caption02};
  padding: 0.375rem 1rem; /* 6px 16px */
  border-radius: 62.4375rem; /* 999px */
  color: ${({ theme, $isSelected }) =>
    $isSelected ? theme.neutral[0] : theme.neutral[600]};
  white-space: nowrap;
  cursor: pointer;

  /* 글자색은 알약이 지나가는 동안 같은 시간에 바꿔요 */
  @media (prefers-reduced-motion: no-preference) {
    transition-property: color, transform;
    transition-duration:
      ${TAB_INDICATOR_DURATION_MS}ms, ${TAB_RELEASE_DURATION_MS}ms;
    transition-timing-function: ${TAB_ENTER_EASING}, ${TAB_POP_EASING};

    &:active {
      transform: scale(${TAB_PRESS_SCALE});
      transition-duration:
        ${TAB_INDICATOR_DURATION_MS}ms, ${TAB_PRESS_DURATION_MS}ms;
      transition-timing-function: ${TAB_ENTER_EASING}, ${TAB_ENTER_EASING};
    }
  }

  &:hover:not([aria-selected="true"]) {
    color: ${({ theme }) => theme.neutral[800]};
  }

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: 2px;
  }
`;
