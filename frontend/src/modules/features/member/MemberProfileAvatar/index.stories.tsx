import { AUTH_ME_API_PATH } from "@api/fetch/api/v1/auth/me";
import { meResponse } from "@api/mock/responses/auth";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { http, HttpResponse } from "msw";

import MemberProfileAvatar from ".";

/**
 * 로그인한 회원의 프로필 아바타예요. GNB 오른쪽에 놓여요.
 *
 * **동작 규칙**
 * - 로그인한 수단(GitHub, Google, 카카오)에서 받아 둔 프로필 이미지를 그려요.
 * - 사진이 없는 회원이거나 이미지를 불러오지 못하면 닉네임 첫 글자로 대신해요. 이것이 기본 프로필 이미지예요.
 *   예: 카카오에서 사진 제공에 동의하지 않은 회원
 * - 회원 정보를 받아 오기 전에는 닉네임도 없어 기본 사람 모양을 그려요.
 * - 지금은 보여 주기만 하고 누르는 동작은 없어요.
 */
const meta = {
  title: "Member/MemberProfileAvatar",
  component: MemberProfileAvatar,
  parameters: {
    layout: "centered",
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=587-516",
    },
  },
} satisfies Meta<typeof MemberProfileAvatar>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 프로필 이미지가 있는 회원이에요. */
export const Default: Story = {};

/** 프로필 사진이 없는 회원이에요. 예: 카카오에서 사진 제공에 동의하지 않고 가입한 회원 */
export const WithoutPhoto: Story = {
  parameters: {
    // 문서 페이지에서는 스토리를 한 화면에 함께 그려 응답이 섞이므로, 이 스토리는 따로 그려요
    docs: { story: { inline: false, iframeHeight: 80 } },
    msw: {
      handlers: {
        me: http.get(`*${AUTH_ME_API_PATH}`, () =>
          HttpResponse.json({ ...meResponse, profileImageUrl: null }),
        ),
      },
    },
  },
};
