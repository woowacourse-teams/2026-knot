import type { Meta, StoryObj } from "@storybook/react-webpack5";

import { OAUTH_PROVIDERS } from "./constants/oauthProviders";
import OAuthLoginButton from "./ui/OAuthLoginButton";

import OAuthLoginButtonList from ".";

/**
 * GitHub, Google, 카카오 계정으로 로그인을 시작하는 버튼 묶음이에요.
 * 로그인 화면(`/login`)의 가운데와 랜딩의 첫 장면 · 마지막 장면의 시작 버튼 자리에 둡니다.
 *
 * **모양**
 * - 세 버튼 모두 지금 GitHub 버튼의 크기와 모양(`Shared/Button` lg, 가로 꽉 채움)을 그대로 써요. 순서는 GitHub이 맨 위예요.
 * - Google은 흰 바탕에 회색 테두리와 원래 색 「G」, 카카오는 노란 바탕에 검은 말풍선이에요. 각 사 로그인 버튼 가이드가 정한 색이라 서비스 색에 맞춰 바꾸지 않아요.
 * - 문구는 세 버튼 모두 「○○로 시작하기」예요.
 * - Figma에는 GitHub 버튼 하나만 있어요. Google · 카카오 버튼은 따로 디자인하지 않고 각 사 가이드대로 만들었어요(유월 동의, #1063).
 * - 버튼 아래에 「처음 가입한 방법으로 로그인해야 같은 계정이에요」를 둬요. 수단마다 다른 knot 계정이라, 다른 버튼을 누르면 빈 새 계정이 생기기 때문이에요.
 *
 * **동작 규칙**
 * - 누르면 앱 안에서 화면을 바꾸는 것이 아니라 **페이지를 통째로** 그 수단의 로그인 페이지로 옮겨요.
 *   이 주소는 화면에 그릴 응답을 주는 API가 아니라 로그인 페이지로 보내는 리다이렉트라서,
 *   API처럼 부르면 다른 도메인에서 막히고 로그인 쿠키도 제대로 심기지 않기 때문이에요.
 * - 하나를 누르면 넘어갈 때까지 누른 버튼은 처리 중이고, 세 버튼 모두 다시 누를 수 없어요. 로그인 요청이 겹치지 않게 해요.
 * - 로그인 화면에서 뒤로 가기로 돌아오면 처리 중이 풀려 다시 누를 수 있어요.
 * - 10초가 지나도 넘어가지 못하고 이 화면이 남아 있으면 처리 중이 풀려요. 예: 넘어가는 도중에 브라우저의 중지 버튼을 누른 경우
 * - 이동 뒤의 흐름은 백엔드가 정해요. 기존 회원은 접근 토큰 쿠키를 받고,
 *   신규 사용자는 온보딩 토큰 쿠키를 받아 닉네임 입력 화면(`/onboarding`)으로 돌아옵니다.
 * - 서버가 허용한 출처에서만 동작해요. 스토리북과 `localhost`에서는 눌러도 로그인되지 않으니 배포된 주소에서 확인해야 합니다.
 * - 로그인이 실패해 돌아오면 버튼 바로 위에 `Auth/OAuthErrorNotice`가 실패 문구를 띄우고, 이 버튼들로 다시 시도해요. 승인 화면에서 취소하고 돌아오면 아무것도 띄우지 않아요.
 */
const meta = {
  title: "Auth/OAuthLoginButtonList",
  component: OAuthLoginButtonList,
  parameters: {
    design: [
      {
        name: "로그인/기본",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=1788-4727",
      },
      {
        name: "로그인/처리 중",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=3812-39720",
      },
    ],
  },
  decorators: [
    // 로그인 화면의 버튼 영역 너비(최대 360px)에 맞춰 가로를 꽉 채워요
    (Story) => (
      <div style={{ width: "22.5rem" }}>
        <Story />
      </div>
    ),
  ],
} satisfies Meta<typeof OAuthLoginButtonList>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 로그인 화면에서 보이는 모습이에요. 버튼 영역의 가로를 꽉 채웁니다. */
export const Default: Story = {};

/**
 * 「카카오로 시작하기」를 누른 직후, 카카오로 넘어가기 전의 모습이에요.
 * 누른 버튼은 글자 자리에 스피너가 돌고, 나머지 두 버튼은 `Shared/Button`의 비활성 모양이 돼요.
 * 실제 버튼은 누르면 페이지가 넘어가서, 버튼을 같은 상태로 나란히 놓아 보여 줘요.
 */
export const Redirecting: Story = {
  render: () => (
    <div style={{ display: "flex", flexDirection: "column", gap: "0.75rem" }}>
      {OAUTH_PROVIDERS.map(({ provider, label, Icon }) => (
        <OAuthLoginButton
          key={provider}
          provider={provider}
          isLoading={provider === "kakao"}
          disabled
        >
          <Icon />
          {label}
        </OAuthLoginButton>
      ))}
    </div>
  ),
};
