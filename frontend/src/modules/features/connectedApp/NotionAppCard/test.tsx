import {
  GetSearchNotionConnectionResponseDto,
  PostSearchNotionConnectionAuthorizationResponseDto,
} from "@api/dto/search";
import {
  searchNotionConnectionAuthorizationResponse,
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
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { createMemoryRouter, Outlet, RouterProvider } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";

import NotionAppCard from ".";

// 경로 파라미터에 무엇이 와도 잡도록 fetch 상수 대신 패턴을 적어요
const CONNECTION_REQUEST =
  "*/api/v1/workspaces/:workspaceId/search/notion-connection";
const AUTHORIZATION_REQUEST = `${CONNECTION_REQUEST}/authorization`;

const expected = new GetSearchNotionConnectionResponseDto(
  searchNotionConnectionResponse,
);
const expectedAuthorization =
  new PostSearchNotionConnectionAuthorizationResponseDto(
    searchNotionConnectionAuthorizationResponse,
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

const CONSENT_TITLE = "노션을 연결하기 전에 확인해 주세요";
const GUEST_NOTICE = "노션 워크스페이스의 멤버만 연결할 수 있어요";
const EXPIRED_NOTICE = "노션 연결이 만료됐어요";

/** 노션 연결 조회가 이 응답을 돌려주게 해요 */
const mockConnection = (connection: SearchNotionConnection) =>
  mockServer.use(
    http.get(CONNECTION_REQUEST, () => HttpResponse.json(connection)),
  );

// jsdom은 다른 주소로 이동하지 못해요. 보내려는 주소를 받아 두는 가짜 location으로 바꿔요.
// 요청 주소를 만들 때 지금 주소를 읽으므로 읽기는 원래 값을 돌려줘요
const stubLocation = () => {
  const visitedUrls: string[] = [];
  const currentHref = window.location.href;
  vi.stubGlobal("location", {
    ...window.location,
    get href() {
      return currentHref;
    },
    set href(url: string) {
      visitedUrls.push(url);
    },
  });

  return visitedUrls;
};

afterEach(() => {
  vi.unstubAllGlobals();
});

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

    it("[연결 끊기]를 누르면 연결을 끊고 연결 전 카드로 바뀐다", async () => {
      let connection: SearchNotionConnection = searchNotionConnectionResponse;
      const deleteRequest = vi.fn();
      mockServer.use(
        http.get(CONNECTION_REQUEST, () => HttpResponse.json(connection)),
        http.delete(CONNECTION_REQUEST, () => {
          deleteRequest();
          connection = NOT_CONNECTED;

          return new HttpResponse(null, { status: 204 });
        }),
      );
      const { card } = renderCard();

      fireEvent.click(
        await within(card).findByRole("button", { name: "연결 끊기" }),
      );

      expect(await within(card).findByText("연결 전")).toBeInTheDocument();
      expect(deleteRequest).toHaveBeenCalledTimes(1);
      expect(
        within(card).getByRole("button", { name: "연결" }),
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

    it("[연결]을 누르면 보이는 범위 안내를 띄우고, [취소]면 연결을 시작하지 않고 닫는다", async () => {
      mockConnection(NOT_CONNECTED);
      const startRequest = vi.fn();
      mockServer.use(
        http.post(AUTHORIZATION_REQUEST, () => {
          startRequest();

          return HttpResponse.json(searchNotionConnectionAuthorizationResponse);
        }),
      );
      const { card } = renderCard();

      fireEvent.click(await within(card).findByRole("button", { name: "연결" }));
      const dialog = screen.getByRole("dialog", { name: CONSENT_TITLE });
      expect(
        within(dialog).getByText(
          "이 워크스페이스 멤버 모두에게 탐색 답변으로 보일 수 있어요.",
          { exact: false },
        ),
      ).toBeInTheDocument();

      fireEvent.click(within(dialog).getByRole("button", { name: "취소" }));

      await waitFor(() =>
        expect(
          screen.queryByRole("dialog", { name: CONSENT_TITLE }),
        ).not.toBeInTheDocument(),
      );
      expect(startRequest).not.toHaveBeenCalled();
    });

    it("[동의하고 연결]을 누르면 동의를 보내고 받은 노션 동의 화면 주소로 이동한다", async () => {
      mockConnection(NOT_CONNECTED);
      const startBody = vi.fn();
      mockServer.use(
        http.post(AUTHORIZATION_REQUEST, async ({ request }) => {
          startBody(await request.json());

          return HttpResponse.json(searchNotionConnectionAuthorizationResponse);
        }),
      );
      const { card } = renderCard();
      const visitedUrls = stubLocation();

      fireEvent.click(await within(card).findByRole("button", { name: "연결" }));
      fireEvent.click(
        within(screen.getByRole("dialog", { name: CONSENT_TITLE })).getByRole(
          "button",
          { name: "동의하고 연결" },
        ),
      );

      await waitFor(() =>
        expect(visitedUrls).toEqual([expectedAuthorization.authorizationUrl]),
      );
      expect(startBody).toHaveBeenCalledWith({ agreed: true });
    });

    it("그사이 다른 멤버가 연결해 연결 시작이 409로 거절되면 안내를 닫고 연결된 카드를 보여 준다", async () => {
      let connection: SearchNotionConnection = NOT_CONNECTED;
      mockServer.use(
        http.get(CONNECTION_REQUEST, () => HttpResponse.json(connection)),
        http.post(AUTHORIZATION_REQUEST, () => {
          connection = { ...searchNotionConnectionResponse, canDisconnect: false };

          return HttpResponse.json(
            { code: "NOTION_CONNECTION_ALREADY_CONNECTED" },
            { status: 409 },
          );
        }),
      );
      const { card } = renderCard();
      const visitedUrls = stubLocation();

      fireEvent.click(await within(card).findByRole("button", { name: "연결" }));
      fireEvent.click(
        within(screen.getByRole("dialog", { name: CONSENT_TITLE })).getByRole(
          "button",
          { name: "동의하고 연결" },
        ),
      );

      expect(await within(card).findByText("연결됨")).toBeInTheDocument();
      expect(
        screen.queryByRole("dialog", { name: CONSENT_TITLE }),
      ).not.toBeInTheDocument();
      expect(
        within(card).queryByRole("button", { name: "연결" }),
      ).not.toBeInTheDocument();
      expect(visitedUrls).toEqual([]);
    });
  });

  describe("만료", () => {
    it("다른 멤버에게는 만료 안내와 [다시 연결]만 보여 주고, 누르면 같은 보이는 범위 안내를 띄운다", async () => {
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

      fireEvent.click(within(card).getByRole("button", { name: "다시 연결" }));

      expect(
        screen.getByRole("dialog", { name: CONSENT_TITLE }),
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

  describe("노션 동의 화면에서 돌아왔을 때", () => {
    it("연결이 끝났으면 「노션을 연결했어요」를 알리고 주소에서 결과를 지운다", async () => {
      const { router, card } = renderCard(
        `${APPS_PATH}?notionConnection=CONNECTED`,
      );

      expect(await screen.findByText("노션을 연결했어요")).toBeInTheDocument();
      expect(await within(card).findByText("연결됨")).toBeInTheDocument();
      await waitFor(() => expect(router.state.location.search).toBe(""));
    });

    it("노션 게스트라 막혔으면 멤버만 연결할 수 있다고 안내하고 [연결]을 둔다", async () => {
      mockConnection(NOT_CONNECTED);
      const { router, card } = renderCard(
        `${APPS_PATH}?notionConnection=NOTION_GUEST_NOT_ALLOWED`,
      );

      expect(await within(card).findByText(GUEST_NOTICE)).toBeInTheDocument();
      expect(
        within(card).getByText(
          "노션에서 멤버로 바꿔 달라고 요청하거나, 노션 멤버인 다른 Knot 멤버가 연결해 주세요.",
        ),
      ).toBeInTheDocument();
      expect(
        within(card).getByRole("button", { name: "연결" }),
      ).toBeInTheDocument();
      await waitFor(() => expect(router.state.location.search).toBe(""));
    });

    it("만료에서 다시 연결하다 노션 게스트라 막혔으면 만료 안내 대신 게스트 안내와 [다시 연결]을 둔다", async () => {
      mockConnection({
        ...searchNotionConnectionResponse,
        status: "EXPIRED",
        canDisconnect: false,
      });
      const { card } = renderCard(
        `${APPS_PATH}?notionConnection=NOTION_GUEST_NOT_ALLOWED`,
      );

      expect(await within(card).findByText(GUEST_NOTICE)).toBeInTheDocument();
      expect(within(card).queryByText(EXPIRED_NOTICE)).not.toBeInTheDocument();
      expect(
        within(card).getByRole("button", { name: "다시 연결" }),
      ).toBeInTheDocument();
    });

    it("알림 모습이 없는 결과는 새 안내 없이 연결 상태만 새로 보여 주고 주소에서 지운다", async () => {
      const { router, card } = renderCard(
        `${APPS_PATH}?notionConnection=CANCELLED`,
      );

      expect(await within(card).findByText("연결됨")).toBeInTheDocument();
      await waitFor(() => expect(router.state.location.search).toBe(""));
      expect(screen.queryByText("노션을 연결했어요")).not.toBeInTheDocument();
      expect(within(card).queryByText(GUEST_NOTICE)).not.toBeInTheDocument();
    });

    it("모르는 결과는 무시한다", async () => {
      const { router, card } = renderCard(
        `${APPS_PATH}?notionConnection=UNKNOWN`,
      );

      expect(await within(card).findByText("연결됨")).toBeInTheDocument();
      expect(router.state.location.search).toBe("?notionConnection=UNKNOWN");
      expect(screen.queryByText("노션을 연결했어요")).not.toBeInTheDocument();
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
