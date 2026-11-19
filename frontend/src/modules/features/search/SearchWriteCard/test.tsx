import { SearchWriteCardDto } from "@api/dto/search";
import {
  searchNotionConnectionResponse,
  searchWriteCardCreatedPageUrl,
  searchWriteCardLongBodyResponse,
  searchWriteCardPageResponse,
  searchWriteCardResponse,
} from "@api/mock/responses/search";
import { mockServer } from "@api/mock/server";
import type { SearchWriteCard as SearchWriteCardRaw } from "@api/mock/types/search";
import { ThemeProvider } from "@emotion/react";
import { theme } from "@provider/themeProvider";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createMemoryRouter, RouterProvider } from "react-router";

import SearchWriteCard from ".";

const WORKSPACE_ID = "1";
const CONVERSATION_ID = "10";
const CONVERSATION_PATH = getRouterPath({
  routeKey: "CHAT_SESSION",
  params: { workspaceId: WORKSPACE_ID, sessionId: CONVERSATION_ID },
});
const APPS_PATH = getRouterPath({
  routeKey: "APPS",
  params: { workspaceId: WORKSPACE_ID },
});
const APPS_SCREEN_TEXT = "연결된 앱 화면";

const EXECUTE_PATH =
  "*/api/v1/workspaces/:workspaceId/search/conversations/:conversationId/write-cards/:cardId/execute";
const CANCEL_PATH =
  "*/api/v1/workspaces/:workspaceId/search/conversations/:conversationId/write-cards/:cardId/cancel";
const NOTION_CONNECTION_PATH =
  "*/api/v1/workspaces/:workspaceId/search/notion-connection";

const CARD_NAME = "노션 페이지 만들기";
const RUNNING_TEXT = "노션에 페이지를 만드는 중이에요.";

const renderCard = (raw: SearchWriteCardRaw) => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const card = new SearchWriteCardDto(raw);
  const router = createMemoryRouter(
    [
      {
        path: PATH_ROUTE.CHAT_SESSION,
        element: <SearchWriteCard card={card} />,
      },
      { path: PATH_ROUTE.APPS, element: <p>{APPS_SCREEN_TEXT}</p> },
    ],
    { initialEntries: [CONVERSATION_PATH] },
  );

  render(
    <ThemeProvider theme={theme}>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </ThemeProvider>,
  );

  return { card, router, region: screen.getByRole("region", { name: CARD_NAME }) };
};

/** 항목 이름(dt) 옆 값(dd)의 글자 */
const fieldValue = (region: HTMLElement, name: string) =>
  within(region).getByText(name, { selector: "dt" }).nextElementSibling
    ?.textContent;

/** 실행 응답을 `respond`를 부를 때까지 붙잡아 두고, 받은 실행 요청 수를 세요 */
const holdExecute = (raw: SearchWriteCardRaw) => {
  let respond = () => {};
  const responded = new Promise<void>((resolve) => {
    respond = resolve;
  });
  const requests: string[] = [];

  mockServer.use(
    http.post(EXECUTE_PATH, async ({ request }) => {
      requests.push(new URL(request.url).pathname);
      await responded;
      return HttpResponse.json(raw);
    }),
  );

  return { requests, respond: () => act(async () => respond()) };
};

const respondWithConnection = (
  status: "CONNECTED" | "NOT_CONNECTED" | "EXPIRED",
) => {
  mockServer.use(
    http.get(NOTION_CONNECTION_PATH, () =>
      HttpResponse.json({ ...searchNotionConnectionResponse, status }),
    ),
  );
};

describe("SearchWriteCard", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("[SEARCH-R48, SEARCH-R41] 대기 카드는 위치, 제목, 내용, DB 칸, 근거와 [취소], [만들기]를 보여주고 값을 고치는 칸은 없다", () => {
    const { card, region } = renderCard(searchWriteCardResponse);

    expect(within(region).getByText(CARD_NAME)).toBeInTheDocument();
    expect(fieldValue(region, "위치")).toBe(`${card.location.path} DB`);
    expect(fieldValue(region, "제목")).toBe(card.title);
    expect(fieldValue(region, "DB 칸")).toBe(
      "이름  11월 13일 주간 회의날짜  11월 13일",
    );
    expect(fieldValue(region, "근거")).toBe(`문서${card.sourceDocument.title}`);
    expect(within(region).getByText("출시일은 12월 3일로 정했어요.", { exact: false })).toBeInTheDocument();
    expect(within(region).getByRole("button", { name: "취소" })).toBeEnabled();
    expect(within(region).getByRole("button", { name: "만들기" })).toBeEnabled();
    expect(within(region).queryByRole("textbox")).not.toBeInTheDocument();
  });

  it("[SEARCH-R49] 일반 페이지 아래에 만드는 카드는 DB 칸을 보여주지 않는다", () => {
    const { card, region } = renderCard(searchWriteCardPageResponse);

    expect(fieldValue(region, "위치")).toBe(card.location.path);
    expect(within(region).queryByText("DB 칸")).not.toBeInTheDocument();
  });

  it("[SEARCH-R48] 내용은 소제목과 글머리를 정리해 보여주고, [전체 보기]로 펼치고 [접기]로 다시 접는다", () => {
    const { region } = renderCard(searchWriteCardLongBodyResponse);

    expect(within(region).getByText("결정")).toBeInTheDocument();
    expect(
      within(region).getByText("• 실패 알림은 따로 모아 보여 줘요."),
    ).toBeInTheDocument();

    const expandButton = within(region).getByRole("button", { name: "전체 보기" });
    expect(expandButton).toHaveAttribute("aria-expanded", "false");

    fireEvent.click(expandButton);
    const collapseButton = within(region).getByRole("button", { name: "접기" });
    expect(collapseButton).toHaveAttribute("aria-expanded", "true");

    fireEvent.click(collapseButton);
    expect(within(region).getByRole("button", { name: "전체 보기" })).toBeInTheDocument();
  });

  it("[SEARCH-R49] DB 칸을 확인하지 못한 카드는 「확인하는 중」과 함께 [만들기]를 누를 수 없다", () => {
    const { region } = renderCard({
      ...searchWriteCardResponse,
      databaseFields: null,
      executable: false,
    });

    expect(fieldValue(region, "DB 칸")).toBe("확인하는 중");
    expect(within(region).getByRole("button", { name: "만들기" })).toBeDisabled();
    expect(within(region).getByRole("button", { name: "취소" })).toBeEnabled();
    expect(
      within(region).getByText("DB 칸을 확인한 뒤에 만들 수 있어요."),
    ).toBeInTheDocument();
  });

  it("[SEARCH-R50] [만들기]를 누르면 그 카드의 실행을 요청하고, 응답이 올 때까지 [취소]와 [만들기]를 누를 수 없고 처리 중임을 보여준다", async () => {
    const { requests, respond } = holdExecute({
      ...searchWriteCardResponse,
      status: "DONE",
      createdPageUrl: searchWriteCardCreatedPageUrl,
    });
    const { card, region } = renderCard(searchWriteCardResponse);

    fireEvent.click(within(region).getByRole("button", { name: "만들기" }));

    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0]).toBe(
      `/api/v1/workspaces/${WORKSPACE_ID}/search/conversations/${CONVERSATION_ID}/write-cards/${card.id}/execute`,
    );
    expect(within(region).getByRole("button", { name: "취소" })).toBeDisabled();
    expect(within(region).getByRole("button", { name: "만들기" })).toHaveAttribute(
      "aria-busy",
      "true",
    );
    expect(within(region).getByText(RUNNING_TEXT)).toBeInTheDocument();

    await respond();
  });

  it("[SEARCH-R43, SEARCH-R50] [만들기]를 두 번 눌러도 실행은 한 번만 요청한다", async () => {
    const { requests, respond } = holdExecute(searchWriteCardResponse);
    const { region } = renderCard(searchWriteCardResponse);
    const button = within(region).getByRole("button", { name: "만들기" });

    fireEvent.click(button);
    fireEvent.click(button);

    await waitFor(() => expect(requests).toHaveLength(1));
    await respond();
    expect(requests).toHaveLength(1);
  });

  it("[SEARCH-R50] 실행 중인 카드는 [취소]와 [만들기]를 누를 수 없고 처리 중임을 보여준다", () => {
    const { region } = renderCard({ ...searchWriteCardResponse, status: "RUNNING" });

    expect(within(region).getByRole("button", { name: "취소" })).toBeDisabled();
    expect(within(region).getByRole("button", { name: "만들기" })).toBeDisabled();
    expect(within(region).getByText(RUNNING_TEXT)).toBeInTheDocument();
  });

  it("[SEARCH-R43] [취소]를 누르면 그 카드의 취소를 요청한다", async () => {
    const requests: string[] = [];
    mockServer.use(
      http.post(CANCEL_PATH, ({ request }) => {
        requests.push(new URL(request.url).pathname);
        return HttpResponse.json({ ...searchWriteCardResponse, status: "CANCELED" });
      }),
    );
    const { card, region } = renderCard(searchWriteCardResponse);

    fireEvent.click(within(region).getByRole("button", { name: "취소" }));

    await waitFor(() =>
      expect(requests).toEqual([
        `/api/v1/workspaces/${WORKSPACE_ID}/search/conversations/${CONVERSATION_ID}/write-cards/${card.id}/cancel`,
      ]),
    );
  });

  it("[SEARCH-R50] 만든 카드는 「만들었어요」와 [노션에서 열기]를 보여주고, 누르면 만든 페이지를 새 탭으로 연다", () => {
    const open = vi.spyOn(window, "open").mockReturnValue(null);
    const { region } = renderCard({
      ...searchWriteCardResponse,
      status: "DONE",
      createdPageUrl: searchWriteCardCreatedPageUrl,
    });

    expect(within(region).getByText("만들었어요")).toBeInTheDocument();
    expect(within(region).queryByRole("button", { name: "만들기" })).not.toBeInTheDocument();
    expect(within(region).queryByRole("button", { name: "취소" })).not.toBeInTheDocument();

    fireEvent.click(within(region).getByRole("button", { name: "노션에서 열기" }));

    expect(open).toHaveBeenCalledWith(
      searchWriteCardCreatedPageUrl,
      "_blank",
      "noopener,noreferrer",
    );
  });

  it("[SEARCH-R41, SEARCH-R43] 취소된 카드는 「취소됨」만 보여주고 실행할 수 없다", () => {
    const { region } = renderCard({ ...searchWriteCardResponse, status: "CANCELED" });

    expect(within(region).getByText("취소됨")).toBeInTheDocument();
    expect(within(region).queryAllByRole("button", { name: /^(취소|만들기)$/ })).toHaveLength(0);
  });

  it.each([
    {
      failureReason: "LOCATION_PERMISSION_LOST" as const,
      text: "이 위치에 페이지를 만들 권한이 없어요. 채팅에 다른 위치를 알려 주세요.",
    },
    {
      failureReason: "NOTION_CONNECTION_CHANGED" as const,
      text: "카드를 만든 뒤 노션 연결이 바뀌었어요. 다시 요청해 주세요.",
    },
    {
      failureReason: "BODY_TOO_LONG" as const,
      text: "내용이 길어 노션이 받지 않았어요. 더 짧게 요청해 주세요.",
    },
    {
      failureReason: "NOTION_NO_RESPONSE" as const,
      text: "노션에서 응답을 받지 못했어요. 노션에 페이지가 생겼는지 확인해 주세요.",
    },
  ])(
    "[SEARCH-R53] 실패한 카드는 「만들지 못했어요」와 $failureReason 이유를 보여주고 다시 실행할 수 없다",
    ({ failureReason, text }) => {
      const { region } = renderCard({
        ...searchWriteCardResponse,
        status: "FAILED",
        failureReason,
      });

      expect(within(region).getByText("만들지 못했어요")).toBeInTheDocument();
      expect(within(region).getByText(text)).toBeInTheDocument();
      expect(within(region).queryAllByRole("button", { name: /^(취소|만들기)$/ })).toHaveLength(0);
    },
  );

  it.each([
    {
      retryReason: "NOTION_RECONNECT_REQUIRED" as const,
      connection: "EXPIRED" as const,
      title: "노션 연결이 만료됐어요",
      description: "다시 연결하면 같은 초안으로 만들 수 있어요.",
      action: "다시 연결",
    },
    {
      retryReason: "NOTION_CONNECTION_REQUIRED" as const,
      connection: "NOT_CONNECTED" as const,
      title: "노션이 연결돼 있지 않아요",
      description: "노션을 연결하면 같은 초안으로 만들 수 있어요.",
      action: "연결",
    },
  ])(
    "[SEARCH-R52] $retryReason로 대기로 돌아온 카드는 안내와 [$action]을 보여주고, 누르면 연결된 앱 화면으로 간다",
    async ({ retryReason, connection, title, description, action }) => {
      respondWithConnection(connection);
      const { router, region } = renderCard({ ...searchWriteCardResponse, retryReason });

      expect(within(region).getByText(title)).toBeInTheDocument();
      expect(within(region).getByText(description)).toBeInTheDocument();
      expect(within(region).getByRole("button", { name: "취소" })).toBeEnabled();
      expect(within(region).queryByRole("button", { name: "만들기" })).not.toBeInTheDocument();

      await act(async () => {
        fireEvent.click(within(region).getByRole("button", { name: action }));
      });

      expect(router.state.location.pathname).toBe(APPS_PATH);
      expect(screen.getByText(APPS_SCREEN_TEXT)).toBeInTheDocument();
    },
  );

  it.each(["NOTION_RECONNECT_REQUIRED" as const, "NOTION_CONNECTION_REQUIRED" as const])(
    "[SEARCH-R52] %s로 대기인 카드도 노션이 다시 연결돼 있으면 같은 초안으로 [만들기]를 누를 수 있다",
    async (retryReason) => {
      respondWithConnection("CONNECTED");
      const { region } = renderCard({ ...searchWriteCardResponse, retryReason });

      expect(
        await within(region).findByRole("button", { name: "만들기" }),
      ).toBeEnabled();
      expect(within(region).queryByRole("button", { name: /^(다시 연결|연결)$/ })).not.toBeInTheDocument();
    },
  );

  it.each([
    {
      retryReason: "NOTION_RATE_LIMITED" as const,
      text: "노션에 요청이 많아요. 잠시 뒤 다시 눌러 주세요.",
    },
    {
      retryReason: "DATABASE_FIELDS_CHANGED" as const,
      text: "DB 칸이 바뀌어 미리보기를 새로 불러왔어요. 확인하고 다시 눌러 주세요.",
    },
  ])(
    "[SEARCH-R50, SEARCH-R52] $retryReason로 대기로 돌아온 카드는 안내와 함께 [만들기]를 다시 누를 수 있다",
    ({ retryReason, text }) => {
      const { region } = renderCard({ ...searchWriteCardResponse, retryReason });

      expect(within(region).getByText(text)).toBeInTheDocument();
      expect(within(region).getByRole("button", { name: "만들기" })).toBeEnabled();
    },
  );

  it("[SEARCH-R51] 같은 대화에서 이미 만든 페이지가 있으면 최근 것부터 안내와 [노션에서 열기]를 보여주고, 만들기는 막지 않는다", () => {
    const open = vi.spyOn(window, "open").mockReturnValue(null);
    const existingPages = [
      { url: "https://www.notion.so/knot-team/recent", createdAt: "2026-11-13T05:00:00Z" },
      { url: "https://www.notion.so/knot-team/older", createdAt: "2026-11-06T05:00:00Z" },
    ];
    const { region } = renderCard({ ...searchWriteCardResponse, existingPages });

    expect(within(region).getAllByText("이미 만든 페이지가 있어요")).toHaveLength(2);
    const openButtons = within(region).getAllByRole("button", { name: "노션에서 열기" });
    fireEvent.click(openButtons[0]);
    fireEvent.click(openButtons[1]);

    expect(open.mock.calls.map(([url]) => url)).toEqual(
      existingPages.map(({ url }) => url),
    );
    expect(within(region).getByRole("button", { name: "만들기" })).toBeEnabled();
  });
});
