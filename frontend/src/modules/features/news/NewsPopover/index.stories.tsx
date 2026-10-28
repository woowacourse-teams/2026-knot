import { RELEASE_NOTES_RELAY_PATH } from "@api/fetch/relay/n";
import {
  imageReleaseNoteItems,
  releaseNotesResponse,
} from "@api/mock/responses/releaseNote";
import styled from "@emotion/styled";
import FeedbackPanel from "@features/feedback/FeedbackPanel";
import { ToastProvider } from "@provider/context/toastContext";
import { PATH_ROUTE } from "@routes/PATH_ROUTE";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { http, HttpResponse } from "msw";
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

/** 버튼을 눌러 메뉴를 열어요. 안 읽은 소식이 있으면 버튼 이름 뒤에 안내가 붙어 앞부분으로 찾아요 */
const openMenu = async (canvasElement: HTMLElement) => {
  await userEvent.click(
    await within(canvasElement).findByRole("button", {
      name: /^새 소식과 피드백/,
    }),
  );
};

/**
 * 메뉴를 열고 항목 하나를 골라요. 메뉴는 body에 그려져 화면 전체에서 찾아요.
 * 안 읽은 소식이 있으면 「새 소식」 이름 뒤에 NEW가 붙어 앞부분으로 찾아요
 */
const selectMenuItem = async (
  canvasElement: HTMLElement,
  name: "새 소식" | "피드백 보내기",
) => {
  await openMenu(canvasElement);
  const menu = within(canvasElement.ownerDocument.body).getByRole("navigation");
  await userEvent.click(
    within(menu).getByRole("button", { name: new RegExp(`^${name}`) }),
  );
};

/** 사진 칸 항목 하나만 있는 노트를 돌려줘요. 사진은 기본 msw 응답이 주소에 적힌 크기로 그려 줘요 */
const respondNoteWithImage = (
  item: (typeof imageReleaseNoteItems)[keyof typeof imageReleaseNoteItems],
) => ({
  handlers: {
    releaseNotes: http.get(`*${RELEASE_NOTES_RELAY_PATH}`, () =>
      HttpResponse.json([
        { id: "pr-1051", releasedAt: "2026-10-27", items: [item] },
      ]),
    ),
  },
});

/** 사진 칸 경우별 모양(보통, 아주 납작함, 세로로 긺, 사진 없음) */
const IMAGE_CASES_DESIGN = {
  type: "figma",
  url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5674-70369",
} as const;

/** 맨 위 노트 하나만 아직 보지 않은 사용자로 둬요 */
const leaveLatestNoteUnread = () => {
  localStorage.setItem(LAST_SEEN_NOTE_ID_KEY, releaseNotesResponse[1].id);
};

/**
 * Knot 서비스의 새 소식(릴리즈 노트)과 피드백 보내기를 여는 「소식과 피드백」 버튼이에요. GNB 오른쪽, 프로필 바로 왼쪽에 있어요.
 *
 * **동작 규칙**
 * - 버튼을 누르면 버튼 8px 아래에 메뉴(새 소식, 피드백 보내기)를 오른쪽 끝을 맞춰 열어요. 고르면 메뉴가 닫히고 같은 자리에 그 패널이 열려요.
 * - 버튼을 다시 누르거나, 바깥을 누르거나, ESC를 누르면 닫아요. 메뉴나 패널 안을 눌러서는 닫히지 않아요. 닫히면 버튼으로 포커스가 돌아와요.
 * - 피드백을 보내는 동안에는 바깥 · ESC · 버튼으로 닫히지 않아요.
 * - 열려 있는 동안 버튼은 채워진 모양이에요. 닫혀 있을 때 마우스를 올리거나 키보드로 포커스하면 툴팁 「새 소식과 피드백」이 떠요.
 * - 노트를 받기 전에는 버튼을 그리지 않아요. 저절로 열지는 노트를 받은 뒤에 정해요.
 * - 이 기기에서 새 소식을 한 번도 열지 않았으면 노트를 받은 뒤 메뉴 없이 새 소식 패널을 저절로 열어요. 녹음 중이거나 노트가 없거나 받지 못하면 열지 않아요.
 * - 새 소식 패널이 열리면(저절로든 메뉴에서 골라서든) 맨 위 노트를 읽음으로 남겨 다음부터는 저절로 열지 않아요.
 * - 휴대폰에서는 피드백을 사이드바에서 열어서, 버튼을 누르면 메뉴 없이 새 소식 패널을 바로 열어요.
 *
 * **안 읽은 새 소식**
 * - 마지막으로 새 소식 패널을 연 뒤에 새 노트가 올라왔으면 버튼 오른쪽 위에 점을 켜고, 메뉴의 「새 소식」에 NEW를 붙여요. 화면 낭독기에는 「새 소식과 피드백, 읽지 않은 소식 있음」으로 알려요.
 * - 새 소식 패널을 열면 점과 메뉴의 NEW를 꺼요. 메뉴만 열었다 닫으면 그대로 둬요.
 * - 패널에서는 안 읽은 노트의 항목에 NEW를 붙여요. 패널을 연 동안은 보이고, 닫았다 다시 열면 사라져요.
 * - 새 소식을 처음 확인하는 사용자는 모든 노트가 안 읽음이에요. 이 기기에 저장할 수 없거나 노트를 받지 못하면 아무것도 표시하지 않아요.
 *
 * **패널 내용**
 * - 노트는 main 머지마다 GitHub Actions의 Claude가 Notion 새 소식 DB에 쓰고, 프론트 Worker(`/relay/n`)가 그 DB에서 읽어 와요. 받지 못하면 빈 상태를 보여 줘요.
 * - 노트를 배포 날짜별(「10월 20일」)로 묶고 최신 날짜를 맨 위에 둬요. 같은 날 두 번 릴리즈하면 한 묶음에 모아요.
 * - 항목은 제목과 설명을 보여 주고, 사진과 「바로 가보기」는 노트에 있을 때만 보여 줘요. 「바로 가보기」는 지금 보고 있는 워크스페이스의 그 화면으로 옮겨요.
 * - 사진은 Actions의 Claude가 머지 PR 본문에서 고른 GitHub 첨부 사진(`https://github.com/user-attachments/assets/…`)만 그려요. 다른 주소면 사진 칸 없이 글만 보여요.
 * - 사진은 항목 폭에 맞춰 원래 비율로 보여 주고 자르지 않아요. 맞춘 높이가 240px을 넘으면 비율을 지킨 채 높이 240px로 줄이고 가운데에 둬요. 남는 양옆은 사진 칸 배경색이에요.
 * - 사진을 다 불러오기 전과 불러오지 못했을 때는 사진 칸을 그리지 않아요.
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
      {
        name: "Item/새 소식",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=4892-1831",
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
  // 새 소식을 연 기록과 적던 피드백은 브라우저에 남아 스토리끼리 이어지므로, 맨 위 노트까지 본 사용자와 빈 피드백으로 시작해요
  beforeEach: () => {
    localStorage.setItem(LAST_SEEN_NOTE_ID_KEY, releaseNotesResponse[0].id);
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

/** 마지막으로 새 소식을 본 뒤 새 노트가 올라온 사용자예요. 버튼 오른쪽 위에 점이 켜져 있어요 */
export const Unread: Story = {
  beforeEach: leaveLatestNoteUnread,
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=4696-29503",
    },
  },
};

/** 안 읽은 노트가 있을 때 버튼을 눌러 연 메뉴예요. 「새 소식」 끝에 NEW가 붙어 있어요 */
export const UnreadMenu: Story = {
  beforeEach: leaveLatestNoteUnread,
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

/** 안 읽은 노트가 있을 때 연 새 소식 패널이에요. 새로 올라온 10월 20일 항목에만 NEW가 붙고, 버튼의 점은 꺼져요 */
export const UnreadNoteList: Story = {
  beforeEach: leaveLatestNoteUnread,
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

/** 이 기기에서 새 소식을 처음 확인하는 사용자예요. 누르지 않아도 메뉴 없이 새 소식 패널이 열려 있고, 모든 항목에 NEW가 붙어 있어요 */
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

/** 화면이 바뀐 항목에 보통 비율 사진이 붙은 모습이에요. 1596×1386 사진은 폭에 맞추면 240px보다 높아 높이 240px로 줄이고 가운데에 둬요 */
export const NoteImage: Story = {
  parameters: {
    msw: respondNoteWithImage(imageReleaseNoteItems.normal),
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=4697-18589",
    },
  },
  play: async ({ canvasElement }) => {
    await selectMenuItem(canvasElement, "새 소식");
  },
};

/** 아주 납작한 사진(2560×420)이에요. 폭에 맞춘 높이 그대로 보여 주고 자르지 않아요 */
export const WideNoteImage: Story = {
  parameters: {
    msw: respondNoteWithImage(imageReleaseNoteItems.wide),
    design: IMAGE_CASES_DESIGN,
  },
  play: async ({ canvasElement }) => {
    await selectMenuItem(canvasElement, "새 소식");
  },
};

/** 세로로 긴 사진(750×1624)이에요. 높이 240px로 줄이고 남는 양옆은 사진 칸 배경색이에요 */
export const TallNoteImage: Story = {
  parameters: {
    msw: respondNoteWithImage(imageReleaseNoteItems.tall),
    design: IMAGE_CASES_DESIGN,
  },
  play: async ({ canvasElement }) => {
    await selectMenuItem(canvasElement, "새 소식");
  },
};

/** 사진을 불러오지 못한 항목이에요. 사진 칸 없이 글만 보여요. 허용하지 않는 주소도 같은 모습이에요 */
export const BrokenNoteImage: Story = {
  parameters: {
    msw: respondNoteWithImage(imageReleaseNoteItems.broken),
    design: IMAGE_CASES_DESIGN,
  },
  play: async ({ canvasElement }) => {
    await selectMenuItem(canvasElement, "새 소식");
  },
};

/** 아직 낸 노트가 하나도 없거나 노트를 받지 못했을 때예요 */
export const Empty: Story = {
  parameters: {
    msw: {
      handlers: {
        releaseNotes: http.get(`*${RELEASE_NOTES_RELAY_PATH}`, () =>
          HttpResponse.json([]),
        ),
      },
    },
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
