import { DialogProvider } from "@provider/context/dialogContext";
import { ToastProvider } from "@provider/context/toastContext";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { MemoryRouter, Route, Routes } from "react-router";

import ConnectedApps from ".";

const APPS_PATH = getRouterPath({
  routeKey: "APPS",
  params: { workspaceId: "1" },
});

/**
 * 연결된 앱 화면의 본문이에요. 사이드바 아래 「연결된 앱」으로 들어와요.
 *
 * 탐색이 읽고 쓰는 앱을 카드로 모아 가운데에 보여 줘요. 지금은 노션을 연결할 수 있어요.
 * 카드마다 상태와 버튼이 달라지는 모습은 「ConnectedApp/NotionAppCard」에서 봐요.
 */
const meta = {
  title: "ConnectedApp/ConnectedApps",
  component: ConnectedApps,
  parameters: {
    layout: "padded",
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=6513-4347",
    },
  },
  decorators: [
    (Story) => (
      <MemoryRouter initialEntries={[APPS_PATH]}>
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
} satisfies Meta<typeof ConnectedApps>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 노션이 연결돼 있고 연결한 사람이 볼 때예요 */
export const Default: Story = {};
