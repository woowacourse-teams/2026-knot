import { AUTH_LOGOUT_API_PATH } from "@api/fetch/api/v1/auth/logout";
import { AUTH_ME_API_PATH, getMeApi } from "@api/fetch/api/v1/auth/me";
import { AUTH_REFRESH_API_PATH } from "@api/fetch/api/v1/auth/refresh";
import { meResponse } from "@api/mock/responses/auth";
import { currentRecordingResponse } from "@api/mock/responses/recording";
import { mockServer } from "@api/mock/server";
import { TOAST_MESSAGES } from "@constants/toastMessages";
import { ThemeProvider } from "@emotion/react";
import { DialogProvider, useDialog } from "@provider/context/dialogContext";
import { ToastProvider } from "@provider/context/toastContext";
import { theme } from "@provider/themeProvider";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import { useRecordingStore } from "@store/recordingStore";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { readAuthReturnPath, saveAuthReturnPath } from "@utils/authReturnPath";
import {
  consumeAuthSessionExpired,
  markAuthSessionConfirmed,
} from "@utils/authSession";
import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { readFeedbackDraft, saveFeedbackDraft } from "@utils/feedbackDraft";
import {
  readLastConversationId,
  saveLastConversationId,
} from "@utils/lastConversation";
import {
  clearRecordingStartProof,
  getRecordingStartProof,
} from "@utils/recordingControlProof";
import { delay, http, HttpResponse } from "msw";
import { createMemoryRouter, Outlet, RouterProvider } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import MemberProfileAvatar from ".";

vi.mock("@utils/captureAnalyticsEvent", () => ({
  captureAnalyticsEvent: vi.fn(),
}));

const WORKSPACE_ID = "1";
const HOME_PATH = getRouterPath({
  routeKey: "WORKSPACE_HOME",
  params: { workspaceId: WORKSPACE_ID },
});
const OUTSIDE_TEXT = "바깥 영역";
const LOGOUT_REQUEST = `*${AUTH_LOGOUT_API_PATH}`;
const CURRENT_RECORDING_REQUEST =
  "*/api/v1/workspaces/:workspaceId/recordings/current";
const END_RECORDING_REQUEST =
  "*/api/v1/workspaces/:workspaceId/recordings/:recordingId/end";
const CACHED_QUERY_KEY = ["cached"];
const END_DIALOG_NAME = "녹음을 끝내고 로그아웃할까요?";
const ELSEWHERE_DIALOG_NAME = "다른 곳에서 녹음 중이에요";
const DRAFT = { type: null, content: "앞 계정이 적던 내용" };
const RETURN_PATH = "/invite/credential";
const OTHER_DIALOG_NAME = "다른 화면이 띄운 창";
const OTHER_DIALOG_OPENER_TEXT = "다른 창 열기";

/** 현재 녹음 조회가 녹음 없음(204)으로 답하게 해요. 기본 mock은 다른 탭에서 녹음 중이에요 */
const respondNoRecording = () =>
  mockServer.use(
    http.get(
      CURRENT_RECORDING_REQUEST,
      () => new HttpResponse(null, { status: 204 }),
    ),
  );

/** 로그아웃 요청이 몇 번 나갔는지 세요 */
const countLogoutRequests = () => {
  const counter = { count: 0 };

  mockServer.use(
    http.post(LOGOUT_REQUEST, () => {
      counter.count += 1;

      return new HttpResponse(null, { status: 204 });
    }),
  );

  return counter;
};

/** 로그아웃 응답을 끝내 보내지 않아 처리 중에 머물러요 */
const holdLogoutRequest = () =>
  mockServer.use(
    http.post(LOGOUT_REQUEST, async () => {
      await delay("infinite");

      return new HttpResponse(null);
    }),
  );

/** 메뉴 밖에서 다른 모달을 띄우는 화면을 흉내 내요 */
function OtherDialogOpener() {
  const { open } = useDialog();

  return (
    <button
      type="button"
      onClick={() =>
        open(({ close }) => (
          <div role="alertdialog" aria-label={OTHER_DIALOG_NAME}>
            <button type="button" onClick={close}>
              확인
            </button>
          </div>
        ))
      }
    >
      {OTHER_DIALOG_OPENER_TEXT}
    </button>
  );
}

const openOtherDialog = () =>
  fireEvent.click(
    screen.getByRole("button", { name: OTHER_DIALOG_OPENER_TEXT }),
  );

const renderAvatar = () => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  queryClient.setQueryData(CACHED_QUERY_KEY, "앞 계정의 데이터");

  const router = createMemoryRouter(
    [
      {
        element: (
          <ToastProvider>
            <Outlet />
          </ToastProvider>
        ),
        children: [
          {
            path: PATH_ROUTE.WORKSPACE_HOME,
            element: (
              <>
                <MemberProfileAvatar />
                <OtherDialogOpener />
                <p>{OUTSIDE_TEXT}</p>
              </>
            ),
          },
          { path: PATH_ROUTE.LOGIN, element: null },
        ],
      },
    ],
    { initialEntries: ["/", HOME_PATH], initialIndex: 1 },
  );

  render(
    <ThemeProvider theme={theme}>
      <QueryClientProvider client={queryClient}>
        <DialogProvider>
          <RouterProvider router={router} />
        </DialogProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  );

  return { router, queryClient };
};

const getTrigger = () => screen.getByRole("button", { name: "내 프로필" });
const queryMenu = () =>
  screen.queryByRole("navigation", { name: "프로필 메뉴" });
const getLogoutItem = () => screen.getByRole("button", { name: "로그아웃" });

const openMenu = () => {
  fireEvent.click(getTrigger());

  return getLogoutItem();
};

const pressLogout = () => {
  fireEvent.click(openMenu());
};

/** 처리 중에도 닫는 세 방법을 모두 써 봐요 */
const tryToCloseMenu = () => {
  fireEvent.click(getTrigger());
  fireEvent.pointerDown(screen.getByText(OUTSIDE_TEXT));
  fireEvent.keyDown(document, { key: "Escape" });
};

const expectLoggedOut = (router: ReturnType<typeof renderAvatar>["router"]) =>
  waitFor(() => expect(router.state.location.pathname).toBe(PATH_ROUTE.LOGIN));

describe("MemberProfileAvatar", () => {
  beforeEach(() => {
    respondNoRecording();
  });

  afterEach(() => {
    // 전역 저장소라 테스트끼리 녹음이 새지 않도록 처음 상태로 되돌려요
    useRecordingStore.getState().discardRecording();
    clearRecordingStartProof();
    sessionStorage.clear();
    vi.mocked(captureAnalyticsEvent).mockClear();
  });

  /** 녹음은 독에서 증명을 만들어 시작하므로, 같은 순서로 미리 시작해 둬요 */
  const startRecording = async () => {
    getRecordingStartProof();

    await act(async () => {
      await useRecordingStore.getState().connectMicrophone();
      useRecordingStore.getState().startRecording({
        workspaceId: Number(WORKSPACE_ID),
        recordingId: 10,
      });
    });
  };

  describe("프로필 메뉴", () => {
    it("[AUTH-R17] 프로필 사진을 누르면 메뉴가 열리고 「로그아웃」 하나만 보인다", () => {
      renderAvatar();

      expect(queryMenu()).not.toBeInTheDocument();

      openMenu();

      expect(queryMenu()).toBeInTheDocument();
      expect(getTrigger()).toHaveAttribute("aria-expanded", "true");
      expect(getLogoutItem()).toHaveFocus();
      expect(
        within(queryMenu() as HTMLElement).getAllByRole("button"),
      ).toHaveLength(1);
    });

    it.each([
      {
        way: "프로필 사진을 다시 누르면",
        close: () => fireEvent.click(getTrigger()),
      },
      {
        way: "바깥을 누르면",
        close: () => fireEvent.pointerDown(screen.getByText(OUTSIDE_TEXT)),
      },
      {
        way: "ESC를 누르면",
        close: () => fireEvent.keyDown(document, { key: "Escape" }),
      },
    ])("[AUTH-R17] $way 메뉴가 닫힌다", ({ close }) => {
      renderAvatar();
      openMenu();

      close();

      expect(queryMenu()).not.toBeInTheDocument();
    });
  });

  describe("로그아웃", () => {
    it("[AUTH-R18] 「로그아웃」을 누르면 확인 없이 쿼리 캐시를 비우고 지금 기록을 바꿔 로그인 화면으로 간다", async () => {
      const { router, queryClient } = renderAvatar();

      pressLogout();

      await expectLoggedOut(router);
      expect(router.state.historyAction).toBe("REPLACE");
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(queryClient.getQueryData(CACHED_QUERY_KEY)).toBeUndefined();
    });

    it("[AUTH-R20] 성공하면 피드백 초안, 마지막 대화, 돌아갈 주소를 지운다", async () => {
      saveFeedbackDraft(DRAFT);
      saveLastConversationId({ workspaceId: "1", conversationId: 10 });
      saveLastConversationId({ workspaceId: "2", conversationId: 20 });
      saveAuthReturnPath(RETURN_PATH);
      const { router } = renderAvatar();

      pressLogout();

      await expectLoggedOut(router);
      expect(readFeedbackDraft().content).toBe("");
      expect(readLastConversationId("1")).toBeUndefined();
      expect(readLastConversationId("2")).toBeUndefined();
      expect(readAuthReturnPath()).toBeUndefined();
    });

    it("[AUTH-R21] 로그아웃 뒤의 /me 401과 재발급 401이 로그인 만료 기록을 남기지 않는다", async () => {
      // 실제 서버처럼 로그아웃한 뒤부터 로그인 확인과 재발급을 거절해요
      let isLoggedOut = false;
      mockServer.use(
        http.post(LOGOUT_REQUEST, () => {
          isLoggedOut = true;

          return new HttpResponse(null, { status: 204 });
        }),
        http.get(`*${AUTH_ME_API_PATH}`, () =>
          isLoggedOut
            ? new HttpResponse(null, { status: 401 })
            : HttpResponse.json(meResponse),
        ),
        http.post(
          `*${AUTH_REFRESH_API_PATH}`,
          () => new HttpResponse(null, { status: 401 }),
        ),
      );
      markAuthSessionConfirmed();
      const { router } = renderAvatar();

      pressLogout();
      await expectLoggedOut(router);
      await expect(getMeApi()).rejects.toThrow();

      expect(consumeAuthSessionExpired()).toBe(false);
    });

    it("[AUTH-R19] 요청하는 동안 「로그아웃」은 처리 중이고 다시 눌러도 요청이 겹치지 않으며 메뉴가 닫히지 않는다", async () => {
      let requestCount = 0;
      mockServer.use(
        http.post(LOGOUT_REQUEST, async () => {
          requestCount += 1;
          await delay("infinite");

          return new HttpResponse(null);
        }),
      );
      renderAvatar();

      pressLogout();

      await waitFor(() =>
        expect(getLogoutItem()).toHaveAttribute("aria-disabled", "true"),
      );
      await waitFor(() => expect(requestCount).toBe(1));

      fireEvent.click(getLogoutItem());
      tryToCloseMenu();
      // 겹친 요청은 조회를 거쳐 나가므로 그만큼 기다린 뒤에 세요
      await act(() => delay(100));

      expect(queryMenu()).toBeInTheDocument();
      expect(requestCount).toBe(1);
    });

    it("[AUTH-R18] 요청하는 동안 시작한 녹음은 로그아웃하면 함께 버린다", async () => {
      let finishLogoutRequest = () => {};
      const logoutResponse = new Promise<void>((resolve) => {
        finishLogoutRequest = resolve;
      });
      mockServer.use(
        http.post(LOGOUT_REQUEST, async () => {
          await logoutResponse;

          return new HttpResponse(null, { status: 204 });
        }),
      );
      const { router } = renderAvatar();
      pressLogout();
      await waitFor(() =>
        expect(getLogoutItem()).toHaveAttribute("aria-disabled", "true"),
      );
      await act(() => delay(50));

      await startRecording();
      finishLogoutRequest();

      await expectLoggedOut(router);
      expect(useRecordingStore.getState().status).toBe("idle");
    });

    it("[AUTH-R20] 처리 중에 다른 화면이 띄운 창은 로그아웃하면 함께 닫는다", async () => {
      useRecordingStore.setState({ isUploadingChunks: true });
      const { router } = renderAvatar();
      pressLogout();
      await waitFor(() =>
        expect(getLogoutItem()).toHaveAttribute("aria-disabled", "true"),
      );
      openOtherDialog();

      act(() => useRecordingStore.setState({ isUploadingChunks: false }));

      await expectLoggedOut(router);
      await waitFor(() =>
        expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument(),
      );
    });

    it.each([
      {
        cause: "서버 오류",
        response: () => new HttpResponse(null, { status: 500 }),
      },
      { cause: "연결 문제", response: () => HttpResponse.error() },
    ])(
      "[AUTH-R19] $cause로 실패하면 오류 토스트를 띄우고 메뉴와 화면, 기록을 그대로 두어 바로 다시 누를 수 있다",
      async ({ response }) => {
        mockServer.use(http.post(LOGOUT_REQUEST, response));
        saveFeedbackDraft(DRAFT);
        saveAuthReturnPath(RETURN_PATH);
        markAuthSessionConfirmed();
        const { router, queryClient } = renderAvatar();

        pressLogout();

        expect(
          await screen.findByText(TOAST_MESSAGES.LOGOUT_FAILED.message),
        ).toBeInTheDocument();
        expect(router.state.location.pathname).toBe(HOME_PATH);
        expect(queryMenu()).toBeInTheDocument();
        expect(getLogoutItem()).toHaveAttribute("aria-disabled", "false");
        expect(queryClient.getQueryData(CACHED_QUERY_KEY)).toBeDefined();
        expect(readFeedbackDraft().content).toBe(DRAFT.content);
        expect(readAuthReturnPath()).toBe(RETURN_PATH);

        respondNoRecording();
        mockServer.use(
          http.post(
            LOGOUT_REQUEST,
            () => new HttpResponse(null, { status: 204 }),
          ),
        );
        fireEvent.click(getLogoutItem());

        await expectLoggedOut(router);
      },
    );
  });

  describe("이 탭에서 녹음 중일 때", () => {
    it("[AUTH-R22] 녹음 중에 「로그아웃」을 누르면 확인 창이 뜨고 메뉴는 뒤에 열려 있으며 로그아웃을 요청하지 않는다", async () => {
      const logoutRequests = countLogoutRequests();
      await startRecording();
      renderAvatar();

      pressLogout();

      expect(
        await screen.findByRole("dialog", { name: END_DIALOG_NAME }),
      ).toBeInTheDocument();
      expect(queryMenu()).toBeInTheDocument();
      expect(logoutRequests.count).toBe(0);
    });

    it("[AUTH-R22] 확인 창이 다른 창으로 바뀌어도 그 창을 닫으면 메뉴를 다시 닫을 수 있다", async () => {
      await startRecording();
      renderAvatar();
      pressLogout();
      await screen.findByRole("dialog", { name: END_DIALOG_NAME });

      openOtherDialog();
      fireEvent.click(screen.getByRole("button", { name: "확인" }));
      await waitFor(() =>
        expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument(),
      );

      fireEvent.keyDown(document, { key: "Escape" });

      expect(queryMenu()).not.toBeInTheDocument();
    });

    it("[AUTH-R22] 일시정지 중에도 확인 창이 뜬다", async () => {
      await startRecording();
      await act(() => useRecordingStore.getState().pauseRecording());
      renderAvatar();

      pressLogout();

      expect(
        await screen.findByRole("dialog", { name: END_DIALOG_NAME }),
      ).toBeInTheDocument();
    });

    it.each([
      {
        way: "「취소」를 누르면",
        cancel: () =>
          fireEvent.click(screen.getByRole("button", { name: "취소" })),
      },
      {
        way: "바깥을 누르면",
        cancel: (dialog: HTMLElement) => {
          fireEvent.pointerDown(dialog.parentElement!);
          fireEvent.click(dialog.parentElement!);
        },
      },
      {
        way: "ESC를 누르면",
        cancel: () => fireEvent.keyDown(document, { key: "Escape" }),
      },
    ])(
      "[AUTH-R22] $way 확인 창만 닫히고 메뉴와 녹음은 그대로다",
      async ({ cancel }) => {
        const logoutRequests = countLogoutRequests();
        await startRecording();
        renderAvatar();
        pressLogout();

        cancel(await screen.findByRole("dialog", { name: END_DIALOG_NAME }));

        await waitFor(() =>
          expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
        );
        expect(queryMenu()).toBeInTheDocument();
        expect(useRecordingStore.getState().status).toBe("recording");
        expect(logoutRequests.count).toBe(0);
      },
    );

    it("[AUTH-R22] 「끝내고 로그아웃」을 누르면 창을 바로 닫고 메뉴에 처리 중을 보인 뒤 녹음을 끝내고 로그아웃한다", async () => {
      const logoutRequests = countLogoutRequests();
      await startRecording();
      const { router } = renderAvatar();
      pressLogout();

      fireEvent.click(
        await screen.findByRole("button", { name: "끝내고 로그아웃" }),
      );

      await waitFor(() =>
        expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
      );
      await expectLoggedOut(router);
      expect(useRecordingStore.getState().status).toBe("idle");
      expect(logoutRequests.count).toBe(1);
    });

    it("「끝내고 로그아웃」으로 끝낸 녹음은 로그아웃 확인 창에서 끝냈다고 사용 기록을 남긴다", async () => {
      await startRecording();
      const { router } = renderAvatar();
      pressLogout();

      fireEvent.click(
        await screen.findByRole("button", { name: "끝내고 로그아웃" }),
      );

      await expectLoggedOut(router);
      expect(captureAnalyticsEvent).toHaveBeenCalledWith({
        event: "recording_ended",
        properties: expect.objectContaining({
          surface: "logout",
          recording_id: 10,
          workspace_id: Number(WORKSPACE_ID),
        }),
      });
    });

    it("[AUTH-R22] 녹음을 끝내는 동안 「로그아웃」은 처리 중이고 메뉴가 닫히지 않는다", async () => {
      const logoutRequests = countLogoutRequests();
      mockServer.use(
        http.post(END_RECORDING_REQUEST, async () => {
          await delay("infinite");

          return new HttpResponse(null);
        }),
      );
      await startRecording();
      renderAvatar();
      pressLogout();

      fireEvent.click(
        await screen.findByRole("button", { name: "끝내고 로그아웃" }),
      );

      await waitFor(() =>
        expect(getLogoutItem()).toHaveAttribute("aria-disabled", "true"),
      );
      tryToCloseMenu();

      expect(queryMenu()).toBeInTheDocument();
      expect(logoutRequests.count).toBe(0);
    });

    it("[AUTH-R22] WebM 녹음을 끝내지 못하면(notEnded) 로그아웃을 요청하지 않고 메뉴는 열린 채 기본으로 돌아오며 기존 토스트를 띄운다", async () => {
      const logoutRequests = countLogoutRequests();
      mockServer.use(
        http.post(
          END_RECORDING_REQUEST,
          () => new HttpResponse(null, { status: 500 }),
        ),
      );
      await startRecording();
      const { router } = renderAvatar();
      pressLogout();

      fireEvent.click(
        await screen.findByRole("button", { name: "끝내고 로그아웃" }),
      );

      expect(
        await screen.findByText(TOAST_MESSAGES.RECORDING_END_FAILED.message),
      ).toBeInTheDocument();
      await waitFor(() =>
        expect(getLogoutItem()).toHaveAttribute("aria-disabled", "false"),
      );
      expect(queryMenu()).toBeInTheDocument();
      expect(useRecordingStore.getState().status).toBe("recording");
      expect(router.state.location.pathname).toBe(HOME_PATH);
      expect(logoutRequests.count).toBe(0);
    });

    it("[AUTH-R22] 끝내기를 기다리는 동안 서버가 먼저 녹음을 끝내면 안내 없이 로그아웃한다", async () => {
      mockServer.use(
        http.post(END_RECORDING_REQUEST, () =>
          HttpResponse.json(
            { code: "RECORDING_ALREADY_ENDED", message: "이미 끝난 녹음" },
            { status: 409 },
          ),
        ),
      );
      await startRecording();
      const { router } = renderAvatar();
      pressLogout();

      fireEvent.click(
        await screen.findByRole("button", { name: "끝내고 로그아웃" }),
      );

      await expectLoggedOut(router);
      expect(
        screen.queryByText(TOAST_MESSAGES.RECORDING_END_FAILED.message),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByText(TOAST_MESSAGES.LOGOUT_FAILED.message),
      ).not.toBeInTheDocument();
    });

    it("[AUTH-R22] 확인 창이 떠 있는 사이 녹음이 비워졌으면 끝낼 것 없이 로그아웃한다", async () => {
      await startRecording();
      const { router } = renderAvatar();
      pressLogout();
      const endButton = await screen.findByRole("button", {
        name: "끝내고 로그아웃",
      });

      act(() => useRecordingStore.getState().discardRecording());
      fireEvent.click(endButton);

      await expectLoggedOut(router);
    });
  });

  describe("끝낸 녹음을 올리는 동안", () => {
    it("[AUTH-R22] 소리를 올리는 동안 「로그아웃」은 처리 중이고 요청하지 않으며, 다 올리면 로그아웃한다", async () => {
      const logoutRequests = countLogoutRequests();
      useRecordingStore.setState({ isUploadingChunks: true });
      const { router } = renderAvatar();

      pressLogout();

      await waitFor(() =>
        expect(getLogoutItem()).toHaveAttribute("aria-disabled", "true"),
      );
      tryToCloseMenu();
      expect(queryMenu()).toBeInTheDocument();
      expect(logoutRequests.count).toBe(0);

      act(() => useRecordingStore.setState({ isUploadingChunks: false }));

      await expectLoggedOut(router);
      expect(logoutRequests.count).toBe(1);
    });

    it("[AUTH-R22] 다른 화면에서 시작한 끝내기가 끝날 때까지 기다린 뒤 로그아웃한다", async () => {
      const logoutRequests = countLogoutRequests();
      await startRecording();
      useRecordingStore.setState({ isEnding: true });
      const { router } = renderAvatar();

      pressLogout();

      await waitFor(() =>
        expect(getLogoutItem()).toHaveAttribute("aria-disabled", "true"),
      );
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(logoutRequests.count).toBe(0);

      act(() => useRecordingStore.getState().discardRecording());

      await expectLoggedOut(router);
    });

    it("[AUTH-R22] 다른 화면에서 시작한 끝내기가 실패해 녹음이 이어지면 로그아웃하지 않는다", async () => {
      const logoutRequests = countLogoutRequests();
      await startRecording();
      useRecordingStore.setState({ isEnding: true });
      renderAvatar();
      pressLogout();

      await waitFor(() =>
        expect(getLogoutItem()).toHaveAttribute("aria-disabled", "true"),
      );

      act(() => useRecordingStore.getState().cancelEnding());

      await waitFor(() =>
        expect(getLogoutItem()).toHaveAttribute("aria-disabled", "false"),
      );
      expect(logoutRequests.count).toBe(0);
    });
  });

  describe("이 탭이 아닌 곳에서 녹음 중일 때", () => {
    const respondRecordingElsewhere = (status: string) =>
      mockServer.use(
        http.get(CURRENT_RECORDING_REQUEST, () =>
          HttpResponse.json({ ...currentRecordingResponse, status }),
        ),
      );

    it.each(["RECORDING", "PAUSED"])(
      "[AUTH-R22] 현재 녹음 조회가 %s이면 알림 창이 뜨고 메뉴는 뒤에 열려 있으며 로그아웃을 요청하지 않는다",
      async (status) => {
        const logoutRequests = countLogoutRequests();
        respondRecordingElsewhere(status);
        renderAvatar();

        pressLogout();

        expect(
          await screen.findByRole("dialog", { name: ELSEWHERE_DIALOG_NAME }),
        ).toBeInTheDocument();
        expect(queryMenu()).toBeInTheDocument();
        expect(logoutRequests.count).toBe(0);
      },
    );

    it("[AUTH-R22] 받아 둔 응답이 아니라 누른 때 새로 조회한다", async () => {
      let lookupCount = 0;
      mockServer.use(
        http.get(CURRENT_RECORDING_REQUEST, () => {
          lookupCount += 1;

          return new HttpResponse(null, { status: 204 });
        }),
      );
      const { router } = renderAvatar();

      pressLogout();

      await expectLoggedOut(router);
      expect(lookupCount).toBe(1);
    });

    it("[AUTH-R22] 조회하는 동안 「로그아웃」은 처리 중이다", async () => {
      mockServer.use(
        http.get(CURRENT_RECORDING_REQUEST, async () => {
          await delay("infinite");

          return new HttpResponse(null);
        }),
      );
      renderAvatar();

      pressLogout();

      await waitFor(() =>
        expect(getLogoutItem()).toHaveAttribute("aria-disabled", "true"),
      );
    });

    it("[AUTH-R22] 표시할 녹음이 없으면(204) 알림 창 없이 로그아웃한다", async () => {
      const { router } = renderAvatar();

      pressLogout();

      await expectLoggedOut(router);
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });

    it("[AUTH-R22] 끝난 녹음을 정리하는 중이면 알림 창 없이 로그아웃한다", async () => {
      respondRecordingElsewhere("PROCESSING");
      const { router } = renderAvatar();

      pressLogout();

      await expectLoggedOut(router);
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });

    it("[AUTH-R22] 조회에 실패하면 알리지 않고 로그아웃을 이어 간다", async () => {
      mockServer.use(
        http.get(
          CURRENT_RECORDING_REQUEST,
          () => new HttpResponse(null, { status: 500 }),
        ),
      );
      const { router } = renderAvatar();

      pressLogout();

      await expectLoggedOut(router);
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });

    it("[AUTH-R22] 알림 창이 떠 있는 동안 키보드로 메뉴의 「로그아웃」을 다시 눌러도 조회와 로그아웃이 나가지 않는다", async () => {
      const logoutRequests = countLogoutRequests();
      let lookupCount = 0;
      mockServer.use(
        http.get(CURRENT_RECORDING_REQUEST, () => {
          lookupCount += 1;

          return HttpResponse.json({
            ...currentRecordingResponse,
            status: "RECORDING",
          });
        }),
      );
      renderAvatar();
      pressLogout();
      await screen.findByRole("dialog", { name: ELSEWHERE_DIALOG_NAME });

      fireEvent.click(within(queryMenu() as HTMLElement).getByRole("button"));
      await act(() => delay(100));

      expect(lookupCount).toBe(1);
      expect(logoutRequests.count).toBe(0);
    });

    it("[AUTH-R22] 알림 창에서 「로그아웃」을 누르면 창을 바로 닫고 메뉴에 처리 중을 보인다", async () => {
      respondRecordingElsewhere("RECORDING");
      holdLogoutRequest();
      const { router } = renderAvatar();
      pressLogout();
      const dialog = await screen.findByRole("dialog", {
        name: ELSEWHERE_DIALOG_NAME,
      });

      fireEvent.click(within(dialog).getByRole("button", { name: "로그아웃" }));

      await waitFor(() =>
        expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
      );
      expect(getLogoutItem()).toHaveAttribute("aria-disabled", "true");
      expect(router.state.location.pathname).toBe(HOME_PATH);
    });

    it("[AUTH-R22] 알림 창에서 「로그아웃」을 누르면 로그아웃하고 로그인 화면으로 간다", async () => {
      respondRecordingElsewhere("RECORDING");
      const { router } = renderAvatar();
      pressLogout();
      const dialog = await screen.findByRole("dialog", {
        name: ELSEWHERE_DIALOG_NAME,
      });

      fireEvent.click(within(dialog).getByRole("button", { name: "로그아웃" }));

      await expectLoggedOut(router);
    });

    it.each([
      {
        way: "「취소」를 누르면",
        cancel: () =>
          fireEvent.click(screen.getByRole("button", { name: "취소" })),
      },
      {
        way: "ESC를 누르면",
        cancel: () => fireEvent.keyDown(document, { key: "Escape" }),
      },
    ])(
      "[AUTH-R22] 알림 창에서 $way 창만 닫히고 메뉴는 기본 상태로 남으며 로그아웃하지 않는다",
      async ({ cancel }) => {
        const logoutRequests = countLogoutRequests();
        respondRecordingElsewhere("RECORDING");
        renderAvatar();
        pressLogout();
        await screen.findByRole("dialog", { name: ELSEWHERE_DIALOG_NAME });

        cancel();

        await waitFor(() =>
          expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
        );
        expect(queryMenu()).toBeInTheDocument();
        expect(getLogoutItem()).toHaveAttribute("aria-disabled", "false");
        expect(logoutRequests.count).toBe(0);
      },
    );
  });
});
