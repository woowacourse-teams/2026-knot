import type { Meta, StoryObj } from "@storybook/react-webpack5";
import type { ChangeEvent } from "react";
import { useArgs } from "storybook/preview-api";

import CheckIcon from "@/assets/icons/check.svg";
import Spinner from "@primitives/ui/Spinner";
import { theme } from "@/shared/provider/themeProvider";

import TextField from ".";

/**
 * 에러·성공 메시지까지 함께 다루는 입력 필드예요. 검증이 필요한 입력에 씁니다.
 * 예: 워크스페이스 이름 입력, 초대 코드 입력
 *
 * **상태는 어떻게 정해지나**
 * - 따로 상태를 넘기지 않고 값과 메시지로 정해져요.
 * - 값이 비었으면 입력 전, 값이 있으면 입력 중 모양이에요.
 * - `errorMessage`를 넘기면 입력창이 에러 색이 되고 아래에 메시지가 생겨요.
 * - `successMessage`를 넘기면 입력창이 성공 색이 되고 아래에 메시지가 생겨요.
 * - 둘 다 넘기면 에러가 이겨요.
 *
 * **동작 규칙**
 * - 보이는 메시지는 입력창과 연결돼서 스크린리더가 입력창에 들어갈 때 함께 읽어요.
 * - `rightComponent`로 입력창 오른쪽에 스피너·아이콘 같은 표시를 붙일 수 있어요.
 * - 바깥 `label`과 연결해야 할 때만 `id`를 넘기면 돼요. 넘기지 않으면 알아서 만들어요.
 * - 모양(`variant`)은 `Shared/Input`과 같아요.
 */
const meta = {
  title: "Shared/TextField",
  component: TextField,
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=424-596",
    },
  },
  args: {
    value: "",
    variant: "text",
    placeholder: "예시: knot",
    maxLength: 20,
    "aria-label": "워크스페이스 이름",
  },
  argTypes: {
    variant: { control: "inline-radio", options: ["text", "code", "copy"] },
  },
  decorators: [
    (Story) => (
      <div style={{ width: "22.5rem" }}>
        <Story />
      </div>
    ),
  ],
  // 리뷰어가 직접 입력해 볼 수 있게, 입력한 값을 스토리 args에 다시 넣어요
  render: function Render(args) {
    const [, updateArgs] = useArgs<typeof args>();
    const handleChange = (event: ChangeEvent<HTMLInputElement>) => {
      updateArgs({ value: event.target.value });
    };

    return <TextField {...args} onChange={handleChange} />;
  },
} satisfies Meta<typeof TextField>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 아직 입력하지 않은 처음 모양이에요. 입력해 보면 입력 중 모양으로 바뀌어요. */
export const Empty: Story = {};

/** 값이 들어 있을 때예요. */
export const Filled: Story = {
  args: { value: "knot" },
};

/** 유효하지 않은 값이에요. 예: 워크스페이스 이름에 쓸 수 없는 문자를 넣었을 때 */
export const WithError: Story = {
  args: {
    value: "knot!",
    errorMessage: "한글, 영어, 숫자와 공백만 사용할 수 있어요.",
  },
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=432-1325",
    },
  },
};

/** 초대 코드를 입력하는 모양이에요. */
export const Code: Story = {
  args: {
    variant: "code",
    placeholder: "코드를 입력하세요",
    maxLength: 6,
    "aria-label": "참여 코드",
  },
};

/** 입력한 값을 확인하는 중이에요. 입력을 잠그고 오른쪽에 스피너를 보여줘요. 예: 초대 코드 확인 중 */
export const Verifying: Story = {
  args: {
    ...Code.args,
    value: "AB12CD",
    readOnly: true,
    "aria-busy": true,
    rightComponent: <Spinner />,
  },
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=627-2967",
    },
  },
};

/** 검증을 통과했을 때예요. 성공 색과 메시지를 보여줘요. 예: 확인된 초대 코드 */
export const Verified: Story = {
  args: {
    ...Code.args,
    value: "AB12CD",
    readOnly: true,
    successMessage: "확인됐어요. 곧 다음 단계로 이동해요.",
    rightComponent: (
      <CheckIcon size={20} style={{ color: theme.sub.accent[500] }} />
    ),
  },
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=664-552",
    },
  },
};

/** 에러와 성공 메시지를 함께 넘긴 경우예요. 에러가 이겨요. */
export const ErrorOverSuccess: Story = {
  args: {
    ...Code.args,
    value: "AB12CD",
    errorMessage: "올바르지 않은 코드예요. 다시 확인해 주세요.",
    successMessage: "확인됐어요. 곧 다음 단계로 이동해요.",
  },
};
