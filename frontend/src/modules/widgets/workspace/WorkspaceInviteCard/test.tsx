import { PostWorkspaceInvitationResponseDto } from "@api/dto/workspaceInvitation";
import { WORKSPACE_INVITATIONS_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]/invitations";
import { workspaceInvitationResponse } from "@api/mock/responses/workspaceInvitation";
import { mockServer } from "@api/mock/server";
import { TOAST_MESSAGES } from "@constants/toastMessages";
import { ThemeProvider } from "@emotion/react";
import { ToastProvider } from "@provider/context/toastContext";
import { theme } from "@provider/themeProvider";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { delay, http, HttpResponse } from "msw";
import { createMemoryRouter, Outlet, RouterProvider } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import WorkspaceInviteCard from ".";

vi.mock("@utils/captureAnalyticsEvent", () => ({
  captureAnalyticsEvent: vi.fn(),
}));

const expected = new PostWorkspaceInvitationResponseDto(
  workspaceInvitationResponse,
);
const WORKSPACE_ID = 1;
const WORKSPACE_HOME_PATH = getRouterPath({
  routeKey: "WORKSPACE_HOME",
  params: { workspaceId: String(WORKSPACE_ID) },
});
const DISPLAY_INVITE_LINK = `/invite/${expected.linkToken}`;
const ELSEWHERE_PATH = "/elsewhere";
const COPIED_DURATION_MS = 2000;
// 홈의 팀원 초대 카드(ERR-09)와 같은 문구예요
const LOAD_FAILED_TEXT = "초대 링크를 불러오지 못했어요. 다시 시도해 주세요.";

const writeText = vi.fn<(text: string) => Promise<void>>();

const renderCard = (workspaceId = String(WORKSPACE_ID)) => {
  const invitePath = getRouterPath({
    routeKey: "WORKSPACE_INVITE",
    params: { workspaceId },
  });
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const router = createMemoryRouter(
    [
      {
        element: (
          <ToastProvider>
            <Outlet />
          </ToastProvider>
        ),
        children: [
          { path: ELSEWHERE_PATH, element: <p>다른 화면</p> },
          {
            path: PATH_ROUTE.WORKSPACE_INVITE,
            element: <WorkspaceInviteCard />,
          },
          { path: PATH_ROUTE.WORKSPACE_HOME, element: <p>워크스페이스 홈</p> },
          { path: PATH_ROUTE.WORKSPACE, element: <p>워크스페이스 선택</p> },
          { path: PATH_ROUTE.LOGIN, element: <p>로그인</p> },
        ],
      },
    ],
    { initialEntries: [ELSEWHERE_PATH, invitePath] },
  );

  render(
    <ThemeProvider theme={theme}>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </ThemeProvider>,
  );

  return { router };
};

const getCodeButton = () => screen.getByRole("button", { name: /^참여 코드/ });
const getCopyLinkButton = () => screen.getByRole("button", { name: "복사" });
const getLinkInput = () => screen.getByRole("textbox", { name: "초대 링크" });

const waitForInvitation = () => screen.findByText(expected.code);

const overrideInvitationStatus = (status: number) => {
  mockServer.use(
    http.post(
      `*${WORKSPACE_INVITATIONS_API_PATH(WORKSPACE_ID)}`,
      () => new HttpResponse(null, { status }),
    ),
  );
};

const holdInvitationResponse = () => {
  const started = vi.fn();
  mockServer.use(
    http.post(`*${WORKSPACE_INVITATIONS_API_PATH(WORKSPACE_ID)}`, async () => {
      started();
      await delay("infinite");
      return HttpResponse.json(workspaceInvitationResponse);
    }),
  );
  return started;
};

const click = async (element: HTMLElement) => {
  await act(async () => {
    fireEvent.click(element);
  });
};

const advanceTimers = async (ms: number) => {
  await act(async () => {
    vi.advanceTimersByTime(ms);
  });
};

const goBack = async (router: ReturnType<typeof createMemoryRouter>) => {
  await act(async () => {
    await router.navigate(-1);
  });
};

describe("WorkspaceInviteCard", () => {
  beforeEach(() => {
    writeText.mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText },
      configurable: true,
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    writeText.mockReset();
    Reflect.deleteProperty(navigator, "clipboard");
  });

  it("초대를 불러오는 동안에는 코드와 링크 복사를 막는다", async () => {
    const requestStarted = holdInvitationResponse();
    renderCard();

    expect(getCodeButton()).toBeDisabled();
    expect(getCodeButton()).toHaveAttribute("aria-busy", "true");
    expect(getCopyLinkButton()).toBeDisabled();
    expect(getCopyLinkButton()).toHaveAttribute("aria-busy", "true");
    expect(getLinkInput()).toHaveValue("");
    // 이 테스트의 지연 요청이 다음 테스트의 요청 관찰에 섞이지 않게 시작까지 기다려요.
    await waitFor(() => expect(requestStarted).toHaveBeenCalledOnce());
  });

  it("workspaceId가 정수가 아니면 초대를 요청하지 않고 복사를 막는다", async () => {
    const requestedUrls: string[] = [];
    const listener = ({ request }: { request: Request }) => {
      requestedUrls.push(request.url);
    };
    mockServer.events.on("request:start", listener);

    renderCard("abc");
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 50));
    });
    mockServer.events.removeListener("request:start", listener);

    expect(requestedUrls).not.toContainEqual(
      expect.stringContaining("/invitations"),
    );
    expect(getCodeButton()).toBeDisabled();
    expect(getCopyLinkButton()).toBeDisabled();
  });

  it("초대 응답의 참여 코드와 초대 링크를 보여준다", async () => {
    renderCard();

    expect(await waitForInvitation()).toBeInTheDocument();
    expect(getCodeButton()).toBeEnabled();
    expect(getLinkInput()).toHaveValue(DISPLAY_INVITE_LINK);
    expect(getLinkInput()).toHaveAttribute("readonly");
    expect(getCopyLinkButton()).toBeEnabled();
  });

  it("참여 코드를 누르면 응답의 코드를 복사한다", async () => {
    renderCard();
    await waitForInvitation();

    await click(getCodeButton());

    expect(writeText).toHaveBeenCalledWith(expected.code);
  });

  it("복사를 누르면 전체 링크를 복사하고 2초 동안 복사됨을 보여준다", async () => {
    renderCard();
    await waitForInvitation();
    vi.useFakeTimers();

    await click(getCopyLinkButton());

    expect(writeText).toHaveBeenCalledWith(
      `${window.location.origin}${DISPLAY_INVITE_LINK}`,
    );
    expect(screen.getByRole("button", { name: "복사됨" })).toBeInTheDocument();

    await advanceTimers(COPIED_DURATION_MS - 1);

    expect(screen.getByRole("button", { name: "복사됨" })).toBeInTheDocument();

    await advanceTimers(1);

    expect(getCopyLinkButton()).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "복사됨" }),
    ).not.toBeInTheDocument();
  });

  it("복사에 성공하면 알림 창 없이 버튼 표시로만 알린다", async () => {
    const alert = vi.spyOn(window, "alert");
    renderCard();
    await waitForInvitation();

    await click(getCodeButton());
    await click(getCopyLinkButton());

    expect(alert).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "복사됨" })).toBeInTheDocument();
  });

  it("참여 코드나 링크 복사에 실패하면 오류 토스트를 띄우고 버튼은 「복사」로 남는다", async () => {
    writeText.mockRejectedValue(new Error("클립보드 권한이 없어요"));
    vi.spyOn(console, "error").mockImplementation(() => {});
    renderCard();
    await waitForInvitation();

    await click(getCopyLinkButton());

    expect(
      await screen.findByText(TOAST_MESSAGES.COPY_FAILED.message),
    ).toBeInTheDocument();
    expect(getCopyLinkButton()).toBeInTheDocument();
  });

  it("다음을 누르면 워크스페이스 홈으로 이동한다", async () => {
    const { router } = renderCard();
    await waitForInvitation();

    await click(screen.getByRole("button", { name: "다음" }));

    expect(router.state.location.pathname).toBe(WORKSPACE_HOME_PATH);
  });

  it("초대 조회가 401이면 로그인 화면으로 replace 이동한다", async () => {
    overrideInvitationStatus(401);
    const { router } = renderCard();

    await waitFor(() => {
      expect(router.state.location.pathname).toBe(PATH_ROUTE.LOGIN);
    });

    await goBack(router);

    expect(router.state.location.pathname).toBe(ELSEWHERE_PATH);
  });

  it.each([403, 404])(
    "초대 조회가 %i면 워크스페이스 선택 화면으로 replace 이동한다",
    async (status) => {
      overrideInvitationStatus(status);
      const { router } = renderCard();

      await waitFor(() => {
        expect(router.state.location.pathname).toBe(PATH_ROUTE.WORKSPACE);
      });

      await goBack(router);

      expect(router.state.location.pathname).toBe(ELSEWHERE_PATH);
    },
  );

  it.each([
    ["서버 오류(500)", () => new HttpResponse(null, { status: 500 })],
    ["요청 제한(429)", () => new HttpResponse(null, { status: 429 })],
    ["네트워크 오류", () => HttpResponse.error()],
  ])(
    "%s로 초대를 불러오지 못하면 제목과 다음은 두고, 코드와 링크 대신 실패 안내와 다시 시도를 보여준다",
    async (_, respond) => {
      mockServer.use(
        http.post(`*${WORKSPACE_INVITATIONS_API_PATH(WORKSPACE_ID)}`, respond),
      );
      renderCard();

      expect(await screen.findByRole("alert")).toHaveTextContent(
        LOAD_FAILED_TEXT,
      );
      expect(
        screen.getByRole("heading", { name: "팀원을 초대하세요" }),
      ).toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: /^참여 코드/ }),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByRole("textbox", { name: "초대 링크" }),
      ).not.toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: "다시 시도" }),
      ).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "다음" })).toBeEnabled();
    },
  );

  it("다시 시도를 누르면 초대만 다시 받아 코드와 링크를 보여준다", async () => {
    let attempts = 0;
    mockServer.use(
      http.post(`*${WORKSPACE_INVITATIONS_API_PATH(WORKSPACE_ID)}`, () => {
        attempts += 1;
        if (attempts === 1) return new HttpResponse(null, { status: 500 });

        return HttpResponse.json(workspaceInvitationResponse);
      }),
    );
    renderCard();

    await click(await screen.findByRole("button", { name: "다시 시도" }));

    expect(await waitForInvitation()).toBeInTheDocument();
    expect(getLinkInput()).toHaveValue(DISPLAY_INVITE_LINK);
    expect(screen.queryByText(LOAD_FAILED_TEXT)).not.toBeInTheDocument();
    expect(attempts).toBe(2);
  });

  it.each([401, 403, 404])(
    "초대 조회가 %i면 이동하는 동안 실패 안내를 그리지 않는다",
    async (status) => {
      overrideInvitationStatus(status);
      // 이동하면 카드가 사라져, 간 뒤에는 안내가 있었는지 알 수 없어요. 가기 전까지 그려진 화면을 모두 지켜봐요
      let isAlertShown = false;
      const observer = new MutationObserver(() => {
        if (screen.queryByRole("alert") !== null) isAlertShown = true;
      });
      observer.observe(document.body, { childList: true, subtree: true });

      const { router } = renderCard();

      try {
        await waitFor(() => {
          expect(router.state.location.pathname).not.toMatch(/invite/);
        });
      } finally {
        observer.disconnect();
      }

      expect(isAlertShown).toBe(false);
    },
  );

  describe("초대 복사 사용 기록", () => {
    // 앞 테스트에서도 사용 기록이 남으므로, 테스트마다 기록을 비우고 시작해요
    beforeEach(() => {
      vi.mocked(captureAnalyticsEvent).mockClear();
    });

    it.each([
      { name: "참여 코드", target: "code", getButton: getCodeButton },
      { name: "초대 링크", target: "link", getButton: getCopyLinkButton },
    ] as const)(
      "$name 복사에 성공하면 워크스페이스를 만든 뒤의 초대 화면에서 복사했다고 한 번 보낸다",
      async ({ target, getButton }) => {
        renderCard();
        await waitForInvitation();

        await click(getButton());

        expect(captureAnalyticsEvent).toHaveBeenCalledTimes(1);
        expect(captureAnalyticsEvent).toHaveBeenCalledWith({
          event: "invite_copied",
          properties: {
            target,
            surface: "create_flow",
            workspace_id: WORKSPACE_ID,
          },
        });
      },
    );

    it("클립보드에 쓰지 못하면 보내지 않는다", async () => {
      writeText.mockRejectedValue(new Error("클립보드 권한이 없어요"));
      vi.spyOn(console, "error").mockImplementation(() => {});
      renderCard();
      await waitForInvitation();

      await click(getCodeButton());
      await click(getCopyLinkButton());

      expect(writeText).toHaveBeenCalledTimes(2);
      expect(captureAnalyticsEvent).not.toHaveBeenCalled();
    });
  });
});
