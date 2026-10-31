import {
  GetDocumentResponseDto,
  GetDocumentTranscriptResponseDto,
} from "@api/dto/document";
import {
  GetRecordingDocumentResponseDto,
  GetRecordingTranscriptResponseDto,
} from "@api/dto/recordingDocument";
import {
  documentDetailsResponse,
  documentTranscriptsResponse,
} from "@api/mock/responses/document";
import {
  recordingDocumentResponse,
  recordingTranscriptResponse,
} from "@api/mock/responses/recordingDocument";
import { mockServer } from "@api/mock/server";
import type { DocumentTranscriptResponse } from "@api/mock/types/document";
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
import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { formatDate } from "@utils/formatDate";
import { formatDurationFromSeconds } from "@utils/formatDurationFromSeconds";
import { formatRecordingTime } from "@utils/formatRecordingTime";
import { delay, http, HttpResponse } from "msw";
import { createMemoryRouter, Outlet, RouterProvider } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";

import TranscriptPanel from ".";
import { formatTranscriptText } from "./utils/formatTranscriptText";

vi.mock("@utils/captureAnalyticsEvent", () => ({
  captureAnalyticsEvent: vi.fn(),
}));

const [transcriptResponse] = documentTranscriptsResponse;
const expectedTranscript = new GetDocumentTranscriptResponseDto(
  transcriptResponse,
);
const expectedDocument = new GetDocumentResponseDto(documentDetailsResponse[0]);
const WORKSPACE_ID = "1";
const DOCUMENT_ID = String(expectedDocument.id);
// 경로 파라미터 자리에 무엇이 와도 잡도록 fetch 상수 대신 패턴을 적어요
const DOCUMENT_REQUEST =
  "*/api/v1/workspaces/:workspaceId/documents/:documentId";
const TRANSCRIPT_REQUEST = `${DOCUMENT_REQUEST}/transcript`;
const PANEL_TITLE = "원문";
const FOOTER_NOTICE =
  "이 문서를 만든 원본이에요. 전사 전체를 그대로 볼 수 있어요";
const MILLIS_PER_SECOND = 1000;
const LOADING_LABEL = "원문을 불러오고 있어요";
/** 복사한 뒤 「복사됨」을 보여 주는 시간 */
const COPIED_DURATION_MS = 3000;
const LOAD_FAILED_NOTICE = "원문을 불러오지 못했어요. 다시 시도해 주세요.";

const renderTranscriptPanel = (documentId = DOCUMENT_ID) => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const router = createMemoryRouter(
    [
      {
        // 복사에 실패하면 토스트로 알려서 토스트 자리를 둬요
        element: (
          <ToastProvider>
            <Outlet />
          </ToastProvider>
        ),
        children: [
          { path: PATH_ROUTE.DOCUMENT, element: <TranscriptPanel /> },
          // 이동을 확인할 수 있게 로그인 경로에 표시만 하는 화면을 둬요
          { path: PATH_ROUTE.LOGIN, element: <p>로그인 화면</p> },
        ],
      },
    ],
    {
      initialEntries: [
        getRouterPath({
          routeKey: "DOCUMENT",
          params: { workspaceId: WORKSPACE_ID, documentId },
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

const respondWithTranscript = (response: DocumentTranscriptResponse) =>
  mockServer.use(
    http.get(TRANSCRIPT_REQUEST, () => HttpResponse.json(response)),
  );

/** 발화 줄. 원문을 받은 뒤에 보여요 */
const findLines = () => screen.findAllByRole("listitem");

/**
 * 원문을 받은 뒤의 패널.
 * 불러오는 중 화면도 같은 이름의 패널을 따로 그리므로, 발화 줄이 보인 뒤에 찾아요
 */
const findLoadedPanel = async () => {
  await findLines();

  return screen.getByRole("region", { name: PANEL_TITLE });
};

const clickRetry = async () =>
  fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));

/** 다른 탭에 다녀온 것처럼 만들어 다시 불러오기를 일으켜요. TanStack Query는 탭이 다시 보이게 된 것을 visibilitychange 이벤트로 알아요 */
const refetchByReturningToTab = () =>
  act(() => {
    window.dispatchEvent(new Event("visibilitychange"));
  });

/** 발화 줄에 보이는 시각. 서버가 준 ms를 초로 바꿔 `분:초`로 보여 줘요 */
const formatLineTime = (startMillis: number) =>
  formatRecordingTime(startMillis / MILLIS_PER_SECOND);

describe("TranscriptPanel", () => {
  afterEach(() => {
    vi.useRealTimers();
    Reflect.deleteProperty(navigator, "clipboard");
  });

  it("제목 「원문」과 문서를 만든 날짜, 녹음 길이를 보여 준다", async () => {
    renderTranscriptPanel();

    const panel = within(await findLoadedPanel());

    expect(
      panel.getByRole("heading", { level: 2, name: PANEL_TITLE }),
    ).toBeInTheDocument();
    expect(
      await panel.findByText(formatDate(expectedDocument.createdAt)),
    ).toBeInTheDocument();
    expect(
      panel.getByText(
        formatDurationFromSeconds(expectedTranscript.recordingDurationSeconds),
      ),
    ).toBeInTheDocument();
  });

  it("발화 줄마다 시각과 「참여자 N:」, 문장을 서버가 준 순서대로 보여 준다", async () => {
    renderTranscriptPanel();

    const lines = await findLines();

    expect(lines).toHaveLength(expectedTranscript.segments.length);
    expectedTranscript.segments.forEach(
      ({ startMillis, speakerNumber, text }, index) => {
        const line = within(lines[index]);

        expect(line.getByText(formatLineTime(startMillis))).toBeInTheDocument();

        // 화자 번호가 없는 구간은 다음 테스트에서 봐요
        if (speakerNumber === null) return;

        expect(
          line.getByText(`참여자 ${speakerNumber}: ${text}`),
        ).toBeInTheDocument();
      },
    );
  });

  it("화자 번호가 없는 구간은 화자 없이 문장만 보여 준다", async () => {
    const lineIndex = expectedTranscript.segments.findIndex(
      ({ speakerNumber }) => speakerNumber === null,
    );
    const { text } = expectedTranscript.segments[lineIndex];

    renderTranscriptPanel();

    const line = (await findLines())[lineIndex];

    expect(within(line).getByText(text)).toBeInTheDocument();
    expect(line).not.toHaveTextContent("참여자");
  });

  it("한 시간이 넘은 발화의 시각은 시:분:초로 보여 준다", async () => {
    const response = {
      ...transcriptResponse,
      segments: [
        {
          startMillis: (60 * 60 + 2 * 60 + 3) * MILLIS_PER_SECOND,
          endMillis: null,
          speakerNumber: 1,
          text: "한 시간이 지났네요.",
        },
      ],
    } satisfies DocumentTranscriptResponse;
    const [segment] = new GetDocumentTranscriptResponseDto(response).segments;

    respondWithTranscript(response);
    renderTranscriptPanel();

    const [line] = await findLines();

    expect(
      within(line).getByText(formatLineTime(segment.startMillis)),
    ).toBeInTheDocument();
    expect(formatLineTime(segment.startMillis)).toBe("1:02:03");
  });

  it("발화 아래에 원문 안내 문구를 보여 준다", async () => {
    renderTranscriptPanel();

    expect(
      within(await findLoadedPanel()).getByText(FOOTER_NOTICE),
    ).toBeInTheDocument();
  });

  it("주소의 문서 id로 원문을 한 번 요청한다", async () => {
    const [, otherDocument] = documentDetailsResponse;
    const requestedDocumentIds: unknown[] = [];

    mockServer.use(
      http.get(TRANSCRIPT_REQUEST, ({ params }) => {
        requestedDocumentIds.push(params.documentId);

        return HttpResponse.json(transcriptResponse);
      }),
    );

    renderTranscriptPanel(String(otherDocument.id));

    await findLines();

    expect(requestedDocumentIds).toEqual([String(otherDocument.id)]);
  });

  it("문서 상세를 받지 못해도 원문과 녹음 길이는 보여 주고, 만든 날짜만 뺀다", async () => {
    let documentRequestCount = 0;

    mockServer.use(
      http.get(DOCUMENT_REQUEST, () => {
        documentRequestCount += 1;

        return new HttpResponse(null, { status: 500 });
      }),
      // 문서 상세의 실패가 화면에 반영된 뒤에 원문이 오게 늦춰요. 그래야 원문이 그려진 화면이 마지막 상태예요
      http.get(TRANSCRIPT_REQUEST, async () => {
        await delay(50);

        return HttpResponse.json(transcriptResponse);
      }),
    );

    renderTranscriptPanel();

    const panel = within(await findLoadedPanel());

    expect(documentRequestCount).toBe(1);
    expect(panel.getAllByRole("listitem")).toHaveLength(
      expectedTranscript.segments.length,
    );
    expect(
      panel.getByText(
        formatDurationFromSeconds(expectedTranscript.recordingDurationSeconds),
      ),
    ).toBeInTheDocument();
    expect(
      panel.queryByText(formatDate(expectedDocument.createdAt)),
    ).not.toBeInTheDocument();
  });

  it("불러오는 동안에는 제목 「원문」을 그대로 두고 불러오는 중이라고 알리고, 원문을 받으면 발화 줄로 바꾼다", async () => {
    mockServer.use(
      http.get(TRANSCRIPT_REQUEST, async () => {
        await delay(50);

        return HttpResponse.json(transcriptResponse);
      }),
    );

    renderTranscriptPanel();

    expect(
      screen.getByRole("status", { name: LOADING_LABEL }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { level: 2, name: PANEL_TITLE }),
    ).toBeInTheDocument();

    await findLines();

    expect(
      screen.queryByRole("status", { name: LOADING_LABEL }),
    ).not.toBeInTheDocument();
  });

  it.each([
    ["잘못된 요청(400)", () => new HttpResponse(null, { status: 400 })],
    ["볼 수 없는 문서(403)", () => new HttpResponse(null, { status: 403 })],
    ["원문 없음(404)", () => new HttpResponse(null, { status: 404 })],
    ["서버 오류(500)", () => new HttpResponse(null, { status: 500 })],
    ["네트워크 오류", () => HttpResponse.error()],
  ])(
    "%s로 불러오지 못하면 제목 「원문」을 그대로 두고 안내와 「다시 시도」를 보여 준다",
    async (_, respond) => {
      mockServer.use(http.get(TRANSCRIPT_REQUEST, respond));

      renderTranscriptPanel();

      expect(await screen.findByRole("alert")).toHaveTextContent(
        LOAD_FAILED_NOTICE,
      );
      expect(
        screen.getByRole("button", { name: "다시 시도" }),
      ).toBeInTheDocument();
      expect(
        screen.getByRole("heading", { level: 2, name: PANEL_TITLE }),
      ).toBeInTheDocument();
      expect(screen.queryByRole("listitem")).not.toBeInTheDocument();
    },
  );

  it("「다시 시도」를 누르면 원문을 다시 조회해 발화 줄을 보여 준다", async () => {
    let requestCount = 0;

    mockServer.use(
      http.get(TRANSCRIPT_REQUEST, () => {
        requestCount += 1;

        // 첫 요청만 실패해요
        return requestCount === 1
          ? new HttpResponse(null, { status: 500 })
          : HttpResponse.json(transcriptResponse);
      }),
    );

    renderTranscriptPanel();

    await clickRetry();

    expect(await findLines()).toHaveLength(expectedTranscript.segments.length);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(requestCount).toBe(2);
  });

  it("원문을 보는 동안 다시 불러오기가 서버 오류로 실패하면 받은 원문을 계속 보여 준다", async () => {
    let failedRequestCount = 0;

    renderTranscriptPanel();

    await findLines();

    mockServer.use(
      http.get(TRANSCRIPT_REQUEST, () => {
        failedRequestCount += 1;

        return new HttpResponse(null, { status: 500 });
      }),
    );
    refetchByReturningToTab();
    await waitFor(() => expect(failedRequestCount).toBe(1));

    expect(screen.getAllByRole("listitem")).toHaveLength(
      expectedTranscript.segments.length,
    );
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("원문을 보는 동안 원문이 없어지면(404) 다시 불러올 때 받은 원문 대신 불러오지 못했다고 알린다", async () => {
    renderTranscriptPanel();

    await findLines();

    mockServer.use(
      http.get(
        TRANSCRIPT_REQUEST,
        () => new HttpResponse(null, { status: 404 }),
      ),
    );
    refetchByReturningToTab();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      LOAD_FAILED_NOTICE,
    );
    expect(screen.queryByRole("listitem")).not.toBeInTheDocument();
  });

  it("로그인이 풀렸으면(401) 안내를 보여 주지 않고 로그인 화면으로 보낸다", async () => {
    mockServer.use(
      http.get(
        TRANSCRIPT_REQUEST,
        () => new HttpResponse(null, { status: 401 }),
      ),
    );

    // 로그인 화면으로 가면 패널이 사라져, 간 뒤에는 안내가 있었는지 알 수 없어요. 가기 전까지 그려진 화면을 모두 지켜봐요
    let isAlertShown = false;
    const observer = new MutationObserver(() => {
      if (screen.queryByRole("alert") !== null) isAlertShown = true;
    });
    observer.observe(document.body, { childList: true, subtree: true });

    renderTranscriptPanel();

    try {
      expect(await screen.findByText("로그인 화면")).toBeInTheDocument();
    } finally {
      observer.disconnect();
    }
    expect(isAlertShown).toBe(false);
  });

  it("주소의 문서 id가 숫자가 아니어도 확인하지 않고 그대로 요청하고, 서버가 거절하면 불러오지 못했다고 알린다", async () => {
    let requestCount = 0;

    mockServer.use(
      http.get(TRANSCRIPT_REQUEST, () => {
        requestCount += 1;

        // 서버는 숫자가 아닌 문서 id를 잘못된 요청으로 거절해요
        return new HttpResponse(null, { status: 400 });
      }),
    );

    renderTranscriptPanel("abc");

    expect(await screen.findByRole("alert")).toHaveTextContent(
      LOAD_FAILED_NOTICE,
    );
    expect(requestCount).toBe(1);
  });

  it("머리의 복사를 누르면 보이는 발화 줄을 한 줄씩 복사하고, 3초 동안 복사됨을 보여 준다", async () => {
    const writeText = vi
      .fn<(text: string) => Promise<void>>()
      .mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText },
      configurable: true,
    });
    renderTranscriptPanel();
    const panel = within(await findLoadedPanel());
    vi.useFakeTimers();

    await act(async () => {
      fireEvent.click(panel.getByRole("button", { name: "복사" }));
    });

    expect(writeText).toHaveBeenCalledWith(
      formatTranscriptText(expectedTranscript.segments),
    );
    expect(panel.getByRole("button", { name: "복사됨" })).toBeInTheDocument();

    await act(async () => {
      vi.advanceTimersByTime(COPIED_DURATION_MS);
    });

    expect(panel.getByRole("button", { name: "복사" })).toBeInTheDocument();
  });

  it("원문을 복사하면 문서 보기의 원문 복사를 사용 기록으로 보낸다", async () => {
    vi.mocked(captureAnalyticsEvent).mockClear();
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText: vi.fn().mockResolvedValue(undefined) },
      configurable: true,
    });
    renderTranscriptPanel();
    const panel = within(await findLoadedPanel());

    await act(async () => {
      fireEvent.click(panel.getByRole("button", { name: "복사" }));
    });

    expect(captureAnalyticsEvent).toHaveBeenCalledWith({
      event: "content_copied",
      properties: {
        content: "transcript",
        screen: "document",
        document_id: Number(DOCUMENT_ID),
        workspace_id: Number(WORKSPACE_ID),
      },
    });
  });
});

describe("TranscriptPanel · 녹음 문서 화면 (STT-R39)", () => {
  const expectedRecordingDocument = new GetRecordingDocumentResponseDto(
    recordingDocumentResponse,
  );
  const expectedRecordingTranscript = new GetRecordingTranscriptResponseDto(
    recordingTranscriptResponse,
  );
  const RECORDING_TRANSCRIPT_REQUEST =
    "*/api/v1/workspaces/:workspaceId/recordings/:recordingId/transcript";

  const renderRecordingTranscriptPanel = () => {
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
              path: PATH_ROUTE.RECORDING_DOCUMENT,
              element: <TranscriptPanel />,
            },
            { path: PATH_ROUTE.LOGIN, element: <p>로그인 화면</p> },
          ],
        },
      ],
      {
        initialEntries: [
          getRouterPath({
            routeKey: "RECORDING_DOCUMENT",
            params: {
              workspaceId: WORKSPACE_ID,
              recordingId: String(expectedRecordingDocument.recordingId),
            },
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

    return { router, queryClient };
  };

  afterEach(() => {
    vi.useRealTimers();
    Reflect.deleteProperty(navigator, "clipboard");
  });

  it("녹음의 전사를 발화 줄로 보여 주고, 녹음을 시작한 날과 녹음 길이를 둔다", async () => {
    renderRecordingTranscriptPanel();

    const panel = within(await findLoadedPanel());

    expect(await findLines()).toHaveLength(
      expectedRecordingTranscript.segments.length,
    );
    expect(
      await panel.findByText(formatDate(expectedRecordingDocument.startedAt)),
    ).toBeInTheDocument();
    expect(
      panel.getByText(
        formatDurationFromSeconds(
          expectedRecordingDocument.recordingDurationSeconds,
        ),
      ),
    ).toBeInTheDocument();
  });

  it("발화 아래에 녹음의 전사 전체라는 안내를 둔다", async () => {
    renderRecordingTranscriptPanel();

    const panel = within(await findLoadedPanel());

    expect(panel.getByText("이 녹음의 전사 전체예요.")).toBeInTheDocument();
  });

  it("문서 원문은 요청하지 않고 녹음 전사만 요청한다", async () => {
    let documentTranscriptRequestCount = 0;
    mockServer.use(
      http.get(TRANSCRIPT_REQUEST, () => {
        documentTranscriptRequestCount += 1;
        return HttpResponse.json(transcriptResponse);
      }),
    );
    renderRecordingTranscriptPanel();

    await findLoadedPanel();

    expect(documentTranscriptRequestCount).toBe(0);
  });

  it("전사를 불러오지 못하면 그 자리에서 알리고, 「다시 시도」로 다시 조회해 발화 줄을 보여 준다", async () => {
    mockServer.use(
      http.get(
        RECORDING_TRANSCRIPT_REQUEST,
        () => new HttpResponse(null, { status: 500 }),
        { once: true },
      ),
    );
    renderRecordingTranscriptPanel();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      LOAD_FAILED_NOTICE,
    );

    await clickRetry();

    expect(await findLines()).toHaveLength(
      expectedRecordingTranscript.segments.length,
    );
  });

  it("머리의 복사를 누르면 녹음 전사의 발화 줄을 한 줄씩 복사한다", async () => {
    const writeText = vi
      .fn<(text: string) => Promise<void>>()
      .mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText },
      configurable: true,
    });
    renderRecordingTranscriptPanel();
    const panel = within(await findLoadedPanel());

    await act(async () => {
      fireEvent.click(panel.getByRole("button", { name: "복사" }));
    });

    expect(writeText).toHaveBeenCalledWith(
      formatTranscriptText(expectedRecordingTranscript.segments),
    );
  });

  it("원문을 복사하면 녹음 문서의 원문 복사를 사용 기록으로 보낸다", async () => {
    vi.mocked(captureAnalyticsEvent).mockClear();
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText: vi.fn().mockResolvedValue(undefined) },
      configurable: true,
    });
    renderRecordingTranscriptPanel();
    const panel = within(await findLoadedPanel());

    await act(async () => {
      fireEvent.click(panel.getByRole("button", { name: "복사" }));
    });

    expect(captureAnalyticsEvent).toHaveBeenCalledWith({
      event: "content_copied",
      properties: {
        content: "transcript",
        screen: "recording_document",
        recording_id: expectedRecordingDocument.recordingId,
        workspace_id: Number(WORKSPACE_ID),
      },
    });
  });

  describe("신규 원문 호환성 (ERR-QA-004 · ERR-QA-005 · ERR-QA-006)", () => {
    const unsupported = () => HttpResponse.json({ code: "REQUEST_PATH_NOT_FOUND" }, { status: 404 });
    it("신규 원문 경로만 미지원이면 해당 패널에서 준비 안내한다", async () => {
      mockServer.use(http.get(RECORDING_TRANSCRIPT_REQUEST, unsupported));
      renderRecordingTranscriptPanel();
      expect(await screen.findByText("업데이트 준비 중")).toBeInTheDocument();
      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    });
    it("정상 원문 캐시 뒤 미지원이면 복사를 막고 다시 확인해 복구한다", async () => {
      const { queryClient } = renderRecordingTranscriptPanel();
      await findLines();
      mockServer.use(http.get(RECORDING_TRANSCRIPT_REQUEST, unsupported));
      await act(async () => { await queryClient.invalidateQueries(); });
      expect(await screen.findByText("업데이트 준비 중")).toBeInTheDocument();
      expect(screen.queryByRole("listitem")).not.toBeInTheDocument();
      expect(screen.queryByRole("button", { name: /복사/ })).not.toBeInTheDocument();
      for (const status of [500, 0]) {
        mockServer.use(http.get(RECORDING_TRANSCRIPT_REQUEST, () => status === 0 ? HttpResponse.error() : new HttpResponse(null, { status })));
        await act(async () => { await queryClient.invalidateQueries(); });
        expect(await screen.findByRole("alert")).toHaveTextContent(LOAD_FAILED_NOTICE);
        expect(screen.queryByRole("listitem")).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: /복사/ })).not.toBeInTheDocument();
        expect(screen.queryByText("업데이트 준비 중")).not.toBeInTheDocument();
      }
      mockServer.use(http.get(RECORDING_TRANSCRIPT_REQUEST, () => HttpResponse.json(recordingTranscriptResponse)));
      fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
      expect(await findLines()).toHaveLength(expectedRecordingTranscript.segments.length);
    });
    it("같은 경로 오류라도 기존 문서 원문에는 준비 안내를 적용하지 않는다", async () => {
      mockServer.use(http.get(TRANSCRIPT_REQUEST, unsupported));
      renderTranscriptPanel();
      expect(await screen.findByRole("alert")).toHaveTextContent(LOAD_FAILED_NOTICE);
      expect(screen.queryByText("업데이트 준비 중")).not.toBeInTheDocument();
    });
    it.each([
      [401, "UNAUTHORIZED"], [403, "WORKSPACE_ACCESS_DENIED"],
      [404, "RECORDING_TRANSCRIPT_NOT_FOUND"], [500, "INTERNAL_SERVER_ERROR"], [0, "NETWORK"],
    ] as const)("신규 원문 실제 오류 %s %s는 준비 안내로 바꾸지 않는다", async (status, code) => {
      mockServer.use(http.get(RECORDING_TRANSCRIPT_REQUEST, () => status === 0
        ? HttpResponse.error() : HttpResponse.json({ code }, { status })));
      renderRecordingTranscriptPanel();
      if (status === 401) expect(await screen.findByText("로그인 화면")).toBeInTheDocument();
      else expect(await screen.findByRole("alert")).toHaveTextContent(LOAD_FAILED_NOTICE);
      expect(screen.queryByText("업데이트 준비 중")).not.toBeInTheDocument();
    });
  });

});
