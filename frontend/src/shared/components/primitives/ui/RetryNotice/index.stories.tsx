import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { fn } from "storybook/test";

import RetryNotice from ".";

/**
 * 잠깐의 실패를 알리고 다시 시도하게 하는 안내예요. 화면이나 상태 하나를 통째로 그리는 데 필요한 요청이 실패했을 때 그 자리에 씁니다.
 * 예: 녹음 저장 상태를 확인하지 못했을 때(정리 중 화면 대신), 로그인 상태를 확인하지 못했을 때, 첫 화면을 준비하지 못했을 때
 *
 * - 모양은 `InlineError`의 가운데 정렬 · 기본 크기와 같아요. 경고 아이콘 옆에 문구 한 줄, 그 아래 「다시 시도」예요.
 * - 경고색은 아이콘에만 쓰고 문구는 본문 색이에요.
 * - 네트워크나 서버 사정처럼 다시 시도하면 될 수도 있는 실패에 써요.
 * - 인증이 풀린 401처럼 다시 시도해도 결과가 같은 실패에는 쓰지 않고 로그인 화면으로 보내요.
 * - 화면 안 한 영역만 실패했을 때는 `InlineError`를 써요.
 * - 나타나자마자 스크린리더가 문구를 읽어요.
 */
const meta = {
  title: "Shared/RetryNotice",
  component: RetryNotice,
  args: {
    message: "녹음 저장 상태를 확인하지 못했어요. 잠시 후 다시 시도해 주세요.",
    onRetry: fn(),
  },
  parameters: {
    design: [
      {
        name: "State/RetryNotice",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=4838-1799",
      },
      {
        name: "문서/녹음 저장 상태 확인 실패",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=4840-23999",
      },
    ],
  },
} satisfies Meta<typeof RetryNotice>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 요청이 실패했을 때예요. 예: 끝낸 녹음의 저장 상태를 확인하지 못했을 때 */
export const Default: Story = {};
