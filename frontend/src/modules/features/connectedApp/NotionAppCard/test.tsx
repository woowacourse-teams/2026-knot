import {
  GetSearchNotionConnectionResponseDto,
} from "@api/dto/search";
import {
  searchNotionConnectionResponse,
} from "@api/mock/responses/search";
import { mockServer } from "@api/mock/server";
import type { SearchNotionConnection } from "@api/mock/types/search";
import { ThemeProvider } from "@emotion/react";
import { DialogProvider } from "@provider/context/dialogContext";
import { ToastProvider } from "@provider/context/toastContext";
import { theme } from "@provider/themeProvider";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { createMemoryRouter, Outlet, RouterProvider } from "react-router";
import { describe, expect, it } from "vitest";

import NotionAppCard from ".";

// 경로 파라미터에 무엇이 와도 잡도록 fetch 상수 대신 패턴을 적어요
const CONNECTION_REQUEST =
  "*/api/v1/workspaces/:workspaceId/search/notion-connection";

const expected = new GetSearchNotionConnectionResponseDto(
  searchNotionConnectionResponse,
);
const NOT_CONNECTED = {
  status: "NOT_CONNECTED",
  notionWorkspaceName: null,
  notionWorkspaceIcon: null,
  connectedBy: null,
  canDisconnect: false,
} satisfies SearchNotionConnection;

const APPS_PATH = getRouterPath({
  routeKey: "APPS",
  params: { workspaceId: "1" },
});

const EXPIRED_NOTICE = "노션 연결이 만료됐어요";

/** 노션 연결 조회가 이 응답을 돌려주게 해요 */
const mockConnection = (connection: SearchNotionConnection) =>
  mockServer.use(
    http.get(CONNECTION_REQUEST, () => HttpResponse.json(connection)),
  );

const renderCard = (path = APPS_PATH) => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const router = createMemoryRouter(
    [
      {
        // 앱(AppProviders)처럼 모달이 토스트 안에 있어야 모달 안에서 토스트를 띄워요
        element: (
          <ToastProvider>
            <DialogProvider>
              <Outlet />
            </DialogProvider>
          </ToastProvider>
        ),
        children: [{ path: PATH_ROUTE.APPS, element: <NotionAppCard /> }],
      },
    ],
    { initialEntries: [path] },
  );

  render(
    <ThemeProvider theme={theme}>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </ThemeProvider>,
  );

  return { router, card: screen.getByRole("article", { name: "노션" }) };
};

describe("NotionAppCard", () => {
  describe("연결됨", () => {
    it("연결된 노션 이름과 아이콘, 연결한 사람, 보이는 범위를 보여 주고 [연결]은 두지 않는다", async () => {
      const { card } = renderCard();

      expect(await within(card).findByText("연결됨")).toBeInTheDocument();
      expect(
        within(card).getByText(expected.notionWorkspaceName ?? ""),
      ).toBeInTheDocument();
      expect(
        within(card).getByText(expected.notionWorkspaceIcon ?? ""),
      ).toBeInTheDocument();
      expect(
        within(card).getByText(expected.connectedBy?.nickname ?? ""),
      ).toBeInTheDocument();
      expect(
        within(card).getByText(
          `${expected.connectedBy?.nickname} 님이 노션에서 볼 수 있는 내용 전부`,
        ),
      ).toBeInTheDocument();
      expect(
        within(card).queryByRole("button", { name: "연결" }),
      ).not.toBeInTheDocument();
      expect(
        within(card).getByRole("button", { name: "연결 끊기" }),
      ).toBeInTheDocument();
    });

    it("끊을 수 없는 멤버에게는 [연결 끊기]를 두지 않는다", async () => {
      mockConnection({ ...searchNotionConnectionResponse, canDisconnect: false });
      const { card } = renderCard();

      expect(await within(card).findByText("연결됨")).toBeInTheDocument();
      expect(within(card).queryByRole("button")).toHaveTextContent(
        "자세히 보기",
      );
    });

    it("노션 워크스페이스 아이콘이 이미지 주소면 그림으로 보여 준다", async () => {
      const iconUrl = "https://example.com/notion-icon.png";
      mockConnection({
        ...searchNotionConnectionResponse,
        notionWorkspaceIcon: iconUrl,
      });
      const { card } = renderCard();

      await within(card).findByText("연결됨");
      expect(card.querySelector(`img[src="${iconUrl}"]`)).not.toBeNull();
    });

    it("노션 워크스페이스 아이콘이 없으면 이름 첫 글자를 보여 준다", async () => {
      mockConnection({
        ...searchNotionConnectionResponse,
        notionWorkspaceIcon: null,
      });
      const { card } = renderCard();

      expect(
        await within(card).findByText(
          (expected.notionWorkspaceName ?? "").charAt(0),
        ),
      ).toBeInTheDocument();
    });

  });

  describe("연결 전", () => {
    it("[연결]과 한 사람이 연결하면 멤버 모두가 쓴다는 안내를 보여 준다", async () => {
      mockConnection(NOT_CONNECTED);
      const { card } = renderCard();

      expect(await within(card).findByText("연결 전")).toBeInTheDocument();
      expect(
        within(card).getByText("한 사람이 연결하면 멤버 모두가 써요."),
      ).toBeInTheDocument();
      expect(
        within(card).queryByRole("button", { name: "연결 끊기" }),
      ).not.toBeInTheDocument();
    });

  });

  describe("만료", () => {
    it("다른 멤버에게는 만료 안내와 [다시 연결]만 보여 준다", async () => {
      mockConnection({
        ...searchNotionConnectionResponse,
        status: "EXPIRED",
        canDisconnect: false,
      });
      const { card } = renderCard();

      expect(await within(card).findByText("만료")).toBeInTheDocument();
      expect(within(card).getByText(EXPIRED_NOTICE)).toBeInTheDocument();
      expect(
        within(card).queryByRole("button", { name: "연결 끊기" }),
      ).not.toBeInTheDocument();
      expect(
        within(card).getByRole("button", { name: "다시 연결" }),
      ).toBeInTheDocument();
    });

    it("연결한 사람과 소유자에게는 [연결 끊기]와 [다시 연결]을 함께 보여 준다", async () => {
      mockConnection({ ...searchNotionConnectionResponse, status: "EXPIRED" });
      const { card } = renderCard();

      expect(await within(card).findByText(EXPIRED_NOTICE)).toBeInTheDocument();
      expect(
        within(card).getByRole("button", { name: "연결 끊기" }),
      ).toBeInTheDocument();
      expect(
        within(card).getByRole("button", { name: "다시 연결" }),
      ).toBeInTheDocument();
    });
  });

  it("[자세히 보기]를 누르면 탐색이 노션으로 하는 일을 펼치고, [접기]로 접는다", async () => {
    const { card } = renderCard();

    fireEvent.click(
      await within(card).findByRole("button", { name: "자세히 보기" }),
    );

    expect(within(card).getByText("노션을 읽고 답해요")).toBeInTheDocument();
    expect(
      within(card).getByText("확인을 받고 페이지를 만들어요"),
    ).toBeInTheDocument();
    expect(within(card).getByText("멤버 모두가 함께 써요")).toBeInTheDocument();

    fireEvent.click(within(card).getByRole("button", { name: "접기" }));

    expect(
      within(card).queryByText("노션을 읽고 답해요"),
    ).not.toBeInTheDocument();
  });
});
