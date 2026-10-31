import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { fn } from "storybook/test";

import PageErrorNotice from ".";

/**
 * 화면 전체를 그리지 못했을 때 그 화면을 대신하는 공통 에러 페이지예요(ERR-01~03, ERR-15).
 *
 * - 로고, 그림, 제목, 설명, 버튼 순서로 놓고 GNB와 독은 두지 않아요.
 * - 문구와 버튼 이름은 `variant`로 정해져요. 어떤 실패인지에 따라 골라요.
 *   - `server`(ERR-03): 서버 오류(5xx)이거나 화면을 그리다 코드 오류가 났을 때. 예: 워크스페이스를 5xx로 불러오지 못했을 때, 앱 맨 바깥의 오류 경계(Error Boundary)가 오류를 잡았을 때
 *   - `network`(ERR-02): 응답을 받지 못했을 때. 네트워크가 끊겼거나 응답이 늦어 시간이 지난 경우예요. 예: 워크스페이스를 불러오다 연결이 끊겼을 때
 *   - `badRequest`(ERR-01): 없는 주소이거나 더 이상 유효하지 않은 요청일 때. 예: 주소창에 잘못된 주소를 입력했을 때
 *   - `loginFailed`(ERR-15): GitHub, Google, 카카오 로그인 처리가 실패했거나 원인을 알 수 없을 때. 예: 백엔드가 `/login?error=oauth2`로 돌려보냈을 때. 승인 화면에서 취소한 경우에는 쓰지 않아요
 * - `다시 시도`(ERR-02, ERR-03)는 페이지를 새로고침하고, `홈으로 이동`(ERR-01)은 홈 경로로, `로그인 화면으로`(ERR-15)는 로그인 화면으로 보내요. 동작은 쓰는 쪽이 넘겨요.
 * - 인증이 풀린 401에는 쓰지 않고 로그인 화면으로 보내요.
 * - 나타나자마자 스크린리더가 문구를 읽어요. 버튼에도 제목과 설명이 설명으로 이어져, 버튼으로 바로 가도 무슨 일인지 들을 수 있어요.
 */
const meta = {
  title: "Shared/PageErrorNotice",
  component: PageErrorNotice,
  parameters: {
    layout: "fullscreen",
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=1788-4849",
    },
  },
  argTypes: {
    variant: {
      control: "select",
      options: ["server", "network", "badRequest", "loginFailed"],
    },
  },
  args: { variant: "server", onAction: fn() },
} satisfies Meta<typeof PageErrorNotice>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 서버 오류나 코드 오류로 화면을 그리지 못했을 때예요(ERR-03). 예: 워크스페이스 조회가 5xx로 실패했을 때 */
export const Default: Story = {};

/** 응답을 받지 못했을 때예요(ERR-02). 예: 워크스페이스를 불러오다 네트워크가 끊겼을 때 */
export const Network: Story = {
  args: { variant: "network" },
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=1788-4838",
    },
  },
};

/** 없는 주소로 들어왔을 때예요(ERR-01). 예: `/workspace/1/foo`처럼 정의되지 않은 주소 */
export const BadRequest: Story = {
  args: { variant: "badRequest" },
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=1788-4827",
    },
  },
};

/** 로그인 처리가 실패해 돌아왔을 때예요(ERR-15). 예: 백엔드가 `/login?error=oauth2`로 돌려보냈을 때 */
export const LoginFailed: Story = {
  args: { variant: "loginFailed" },
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=3595-13883",
    },
  },
};
