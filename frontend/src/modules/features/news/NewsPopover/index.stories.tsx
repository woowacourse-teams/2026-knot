import styled from "@emotion/styled";
import FeedbackPanel from "@features/feedback/FeedbackPanel";
import { ToastProvider } from "@provider/context/toastContext";
import { PATH_ROUTE } from "@routes/PATH_ROUTE";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { MemoryRouter, Route, Routes } from "react-router";
import { userEvent, within } from "storybook/test";

import NewsPopover from ".";

import { LAST_SEEN_NOTE_ID_KEY } from "./constants/lastSeenNote";

/** GNB 오른쪽 끝처럼 버튼을 오른쪽 위에 두고, 아래로 열리는 패널이 들어갈 높이를 둬요 */
const Stage = styled.div`
  display: flex;
  align-items: flex-start;
  justify-content: flex-end;
  height: 32rem; /* 512px */
  padding: 1rem 1.5rem; /* 16px 24px */
  background-color: ${({ theme }) => theme.neutral[50]};
`;

/** 버튼을 눌러 메뉴를 열어요 */
const openMenu = async (canvasElement: HTMLElement) => {
  await userEvent.click(
    within(canvasElement).getByRole("button", { name: "새 소식과 피드백" }),
  );
};

/** 메뉴를 열고 항목 하나를 골라요. 메뉴는 body에 그려져 화면 전체에서 찾아요 */
const selectMenuItem = async (
  canvasElement: HTMLElement,
  name: "새 소식" | "피드백 보내기",
) => {
  await openMenu(canvasElement);
  await userEvent.click(
    within(canvasElement.ownerDocument.body).getByRole("button", { name }),
  );
};

/**
 * Knot 서비스의 새 소식(릴리즈 노트)과 피드백 보내기를 여는 「소식과 피드백」 버튼이에요. GNB 오른쪽, 프로필 바로 왼쪽에 있어요.
 *
 * **동작 규칙**
 * - 버튼을 누르면 버튼 8px 아래에 메뉴(새 소식, 피드백 보내기)를 오른쪽 끝을 맞춰 열어요. 고르면 메뉴가 닫히고 같은 자리에 그 패널이 열려요.
 * - 버튼을 다시 누르거나, 바깥을 누르거나, ESC를 누르면 닫아요. 메뉴나 패널 안을 눌러서는 닫히지 않아요. 닫히면 버튼으로 포커스가 돌아와요.
 * - 피드백을 보내는 동안에는 바깥 · ESC · 버튼으로 닫히지 않아요.
 * - 열려 있는 동안 버튼은 채워진 모양이에요. 닫혀 있을 때 마우스를 올리거나 키보드로 포커스하면 툴팁 「새 소식과 피드백」이 떠요.
 * - 이 기기에서 새 소식을 한 번도 열지 않았으면 처음 그릴 때 메뉴 없이 새 소식 패널을 저절로 열어요. 녹음 중이거나 노트가 없으면 열지 않아요.
 * - 새 소식 패널이 열리면(저절로든 메뉴에서 골라서든) 맨 위 노트를 읽음으로 남겨 다음부터는 저절로 열지 않아요.
 * - 휴대폰에서는 피드백을 사이드바에서 열어서, 버튼을 누르면 메뉴 없이 새 소식 패널을 바로 열어요.
 *
 * **패널 내용**
 * - 노트는 기획자가 릴리즈 직전에 `shared/constants/releaseNote`에 써서 배포와 함께 내요. 서버에서 받아 오지 않아요.
 * - 노트를 배포 날짜별(「10월 20일」)로 묶고 최신 날짜를 맨 위에 둬요. 같은 날 두 번 릴리즈하면 한 묶음에 모아요.
 * - 항목은 제목과 설명을 보여 주고, 이미지와 「바로 가보기」는 노트에 있을 때만 보여 줘요. 「바로 가보기」는 지금 보고 있는 워크스페이스의 그 화면으로 옮겨요.
 * - 사용자가 알려 준 문제를 고친 항목에는 「피드백 덕분에 바뀌었어요」 칩을 붙여요.
 * - 화면 높이를 넘으면 머리는 그대로 두고 목록만 패널 안에서 스크롤해요.
 */
const meta = {
  title: "News/NewsPopover",
  component: NewsPopover,
  parameters: {
    design: [
      {
        name: "진입점 (소식과 피드백 메뉴)",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=4694-47946",
      },
      {
        name: "Btn/소식과 피드백",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=4696-29512",
      },
      {
        name: "새 소식 컴포넌트와 상태",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=4696-29495",
      },
    ],
    // 열린 패널은 body에 그려져서, 문서 페이지에서 스토리마다 따로 그려야 서로 겹치지 않아요
    docs: { story: { inline: false, iframeHeight: 640 } },
  },
  args: {
    renderFeedbackPanel: ({ onClose }) => <FeedbackPanel onClose={onClose} />,
  },
  decorators: [
    // 「바로 가보기」로 옮겨도 GNB처럼 버튼과 패널이 그대로 남아요
    (Story) => (
      <MemoryRouter initialEntries={["/workspace/1"]}>
        {/* 피드백을 보내면 토스트를 띄워요 */}
        <ToastProvider>
          <Routes>
            <Route path={PATH_ROUTE.WORKSPACE_HOME} element={<Story />} />
            <Route path={PATH_ROUTE.CHAT} element={<Story />} />
          </Routes>
        </ToastProvider>
      </MemoryRouter>
    ),
  ],
  // 새 소식을 연 기록과 적던 피드백은 브라우저에 남아 스토리끼리 이어지므로, 이미 본 사용자와 빈 피드백으로 시작해요
  beforeEach: () => {
    localStorage.setItem(LAST_SEEN_NOTE_ID_KEY, "v0.1.0");
    sessionStorage.clear();
  },
  render: (args) => (
    <Stage>
      <NewsPopover {...args} />
    </Stage>
  ),
} satisfies Meta<typeof NewsPopover>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 처음에는 버튼만 보여요. 눌러서 메뉴를 열어 봐요 */
export const Default: Story = {};

/** 버튼을 눌러 메뉴를 연 모습이에요. 「새 소식」과 「피드백 보내기」 중 하나를 골라요 */
export const Menu: Story = {
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=4888-24607",
    },
  },
  play: async ({ canvasElement }) => {
    await openMenu(canvasElement);
  },
};

/** 이 기기에서 새 소식을 처음 확인하는 사용자예요. 누르지 않아도 메뉴 없이 새 소식 패널이 열려 있어요 */
export const FirstVisit: Story = {
  beforeEach: () => {
    localStorage.removeItem(LAST_SEEN_NOTE_ID_KEY);
  },
};

/** 지금까지 낸 노트를 날짜별로 보여 줘요. 예: 「탐색」 항목의 「바로 가보기」, 마이크 권한 항목의 피드백 칩 */
export const NoteList: Story = {
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=4892-55099",
    },
  },
  play: async ({ canvasElement }) => {
    await selectMenuItem(canvasElement, "새 소식");
  },
};

/** 아직 낸 노트가 하나도 없을 때예요 */
export const Empty: Story = {
  args: { notes: [] },
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=4892-55258",
    },
  },
  play: async ({ canvasElement }) => {
    await selectMenuItem(canvasElement, "새 소식");
  },
};

/** 메뉴에서 「피드백 보내기」를 골라 같은 자리에 피드백 패널을 연 모습이에요. 패널의 상태는 「Feedback/FeedbackPanel」에 있어요 */
export const Feedback: Story = {
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=4694-48079",
    },
  },
  play: async ({ canvasElement }) => {
    await selectMenuItem(canvasElement, "피드백 보내기");
  },
};
