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
import { act, fireEvent, render, screen } from "@testing-library/react";
import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { delay, http, HttpResponse } from "msw";
import { createMemoryRouter, Outlet, RouterProvider } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import WorkspaceInviteMemberCard from ".";

vi.mock("@utils/captureAnalyticsEvent", () => ({
  captureAnalyticsEvent: vi.fn(),
}));

const expected = new PostWorkspaceInvitationResponseDto(
  workspaceInvitationResponse,
);
const WORKSPACE_ID = 1;
const HOME_PATH = getRouterPath({
  routeKey: "WORKSPACE_HOME",
  params: { workspaceId: String(WORKSPACE_ID) },
});
const DISPLAY_INVITE_LINK = `/invite/${expected.linkToken}`;
const COPIED_DURATION_MS = 2000;
const LOAD_FAILED_TEXT = "초대 링크를 불러오지 못했어요. 다시 시도해 주세요.";

const writeText = vi.fn<(text: string) => Promise<void>>();

const renderCard = () => {
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
          {
            path: PATH_ROUTE.WORKSPACE_HOME,
            element: <WorkspaceInviteMemberCard />,
          },
        ],
      },
    ],
    { initialEntries: [HOME_PATH] },
  );

  render(
    <ThemeProvider theme={theme}>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </ThemeProvider>,
  );

  const linkInput = screen.getByRole("textbox", { name: "초대 링크" });
  const copyLinkButton = screen.getByRole("button", { name: "복사" });
  const copyCodeButton = screen.getByRole("button", { name: "초대 코드 복사" });

  return { linkInput, copyLinkButton, copyCodeButton };
};

const waitForInvitation = () => screen.findByDisplayValue(DISPLAY_INVITE_LINK);

const holdInvitationResponse = () => {
  mockServer.use(
    http.post(`*${WORKSPACE_INVITATIONS_API_PATH(WORKSPACE_ID)}`, async () => {
      await delay("infinite");
      return HttpResponse.json(workspaceInvitationResponse);
    }),
  );
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

describe("WorkspaceInviteMemberCard", () => {
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

  it("초대를 불러오는 동안에는 링크와 코드 복사를 막는다", () => {
    holdInvitationResponse();
    const { linkInput, copyLinkButton, copyCodeButton } = renderCard();

    expect(linkInput).toHaveValue("");
    expect(copyLinkButton).toBeDisabled();
    expect(copyLinkButton).toHaveAttribute("aria-busy", "true");
    expect(copyCodeButton).toBeDisabled();
  });

  it("초대 응답의 링크를 읽기 전용으로 보여준다", async () => {
    const { linkInput, copyLinkButton, copyCodeButton } = renderCard();

    expect(await waitForInvitation()).toBeInTheDocument();
    expect(linkInput).toHaveAttribute("readonly");
    expect(copyLinkButton).toBeEnabled();
    expect(copyCodeButton).toBeEnabled();
  });

  it("복사를 누르면 전체 링크를 복사하고 2초 동안 복사됨을 보여준다", async () => {
    const { copyLinkButton } = renderCard();
    await waitForInvitation();
    vi.useFakeTimers();

    await click(copyLinkButton);

    expect(writeText).toHaveBeenCalledWith(
      `${window.location.origin}${DISPLAY_INVITE_LINK}`,
    );
    expect(screen.getByRole("button", { name: "복사됨" })).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "복사" }),
    ).not.toBeInTheDocument();

    await advanceTimers(COPIED_DURATION_MS - 1);

    expect(screen.getByRole("button", { name: "복사됨" })).toBeInTheDocument();

    await advanceTimers(1);

    expect(screen.getByRole("button", { name: "복사" })).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "복사됨" }),
    ).not.toBeInTheDocument();
  });

  it("초대 코드 복사를 누르면 응답의 코드를 복사하고 2초 동안 복사됨으로 바꾼다", async () => {
    const { copyCodeButton } = renderCard();
    await waitForInvitation();
    vi.useFakeTimers();

    await click(copyCodeButton);

    expect(writeText).toHaveBeenCalledWith(expected.code);
    expect(copyCodeButton).toHaveTextContent("복사됨");
    expect(screen.getByRole("button", { name: "복사" })).toBeInTheDocument();

    await advanceTimers(COPIED_DURATION_MS);

    expect(copyCodeButton).toHaveTextContent("초대 코드 복사");
  });

  it("클립보드에 쓰지 못하면 버튼은 그대로 두고 오류 토스트를 띄운다 (ERR-08)", async () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    writeText.mockRejectedValue(new Error("denied"));
    const { copyLinkButton, copyCodeButton } = renderCard();
    await waitForInvitation();

    await click(copyLinkButton);
    await click(copyCodeButton);

    expect(copyLinkButton).toHaveTextContent("복사");
    expect(copyLinkButton).not.toHaveTextContent("복사됨");
    expect(copyCodeButton).toHaveTextContent("초대 코드 복사");
    expect(
      screen.getByText(TOAST_MESSAGES.COPY_FAILED.message),
    ).toBeInTheDocument();
  });

  it("초대를 불러오지 못하면 제목은 두고, 링크 칸과 초대 코드 복사 대신 실패 안내와 다시 시도를 보여준다 (ERR-09)", async () => {
    mockServer.use(
      http.post(
        `*${WORKSPACE_INVITATIONS_API_PATH(WORKSPACE_ID)}`,
        () => new HttpResponse(null, { status: 500 }),
      ),
    );
    renderCard();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      LOAD_FAILED_TEXT,
    );
    expect(
      screen.getByRole("heading", { name: "팀원 초대" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("textbox", { name: "초대 링크" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "초대 코드 복사" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "다시 시도" }),
    ).toBeInTheDocument();
  });

  it("다시 시도를 누르면 초대만 다시 받아 링크를 보여준다", async () => {
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
    expect(screen.queryByText(LOAD_FAILED_TEXT)).not.toBeInTheDocument();
    expect(attempts).toBe(2);
  });

  describe("초대 복사 사용 기록", () => {
    // 앞 테스트에서도 사용 기록이 남으므로, 테스트마다 기록을 비우고 시작해요
    beforeEach(() => {
      vi.mocked(captureAnalyticsEvent).mockClear();
    });

    it.each([
      { name: "초대 링크", target: "link", button: "copyLinkButton" },
      { name: "초대 코드", target: "code", button: "copyCodeButton" },
    ] as const)(
      "$name 복사에 성공하면 홈 카드에서 복사했다고 한 번 보낸다",
      async ({ target, button }) => {
        const buttons = renderCard();
        await waitForInvitation();

        await click(buttons[button]);

        expect(captureAnalyticsEvent).toHaveBeenCalledTimes(1);
        expect(captureAnalyticsEvent).toHaveBeenCalledWith({
          event: "invite_copied",
          properties: {
            target,
            surface: "home_card",
            workspace_id: WORKSPACE_ID,
          },
        });
      },
    );

    it("클립보드에 쓰지 못하면 보내지 않는다", async () => {
      vi.spyOn(console, "error").mockImplementation(() => {});
      writeText.mockRejectedValue(new Error("denied"));
      const { copyLinkButton, copyCodeButton } = renderCard();
      await waitForInvitation();

      await click(copyLinkButton);
      await click(copyCodeButton);

      expect(writeText).toHaveBeenCalledTimes(2);
      expect(captureAnalyticsEvent).not.toHaveBeenCalled();
    });
  });
});
