import type { Meta, StoryObj } from "@storybook/react-webpack5";

import NotionEvidenceButton from ".";

/**
 * 누르면 탐색 답변에 쓴 노션 페이지가 열리는 버튼이에요. 답변 아래 「기록 N개에서 찾았어요」 버튼 옆에 노션 페이지마다 하나씩 둬요.
 *
 * - 공식 노션 로고, 페이지 제목, 바깥 링크 아이콘 순서로 보여요.
 * - 누르면 그 노션 페이지를 새 탭으로 열어요. 탐색 화면은 닫히지 않아요.
 * - 제목이 답변 폭보다 길면 말줄임으로 줄여 버튼이 답변 밖으로 넘치지 않게 해요.
 */
const meta = {
  title: "Shared/NotionEvidenceButton",
  component: NotionEvidenceButton,
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=6563-7887",
    },
  },
  args: {
    title: "출시 계획",
    href: "https://www.notion.so/knot-team/launch-plan",
  },
} satisfies Meta<typeof NotionEvidenceButton>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 답에 쓴 노션 페이지 하나를 보여줄 때예요. 예: 「기획서 출시일이 회의 결정과 같아?」의 답에 쓴 「출시 계획」 */
export const Default: Story = {};

/** 페이지 제목이 답변 폭(여기서는 240px)보다 길 때예요. 제목 끝을 말줄임으로 줄여요. */
export const LongTitle: Story = {
  args: { title: "2026년 12월 정식 출시 전 배포 체크리스트와 QA 일정 정리" },
  decorators: [
    (Story) => (
      <div style={{ width: "15rem" }}>
        <Story />
      </div>
    ),
  ],
};
