import { searchNotionConnectionResponse } from "@api/mock/responses/search";
import type { SearchNotionConnection } from "@api/mock/types/search";
import { DialogProvider } from "@provider/context/dialogContext";
import { ToastProvider } from "@provider/context/toastContext";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";
import { userEvent, within } from "storybook/test";

import NotionAppCard from ".";

const CONNECTION_REQUEST =
  "*/api/v1/workspaces/:workspaceId/search/notion-connection";
// 문서 페이지에서는 스토리를 한 화면에 함께 그려 응답이 섞이므로, 응답을 덮는 스토리는 따로 그려요
const ISOLATED_DOCS = { story: { inline: false, iframeHeight: 420 } };
const APPS_PATH = getRouterPath({
  routeKey: "APPS",
  params: { workspaceId: "1" },
});
const FIGMA_URL = "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot";

const NOT_CONNECTED = {
  status: "NOT_CONNECTED",
  notionWorkspaceName: null,
  notionWorkspaceIcon: null,
  connectedBy: null,
  canDisconnect: false,
} satisfies SearchNotionConnection;

/** 노션 연결 조회가 이 응답을 돌려주게 해요 */
const respondConnection = (connection: SearchNotionConnection) => ({
  notionConnection: http.get(CONNECTION_REQUEST, () =>
    HttpResponse.json(connection),
  ),
});

/**
 * 연결된 앱 화면에 놓이는 노션 앱 카드예요. 워크스페이스에 노션을 하나 연결해 두면 멤버 모두가 탐색 채팅에서 그 노션을 읽고 써요.
 *
 * **동작 규칙**
 * - 연결 전이면 멤버 누구나 [연결]을 눌러요. 이미 연결돼 있으면 [연결]이 없어요. 다른 노션을 쓰려면 끊은 뒤 새로 연결해요.
 * - [연결]과 [다시 연결]은 「내가 노션에서 볼 수 있는 내용 전부가 멤버 모두에게 보일 수 있다」는 안내를 먼저 띄워요. [동의하고 연결]을 눌러야 노션 동의 화면으로 넘어가요.
 *   스토리에서 누르면 mock 노션 동의 화면 주소로 넘어가요.
 * - 연결되면 연결된 노션 워크스페이스의 이름과 아이콘, 연결한 사람, 보이는 범위를 보여 줘요.
 * - 연결을 끊는 일은 연결한 사람과 워크스페이스 소유자만 해요. 다른 멤버에게는 [연결 끊기]가 없어요.
 * - 노션이 연결을 거절하면 만료로 바뀌고, 멤버 누구나 [다시 연결]을 눌러요. 연결한 사람과 소유자에게는 [연결 끊기]도 있어요.
 * - 노션 동의 화면에서 돌아오면 결과를 알려요. 연결을 마쳤으면 「노션을 연결했어요」 토스트를, 노션 게스트라 막혔으면 카드 안에 멤버만 연결할 수 있다는 안내를 보여 줘요.
 * - 탐색이 노션으로 하는 일은 기본으로 접어 두고 「자세히 보기」로 펼쳐요. 화면에 늘 펼쳐 두면 설명이 앞에 나서서 접었어요.
 */
const meta = {
  title: "ConnectedApp/NotionAppCard",
  component: NotionAppCard,
  parameters: {
    layout: "padded",
    initialPath: APPS_PATH,
    design: [
      {
        name: "노션 앱 카드 상태",
        type: "figma",
        url: `${FIGMA_URL}?node-id=6513-4486`,
      },
      {
        name: "노션 연결 흐름 4단계와 동의 안내 창",
        type: "figma",
        url: `${FIGMA_URL}?node-id=6513-4649`,
      },
    ],
  },
  decorators: [
    (Story, { parameters }) => (
      <MemoryRouter initialEntries={[parameters.initialPath]}>
        <ToastProvider>
          <DialogProvider>
            <Routes>
              <Route path={PATH_ROUTE.APPS} element={<Story />} />
            </Routes>
          </DialogProvider>
        </ToastProvider>
      </MemoryRouter>
    ),
  ],
} satisfies Meta<typeof NotionAppCard>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 연결한 사람이나 소유자가 연결된 노션을 볼 때예요. 연결 정보와 [연결 끊기]가 있어요 */
export const Default: Story = {
  parameters: {
    design: { type: "figma", url: `${FIGMA_URL}?node-id=6513-4513` },
  },
};

/** 연결한 사람도 소유자도 아닌 멤버가 연결된 노션을 볼 때예요. 누를 버튼이 없어 아래 줄이 없어요 */
export const ConnectedMember: Story = {
  parameters: {
    design: { type: "figma", url: `${FIGMA_URL}?node-id=6513-4553` },
    docs: ISOLATED_DOCS,
    msw: {
      handlers: respondConnection({
        ...searchNotionConnectionResponse,
        canDisconnect: false,
      }),
    },
  },
};

/** 아직 아무도 노션을 연결하지 않았을 때예요. 멤버 누구나 [연결]을 눌러요 */
export const NotConnected: Story = {
  parameters: {
    design: { type: "figma", url: `${FIGMA_URL}?node-id=6513-4490` },
    docs: ISOLATED_DOCS,
    msw: { handlers: respondConnection(NOT_CONNECTED) },
  },
};

/** 「자세히 보기」를 펼쳐 탐색이 노션으로 하는 일 세 가지를 볼 때예요. 「접기」로 다시 접어요 */
export const DetailOpen: Story = {
  parameters: {
    design: { type: "figma", url: `${FIGMA_URL}?node-id=6523-6520` },
    docs: { story: { inline: false, iframeHeight: 560 } },
    msw: { handlers: respondConnection(NOT_CONNECTED) },
  },
  play: async ({ canvasElement }) => {
    await userEvent.click(
      await within(canvasElement).findByRole("button", {
        name: "자세히 보기",
      }),
    );
  },
};

/** [연결]을 눌러 노션으로 넘어가기 전에 보이는 범위를 알리고 동의를 받을 때예요 */
export const ConsentDialog: Story = {
  parameters: {
    design: { type: "figma", url: `${FIGMA_URL}?node-id=6513-4751` },
    docs: { story: { inline: false, iframeHeight: 640 } },
    msw: { handlers: respondConnection(NOT_CONNECTED) },
  },
  play: async ({ canvasElement }) => {
    await userEvent.click(
      await within(canvasElement).findByRole("button", { name: "연결" }),
    );
  },
};

/** 연결이 만료됐는데 연결한 사람도 소유자도 아닌 멤버가 볼 때예요. 멤버 누구나 [다시 연결]을 눌러요 */
export const ExpiredMember: Story = {
  parameters: {
    design: { type: "figma", url: `${FIGMA_URL}?node-id=6513-4593` },
    docs: ISOLATED_DOCS,
    msw: {
      handlers: respondConnection({
        ...searchNotionConnectionResponse,
        status: "EXPIRED",
        canDisconnect: false,
      }),
    },
  },
};

/** 연결이 만료됐고 연결한 사람이나 소유자가 볼 때예요. [연결 끊기]와 [다시 연결]이 함께 있어요 */
export const ExpiredOwner: Story = {
  parameters: {
    design: { type: "figma", url: `${FIGMA_URL}?node-id=6554-4277` },
    docs: ISOLATED_DOCS,
    msw: {
      handlers: respondConnection({
        ...searchNotionConnectionResponse,
        status: "EXPIRED",
      }),
    },
  },
};

/** 노션 동의 화면에서 연결을 마치고 돌아왔을 때예요. 「노션을 연결했어요」 토스트가 떠요 */
export const ReturnedConnected: Story = {
  parameters: {
    initialPath: `${APPS_PATH}?notionConnection=CONNECTED`,
    design: { type: "figma", url: `${FIGMA_URL}?node-id=6513-4660` },
    docs: { story: { inline: false, iframeHeight: 520 } },
  },
};

/** 연결하려던 사람이 고른 노션의 게스트라 막혀 돌아왔을 때예요. 멤버만 연결할 수 있다고 알리고 [연결]을 둬요 */
export const GuestBlocked: Story = {
  parameters: {
    initialPath: `${APPS_PATH}?notionConnection=NOTION_GUEST_NOT_ALLOWED`,
    design: { type: "figma", url: `${FIGMA_URL}?node-id=6513-4622` },
    docs: ISOLATED_DOCS,
    msw: { handlers: respondConnection(NOT_CONNECTED) },
  },
};

/** 만료된 연결을 다시 연결하다 노션 게스트라 막혀 돌아왔을 때예요. 만료 안내 대신 게스트 안내를 두고 [다시 연결]을 둬요 */
export const GuestBlockedExpired: Story = {
  parameters: {
    initialPath: `${APPS_PATH}?notionConnection=NOTION_GUEST_NOT_ALLOWED`,
    design: { type: "figma", url: `${FIGMA_URL}?node-id=6554-4302` },
    docs: ISOLATED_DOCS,
    msw: {
      handlers: respondConnection({
        ...searchNotionConnectionResponse,
        status: "EXPIRED",
        canDisconnect: false,
      }),
    },
  },
};
