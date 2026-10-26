import { DOCUMENT_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]/documents/[documentId]";
import { WORKSPACE_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]";
import { documentDetailsResponse } from "@api/mock/responses/document";
import DockablePanel from "@composites/DockablePanel";
import {
  DOCUMENT_FROM_PARAM,
  DOCUMENT_FROM_RECORDING,
} from "@constants/document";
import SearchEvidenceToggle from "@features/search/SearchEvidenceToggle";
import { ToastProvider } from "@provider/context/toastContext";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import WorkspaceSidebar from "@widgets/workspace/WorkspaceSidebar";
import { delay, http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";

import SidebarIcon from "@/assets/icons/sidebar.svg";

import WorkspaceGnb from ".";

const WORKSPACE_ID = "1";
const HOME_PATH = getRouterPath({
  routeKey: "WORKSPACE_HOME",
  params: { workspaceId: WORKSPACE_ID },
});
const CHAT_PATH = getRouterPath({
  routeKey: "CHAT",
  params: { workspaceId: WORKSPACE_ID },
});
const CONVERSATION_PATH = getRouterPath({
  routeKey: "CHAT_SESSION",
  params: { workspaceId: WORKSPACE_ID, sessionId: "10" },
});
const DOCUMENTS_PATH = getRouterPath({
  routeKey: "DOCUMENTS",
  params: { workspaceId: WORKSPACE_ID },
});
const [documentResponse] = documentDetailsResponse;
const DOCUMENT_PATH = getRouterPath({
  routeKey: "DOCUMENT",
  params: {
    workspaceId: WORKSPACE_ID,
    documentId: String(documentResponse.id),
  },
});
const RECORDING_PATH = getRouterPath({
  routeKey: "RECORDING",
  params: { workspaceId: WORKSPACE_ID },
});
// 문서 탭 「녹음별」 탭의 녹음 행으로 여는 녹음 문서 화면. 위치 표시가 녹음 제목을 보여 주도록 mock에 있는 녹음을 열어요
const RECORDING_DOCUMENT_PATH = getRouterPath({
  routeKey: "RECORDING_DOCUMENT",
  params: { workspaceId: WORKSPACE_ID, recordingId: "42" },
});
const WORKSPACE_REQUEST = `*${WORKSPACE_API_PATH(Number(WORKSPACE_ID))}`;
const DOCUMENT_REQUEST = `*${DOCUMENT_API_PATH(Number(WORKSPACE_ID), documentResponse.id)}`;
const DOCK_RAIL_ID = "story-dock-rail";

// 문서 페이지는 스토리를 한 화면에 함께 그리는데 msw는 화면에 하나뿐이라, 마지막 스토리의 응답이 모든 스토리에 적용돼요.
// 응답을 바꾸는 스토리만 따로 그려 각자의 응답을 받게 해요
const ISOLATED_DOCS = { story: { inline: false, iframeHeight: 120 } };

/** 응답을 끝내 보내지 않아 불러오는 중에 머물러요 */
const respondNever = async () => {
  await delay("infinite");
  return new HttpResponse(null);
};

const FIGMA_LOADING_AND_LONG_NAME = {
  type: "figma",
  url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5231-25588",
} as const;

/**
 * 워크스페이스 안의 모든 화면 맨 위에 떠 있는 상단바(GNB)예요. 워크스페이스 홈 · 탐색 · 문서 화면이 함께 써요.
 *
 * **구성**
 * - 왼쪽: 사이드바 같은 패널을 여는 버튼들이에요. 어떤 패널을 둘지는 화면마다 달라(대화 목록은 탐색 화면에만 있어요) 화면 레이아웃이 넣어 줘요.
 *   이 스토리에는 모든 화면에 공통인 사이드바 버튼만 넣었어요.
 *   버튼 오른쪽에는 지금 있는 워크스페이스와 탭을 알려 주는 위치 표시가 있어요. 예: `Knot 팀 › 홈`, 문서 하나를 볼 때는 `Knot 팀 › 문서 › 회원 탈퇴 정책`.
 * - 가운데: 홈 · 탐색 · 문서를 오가는 내비 필이에요. 지금 있는 화면이 채워진 모양으로 표시돼요.
 * - 오른쪽: 오른쪽 패널을 여는 버튼과 로그인한 사람의 GitHub 프로필 이미지예요. 프로필은 지금은 보여 주기만 하고 누를 수 없어요.
 *   오른쪽 패널 버튼(찾은 기록)은 탐색 화면에만 있어 왼쪽 버튼처럼 화면 레이아웃이 넣어 줘요.
 *
 * **동작 규칙**
 * - 배경 없이 본문 위에 떠 있어요.
 * - 좌우 영역이 같은 비율로 늘어나 내비 필은 늘 화면 한가운데에 놓여요.
 * - 지금 있는 화면의 버튼은 눌러도 이동하지 않아 뒤로 가기 기록이 같은 화면으로 쌓이지 않아요. 탐색 화면은 대화를 연 상태여도 탐색으로 표시돼요.
 * - 문서 하나를 보는 화면도 문서로 표시되고, 거기서 「문서」를 누르면 문서 목록으로 가요. 문서 탭은 언제나 폴더별 목록을 보여 주기 때문이에요.
 * - 프로필은 정보를 받기 전에는 기본 모양으로, 이미지가 없거나 불러오지 못하면 닉네임 첫 글자로 대신해요.
 *
 * **위치 표시**
 * - 보여 주기만 하고 눌러도 아무 일도 없어요. 다른 워크스페이스로 옮기려면 사이드바 위쪽의 워크스페이스 이름을 눌러요.
 * - 녹음 화면과 녹음 직후 문서 확인 화면은 탭이 아니라서 아이콘과 워크스페이스 이름만 보여요.
 * - 이름은 200px까지, 문서 제목은 남은 자리만큼 한 줄로 보이고 넘치면 말줄임해요.
 * - 1024 미만(태블릿)에서는 탭 이름과 문서 제목을 빼고 아이콘과 이름만 보여요. 767 이하(모바일)에서는 왼쪽 자리가 모자라 숨겨요.
 * - 워크스페이스 이름이나 문서 제목을 불러오는 동안에는 그 자리를 회색 덩어리로 채우고 아이콘은 기본 모양으로 둬요.
 *
 * - 탭 전환 모션은 `Workspace/WorkspaceLayout` 스토리에서 본문 전환과 함께 직접 눌러 볼 수 있어요.
 * **탭 전환 모션**
 * - 탭 칸을 누르는 동안 칸이 살짝 작아지고, 떼면 통 튀어 원래 크기로 돌아와요. 지금 탭을 누르면 이 눌림과 맺힘만 보이고 화면은 그대로예요.
 * - 진한 표시 하나가 이전 탭에서 새 탭으로 미끄러지고, 글자색도 같은 시간에 바뀌어요. 칸마다 따로 칠하면 바뀌는 동안 두 칸이 함께 반쯤 어두워 보여서예요.
 * - 표시는 미끄러지는 동안 진행 방향으로 살짝 늘어났다가, 새 탭에 도착하며 물방울이 맺히듯 한 번 부풀고 자리잡아요. 지금 탭을 누르면 표시도 함께 눌렸다가 제자리에서 톡 맺혀요.
 * - 처음 들어올 때와 창 크기가 바뀔 때는 표시가 움직이거나 맺히지 않고 제자리에 놓여요. 빠르게 연달아 누르면 지금 있는 자리와 모양에서 이어서 미끄러져요.
 * - 본문은 탭 방향(홈, 탐색, 문서 순)에서 8px 안쪽으로 살짝 밀려 들어오며 또렷해져요. 반쯤 보이는 데서 시작해 빈 화면이 보이는 순간이 없어요.
 * - 같은 탭 안에서 옮길 때(대화나 문서를 바꿀 때), 처음 들어올 때, 새로고침할 때는 본문이 움직이지 않아요.
 * - 운영체제에서 움직임 줄이기를 켜 두면 눌림, 표시 이동과 물방울, 본문 이동 없이 바로 바뀌어요.
 */
const meta = {
  title: "Workspace/WorkspaceGnb",
  component: WorkspaceGnb,
  parameters: {
    layout: "fullscreen",
    initialPath: HOME_PATH,
    design: [
      {
        name: "GNB/Floating nav=홈",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=1364-6863",
      },
      {
        name: "Pill",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=1364-839",
      },
      {
        name: "GNB/WorkspaceLocation",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5229-1722",
      },
      {
        name: "상단 바 위치 표시 정책",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5235-12337",
      },
    ],
  },
  args: {
    left: (
      <DockablePanel
        label="사이드바"
        icon={<SidebarIcon size={18} />}
        dockTargetId={DOCK_RAIL_ID}
      >
        <WorkspaceSidebar />
      </DockablePanel>
    ),
  },
  argTypes: { left: { control: false }, right: { control: false } },
  decorators: [
    (Story, { parameters }) => {
      return (
        <MemoryRouter initialEntries={[parameters.initialPath]}>
          <ToastProvider>
            <Routes>
              {/* 찾은 기록 버튼이 대화 ID를 읽도록 대화 경로를 따로 둬요 */}
              {/* 위치 표시가 문서 ID · 녹음 ID를 읽도록 문서 경로와 녹음 문서 경로도 따로 둬요 */}
              {[
                `${PATH_ROUTE.WORKSPACE_HOME}/*`,
                PATH_ROUTE.CHAT_SESSION,
                PATH_ROUTE.DOCUMENT,
                PATH_ROUTE.RECORDING_DOCUMENT,
              ].map((path) => (
                <Route
                  key={path}
                  path={path}
                  element={
                    // 화면 레이아웃처럼 GNB 위에 여백을, 아래에 사이드바를 고정할 자리를 둬요
                    <div style={{ paddingTop: "1.5rem" }}>
                      <Story />
                      <div
                        id={DOCK_RAIL_ID}
                        style={{
                          display: "flex",
                          height: "30rem",
                          padding: "1.25rem 0 0 2.5rem",
                        }}
                      />
                    </div>
                  }
                />
              ))}
            </Routes>
          </ToastProvider>
        </MemoryRouter>
      );
    },
  ],
} satisfies Meta<typeof WorkspaceGnb>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 워크스페이스 홈에서 보이는 모양이에요. 내비 필의 「홈」이 채워져 있어요. */
export const OnHomeScreen: Story = {};

/** 탐색 화면에서 보이는 모양이에요. 내비 필의 「탐색」이 채워져 있어요. */
export const OnChatScreen: Story = {
  parameters: {
    initialPath: CHAT_PATH,
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=1364-7028",
    },
  },
};

/** 질문과 답변이 오간 탐색 대화에서 보이는 모양이에요. 근거가 있는 답변이 있어 아바타 앞에 찾은 기록 버튼이 있어요. */
export const OnConversationScreen: Story = {
  args: { right: <SearchEvidenceToggle /> },
  parameters: {
    initialPath: CONVERSATION_PATH,
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2106-28969",
    },
  },
};

/**
 * 문서 목록 화면에서 보이는 모양이에요. 내비 필의 「문서」가 채워져 있어요.
 * 문서 하나를 보는 화면에서도 내비 필은 같은 모양이고, 거기서 「문서」를 누르면 문서 목록으로 가요.
 */
export const OnDocumentsScreen: Story = {
  parameters: {
    initialPath: DOCUMENTS_PATH,
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2017-36056",
    },
  },
};

/**
 * 문서 하나를 보는 화면에서 보이는 모양이에요. 위치 표시가 `워크스페이스 이름 › 문서 › 문서 제목`으로 어느 문서인지 알려 줘요.
 * 그래서 문서 본문 위에는 경로를 따로 두지 않아요.
 */
export const OnDocumentScreen: Story = {
  parameters: {
    initialPath: DOCUMENT_PATH,
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2017-36058",
    },
  },
};

/**
 * 녹음 문서 화면에서 보이는 모양이에요. 문서 탭 「녹음별」 탭에서 여는 화면이라 내비 필의 문서가 채워져 있고,
 * 위치 표시가 `워크스페이스 이름 › 문서 › 녹음 제목`으로 어느 녹음인지 알려 줘요. 예: 「회원 탈퇴 정책 논의」 녹음을 열었을 때
 */
export const OnRecordingDocumentViewerScreen: Story = {
  parameters: {
    initialPath: RECORDING_DOCUMENT_PATH,
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5373-3187",
    },
  },
};

/**
 * 녹음을 끝내고 만든 문서를 확인하는 화면이에요. 탭이 아니라서 위치 표시에 아이콘과 워크스페이스 이름만 있어요.
 * 녹음 중인 화면도 같은 모양이에요.
 */
export const OnRecordingDocumentScreen: Story = {
  parameters: {
    initialPath: `${DOCUMENT_PATH}?${DOCUMENT_FROM_PARAM}=${DOCUMENT_FROM_RECORDING}`,
  },
};

/** 녹음 화면에서 보이는 모양이에요. 내비 필에 채워진 탭이 없고, 위치 표시에는 아이콘과 워크스페이스 이름만 있어요. */
export const OnRecordingScreen: Story = {
  parameters: { initialPath: RECORDING_PATH },
};

/** 워크스페이스 이름과 문서 제목을 불러오는 동안이에요. 그 자리를 회색 덩어리로 채우고 아이콘은 기본 모양으로 둬요. */
export const Loading: Story = {
  parameters: {
    initialPath: DOCUMENT_PATH,
    msw: {
      handlers: {
        workspace: http.get(WORKSPACE_REQUEST, respondNever),
        document: http.get(DOCUMENT_REQUEST, respondNever),
      },
    },
    docs: ISOLATED_DOCS,
    design: FIGMA_LOADING_AND_LONG_NAME,
  },
};

/** 문서를 불러오지 못했을 때예요. 제목을 모르니 위치 표시는 `워크스페이스 이름 › 문서`에서 끝나요. */
export const DocumentLoadFailed: Story = {
  parameters: {
    initialPath: DOCUMENT_PATH,
    msw: {
      handlers: {
        document: http.get(
          DOCUMENT_REQUEST,
          () => new HttpResponse(null, { status: 404 }),
        ),
      },
    },
    docs: ISOLATED_DOCS,
  },
};

/** 태블릿처럼 1024보다 좁은 화면이에요. 자리가 모자라 위치 표시에 아이콘과 워크스페이스 이름만 남아요. */
export const OnTablet: Story = {
  globals: { viewport: { value: "tablet834", isRotated: false } },
  parameters: { initialPath: DOCUMENT_PATH },
};
