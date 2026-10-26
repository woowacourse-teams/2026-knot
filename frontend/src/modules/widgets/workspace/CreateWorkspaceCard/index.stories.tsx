import { WORKSPACES_API_PATH } from "@api/fetch/api/v1/workspaces";
import { workspaceCreateResponse } from "@api/mock/responses/workspace";
import { PATH_ROUTE } from "@routes/PATH_ROUTE";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { delay, http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";
import { userEvent, within } from "storybook/test";

import CreateWorkspaceCard from ".";

const WORKSPACE_NAME = "Knot 팀";

/** 이름 입력창에 값을 적어요. */
const typeName = async (canvasElement: HTMLElement, name: string) => {
  const canvas = within(canvasElement);

  await userEvent.type(
    canvas.getByRole("textbox", { name: "워크스페이스 이름" }),
    name,
  );
};

/** 이름을 적고 생성 버튼을 눌러요. */
const submitName = async (canvasElement: HTMLElement) => {
  await typeName(canvasElement, WORKSPACE_NAME);
  await userEvent.click(
    within(canvasElement).getByRole("button", { name: "워크스페이스 생성" }),
  );
};

/** 생성 요청이 이 상태 코드로 실패하게 해요. */
const createWorkspaceFails = (status: number) => ({
  msw: {
    handlers: {
      createWorkspace: http.post(
        `*${WORKSPACES_API_PATH}`,
        () => new HttpResponse(null, { status }),
      ),
    },
  },
});

/**
 * 새 워크스페이스의 이름을 받아 만드는 카드예요. 워크스페이스 생성 화면(`/workspace/create`)에 놓여요.
 *
 * **동작 규칙**
 * - 입력 전 · 입력 중 · 입력 에러 세 상태를 한 카드에서 다뤄요.
 * - 이름은 한글, 영어, 숫자(0~9), 공백만 쓸 수 있어요. 글자를 칠 때마다 검사해 어긋나면 입력창 아래에 바로 알려요.
 * - 20자까지 적을 수 있고, 위의 `n/20`이 지금 글자 수를 보여 줘요.
 * - 값이 비었거나(공백만 있는 값 포함) 에러가 있으면 버튼을 누를 수 없어요. 라벨은 늘 「워크스페이스 생성」이에요.
 * - 버튼을 누르면 워크스페이스를 만들고, 요청 중에는 버튼이 로딩으로 잠겨요.
 * - 만들어지면 그 워크스페이스의 팀원 초대 화면(`/workspace/:workspaceId/invite`)으로 넘어가요.
 * - 실패하면 이유를 입력창 아래 같은 자리에 띄우고 커서를 입력창으로 되돌려요. 이름을 고치면 이 문구는 사라져요.
 *   로그인이 풀린 경우(401)만 문구 없이 로그인 화면으로 보내요.
 * - 로고와 화면 가운데 배치는 화면 레이아웃이 맡아요. 이 카드는 카드 모양만 그려요.
 */
const meta = {
  title: "Workspace/CreateWorkspaceCard",
  component: CreateWorkspaceCard,
  parameters: {
    layout: "centered",
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=431-1294",
    },
  },
  decorators: [
    (Story) => (
      <MemoryRouter initialEntries={[PATH_ROUTE.WORKSPACE_CREATE]}>
        <Routes>
          <Route path={PATH_ROUTE.WORKSPACE_CREATE} element={<Story />} />
          <Route
            path={PATH_ROUTE.WORKSPACE_INVITE}
            element={<p>팀원 초대 화면으로 이동했어요.</p>}
          />
        </Routes>
      </MemoryRouter>
    ),
  ],
} satisfies Meta<typeof CreateWorkspaceCard>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 생성 화면에 처음 들어온 상태예요. 이름이 비어 있어 버튼을 누를 수 없어요. */
export const Empty: Story = {};

/** 쓸 수 있는 이름을 적은 상태예요. 버튼을 누를 수 있게 돼요. */
export const Filled: Story = {
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=432-1576",
    },
  },
  play: ({ canvasElement }) => typeName(canvasElement, WORKSPACE_NAME),
};

/** 생성 요청을 보내고 응답을 기다리는 상태예요. 버튼이 로딩으로 잠겨 두 번 보내지 않아요. */
export const Submitting: Story = {
  parameters: {
    // 문서 페이지에서는 스토리를 한 화면에 함께 그려 응답이 섞이므로, 이 스토리는 따로 그려요
    docs: { story: { inline: false, iframeHeight: 360 } },
    msw: {
      handlers: {
        createWorkspace: http.post(`*${WORKSPACES_API_PATH}`, async () => {
          await delay("infinite");
          return HttpResponse.json(workspaceCreateResponse, { status: 201 });
        }),
      },
    },
  },
  play: ({ canvasElement }) => submitName(canvasElement),
};

/** 화면 검사를 통과한 이름을 서버가 형식 오류(400)로 거절한 상태예요. 이름 규칙 문구를 다시 보여 줘요. */
export const RejectedName: Story = {
  parameters: {
    ...createWorkspaceFails(400),
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=432-1594",
    },
  },
  play: ({ canvasElement }) => submitName(canvasElement),
};

/** 보안 확인(CSRF)에 실패한 상태(403)예요. 예: 오래 열어 둔 화면에서 보냈을 때. 새로고침을 안내해요. */
export const SecurityCheckFailed: Story = {
  parameters: createWorkspaceFails(403),
  play: ({ canvasElement }) => submitName(canvasElement),
};

/** 서버 오류처럼 이유를 알 수 없이 실패한 상태예요. 잠시 후 다시 시도하라고 안내해요. */
export const ServerError: Story = {
  parameters: createWorkspaceFails(500),
  play: ({ canvasElement }) => submitName(canvasElement),
};
