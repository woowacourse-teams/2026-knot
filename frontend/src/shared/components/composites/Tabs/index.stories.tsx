import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { useState } from "react";

import Tabs from ".";

const ITEMS = [
  { value: "recording", label: "녹음별" },
  { value: "topic", label: "주제별" },
] as const;

type TabValue = (typeof ITEMS)[number]["value"];

/**
 * 알약 모양 탭 목록이에요. 고른 탭은 진한 알약, 나머지는 글자만 보여요.
 *
 * **동작 규칙**
 * - 어느 탭을 골랐는지는 쓰는 쪽이 들고 있어요. 탭을 누르면 `onChange`로 알리고, 이미 고른 탭을 다시 눌러도 알리지 않아요.
 * - 고른 탭만 Tab 키 순서에 들어가요. 탭에 포커스가 있을 때 왼쪽 · 오른쪽 화살표로 옆 탭, Home · End로 처음 · 끝 탭으로 옮기고 바로 골라요. 양 끝에서는 반대쪽 끝으로 이어져요.
 * - 탭이 가리키는 영역은 쓰는 쪽이 `role="tabpanel"`로 그리고, `panelId`와 탭 id(`${id}-${value}`)로 서로 이어요.
 * - 움직임은 GNB 내비 필과 같아요. 진한 알약 하나가 탭 사이를 미끄러지며 살짝 늘어났다가 도착해 물방울처럼 맺히고, 누르는 동안 탭이 0.96배로 작아졌다가 떼면 튀어 돌아와요.
 * - 고른 탭을 다시 누르면 알약도 함께 눌렸다가 제자리에서 맺히고, 탭은 그대로예요. 움직임 줄이기를 켜 두면 모션 없이 바로 바뀌어요.
 */
const meta = {
  title: "Composites/Tabs",
  component: Tabs,
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5326-1712",
    },
  },
  args: {
    id: "story-tabs",
    items: ITEMS,
    value: "recording",
    panelId: "story-tabs-panel",
    "aria-label": "문서 보기 방식",
    onChange: () => {},
  },
  render: function Render(args) {
    const [value, setValue] = useState<TabValue>(args.value as TabValue);

    return (
      <>
        <Tabs {...args} value={value} onChange={setValue} />
        <p
          id={args.panelId}
          role="tabpanel"
          aria-labelledby={`${args.id}-${value}`}
          style={{ marginTop: 16 }}
        >
          {ITEMS.find((item) => item.value === value)?.label} 내용
        </p>
      </>
    );
  },
} satisfies Meta<typeof Tabs<TabValue>>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 첫 탭을 고른 기본 모습이에요. 탭을 누르거나 화살표로 옮겨 보세요 */
export const Default: Story = {};

/** 두 번째 탭을 고른 모습이에요 */
export const SecondSelected: Story = {
  args: { value: "topic" },
};
