import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { MemoryRouter } from "react-router";

import FileIcon from "@/assets/icons/file.svg";
import MicIcon from "@/assets/icons/mic.svg";

import RelatedCard from ".";

/**
 * 다른 화면으로 이어지는 흰 카드예요. 아이콘 · 제목 · 화살표 아래에 한 줄 설명을 두고, 누르면 그 화면으로 가요.
 * 예: 녹음 문서의 「이 회의에서 나온 문서」(문서 카드), 문서 보기의 「이 문서가 나온 녹음」(녹음 카드)
 *
 * - 아이콘으로 어디로 가는지 구분해요. 문서로 가면 파일 아이콘, 녹음으로 가면 마이크 아이콘이에요.
 * - 제목과 설명은 한 줄을 넘으면 말줄임해요. 설명이 없으면 제목만 있어요.
 * - 폭은 놓이는 자리가 정해요. 카드는 그 폭을 채워요.
 */
const meta = {
  title: "Shared/RelatedCard",
  component: RelatedCard,
  args: {
    href: "/workspace/1/documents/1",
    icon: <FileIcon size={16} aria-hidden />,
    title: "회원 탈퇴 정책",
    description: "탈퇴한 사용자의 게시글은 유지하고 작성자를 바꿔 표시해요.",
  },
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5374-1752",
    },
  },
  decorators: [
    (Story) => (
      <MemoryRouter>
        <div style={{ maxWidth: "21.875rem" }}>
          <Story />
        </div>
      </MemoryRouter>
    ),
  ],
} satisfies Meta<typeof RelatedCard>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 문서로 가는 카드예요. 예: 녹음 문서의 「이 회의에서 나온 문서」 */
export const Default: Story = {};

/** 녹음으로 가는 카드예요. 예: 문서 보기의 「이 문서가 나온 녹음」 */
export const Recording: Story = {
  args: {
    href: "/workspace/1/recordings/42/document",
    icon: <MicIcon size={16} aria-hidden />,
    title: "회원 탈퇴 정책 논의",
    description: "회의 전체 요약, 원문, 녹음 음성을 볼 수 있어요.",
  },
};

/** 설명이 없을 때예요. 예: 한 줄 요약이 없는 문서 */
export const WithoutDescription: Story = {
  args: { description: null },
};

/** 제목이 길 때예요. 한 줄에서 말줄임해요 */
export const LongTitle: Story = {
  args: {
    icon: <MicIcon size={16} aria-hidden />,
    title:
      "회원 정책과 탈퇴 이후 데이터 보관 기간과 복구 절차를 정한 주간 회의",
    description: "회의 전체 요약, 원문, 녹음 음성을 볼 수 있어요.",
  },
};
