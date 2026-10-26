import { GetDocumentRecordingsResponseDto } from "@api/dto/document";
import { documentRecordingsResponse } from "@api/mock/responses/document";
import { mockServer } from "@api/mock/server";
import type { DocumentRecordingsResponse } from "@api/mock/types/document";
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
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import RecordingDocumentList from ".";

const expected = new GetDocumentRecordingsResponseDto(
  documentRecordingsResponse,
);
const WORKSPACE_ID = "1";
// 경로 파라미터 자리에 무엇이 와도 잡도록 fetch 상수 대신 패턴을 적어요
const DOCUMENT_RECORDINGS_REQUEST =
  "*/api/v1/workspaces/:workspaceId/document-recordings";
const LOADING_LABEL = "녹음별 문서 목록을 불러오고 있어요";
const LOADING_MORE_LABEL = "다음 녹음을 불러오고 있어요";
const LOAD_FAILED_NOTICE = "목록을 불러오지 못했어요. 다시 시도해 주세요.";
const LOAD_MORE_FAILED_NOTICE =
  "녹음을 더 불러오지 못했어요. 다시 시도해 주세요.";

// jsdom에는 IntersectionObserver가 없어요. 관찰을 시작한 콜백을 모아 두고, 테스트가 목록 끝에 닿은 것처럼 직접 불러요
const intersectionCallbacks: IntersectionObserverCallback[] = [];

class FakeIntersectionObserver {
  constructor(callback: IntersectionObserverCallback) {
    intersectionCallbacks.push(callback);
  }

  observe() {}

  unobserve() {}

  disconnect() {}
}

/** 목록 끝 표시가 화면에 들어온 것처럼, 지금 관찰 중인 모든 콜백에 알려요 */
const reachEnd = () =>
  act(() => {
    intersectionCallbacks.forEach((callback) =>
      callback(
        [{ isIntersecting: true } as IntersectionObserverEntry],
        {} as IntersectionObserver,
      ),
    );
  });

// 어느 화면으로 갔는지 확인할 수 있게 문서 보기 · 녹음 문서 경로에 표시만 하는 화면을 둬요
function DocumentViewStub() {
  const { documentId } = useParams();

  return <p>문서 보기 화면 {documentId}</p>;
}

function RecordingDocumentStub() {
  const { recordingId } = useParams();

  return <p>녹음 문서 화면 {recordingId}</p>;
}

const renderRecordingDocumentList = (workspaceId = WORKSPACE_ID) => {
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
            path: PATH_ROUTE.DOCUMENTS_BY_RECORDING,
            element: <RecordingDocumentList />,
          },
          { path: PATH_ROUTE.DOCUMENT, element: <DocumentViewStub /> },
          {
            path: PATH_ROUTE.RECORDING_DOCUMENT,
            element: <RecordingDocumentStub />,
          },
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
          routeKey: "DOCUMENTS_BY_RECORDING",
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

const respondWith = (response: DocumentRecordingsResponse) =>
  mockServer.use(
    http.get(DOCUMENT_RECORDINGS_REQUEST, () => HttpResponse.json(response)),
  );

/** 녹음 묶음은 제목이 이름인 영역이에요 */
const findGroup = (title: string) =>
  screen.findByRole("region", { name: title });

// 행은 링크예요. 링크 이름은 행 안의 글 전체라 제목으로 시작하는 것을 찾고,
// 녹음 제목과 문서 제목이 같게 시작할 수 있어 문서 행은 목록 항목 안에 있는 것, 녹음 행은 밖에 있는 것으로 가려요
const findLinks = (title: string) =>
  screen.findAllByRole("link", { name: (name) => name.startsWith(title) });

const findRow = async (title: string) => {
  const links = await findLinks(title);
  const row = links.find((link) => link.closest("li") !== null);

  if (row === undefined) throw new Error(`문서 행이 없어요: ${title}`);

  return row;
};

const findRecordingRow = async (title: string) => {
  const links = await findLinks(title);
  const row = links.find((link) => link.closest("li") === null);

  if (row === undefined) throw new Error(`녹음 행이 없어요: ${title}`);

  return row;
};

const clickRetry = async () =>
  fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));

/** 첫 페이지에 녹음 하나, 다음 페이지에 나머지를 주는 두 페이지 응답 */
const twoPages = () => {
  const [firstRecording, ...restRecordings] = documentRecordingsResponse.items;

  return {
    firstPage: {
      items: [firstRecording],
      nextCursor: String(firstRecording.recordingId),
    } satisfies DocumentRecordingsResponse,
    secondPage: {
      items: restRecordings,
      nextCursor: null,
    } satisfies DocumentRecordingsResponse,
  };
};

describe("RecordingDocumentList", () => {
  beforeEach(() => {
    intersectionCallbacks.length = 0;
    vi.stubGlobal("IntersectionObserver", FakeIntersectionObserver);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("녹음마다 묶음을 두고, 머리 행에 표시 제목 · 녹음 시작 날짜 · 녹음 길이 · 문서 수를 보여 준다", async () => {
    renderRecordingDocumentList();

    for (const recording of expected.items) {
      const group = within(await findGroup(recording.title));

      expect(
        group.getByText(formatDate(recording.startedAt)),
      ).toBeInTheDocument();
      expect(
        group.getByText(
          formatDurationFromSeconds(recording.recordingDurationSeconds),
        ),
      ).toBeInTheDocument();
      expect(
        group.getByText(`문서 ${recording.documentCount}`),
      ).toBeInTheDocument();
    }
  });

  it("서버가 준 녹음 순서와 묶음 안 문서 순서를 그대로 보여 주고, 문서가 많아도 모두 보여 준다", async () => {
    renderRecordingDocumentList();

    await findGroup(expected.items[0].title);
    const groups = screen.getAllByRole("region");

    expect(groups).toHaveLength(expected.items.length);
    expected.items.forEach((recording, index) => {
      expect(groups[index]).toHaveAccessibleName(recording.title);
      const rows = within(groups[index]).getAllByRole("listitem");

      expect(rows).toHaveLength(recording.documents.length);
      rows.forEach((row, rowIndex) => {
        expect(row).toHaveTextContent(recording.documents[rowIndex].title);
      });
    });
  });

  it("문서 행에는 제목과 한 줄 요약만 있고, 요약이 없는 문서는 제목만 보여 준다", async () => {
    const documents = expected.items.flatMap(({ documents }) => documents);
    const [withSummary] = documents.filter(({ summary }) => summary !== null);
    const [withoutSummary] = documents.filter(
      ({ summary }) => summary === null,
    );

    renderRecordingDocumentList();

    const rowWithSummary = within(await findRow(withSummary.title));

    expect(rowWithSummary.getByText(withSummary.title)).toBeInTheDocument();
    expect(
      rowWithSummary.getByText(withSummary.summary ?? ""),
    ).toBeInTheDocument();
    // 글 줄은 제목과 요약이에요. 요약이 없으면 빈 줄도 남기지 않아 제목 한 줄만 있어요
    expect(rowWithSummary.getAllByRole("paragraph")).toHaveLength(2);
    expect(
      within(await findRow(withoutSummary.title)).getAllByRole("paragraph"),
    ).toHaveLength(1);
  });

  it("문서 행을 누르면 그 문서의 문서 보기 화면으로 간다", async () => {
    const [{ documents }] = expected.items;
    const [document] = documents;

    renderRecordingDocumentList();
    fireEvent.click(await findRow(document.title));

    expect(
      await screen.findByText(`문서 보기 화면 ${document.id}`),
    ).toBeInTheDocument();
  });

  it("녹음 행을 누르면 그 녹음의 녹음 문서 화면으로 간다", async () => {
    const [recording] = expected.items;

    renderRecordingDocumentList();
    fireEvent.click(await findRecordingRow(recording.title));

    expect(
      await screen.findByText(`녹음 문서 화면 ${recording.recordingId}`),
    ).toBeInTheDocument();
  });

  it("첫 페이지는 커서 없이 받고, 목록 끝에 닿으면 앞 응답의 nextCursor로 다음 페이지를 받아 이어 보여 준다", async () => {
    const { firstPage, secondPage } = twoPages();
    const requestedCursors: (string | null)[] = [];

    mockServer.use(
      http.get(DOCUMENT_RECORDINGS_REQUEST, ({ request }) => {
        const cursor = new URL(request.url).searchParams.get("cursor");

        requestedCursors.push(cursor);

        return HttpResponse.json(cursor === null ? firstPage : secondPage);
      }),
    );

    renderRecordingDocumentList();

    await findGroup(expected.items[0].title);
    expect(screen.getAllByRole("region")).toHaveLength(1);

    reachEnd();

    for (const { title } of expected.items) {
      expect(await findGroup(title)).toBeInTheDocument();
    }
    expect(requestedCursors).toEqual([null, firstPage.nextCursor]);
    // 다음 페이지가 없으면 끝에 닿아도 더 요청하지 않아요
    reachEnd();
    expect(requestedCursors).toHaveLength(2);
  });

  it("다음 페이지를 받는 동안 불러오는 중이라고 알린다", async () => {
    const { firstPage, secondPage } = twoPages();

    mockServer.use(
      http.get(DOCUMENT_RECORDINGS_REQUEST, async ({ request }) => {
        const cursor = new URL(request.url).searchParams.get("cursor");

        if (cursor === null) return HttpResponse.json(firstPage);

        await delay(50);

        return HttpResponse.json(secondPage);
      }),
    );

    renderRecordingDocumentList();
    await findGroup(expected.items[0].title);

    reachEnd();

    expect(
      await screen.findByRole("status", { name: LOADING_MORE_LABEL }),
    ).toBeInTheDocument();
    await findGroup(expected.items[1].title);
    expect(
      screen.queryByRole("status", { name: LOADING_MORE_LABEL }),
    ).not.toBeInTheDocument();
  });

  it("다음 페이지를 받지 못하면 이미 보인 녹음은 그대로 두고 목록 끝에서 안내하며, 「다시 시도」로 다음 페이지를 다시 받는다", async () => {
    const { firstPage, secondPage } = twoPages();
    let hasFailed = false;

    mockServer.use(
      http.get(DOCUMENT_RECORDINGS_REQUEST, ({ request }) => {
        const cursor = new URL(request.url).searchParams.get("cursor");

        if (cursor === null) return HttpResponse.json(firstPage);

        if (!hasFailed) {
          hasFailed = true;

          return new HttpResponse(null, { status: 500 });
        }

        return HttpResponse.json(secondPage);
      }),
    );

    renderRecordingDocumentList();
    await findGroup(expected.items[0].title);

    reachEnd();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      LOAD_MORE_FAILED_NOTICE,
    );
    expect(
      screen.getByRole("region", { name: expected.items[0].title }),
    ).toBeInTheDocument();

    await clickRetry();

    expect(await findGroup(expected.items[1].title)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("다음 페이지의 커서가 거절(400)되면 「다시 시도」는 처음부터 다시 받는다", async () => {
    const { firstPage } = twoPages();
    const requestedCursors: (string | null)[] = [];

    mockServer.use(
      http.get(DOCUMENT_RECORDINGS_REQUEST, ({ request }) => {
        const cursor = new URL(request.url).searchParams.get("cursor");

        requestedCursors.push(cursor);

        if (cursor !== null) return new HttpResponse(null, { status: 400 });

        // 다시 받은 첫 페이지는 다음 페이지가 없어요
        return HttpResponse.json(
          requestedCursors.length > 2 ? documentRecordingsResponse : firstPage,
        );
      }),
    );

    renderRecordingDocumentList();
    await findGroup(expected.items[0].title);

    reachEnd();
    await screen.findByRole("alert");
    await clickRetry();

    for (const { title } of expected.items) {
      expect(await findGroup(title)).toBeInTheDocument();
    }
    expect(requestedCursors).toEqual([null, firstPage.nextCursor, null]);
  });

  it("불러오는 동안에는 불러오는 중이라고 알리고, 목록을 받으면 묶음으로 바꾼다", async () => {
    mockServer.use(
      http.get(DOCUMENT_RECORDINGS_REQUEST, async () => {
        await delay(50);

        return HttpResponse.json(documentRecordingsResponse);
      }),
    );

    renderRecordingDocumentList();

    expect(
      screen.getByRole("status", { name: LOADING_LABEL }),
    ).toBeInTheDocument();

    await findGroup(expected.items[0].title);

    expect(
      screen.queryByRole("status", { name: LOADING_LABEL }),
    ).not.toBeInTheDocument();
  });

  it("문서가 나온 녹음이 하나도 없으면 아직 없다고 알린다", async () => {
    respondWith({ items: [], nextCursor: null });

    renderRecordingDocumentList();

    expect(
      await screen.findByText("아직 문서가 나온 녹음이 없어요"),
    ).toBeInTheDocument();
    expect(
      screen.getByText("녹음을 마치면 그 녹음에서 나온 문서가 여기에 모여요"),
    ).toBeInTheDocument();
  });

  it.each([
    ["잘못된 요청(400)", () => new HttpResponse(null, { status: 400 })],
    ["서버 오류(500)", () => new HttpResponse(null, { status: 500 })],
    ["네트워크 오류", () => HttpResponse.error()],
  ])(
    "%s로 불러오지 못하면 목록 자리에 안내와 「다시 시도」를 보여 준다",
    async (_, respond) => {
      mockServer.use(http.get(DOCUMENT_RECORDINGS_REQUEST, respond));

      renderRecordingDocumentList();

      expect(await screen.findByRole("alert")).toHaveTextContent(
        LOAD_FAILED_NOTICE,
      );
      expect(
        screen.getByRole("button", { name: "다시 시도" }),
      ).toBeInTheDocument();
    },
  );

  it("「다시 시도」를 누르면 다시 불러와 묶음을 보여 준다", async () => {
    let requestCount = 0;
    mockServer.use(
      http.get(DOCUMENT_RECORDINGS_REQUEST, () => {
        requestCount += 1;

        return requestCount === 1
          ? new HttpResponse(null, { status: 500 })
          : HttpResponse.json(documentRecordingsResponse);
      }),
    );

    renderRecordingDocumentList();
    await clickRetry();

    expect(await findGroup(expected.items[0].title)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("목록을 보는 동안 다시 불러오기가 서버 오류로 실패하면 받은 목록을 계속 보여 준다", async () => {
    const [{ title }] = expected.items;
    let failedRequestCount = 0;
    renderRecordingDocumentList();
    await findGroup(title);

    mockServer.use(
      http.get(DOCUMENT_RECORDINGS_REQUEST, () => {
        failedRequestCount += 1;

        return new HttpResponse(null, { status: 500 });
      }),
    );
    // 다른 탭에 다녀오면 목록을 다시 받아요
    act(() => {
      window.dispatchEvent(new Event("visibilitychange"));
    });
    await waitFor(() => expect(failedRequestCount).toBe(1));

    expect(screen.getByRole("region", { name: title })).toBeInTheDocument();
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
        http.get(
          DOCUMENT_RECORDINGS_REQUEST,
          () => new HttpResponse(null, { status }),
        ),
      );
      // 다른 화면으로 가면 목록이 사라져, 간 뒤에는 안내가 있었는지 알 수 없어요. 가기 전까지 그려진 화면을 모두 지켜봐요
      let isAlertShown = false;
      const observer = new MutationObserver(() => {
        if (screen.queryByRole("alert") !== null) isAlertShown = true;
      });
      observer.observe(document.body, { childList: true, subtree: true });

      renderRecordingDocumentList();

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
      http.get(DOCUMENT_RECORDINGS_REQUEST, () => {
        requestCount += 1;

        // 서버는 숫자가 아닌 워크스페이스 id를 잘못된 요청으로 거절해요
        return new HttpResponse(null, { status: 400 });
      }),
    );

    renderRecordingDocumentList("abc");

    expect(await screen.findByRole("alert")).toHaveTextContent(
      LOAD_FAILED_NOTICE,
    );
    expect(requestCount).toBe(1);
  });
});
