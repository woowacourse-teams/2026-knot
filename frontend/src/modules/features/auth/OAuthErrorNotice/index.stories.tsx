import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { MemoryRouter } from "react-router";

import OAuthErrorNotice from ".";

/**
 * GitHub, Google, 카카오 로그인이 실패해 돌아왔을 때 그 사실을 알리는 문구예요. 로그인 화면(`/login`)의 로그인 버튼 바로 위에 둡니다.
 *
 * **언제 보이나**
 * - 백엔드는 로그인 처리에 실패하면 로그인 화면 주소에 `?error=oauth2`를 붙여 돌려보내요. 이때만 문구가 보입니다.
 * - 그 수단의 승인 화면에서 취소하고 돌아오면 `?error=oauth2_cancelled`가 붙어요. 사용자가 고른 일이라 알리지 않습니다(AUTH-R16).
 * - 그 밖에는 아무것도 그리지 않아요. 로그인 화면의 여백이 평소 그대로 유지됩니다.
 *
 * **동작 규칙**
 * - 실패 사유는 사용자가 손쓸 수 있는 것이 아니라서 구분하지 않고 한 문구로 알려요.
 *   다시 시도는 바로 아래의 로그인 버튼으로 합니다.
 * - 문구가 보일 때만 아래 간격(12px)도 함께 차지해요. 화면 쪽에서 간격을 따로 두면 문구가 없을 때도 빈 자리가 남기 때문이에요.
 */
const meta = {
  title: "Auth/OAuthErrorNotice",
  component: OAuthErrorNotice,
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=414-7",
    },
  },
  decorators: [
    // 로그인 화면의 버튼 영역 너비(최대 360px)에 맞춰요
    (Story) => (
      <div style={{ width: "22.5rem" }}>
        <Story />
      </div>
    ),
  ],
} satisfies Meta<typeof OAuthErrorNotice>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 로그인에 실패해 `/login?error=oauth2`로 돌아온 경우예요. */
export const Failed: Story = {
  decorators: [
    (Story) => (
      <MemoryRouter initialEntries={["/login?error=oauth2"]}>
        <Story />
      </MemoryRouter>
    ),
  ],
};

/** 처음 들어온 로그인 화면(`/login`)이에요. 아무것도 그리지 않아 빈 화면이 정상입니다. */
export const Hidden: Story = {
  decorators: [
    (Story) => (
      <MemoryRouter initialEntries={["/login"]}>
        <Story />
      </MemoryRouter>
    ),
  ],
};
