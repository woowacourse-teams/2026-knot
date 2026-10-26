import styled from "@emotion/styled";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { TOAST_MESSAGES } from "@constants/toastMessages";

import HeadphonesIcon from "@/assets/icons/headphones.svg";

import Toast from ".";

/**
 * 잠깐 떠서 일의 결과를 알리는 토스트예요. 화면 위에 떠서 하던 일을 막지 않아요.
 *
 * - 종류는 `variant`로 골라요. 종류에 따라 배경색과 아이콘만 바뀌어요.
 * - 종류의 아이콘이 안내에 맞지 않으면 `icon`으로 아이콘만 바꿔요. 배경색은 종류를 따라요.
 *   - `success`(정상) : 방금 한 일의 결과를 알릴 때
 *   - `caution`(주의) : 성공도 실패도 아닐 때. 예: 연결이 끊겨 녹음이 중간에 끝남
 *   - `error`(오류) : 내가 한 동작이 실패했고 다시 하면 될 때
 * - 글자 길이만큼 폭이 늘어나다가, 놓인 자리의 폭(화면에서는 독 폭)을 넘으면 줄을 바꿔요. 아이콘은 첫 줄에 맞춰요.
 * - 이 컴포넌트는 겉모양만 그려요. 띄우는 위치·사라지는 시간·여러 개 쌓기는 다루지 않아요.
 */
const meta = {
  title: "Shared/Toast",
  component: Toast,
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=1783-13216",
    },
  },
  argTypes: {
    variant: {
      control: "inline-radio",
      options: ["success", "caution", "error"],
    },
  },
  args: TOAST_MESSAGES.ALL_DOCUMENTS_CONFIRMED,
} satisfies Meta<typeof Toast>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 방금 한 일의 결과를 알릴 때 써요. 예: 녹음 직후 문서를 모두 확인함 */
export const Success: Story = {};

/** 성공도 실패도 아닌 결과를 알릴 때 써요. 예: 연결이 끊겨 녹음이 중간에 끝남 */
export const Caution: Story = {
  args: TOAST_MESSAGES.RECORDING_CONNECTION_LOST,
};

/** 내가 한 동작이 실패했고 다시 하면 될 때 써요. 예: 탐색 질문 전송 실패 */
export const Error: Story = {
  args: TOAST_MESSAGES.QUESTION_SEND_FAILED,
};

/** 종류의 기본 아이콘 대신 안내에 맞는 아이콘을 쓸 때예요. 예: 화상 회의를 연결한 뒤 이어폰을 권하는 안내 */
export const CustomIcon: Story = {
  args: { ...TOAST_MESSAGES.MEETING_AUDIO_CONNECTED, icon: HeadphonesIcon },
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5466-64810",
    },
  },
};

/** 놓인 자리보다 긴 문구예요. 폭을 넘으면 줄을 바꾸고 아이콘은 첫 줄에 맞춰요. 예: 최대 녹음 시간 안내 */
export const LongMessage: Story = {
  decorators: [
    (Story) => (
      <NarrowSpace>
        <Story />
      </NarrowSpace>
    ),
  ],
  args: TOAST_MESSAGES.RECORDING_TIME_LIMIT_APPROACHING,
};

// 가장 긴 실제 문구도 독 폭(최대 760px)에는 한 줄로 들어가서, 좁은 화면의 독 폭으로 줄여 줄바꿈을 보여 줘요
const NarrowSpace = styled.div`
  width: 20rem; /* 320px */
`;
