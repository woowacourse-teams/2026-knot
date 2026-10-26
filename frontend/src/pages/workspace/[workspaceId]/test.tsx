import { GetMeResponseDto } from "@api/dto/auth";
import { meResponse } from "@api/mock/responses/auth";
import { mockServer } from "@api/mock/server";
import { ThemeProvider } from "@emotion/react";
import { ToastProvider } from "@provider/context/toastContext";
import { theme } from "@provider/themeProvider";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { createMemoryRouter, Outlet, RouterProvider } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";

import WorkspaceHomePage from ".";

const WORKSPACE_ID = "1";
const HOME_PATH = getRouterPath({
  routeKey: "WORKSPACE_HOME",
  params: { workspaceId: WORKSPACE_ID },
});
// 경로 파라미터 자리에 무엇이 와도 잡도록 fetch 상수 대신 패턴을 적어요
const DOCUMENTS_REQUEST = "*/api/v1/workspaces/:workspaceId/documents";
const RECORDINGS_REQUEST = "*/api/v1/workspaces/:workspaceId/recordings";
const GREETING = `반가워요, ${new GetMeResponseDto(meResponse).nickname} 님`;
const PENDING_DOCUMENTS_TITLE = "확인이 필요한 문서";
const RECORDING_HISTORY_TITLE = "녹음 기록";
const INVITE_MEMBER_TITLE = "팀원 초대";

// jsdom에는 IntersectionObserver가 없어요. 녹음 기록의 다음 묶음은 받지 않으므로 아무것도 하지 않는 가짜를 둬요
class IdleIntersectionObserver {
  observe() {}

  unobserve() {}

  disconnect() {}
}

const renderHomePage = () => {
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
          { path: PATH_ROUTE.WORKSPACE_HOME, element: <WorkspaceHomePage /> },
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
};

const LOAD_FAILED_NOTICE = "목록을 불러오지 못했어요. 다시 시도해 주세요.";
const RECORDING_HISTORY_LOAD_FAILED_NOTICE =
  "녹음 기록을 불러오지 못했어요. 다시 시도해 주세요.";

const findCardTitle = (name: string) =>
  screen.findByRole("heading", { level: 2, name });

const getCard = (name: string) => screen.getByRole("region", { name });

const SERVER_ERROR = () => new HttpResponse(null, { status: 500 });

describe("WorkspaceHomePage", () => {
  beforeEach(() => {
    vi.stubGlobal("IntersectionObserver", IdleIntersectionObserver);
  });

  it("인사말과 녹음 기록 · 확인이 필요한 문서 · 팀원 초대 카드를 보여 준다", async () => {
    renderHomePage();

    expect(
      await screen.findByRole("heading", { level: 1, name: GREETING }),
    ).toBeInTheDocument();
    expect(await findCardTitle(RECORDING_HISTORY_TITLE)).toBeInTheDocument();
    expect(await findCardTitle(PENDING_DOCUMENTS_TITLE)).toBeInTheDocument();
    expect(await findCardTitle(INVITE_MEMBER_TITLE)).toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { level: 2, name: "최근 추가된 문서" }),
    ).not.toBeInTheDocument();
  });

  it("녹음 기록을 불러오지 못해도 그 카드 안에서만 알리고, 다른 카드는 그대로 보여 준다", async () => {
    mockServer.use(http.get(RECORDINGS_REQUEST, SERVER_ERROR));

    renderHomePage();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      RECORDING_HISTORY_LOAD_FAILED_NOTICE,
    );
    expect(
      within(getCard(RECORDING_HISTORY_TITLE)).getByRole("alert"),
    ).toBeInTheDocument();
    expect(
      await within(getCard(PENDING_DOCUMENTS_TITLE)).findAllByRole("listitem"),
    ).not.toHaveLength(0);
    expect(await findCardTitle(INVITE_MEMBER_TITLE)).toBeInTheDocument();
    expect(screen.getAllByRole("alert")).toHaveLength(1);
  });

  it("확인이 필요한 문서를 불러오지 못해도 그 카드 안에서만 알리고, 다른 카드는 그대로 보여 준다", async () => {
    mockServer.use(http.get(DOCUMENTS_REQUEST, SERVER_ERROR));

    renderHomePage();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      LOAD_FAILED_NOTICE,
    );
    expect(
      within(getCard(PENDING_DOCUMENTS_TITLE)).getByRole("alert"),
    ).toBeInTheDocument();
    expect(
      await within(getCard(RECORDING_HISTORY_TITLE)).findAllByRole("listitem"),
    ).not.toHaveLength(0);
    expect(await findCardTitle(INVITE_MEMBER_TITLE)).toBeInTheDocument();
    expect(screen.getAllByRole("alert")).toHaveLength(1);
  });
});
