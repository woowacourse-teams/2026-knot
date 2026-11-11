import { GetDocumentsResponseDto } from "@api/dto/document";
import { documentsResponse } from "@api/mock/responses/document";
import { mockServer } from "@api/mock/server";
import { findDocuments } from "@api/mock/state/document";
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
import { afterEach, describe, expect, it, vi } from "vitest";

import TopicDocumentList from ".";

const expected = new GetDocumentsResponseDto(documentsResponse);
const WORKSPACE_ID = "1";
// 경로 파라미터 자리에 무엇이 와도 잡도록 fetch 상수 대신 패턴을 적어요
const DOCUMENTS_REQUEST = "*/api/v1/workspaces/:workspaceId/documents";
// 한 번에 요청하는 문서 수와 이어 받는 횟수의 한계. getAllDocumentsApi의 값과 같아요
const REQUEST_SIZE = "100";
const MAX_REQUEST_COUNT = 20;
// 이어 받기를 확인할 때 mock이 요청한 size와 관계없이 한 번에 주는 문서 수
const MOCK_PAGE_SIZE = 3;
const LOADING_LABEL = "문서 목록을 불러오고 있어요";
const LOAD_FAILED_NOTICE = "목록을 불러오지 못했어요. 다시 시도해 주세요.";
// 화면 근처의 행만 그리는지 확인할 때 쓰는 폴더 하나의 문서 수와 스크롤 영역 높이
const MANY_DOCUMENT_COUNT = 100;
const MAIN_HEIGHT = 640;

// 어느 문서로 갔는지 확인할 수 있게 문서 보기 경로에 표시만 하는 화면을 둬요
function DocumentViewStub() {
  const { documentId } = useParams();

  return <p>문서 보기 화면 {documentId}</p>;
}

interface RenderDocumentListParams {
  workspaceId?: string;
  /** 앱처럼 스크롤 영역(`main`) 안에 그릴지 */
  isInMain?: boolean;
}

// 이동을 확인할 수 있게 로그인 · 워크스페이스 선택 경로에 표시만 하는 화면을 둬요
const renderDocumentList = ({
  workspaceId = WORKSPACE_ID,
  isInMain = false,
}: RenderDocumentListParams = {}) => {
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
            path: PATH_ROUTE.DOCUMENTS_BY_TOPIC,
            element: isInMain ? (
              <main>
                <TopicDocumentList />
              </main>
            ) : (
              <TopicDocumentList />
            ),
          },
          { path: PATH_ROUTE.DOCUMENT, element: <DocumentViewStub /> },
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
          routeKey: "DOCUMENTS_BY_TOPIC",
          params: { workspaceId },
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

const findFolder = (topic: string) =>
  screen.findByRole("region", { name: topic });

// 행은 링크예요. 링크 이름은 행 안의 글 전체라 제목으로 시작하는 것을 찾아요
const findRow = (title: string) =>
  screen.findByRole("link", { name: (name) => name.startsWith(title) });

const queryRow = (title: string) =>
  screen.queryByRole("link", { name: (name) => name.startsWith(title) });

const documentsOf = (topic: string) =>
  expected.items.filter((item) => item.topic === topic);

// 요약 없는 문서가 많은 폴더 하나. 녹음 ID를 거꾸로 매겨 만든 순서 그대로 놓이게 해요
const [baseItem] = documentsResponse.items;
const manyDocumentsResponse: DocumentsResponse = {
  topics: [{ topic: baseItem.topic, documentCount: MANY_DOCUMENT_COUNT }],
  items: Array.from({ length: MANY_DOCUMENT_COUNT }, (_, index) => ({
    ...baseItem,
    id: index + 1,
    // 「1번째 문서」가 「11번째 문서」의 앞부분과 겹치지 않게 번호를 앞에 둬요
    title: `${index + 1}번째 문서`,
    summary: null,
    recordingSessionId: MANY_DOCUMENT_COUNT - index,
  })),
  nextCursor: null,
};
const manyDocuments = new GetDocumentsResponseDto(manyDocumentsResponse).items;

// jsdom은 레이아웃을 계산하지 않아 크기와 위치가 모두 0이에요.
// 스크롤 영역의 보이는 높이와, 카드 위쪽 끝이 스크롤 영역 위쪽 끝에서 몇 px 떨어졌는지 바꿀 수 있게 해요
const mockLayout = () => {
  const layout = { cardTop: 0, mainHeight: MAIN_HEIGHT };

  vi.spyOn(HTMLElement.prototype, "clientHeight", "get").mockImplementation(
    function (this: HTMLElement) {
      return this.tagName === "MAIN" ? layout.mainHeight : 0;
    },
  );
  vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(
    function (this: HTMLElement) {
      return DOMRect.fromRect({
        y: this.tagName === "UL" ? layout.cardTop : 0,
      });
    },
  );

  return layout;
};

// jsdom의 ResizeObserver는 크기 변화를 알리지 않는 빈 구현이에요.
// 테스트가 정한 때에 지켜보는 중인 ResizeObserver에 크기 변화를 알릴 수 있게 바꿔요
const stubResizeObserver = () => {
  const observers = new Set<{ notify: () => void }>();

  vi.stubGlobal(
    "ResizeObserver",
    class {
      notify: () => void;

      constructor(callback: ResizeObserverCallback) {
        this.notify = () => callback([], this);
      }

      observe() {
        observers.add(this);
      }

      unobserve() {}

      disconnect() {
        observers.delete(this);
      }
    },
  );

  return {
    notifyResize: () =>
      act(() => observers.forEach((observer) => observer.notify())),
  };
};

afterEach(() => {
  vi.unstubAllGlobals();
});

const clickRetry = async () =>
  fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));

describe("TopicDocumentList", () => {
  it("서버가 주제를 어떤 순서로 줘도 폴더를 이름순으로 놓는다", async () => {
    // 기본 응답의 주제는 이름순이라, 순서를 바꿔서 줘요
    const [first, second, third] = documentsResponse.topics;
    respondWith({ ...documentsResponse, topics: [third, first, second] });

    renderDocumentList();

    const folderNames = await screen.findAllByRole("heading", { level: 3 });

    expect(folderNames.map(({ textContent }) => textContent)).toEqual(
      expected.topics.map(({ topic }) => topic),
    );
  });

  it("폴더 이름 옆에 서버가 준 문서 수를 보여 준다", async () => {
    // 받은 문서 수와 다른 값을 줘서, 행을 세지 않고 서버 값을 쓰는지 봐요
    const extraCount = 10;
    const response = {
      ...documentsResponse,
      topics: documentsResponse.topics.map((topic) => ({
        ...topic,
        documentCount: topic.documentCount + extraCount,
      })),
    };
    respondWith(response);

    renderDocumentList();

    for (const { topic, documentCount } of new GetDocumentsResponseDto(response)
      .topics) {
      const folder = await findFolder(topic);

      expect(
        within(folder).getByText(String(documentCount)),
      ).toBeInTheDocument();
    }
  });

  it("서버가 문서를 어떤 순서로 줘도 폴더 안에서는 최근 녹음에서 나온 문서를 위에 놓는다", async () => {
    // 기본 응답의 문서는 최근 녹음순이라, 순서를 뒤집어서 줘요
    respondWith({
      ...documentsResponse,
      items: [...documentsResponse.items].reverse(),
    });

    renderDocumentList();

    for (const { topic } of expected.topics) {
      const folder = await findFolder(topic);
      const rows = within(folder).getAllByRole("listitem");
      const titles = documentsOf(topic).map(({ title }) => title);

      expect(rows).toHaveLength(titles.length);
      rows.forEach((row, index) => {
        expect(row).toHaveTextContent(titles[index]);
      });
    }
  });

  it("행에 제목 · 한 줄 요약 · 만든 날짜 · 녹음 길이를 보여 준다", async () => {
    const [document] = expected.items.filter(({ summary }) => summary !== null);

    renderDocumentList();

    const row = within(await findRow(document.title));

    expect(row.getByText(document.title)).toBeInTheDocument();
    expect(row.getByText(document.summary ?? "")).toBeInTheDocument();
    expect(row.getByText(formatDate(document.createdAt))).toBeInTheDocument();
    expect(
      row.getByText(
        formatDurationFromSeconds(document.recordingDurationSeconds),
      ),
    ).toBeInTheDocument();
  });

  it("요약이 없는 문서는 요약 줄을 그리지 않는다", async () => {
    const [withSummary] = expected.items.filter(
      ({ summary }) => summary !== null,
    );
    const [withoutSummary] = expected.items.filter(
      ({ summary }) => summary === null,
    );

    renderDocumentList();

    // 글 줄은 제목과 요약이에요. 요약이 없으면 빈 줄도 남기지 않아 제목 한 줄만 있어요
    expect(
      within(await findRow(withSummary.title)).getAllByRole("paragraph"),
    ).toHaveLength(2);
    expect(
      within(await findRow(withoutSummary.title)).getAllByRole("paragraph"),
    ).toHaveLength(1);
  });

  it("행을 누르면 그 문서의 문서 보기 화면으로 간다", async () => {
    const [document] = expected.items;

    renderDocumentList();
    fireEvent.click(await findRow(document.title));

    expect(
      await screen.findByText(`문서 보기 화면 ${document.id}`),
    ).toBeInTheDocument();
  });

  it("문서가 한 번에 다 오지 않으면 다음 페이지가 없을 때까지 이어 받아 모두 보여 준다", async () => {
    const requests: { cursor: string | null; size: string | null }[] = [];
    const nextCursors: (string | null)[] = [];

    // 요청한 size와 관계없이 조금씩만 줘서 여러 번 받게 해요
    mockServer.use(
      http.get(DOCUMENTS_REQUEST, ({ request }) => {
        const { searchParams } = new URL(request.url);
        const cursor = searchParams.get("cursor");
        const page = findDocuments({
          cursor,
          size: MOCK_PAGE_SIZE,
          recordingSessionId: null,
          myConfirmation: null,
        });

        if (page === undefined) return HttpResponse.json(null, { status: 400 });

        requests.push({ cursor, size: searchParams.get("size") });
        nextCursors.push(page.nextCursor);

        return HttpResponse.json(page);
      }),
    );

    renderDocumentList();

    for (const { title } of expected.items) {
      expect(await findRow(title)).toBeInTheDocument();
    }

    expect(requests).toHaveLength(
      Math.ceil(expected.items.length / MOCK_PAGE_SIZE),
    );
    // 첫 요청에는 커서가 없고, 그 뒤로는 앞 응답의 nextCursor를 그대로 보내요
    expect(requests.map(({ cursor }) => cursor)).toEqual([
      null,
      ...nextCursors.slice(0, -1),
    ]);
    expect(requests.every(({ size }) => size === REQUEST_SIZE)).toBe(true);
  });

  it("스무 번을 받아도 다음 페이지가 남아 있으면 더 받지 않고, 그때까지 받은 문서를 보여 준다", async () => {
    const [item] = documentsResponse.items;
    // 폴더 이름의 기대값은 같은 문서를 DTO로 바꾼 값에서 꺼내요
    const [{ topic }] = expected.items;
    let requestCount = 0;

    // 몇 번을 받아도 다음 페이지가 있다고 답해요
    mockServer.use(
      http.get(DOCUMENTS_REQUEST, () => {
        requestCount += 1;

        return HttpResponse.json({
          topics: [{ topic: item.topic, documentCount: 9999 }],
          items: [{ ...item, id: requestCount, title: `문서 ${requestCount}` }],
          nextCursor: `cursor-${requestCount}`,
        } satisfies DocumentsResponse);
      }),
    );

    renderDocumentList();

    const folder = await findFolder(topic);

    expect(within(folder).getAllByRole("listitem")).toHaveLength(
      MAX_REQUEST_COUNT,
    );
    expect(requestCount).toBe(MAX_REQUEST_COUNT);
  });

  it("불러오는 동안에는 불러오는 중이라고 알리고, 문서를 받으면 폴더로 바꾼다", async () => {
    mockServer.use(
      http.get(DOCUMENTS_REQUEST, async () => {
        await delay(50);

        return HttpResponse.json(documentsResponse);
      }),
    );

    renderDocumentList();

    expect(
      screen.getByRole("status", { name: LOADING_LABEL }),
    ).toBeInTheDocument();

    await findFolder(expected.topics[0].topic);

    expect(
      screen.queryByRole("status", { name: LOADING_LABEL }),
    ).not.toBeInTheDocument();
  });

  it("문서가 하나도 없으면 아직 문서가 없다고 알린다", async () => {
    respondWith({ topics: [], items: [], nextCursor: null });

    renderDocumentList();

    expect(await screen.findByText("아직 문서가 없어요")).toBeInTheDocument();
    expect(
      screen.getByText(
        "독의 마이크로 회의를 녹음하면 주제별로 정리된 문서가 폴더에 모여요",
      ),
    ).toBeInTheDocument();
  });

  it.each([
    ["잘못된 요청(400)", () => new HttpResponse(null, { status: 400 })],
    ["서버 오류(500)", () => new HttpResponse(null, { status: 500 })],
    ["네트워크 오류", () => HttpResponse.error()],
  ])(
    "%s로 불러오지 못하면 목록 자리에 안내와 「다시 시도」를 보여 준다",
    async (_, respond) => {
      mockServer.use(http.get(DOCUMENTS_REQUEST, respond));

      renderDocumentList();

      expect(await screen.findByRole("alert")).toHaveTextContent(
        LOAD_FAILED_NOTICE,
      );
      expect(
        screen.getByRole("button", { name: "다시 시도" }),
      ).toBeInTheDocument();
    },
  );

  it("「다시 시도」를 누르면 다시 불러와 폴더를 보여 준다", async () => {
    let requestCount = 0;
    mockServer.use(
      http.get(DOCUMENTS_REQUEST, () => {
        requestCount += 1;

        return requestCount === 1
          ? new HttpResponse(null, { status: 500 })
          : HttpResponse.json(documentsResponse);
      }),
    );

    renderDocumentList();
    await clickRetry();

    expect(await findFolder(expected.topics[0].topic)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("목록을 보는 동안 다시 불러오기가 서버 오류로 실패하면 받은 목록을 계속 보여 준다", async () => {
    const [{ topic }] = expected.topics;
    let failedRequestCount = 0;
    renderDocumentList();
    await findFolder(topic);

    mockServer.use(
      http.get(DOCUMENTS_REQUEST, () => {
        failedRequestCount += 1;

        return new HttpResponse(null, { status: 500 });
      }),
    );
    // 다른 탭에 다녀오면 목록을 다시 받아요
    act(() => {
      window.dispatchEvent(new Event("visibilitychange"));
    });
    await waitFor(() => expect(failedRequestCount).toBe(1));

    expect(screen.getByRole("region", { name: topic })).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("이어 받는 도중 한 번이라도 실패하면 받은 문서를 보여 주지 않고, 「다시 시도」는 처음부터 다시 받는다", async () => {
    const requestedCursors: (string | null)[] = [];
    let hasFailed = false;

    // 조금씩 주다가, 다음 페이지를 처음 요청받았을 때 한 번만 실패해요
    mockServer.use(
      http.get(DOCUMENTS_REQUEST, ({ request }) => {
        const cursor = new URL(request.url).searchParams.get("cursor");

        requestedCursors.push(cursor);

        if (cursor !== null && !hasFailed) {
          hasFailed = true;

          return new HttpResponse(null, { status: 500 });
        }

        return HttpResponse.json(
          findDocuments({
            cursor,
            size: MOCK_PAGE_SIZE,
            recordingSessionId: null,
            myConfirmation: null,
          }),
        );
      }),
    );

    renderDocumentList();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      LOAD_FAILED_NOTICE,
    );
    // 첫 페이지의 문서는 받았지만 보여 주지 않아요
    expect(screen.queryByRole("link")).not.toBeInTheDocument();

    await clickRetry();

    for (const { title } of expected.items) {
      expect(await findRow(title)).toBeInTheDocument();
    }

    // 실패한 요청 다음은 커서 없는 첫 페이지 요청이에요
    const [, failedCursor, cursorAfterRetry] = requestedCursors;

    expect(failedCursor).not.toBeNull();
    expect(cursorAfterRetry).toBeNull();
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
      // 다른 화면으로 가면 문서 목록이 사라져, 간 뒤에는 안내가 있었는지 알 수 없어요. 가기 전까지 그려진 화면을 모두 지켜봐요
      let isAlertShown = false;
      const observer = new MutationObserver(() => {
        if (screen.queryByRole("alert") !== null) isAlertShown = true;
      });
      observer.observe(document.body, { childList: true, subtree: true });

      renderDocumentList();

      try {
        expect(await screen.findByText(destination)).toBeInTheDocument();
      } finally {
        observer.disconnect();
      }
      expect(isAlertShown).toBe(false);
    },
  );

  it("주소의 워크스페이스 id가 숫자가 아니어도 확인하지 않고 그대로 요청하고, 서버가 거절하면 목록을 불러오지 못했다고 알린다", async () => {
    let requestCount = 0;
    mockServer.use(
      http.get(DOCUMENTS_REQUEST, () => {
        requestCount += 1;

        // 서버는 숫자가 아닌 워크스페이스 id를 잘못된 요청으로 거절해요
        return new HttpResponse(null, { status: 400 });
      }),
    );

    renderDocumentList({ workspaceId: "abc" });

    expect(await screen.findByRole("alert")).toHaveTextContent(
      LOAD_FAILED_NOTICE,
    );
    expect(requestCount).toBe(1);
  });

  it("스크롤 영역 안에서는 화면 근처의 행만 그리고, 카드는 행을 모두 그린 높이로 둔다", async () => {
    mockLayout();
    respondWith(manyDocumentsResponse);
    const [firstDocument] = manyDocuments;
    // 카드 맨 위에서 584~649px에 있어 화면 아래쪽 끝(640px)에 걸치는 행이에요
    const documentAtViewEnd = manyDocuments[9];
    const lastDocument = manyDocuments[MANY_DOCUMENT_COUNT - 1];

    renderDocumentList({ isInMain: true });

    const folder = await findFolder(firstDocument.topic);

    expect(await findRow(firstDocument.title)).toBeInTheDocument();
    expect(queryRow(documentAtViewEnd.title)).toBeInTheDocument();
    expect(queryRow(lastDocument.title)).not.toBeInTheDocument();
    expect(within(folder).getAllByRole("listitem").length).toBeLessThan(
      MANY_DOCUMENT_COUNT,
    );
    // 요약 없는 행은 64px이고, 둘째 행부터 구분선 1px을 더해 65px이에요
    expect(within(folder).getByRole("list")).toHaveStyle({
      height: `${64 + 65 * (MANY_DOCUMENT_COUNT - 1)}px`,
    });
  });

  it("스크롤하면 화면 근처로 온 행을 그리고 멀어진 행은 지운다", async () => {
    const layout = mockLayout();
    respondWith(manyDocumentsResponse);
    const [firstDocument] = manyDocuments;
    // 카드 맨 위에서 2,989~3,054px에 있는 47번째 행이에요
    const documentAtScrolledView = manyDocuments[46];
    const lastDocument = manyDocuments[MANY_DOCUMENT_COUNT - 1];

    renderDocumentList({ isInMain: true });
    await findRow(firstDocument.title);

    // 카드 위쪽 끝이 스크롤 영역 위쪽 끝보다 3,000px 위로 올라가게 스크롤해요
    layout.cardTop = -3000;
    fireEvent.scroll(screen.getByRole("main"));

    expect(await findRow(documentAtScrolledView.title)).toBeInTheDocument();
    expect(queryRow(firstDocument.title)).not.toBeInTheDocument();
    expect(queryRow(lastDocument.title)).not.toBeInTheDocument();
  });

  it("스크롤로 위쪽 행을 지워도 그린 첫 행의 위쪽에 구분선을 그어, 행을 모두 그렸을 때와 같은 높이로 둔다", async () => {
    const layout = mockLayout();
    respondWith(manyDocumentsResponse);
    const [firstDocument] = manyDocuments;
    // 카드 위쪽 끝을 3,000px 올리면 그리는 범위가 42번째 행부터 시작해요
    const firstDrawnDocument = manyDocuments[41];

    renderDocumentList({ isInMain: true });
    const folder = await findFolder(firstDocument.topic);

    // 폴더의 첫 행에는 구분선을 긋지 않아요
    expect(within(folder).getAllByRole("listitem")[0]).not.toHaveStyle({
      borderTopWidth: "1px",
    });

    layout.cardTop = -3000;
    fireEvent.scroll(screen.getByRole("main"));
    await findRow(firstDrawnDocument.title);

    const [firstDrawnRow, secondDrawnRow] =
      within(folder).getAllByRole("listitem");

    expect(firstDrawnRow).toHaveTextContent(firstDrawnDocument.title);
    expect(firstDrawnRow).toHaveStyle({ borderTopWidth: "1px" });
    expect(secondDrawnRow).toHaveStyle({ borderTopWidth: "1px" });
  });

  it("스크롤 영역의 높이가 바뀌면 스크롤하지 않아도 넓어진 화면 근처의 행을 그린다", async () => {
    const { notifyResize } = stubResizeObserver();
    const layout = mockLayout();
    respondWith(manyDocumentsResponse);
    const [firstDocument] = manyDocuments;
    // 카드 맨 위에서 1,299~1,364px에 있어, 스크롤 영역이 1,200px로 높아지면 미리 그릴 거리 안에 들어오는 21번째 행이에요
    const documentNearGrownView = manyDocuments[20];

    renderDocumentList({ isInMain: true });
    await findRow(firstDocument.title);

    expect(queryRow(documentNearGrownView.title)).not.toBeInTheDocument();

    layout.mainHeight = 1200;
    notifyResize();

    expect(await findRow(documentNearGrownView.title)).toBeInTheDocument();
  });
});
