import { ToastProvider } from "@provider/context/toastContext";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { MemoryRouter, Route, Routes } from "react-router";

import DocumentArchive from ".";

const WORKSPACE_ID = 1;

/** 주소로 탭이 정해지므로, 스토리마다 시작 주소를 바꿔 다른 탭을 보여 줘요 */
const withRouter =
  (routeKey: "DOCUMENTS_BY_RECORDING" | "DOCUMENTS_BY_TOPIC") =>
  (Story: React.ComponentType) => (
    <MemoryRouter
      initialEntries={[
        getRouterPath({
          routeKey,
          params: { workspaceId: String(WORKSPACE_ID) },
        }),
      ]}
    >
      <ToastProvider>
        <Routes>
          <Route path={PATH_ROUTE.DOCUMENTS_BY_RECORDING} element={<Story />} />
          <Route path={PATH_ROUTE.DOCUMENTS_BY_TOPIC} element={<Story />} />
          <Route
            path={PATH_ROUTE.DOCUMENT}
            element={<p>문서 보기 화면으로 이동했어요.</p>}
          />
          <Route
            path={PATH_ROUTE.RECORDING_DOCUMENT}
            element={<p>녹음 문서 화면으로 이동했어요.</p>}
          />
        </Routes>
      </ToastProvider>
    </MemoryRouter>
  );

/**
 * 문서 탭 섹션이에요. 제목 「문서」 아래에 「녹음별」 · 「주제별」 두 탭을 두고, 고른 탭의 목록을 보여 줘요.
 * GNB의 「문서」를 누르면 열리는 문서 탭 화면의 가운데에 놓여요.
 *
 * **동작 규칙**
 * - 탭은 「녹음별」, 「주제별」 순서이고, 문서 탭은 늘 「녹음별」로 열려요. 마지막으로 본 탭을 기억하지 않아요.
 * - 어느 탭인지는 주소가 정해요. 「녹음별」은 `…/documents/recordings`, 「주제별」은 `…/documents/topics`이고, 탭을 누르면 그 주소로 가요.
 *   그래서 뒤로 가기와 공유 링크가 보던 탭을 그대로 가리켜요. 탭 없는 주소 `…/documents`는 「녹음별」로 보내요.
 * - 탭에 포커스가 있을 때 왼쪽 · 오른쪽 화살표로 옆 탭으로 옮기면 바로 그 탭이 열려요.
 * - 「녹음별」 목록은 `RecordingDocumentList`, 「주제별」 목록은 `TopicDocumentList` 스토리에서 상태별 모습을 볼 수 있어요.
 * - 탭을 바꿔도 사이드바의 주제 목록은 그대로예요. 사이드바는 이 섹션 밖에서 따로 그려요.
 */
const meta = {
  title: "Document/DocumentArchive",
  component: DocumentArchive,
  parameters: {
    design: [
      {
        name: "문서 탭/녹음별 · 기본",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5327-2402",
      },
      {
        name: "Tabs/문서 목록 · Row/Recording · Item/RecordingDoc",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5326-1701",
      },
    ],
  },
} satisfies Meta<typeof DocumentArchive>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 「녹음별」 탭이 열린 기본 모습이에요. 문서 탭에 들어오면 늘 이 탭이에요 */
export const Default: Story = {
  decorators: [withRouter("DOCUMENTS_BY_RECORDING")],
};

/** 「주제별」 탭을 고른 모습이에요. 기존 주제 폴더 목록이 그대로 보여요 */
export const TopicTab: Story = {
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5327-2221",
    },
  },
  decorators: [withRouter("DOCUMENTS_BY_TOPIC")],
};
