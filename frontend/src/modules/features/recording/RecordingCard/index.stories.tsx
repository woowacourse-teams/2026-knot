import { AUTH_ME_API_PATH } from "@api/fetch/api/v1/auth/me";
import { meResponse } from "@api/mock/responses/auth";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { delay, http, HttpResponse } from "msw";

import RecordingCard from ".";

/**
 * 녹음 화면(`/workspace/:workspaceId/recording`)에서 녹음 조작 바 아래에 놓는 카드예요.
 *
 * **무엇을 보여 주나**
 * - 제목 자리에 회의 제목 대신 `{시작한 사람} 님의 녹음`을 보여 줘요. 문서 제목은 녹음이 끝난 뒤 주제별로 자동으로 붙기 때문이에요.
 * - 아래에는 녹음이 어떻게 이어지는지 안내 두 줄을 둬요. 다른 화면으로 옮겨 가도 녹음이 계속되고, 끝내면 문서로 정리된다는 내용입니다.
 *
 * **동작 규칙**
 * - 닉네임은 로그인한 회원 정보(`GET /auth/me`)에서 와요. 녹음 화면에서는 진행 중인 녹음이 언제나 본인 것이라 시작한 사람이 곧 로그인한 회원이에요.
 * - 정보가 오기 전이나 받지 못했을 때는 `녹음`만 보여 자리를 지켜요. 이름이 비어도 녹음은 그대로 이어지므로 따로 안내하지 않아요.
 */
const meta = {
  title: "Recording/RecordingCard",
  component: RecordingCard,
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2241-530",
    },
  },
  decorators: [
    // 녹음 화면의 가운데 열 너비(최대 960px)에 맞춰요
    (Story) => (
      <div style={{ width: "60rem" }}>
        <Story />
      </div>
    ),
  ],
} satisfies Meta<typeof RecordingCard>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 녹음이 진행 중일 때 녹음 화면에서 보이는 모습이에요. 회원 정보를 받아 닉네임까지 채워요. */
export const Default: Story = {};

/** 회원 정보를 받아 오는 중이에요. 닉네임 없이 `녹음`만 보여요. */
export const Loading: Story = {
  parameters: {
    msw: {
      handlers: {
        me: http.get(`*${AUTH_ME_API_PATH}`, async () => {
          await delay("infinite");
          return HttpResponse.json(meResponse);
        }),
      },
    },
  },
};
