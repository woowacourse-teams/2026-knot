import { GetDocumentsResponseDto } from "@api/dto/document";
import { documentsResponse } from "@api/mock/responses/document";
import { mockServer } from "@api/mock/server";
import type { DocumentsResponse } from "@api/mock/types/document";
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
  within,
} from "@testing-library/react";
import { formatDate } from "@utils/formatDate";
import { formatDurationFromSeconds } from "@utils/formatDurationFromSeconds";
import { delay, http, HttpResponse } from "msw";
import {
  createMemoryRouter,
  Outlet,
  RouterProvider,
  useParams,
} from "react-router";
import { describe, expect, it } from "vitest";

import RecentDocumentsCard from ".";

// 카드가 한 번에 요청하는 문서 수. useRecentDocumentsQuery의 값과 같아요
const RECENT_DOCUMENT_COUNT = 3;
const expected = new GetDocumentsResponseDto(documentsResponse);
// mock 응답은 최신순이라 앞의 세 개가 가장 최근 문서예요
const expectedRecentDocuments = expected.items.slice(0, RECENT_DOCUMENT_COUNT);
const WORKSPACE_ID = "1";
// 경로 파라미터 자리에 무엇이 와도 잡도록 fetch 상수 대신 패턴을 적어요
const DOCUMENTS_REQUEST = "*/api/v1/workspaces/:workspaceId/documents";
const CARD_TITLE = "최근 추가된 문서";
const LOADING_LABEL = "최근 추가된 문서를 불러오고 있어요";
const LOAD_FAILED_NOTICE = "목록을 불러오지 못했어요. 다시 시도해 주세요.";

// 어느 문서로 갔는지 확인할 수 있게 문서 보기 경로에 표시만 하는 화면을 둬요
function DocumentViewStub() {
  const { documentId } = useParams();

  return <p>문서 보기 화면 {documentId}</p>;
}

const renderRecentDocumentsCard = () => {
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
          { path: PATH_ROUTE.WORKSPACE_HOME, element: <RecentDocumentsCard /> },
          {
            path: PATH_ROUTE.DOCUMENTS_BY_RECORDING,
            element: <p>문서 목록 화면</p>,
          },
          { path: PATH_ROUTE.DOCUMENT, element: <DocumentViewStub /> },
          // 이동을 확인할 수 있게 로그인 · 워크스페이스 선택 경로에 표시만 하는 화면을 둬요
          { path: PATH_ROUTE.LOGIN, element: <p>로그인 화면</p> },
          {
            path: PATH_ROUTE.WORKSPACE,
            element: <p>워크스페이스 선택 화면</p>,
          },
        ],
      },
    ],
    {
      initialEntries: [
        getRouterPath({
          routeKey: "WORKSPACE_HOME",
          params: { workspaceId: WORKSPACE_ID },
        }),
      ],
    },
  );

  render(
    <ThemeProvider theme={theme}>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </ThemeProvider>,
  );
};

const respondWith = (response: DocumentsResponse) =>
  mockServer.use(
    http.get(DOCUMENTS_REQUEST, () => HttpResponse.json(response)),
  );

// 문서 행은 카드 안 목록의 링크예요. 「전체 보기」 링크는 목록 밖에 있어요.
// 불러오는 동안의 카드는 문서를 받으면 다른 요소로 바뀌므로, 카드를 먼저 잡지 않고 목록을 바로 찾아요
const findRows = async () =>
  within(await screen.findByRole("list")).getAllByRole("link");

// 불러오는 동안의 카드도 이름이 같은 region이라, 문서 행이 나온 뒤에 카드를 잡아요
const findCard = async () => {
  await findRows();

  return screen.getByRole("region", { name: CARD_TITLE });
};

const queryViewAllLink = () =>
  screen.queryByRole("link", { name: "전체 보기" });

const clickRetry = async () =>
  fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));

describe("RecentDocumentsCard", () => {
  it("카드 제목과 전체 보기를 보여 준다", async () => {
    renderRecentDocumentsCard();

    const card = await findCard();

    expect(
      within(card).getByRole("heading", { level: 2, name: CARD_TITLE }),
    ).toBeInTheDocument();
    expect(
      within(card).getByRole("link", { name: "전체 보기" }),
    ).toBeInTheDocument();
  });

  it("가장 최근 문서 세 개를 서버가 준 순서대로 보여 준다", async () => {
    renderRecentDocumentsCard();

    const rows = await findRows();

    expect(rows).toHaveLength(RECENT_DOCUMENT_COUNT);
    rows.forEach((row, index) => {
      expect(
        within(row).getByText(expectedRecentDocuments[index].title),
      ).toBeInTheDocument();
    });
  });

  it("행마다 만든 날짜와 녹음 길이를 보여 준다", async () => {
    renderRecentDocumentsCard();

    const rows = await findRows();

    rows.forEach((row, index) => {
      const { createdAt, recordingDurationSeconds } =
        expectedRecentDocuments[index];

      expect(within(row).getByText(formatDate(createdAt))).toBeInTheDocument();
      expect(
        within(row).getByText(
          formatDurationFromSeconds(recordingDurationSeconds),
        ),
      ).toBeInTheDocument();
    });
  });

  it("문서를 세 개만, 한 번 요청한다", async () => {
    const requestedParams: URLSearchParams[] = [];
    mockServer.use(
      http.get(DOCUMENTS_REQUEST, ({ request }) => {
        requestedParams.push(new URL(request.url).searchParams);

        // 핸들러 없이 넘기면 기본 핸들러가 이어서 답해요
        return undefined;
      }),
    );

    renderRecentDocumentsCard();
    await findRows();

    expect(requestedParams).toHaveLength(1);
    expect(requestedParams[0].get("size")).toBe(String(RECENT_DOCUMENT_COUNT));
    expect(requestedParams[0].get("cursor")).toBeNull();
  });

  it("문서가 세 개보다 적으면 있는 만큼 보여 준다", async () => {
    const [onlyDocument] = documentsResponse.items;
    respondWith({ ...documentsResponse, items: [onlyDocument] });

    renderRecentDocumentsCard();

    const rows = await findRows();

    expect(rows).toHaveLength(1);
    expect(
      within(rows[0]).getByText(expectedRecentDocuments[0].title),
    ).toBeInTheDocument();
  });

  it("행을 누르면 그 문서 보기로 간다", async () => {
    renderRecentDocumentsCard();

    const [, secondRow] = await findRows();
    fireEvent.click(secondRow);

    expect(
      await screen.findByText(
        `문서 보기 화면 ${expectedRecentDocuments[1].id}`,
      ),
    ).toBeInTheDocument();
  });

  it("전체 보기를 누르면 문서 목록으로 간다", async () => {
    renderRecentDocumentsCard();

    fireEvent.click(
      within(await findCard()).getByRole("link", { name: "전체 보기" }),
    );

    await waitFor(() =>
      expect(screen.getByText("문서 목록 화면")).toBeInTheDocument(),
    );
  });

  it("불러오는 동안에는 제목과 전체 보기를 그대로 두고 불러오는 중이라고 알리고, 문서를 받으면 행으로 바꾼다", async () => {
    mockServer.use(
      http.get(DOCUMENTS_REQUEST, async () => {
        await delay(50);

        return HttpResponse.json(documentsResponse);
      }),
    );

    renderRecentDocumentsCard();

    expect(
      screen.getByRole("status", { name: LOADING_LABEL }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { level: 2, name: CARD_TITLE }),
    ).toBeInTheDocument();
    expect(queryViewAllLink()).toBeInTheDocument();

    await findRows();

    expect(
      screen.queryByRole("status", { name: LOADING_LABEL }),
    ).not.toBeInTheDocument();
  });

  it("문서가 하나도 없으면 아직 문서가 없다고 알리고, 제목만 두고 전체 보기는 두지 않는다", async () => {
    respondWith({ topics: [], items: [], nextCursor: null });

    renderRecentDocumentsCard();

    expect(await screen.findByText("아직 문서가 없어요")).toBeInTheDocument();
    expect(
      screen.getByText("녹음을 끝내면 정리된 문서가 여기에 쌓여요"),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { level: 2, name: CARD_TITLE }),
    ).toBeInTheDocument();
    expect(queryViewAllLink()).not.toBeInTheDocument();
  });

  it.each([
    ["잘못된 요청(400)", () => new HttpResponse(null, { status: 400 })],
    ["서버 오류(500)", () => new HttpResponse(null, { status: 500 })],
    ["네트워크 오류", () => HttpResponse.error()],
  ])(
    "%s로 불러오지 못하면 카드 안에 안내와 「다시 시도」를 보여 주고, 제목과 전체 보기는 그대로 둔다",
    async (_, respond) => {
      mockServer.use(http.get(DOCUMENTS_REQUEST, respond));

      renderRecentDocumentsCard();

      expect(await screen.findByRole("alert")).toHaveTextContent(
        LOAD_FAILED_NOTICE,
      );
      expect(
        screen.getByRole("button", { name: "다시 시도" }),
      ).toBeInTheDocument();
      expect(
        screen.getByRole("heading", { level: 2, name: CARD_TITLE }),
      ).toBeInTheDocument();
      expect(queryViewAllLink()).toBeInTheDocument();
    },
  );

  it("「다시 시도」를 누르면 다시 불러와 문서 행을 보여 준다", async () => {
    let requestCount = 0;
    mockServer.use(
      http.get(DOCUMENTS_REQUEST, () => {
        requestCount += 1;

        // 첫 요청만 실패하고, 다시 시도한 요청은 기본 핸들러가 이어서 답해요
        return requestCount === 1
          ? new HttpResponse(null, { status: 500 })
          : undefined;
      }),
    );

    renderRecentDocumentsCard();
    await clickRetry();

    expect(await findRows()).toHaveLength(RECENT_DOCUMENT_COUNT);
    expect(requestCount).toBe(2);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("문서를 보는 동안 다시 불러오기가 서버 오류로 실패하면 받은 문서를 계속 보여 준다", async () => {
    let failedRequestCount = 0;
    renderRecentDocumentsCard();
    await findRows();

    mockServer.use(
      http.get(DOCUMENTS_REQUEST, () => {
        failedRequestCount += 1;

        return new HttpResponse(null, { status: 500 });
      }),
    );
    // 다른 탭에 다녀오면 문서를 다시 받아요
    act(() => {
      window.dispatchEvent(new Event("visibilitychange"));
    });
    await waitFor(() => expect(failedRequestCount).toBe(1));

    expect(await findRows()).toHaveLength(RECENT_DOCUMENT_COUNT);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it.each([
    ["로그인이 풀렸으면(401)", "로그인 화면", 401],
    ["워크스페이스 멤버가 아니면(403)", "워크스페이스 선택 화면", 403],
    ["없는 워크스페이스면(404)", "워크스페이스 선택 화면", 404],
  ])(
    "%s 불러오지 못했다는 안내를 보여 주지 않고 %s으로 보낸다",
    async (_, destination, status) => {
      mockServer.use(
        http.get(DOCUMENTS_REQUEST, () => new HttpResponse(null, { status })),
      );
      // 다른 화면으로 가면 카드가 사라져, 간 뒤에는 안내가 있었는지 알 수 없어요. 가기 전까지 그려진 화면을 모두 지켜봐요
      let isAlertShown = false;
      const observer = new MutationObserver(() => {
        if (screen.queryByRole("alert") !== null) isAlertShown = true;
      });
      observer.observe(document.body, { childList: true, subtree: true });

      renderRecentDocumentsCard();

      try {
        expect(await screen.findByText(destination)).toBeInTheDocument();
      } finally {
        observer.disconnect();
      }
      expect(isAlertShown).toBe(false);
    },
  );
});
