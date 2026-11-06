import { AUTH_LOGOUT_API_PATH } from "@api/fetch/api/v1/auth/logout";
import { AUTH_ME_API_PATH } from "@api/fetch/api/v1/auth/me";
import { meResponse } from "@api/mock/responses/auth";
import { DialogProvider } from "@provider/context/dialogContext";
import { ToastProvider } from "@provider/context/toastContext";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import { useRecordingStore } from "@store/recordingStore";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { delay, http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";
import { userEvent, within } from "storybook/test";

import MemberProfileAvatar from ".";

const HOME_PATH = getRouterPath({
  routeKey: "WORKSPACE_HOME",
  params: { workspaceId: "1" },
});
const FIGMA_URL =
  "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=";

// 문서 페이지는 스토리를 한 화면에 함께 그리는데 msw는 화면에 하나뿐이라, 마지막 스토리의 응답이 모든 스토리에 적용돼요.
// 응답을 바꾸는 스토리만 따로 그려 각자의 응답을 받게 해요. 높이는 메뉴와 토스트가 잘리지 않는 값이에요
const ISOLATED_DOCS = { story: { inline: false, iframeHeight: 320 } };

/** 기본 mock은 다른 탭에서 녹음 중이라, 확인 없이 로그아웃되는 스토리는 녹음이 없다고 답해요 */
const respondNoRecording = http.get(
  "*/api/v1/workspaces/:workspaceId/recordings/current",
  () => new HttpResponse(null, { status: 204 }),
);

/** 녹음을 처음 상태로 되돌려요. 녹음은 전역 저장소에 남아 스토리끼리 이어지기 때문이에요. */
const resetRecording = () => {
  useRecordingStore.getState().discardRecording();
};

/** 프로필 사진을 눌러 메뉴를 열어요. 메뉴는 `body`에 그려져요 */
const openMenu = async (canvasElement: HTMLElement) => {
  await userEvent.click(
    within(canvasElement).getByRole("button", { name: "내 프로필" }),
  );

  return within(canvasElement.ownerDocument.body).findByRole("button", {
    name: "로그아웃",
  });
};

/** 메뉴를 열고 「로그아웃」을 눌러요 */
const pressLogout = async (canvasElement: HTMLElement) => {
  await userEvent.click(await openMenu(canvasElement));
};

/**
 * 로그인한 회원의 프로필 아바타예요. GNB 오른쪽에 놓이고, 누르면 프로필 메뉴가 열려요.
 *
 * **프로필 사진**
 * - 로그인한 수단(GitHub, Google, 카카오)에서 받아 둔 프로필 이미지를 그려요.
 * - 사진이 없는 회원이거나 이미지를 불러오지 못하면 닉네임 첫 글자로 대신해요. 이것이 기본 프로필 이미지예요.
 *   예: 카카오에서 사진 제공에 동의하지 않은 회원
 * - 회원 정보를 받아 오기 전에는 닉네임도 없어 기본 사람 모양을 그려요.
 *
 * **프로필 메뉴**
 * - 프로필 사진 8px 아래에 오른쪽 끝을 맞춰 열려요. 메뉴에는 「로그아웃」 하나만 있어요.
 * - 프로필 사진을 다시 누르거나 바깥을 누르거나 `Esc`를 누르면 닫혀요.
 * - 처리 중이거나 창이 떠 있는 동안에는 닫히지 않아요. 연결이 끊겨 오래 기다릴 수 있는데, 닫으면 로그아웃이 진행 중인지 볼 곳이 없어져서예요.
 *
 * **로그아웃**
 * - 녹음이 없으면 확인 없이 이 브라우저의 로그인을 끝내고 로그인 화면으로 가요. 다른 기기의 로그인은 그대로예요.
 * - 실패하면 메뉴는 열린 채 「로그아웃」이 기본으로 돌아오고, 화면과 로그인은 그대로예요. 바로 다시 누를 수 있어요.
 * - 이 탭에서 녹음 중이면 끝낼지 묻고, 「끝내고 로그아웃」은 창을 바로 닫은 뒤 메뉴의 「로그아웃」에 처리 중을 보여요. 기다리는 동안 화면은 계속 쓸 수 있어요.
 * - 녹음을 끝내지 못해 녹음이 이어지면 로그아웃하지 않고, 메뉴는 열린 채 기본으로 돌아와요.
 * - 끝낸 녹음의 소리를 올리는 중이면 다 올릴 때까지 처리 중으로 기다린 뒤 로그아웃해요.
 * - 이 탭에 녹음이 없으면 누른 때 보고 있는 워크스페이스의 녹음을 새로 확인해, 다른 곳에서 녹음 중이면 알려요. 확인하지 못하면 알리지 않고 이어 가요.
 * - 창에서 「취소」, 바깥 누르기, `Esc`는 창만 닫아요. 메뉴는 뒤에 그대로 있어요.
 * - 처리 중에도 화면을 쓸 수 있어서, 로그아웃되면 그사이 시작한 녹음과 띄운 창도 함께 닫아요.
 */
const meta = {
  title: "Member/MemberProfileAvatar",
  component: MemberProfileAvatar,
  parameters: {
    layout: "centered",
    design: [
      { name: "Avatar", type: "figma", url: `${FIGMA_URL}587-516` },
      { name: "Menu/프로필", type: "figma", url: `${FIGMA_URL}6063-52406` },
      {
        name: "Menu/프로필 기본",
        type: "figma",
        url: `${FIGMA_URL}6063-52380`,
      },
      {
        name: "Menu/프로필 처리 중",
        type: "figma",
        url: `${FIGMA_URL}6063-52395`,
      },
      { name: "Icon/Logout", type: "figma", url: `${FIGMA_URL}6063-52375` },
      {
        name: "끝내고 로그아웃 처리 중",
        type: "figma",
        url: `${FIGMA_URL}6063-53522`,
      },
      { name: "태블릿 834", type: "figma", url: `${FIGMA_URL}6063-54059` },
      { name: "모바일 390", type: "figma", url: `${FIGMA_URL}6063-54331` },
      {
        name: "모바일 확인 시트",
        type: "figma",
        url: `${FIGMA_URL}6063-54844`,
      },
      {
        name: "모바일 알림 시트",
        type: "figma",
        url: `${FIGMA_URL}6063-55228`,
      },
      { name: "정리 카드", type: "figma", url: `${FIGMA_URL}6063-55637` },
    ],
  },
  beforeEach: resetRecording,
  decorators: [
    (Story) => (
      <DialogProvider>
        <MemoryRouter initialEntries={[HOME_PATH]}>
          <ToastProvider>
            <Routes>
              <Route
                path={PATH_ROUTE.WORKSPACE_HOME}
                element={
                  // 오른쪽 끝을 맞춰 여는 메뉴(240px)가 잘리지 않게 자리를 줘요
                  <div
                    style={{
                      display: "flex",
                      justifyContent: "flex-end",
                      width: "16rem",
                    }}
                  >
                    <Story />
                  </div>
                }
              />
            </Routes>
          </ToastProvider>
        </MemoryRouter>
      </DialogProvider>
    ),
  ],
} satisfies Meta<typeof MemberProfileAvatar>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 프로필 이미지가 있는 회원이에요. */
export const Default: Story = {};

/** 프로필 사진이 없는 회원이에요. 예: 카카오에서 사진 제공에 동의하지 않고 가입한 회원 */
export const WithoutPhoto: Story = {
  parameters: {
    // 문서 페이지에서는 스토리를 한 화면에 함께 그려 응답이 섞이므로, 이 스토리는 따로 그려요
    docs: { story: { inline: false, iframeHeight: 80 } },
    msw: {
      handlers: {
        me: http.get(`*${AUTH_ME_API_PATH}`, () =>
          HttpResponse.json({ ...meResponse, profileImageUrl: null }),
        ),
      },
    },
  },
};

/** 프로필 사진을 눌러 메뉴를 연 모습이에요. 메뉴에는 「로그아웃」 하나만 있어요. */
export const MenuOpen: Story = {
  parameters: {
    design: { type: "figma", url: `${FIGMA_URL}6063-52425` },
  },
  play: async ({ canvasElement }) => {
    await openMenu(canvasElement);
  },
};

/** 로그아웃을 처리하는 동안이에요. 아이콘 자리에 스피너가 돌고 글자가 흐려지며, 다시 누르거나 메뉴를 닫을 수 없어요. 예: 서버 응답을 기다릴 때 */
export const LoggingOut: Story = {
  parameters: {
    docs: ISOLATED_DOCS,
    design: { type: "figma", url: `${FIGMA_URL}6063-52700` },
    msw: {
      handlers: {
        current: respondNoRecording,
        logout: http.post(`*${AUTH_LOGOUT_API_PATH}`, async () => {
          await delay("infinite");

          return new HttpResponse(null);
        }),
      },
    },
  },
  play: async ({ canvasElement }) => {
    await pressLogout(canvasElement);
  },
};

/** 로그아웃에 실패했을 때예요. 오류 토스트가 뜨고 메뉴는 열린 채 기본으로 돌아와 바로 다시 누를 수 있어요. 예: 서버 오류, 연결 끊김 */
export const LogoutFailed: Story = {
  parameters: {
    docs: ISOLATED_DOCS,
    design: { type: "figma", url: `${FIGMA_URL}6063-52974` },
    msw: {
      handlers: {
        current: respondNoRecording,
        logout: http.post(
          `*${AUTH_LOGOUT_API_PATH}`,
          () => new HttpResponse(null, { status: 500 }),
        ),
      },
    },
  },
  play: async ({ canvasElement }) => {
    await pressLogout(canvasElement);
  },
};

/** 이 탭에서 녹음 중에 「로그아웃」을 누르면 뜨는 확인 창이에요. 메뉴는 뒤에 열려 있어요. 일시정지 중에도 같아요. */
export const RecordingConfirm: Story = {
  parameters: {
    design: { type: "figma", url: `${FIGMA_URL}6063-53253` },
  },
  beforeEach: () => {
    useRecordingStore.setState({
      status: "recording",
      accumulatedMs: 768_000,
      resumedAt: Date.now(),
    });
  },
  play: async ({ canvasElement }) => {
    await pressLogout(canvasElement);
  },
};

/** 이 탭이 아닌 곳에서 녹음 중에 「로그아웃」을 누르면 뜨는 알림 창이에요. 예: 같은 브라우저의 다른 탭이나 다른 기기에서 녹음 중일 때 */
export const RecordingElsewhere: Story = {
  parameters: {
    design: { type: "figma", url: `${FIGMA_URL}6063-53790` },
  },
  play: async ({ canvasElement }) => {
    await pressLogout(canvasElement);
  },
};
