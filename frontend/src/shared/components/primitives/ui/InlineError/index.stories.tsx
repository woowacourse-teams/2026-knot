import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { fn } from "storybook/test";

import InlineError from ".";

/**
 * 화면 안 한 영역이나 한 동작만 실패했을 때, 그 내용이 있어야 할 자리에 남기는 안내예요.
 * 예: 문서 목록을 불러오지 못했을 때, 탐색 답변을 만들지 못했을 때
 *
 * - 실패한 자리에 들어가고, 사용자가 다시 시도할 때까지 남아요.
 * - 가로 정렬은 `align`으로 골라요. 그 자리의 빈 상태 안내와 같은 정렬을 따라요.
 *   - `center` : 영역 전체를 대신할 때. 예: 홈 카드 네 종, 문서 목록
 *   - `start`(기본값) : 글 흐름 안에 들어갈 때. 예: 탐색 답변, 원문 패널
 * - 크기는 `size`로 골라요. 폭이 좁은 자리에는 `sm`을 쓰고 문구도 짧게 줄여요. 예: 사이드바의 문서 목록
 * - 다시 시도할 수 없는 실패면 `onRetry`를 넘기지 않아요. 그때는 「다시 시도」 없이 문구만 남아요. 예: 녹음 문서에서 다시 만들 수 없는 회의 전체 요약
 * - 같은 실패를 토스트로 또 알리지 않아요.
 * - 화면 전체가 막히는 실패(로그인 확인·첫 화면 준비)에는 `RetryNotice`를 써요. `InlineError`는 `center`로 두어도 화면 안 한 영역의 실패에만 써요.
 * - 나타나자마자 스크린리더가 문구를 읽어요.
 */
const meta = {
  title: "Shared/InlineError",
  component: InlineError,
  args: {
    message: "목록을 불러오지 못했어요. 다시 시도해 주세요.",
    onRetry: fn(),
  },
  argTypes: {
    align: { control: "inline-radio", options: ["start", "center"] },
    size: { control: "inline-radio", options: ["md", "sm"] },
  },
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2243-32815",
    },
  },
} satisfies Meta<typeof InlineError>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 기본 정렬(`start`)이에요. 문구와 「다시 시도」를 왼쪽에 맞추고, 글 흐름 안에 들어갈 때 써요 */
export const Default: Story = {};

/** 영역 전체를 대신할 때예요. 예: 문서 목록을 불러오지 못했을 때, 홈의 최근 추가된 문서 카드가 문서를 불러오지 못했을 때 */
export const Centered: Story = {
  args: { align: "center" },
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=3892-15512",
    },
  },
};

/** 탐색에서 답변을 만들지 못했을 때, 답변 자리에 들어가요 */
export const AnswerFailed: Story = {
  args: {
    message: "답변을 만들지 못했어요. 잠시 후 다시 시도해 주세요.",
  },
};

/** 폭이 좁은 자리에 쓰는 작은 안내예요. 아이콘 · 문구 · 「다시 시도」가 모두 한 단계 작아요. 예: 사이드바의 문서 목록을 불러오지 못했을 때 */
export const Small: Story = {
  args: {
    size: "sm",
    align: "center",
    message: "목록을 불러오지 못했어요",
  },
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=3997-990",
    },
  },
};

/** 다시 시도할 수 없는 실패예요. 「다시 시도」 없이 문구만 남아요. 예: 녹음 문서에서 회의 전체 요약을 다시 만들 수 없을 때 */
export const WithoutRetry: Story = {
  args: {
    align: "center",
    message: "요약을 만들지 못했어요",
    onRetry: undefined,
  },
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5371-1807",
    },
  },
};
