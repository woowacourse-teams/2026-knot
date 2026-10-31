import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { fn } from "storybook/test";

import UpdatePreparingNotice from ".";

/**
 * 서버가 아직 지원하지 않는 신규 기능 자리에 놓는 안내예요. 기존 화면은 그대로 읽을 수 있어요.
 * 「다시 확인」은 화면 전체를 새로고침하지 않고 해당 기능의 지원 여부를 다시 조회해요.
 * 기존 버튼과 중립 색상으로 구성했어요.
 */
const meta = {
  title: "Shared/UpdatePreparingNotice",
  component: UpdatePreparingNotice,
  args: { onCheck: fn() },
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=511-284",
    },
  },
} satisfies Meta<typeof UpdatePreparingNotice>;

export default meta;
type Story = StoryObj<typeof meta>;

/** 신규 조회 API의 미지원이 확인되면 그 기능 자리에 표시해요. */
export const Default: Story = {};
