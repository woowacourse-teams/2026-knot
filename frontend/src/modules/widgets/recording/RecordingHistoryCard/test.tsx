import { GetMeResponseDto } from "@api/dto/auth";
import {
  GetCurrentRecordingResponseDto,
  GetRecordingsResponseDto,
} from "@api/dto/recording";
import { DOCUMENT_GENERATION_JOB_RETRY_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]/documentGenerationJobs/[jobId]/retry";
import { RECORDINGS_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings";
import { RECORDING_TRANSCRIPTION_RETRY_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/transcription/retry";
import { CURRENT_RECORDING_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/current";
import { meResponse } from "@api/mock/responses/auth";
import {
  currentRecordingResponse,
  recordingsResponse,
} from "@api/mock/responses/recording";
import { mockServer } from "@api/mock/server";
import { ThemeProvider } from "@emotion/react";
import { ToastProvider } from "@provider/context/toastContext";
import { theme } from "@provider/themeProvider";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import { useRecordingStore } from "@store/recordingStore";
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
import { formatRecordingTime } from "@utils/formatRecordingTime";
import { delay, http, HttpResponse } from "msw";
import {
  createMemoryRouter,
  Outlet,
  RouterProvider,
  useParams,
} from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import RecordingHistoryCard from ".";

const WORKSPACE_ID = 1;
const HOME_PATH = getRouterPath({
  routeKey: "WORKSPACE_HOME",
  params: { workspaceId: String(WORKSPACE_ID) },
});
const RECORDING_PATH = getRouterPath({
  routeKey: "RECORDING",
  params: { workspaceId: String(WORKSPACE_ID) },
});
const RECORDINGS_URL = `*${RECORDINGS_API_PATH(WORKSPACE_ID)}`;
const CURRENT_URL = `*${CURRENT_RECORDING_API_PATH(WORKSPACE_ID)}`;
const jobRetryUrl = (jobId: number) =>
  `*${DOCUMENT_GENERATION_JOB_RETRY_API_PATH(WORKSPACE_ID, jobId)}`;

const CARD_TITLE = "녹음 기록";
const RECORDING_TITLE = `${new GetMeResponseDto(meResponse).nickname} 님의 녹음`;
const OPEN_RECORDING = "녹음 화면으로";
const VIEW_RECORDING = "녹음 보기";
const RETRY = "다시 시도";
const OTHER_TAB_HINT = "녹음 중인 탭에서 일시정지·끝내기를 할 수 있어요";
const EMPTY_TEXT = "아직 녹음이 없어요";
const LOAD_FAILED_TEXT = "녹음 기록을 불러오지 못했어요. 다시 시도해 주세요.";
const RETRY_FAILED_TOAST =
  "다시 시도하지 못했어요. 잠시 후 다시 시도해 주세요.";
const STATUS_TEXT = {
  PROCESSING: "문서 정리 중",
  TRANSCRIPTION_FAILED: "녹음을 글로 옮기지 못했어요",
  DOCUMENT_FAILED: "문서를 만들지 못했어요",
  NO_CONTENT: "변환된 음성 기록이 없어요.",
} as const;
// 문서 정리 중인 행이 있을 때 목록을 다시 받는 간격
const POLL_INTERVAL_MS = 3000;
const MS_PER_SECOND = 1000;

const expected = new GetRecordingsResponseDto(recordingsResponse);
const expectedCurrent = new GetCurrentRecordingResponseDto(
  currentRecordingResponse,
);
// 서버가 한 페이지에 주는 녹음 수. mock 핸들러도 이만큼 잘라 줘요
const PAGE_SIZE = 20;
const firstPageItems = expected.items.slice(0, PAGE_SIZE);

type MockItem = (typeof recordingsResponse.items)[number];

/** mock 녹음 기록에서 조건에 맞는 첫 행(서버 JSON 모양) */
const findMockItem = (predicate: (item: MockItem) => boolean) =>
  recordingsResponse.items.find(predicate)!;

const PROCESSING_ITEM = findMockItem(
  ({ processingStatus }) => processingStatus === "PROCESSING",
);
const COMPLETED_ITEM = findMockItem(
  ({ processingStatus }) => processingStatus === "COMPLETED",
);
const NO_CONTENT_ITEM = findMockItem(
  ({ processingStatus }) => processingStatus === "NO_CONTENT",
);
const TRANSCRIPTION_FAILED_ITEM = findMockItem(
  ({ processingStatus }) => processingStatus === "TRANSCRIPTION_FAILED",
);
// 작업이 둘 이상인 문서 생성 실패. 작업마다 요청하는지는 둘 이상이어야 드러나요
const MULTI_JOB_FAILED_ITEM = findMockItem(
  ({ processingStatus, retryableJobIds }) =>
    processingStatus === "DOCUMENT_FAILED" && retryableJobIds.length > 1,
);
const NOT_RETRYABLE_FAILED_ITEM = findMockItem(
  ({ processingStatus, retryable }) =>
    processingStatus === "DOCUMENT_FAILED" && !retryable,
);

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

// 어느 녹음 문서로 갔는지 확인할 수 있게 녹음 문서 경로에 표시만 하는 화면을 둬요
function RecordingDocumentStub() {
  const { recordingId } = useParams();

  return <p>녹음 문서 화면 {recordingId}</p>;
}

const renderCard = () => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  // 다시 시도의 오류 토스트가 보이도록 토스트 공급자를 라우트 안에 둬요. 토스트는 현재 주소를 읽어요
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
            element: <RecordingHistoryCard />,
          },
          { path: PATH_ROUTE.RECORDING, element: null },
          {
            path: PATH_ROUTE.RECORDING_DOCUMENT,
            element: <RecordingDocumentStub />,
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

  return { router };
};

const getCard = () => screen.getByRole("region", { name: CARD_TITLE });

/** 목록에서 그 녹음의 행. 행은 서버가 준 순서 그대로예요 */
const findRow = async (recordingId: number) => {
  const rows = await within(getCard()).findAllByRole("listitem");

  return rows[
    firstPageItems.findIndex((item) => item.recordingId === recordingId)
  ];
};

/** 녹음 기록 조회가 이 응답으로 답하게 해요 */
const respondRecordings = (resolver: Parameters<typeof http.get>[1]) => {
  mockServer.use(http.get(RECORDINGS_URL, resolver));
};

const respondItems = (items: MockItem[]) =>
  respondRecordings(() => HttpResponse.json({ items, nextCursor: null }));

const respondNoCurrentRecording = () =>
  mockServer.use(
    http.get(CURRENT_URL, () => new HttpResponse(null, { status: 204 })),
  );

/** 이 주소로 간 요청 수를 세요 */
const countRequests = (method: "get" | "post", url: string) => {
  let count = 0;
  mockServer.events.on("request:start", ({ request }) => {
    const { pathname } = new URL(request.url);
    if (
      request.method.toLowerCase() === method &&
      pathname === url.replace(/^\*/, "")
    ) {
      count += 1;
    }
  });

  return () => count;
};

/** 가짜 타이머를 ms만큼 진행시키며 사이사이의 응답 마이크로태스크도 흘려보내요 */
const advanceTimers = async (ms: number) => {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
};

/** 독에서 시작한 것처럼 이 탭에서 녹음을 시작해 둬요 */
const startRecordingInThisTab = async () => {
  await act(async () => {
    await useRecordingStore.getState().connectMicrophone();
    useRecordingStore.getState().startRecording({
      workspaceId: WORKSPACE_ID,
      recordingId: expectedCurrent.recordingId,
    });
  });
};

describe("RecordingHistoryCard", () => {
  beforeEach(() => {
    intersectionCallbacks.length = 0;
    vi.stubGlobal("IntersectionObserver", FakeIntersectionObserver);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    // 전역 저장소라 테스트끼리 녹음이 새지 않도록 처음 상태로 되돌려요
    useRecordingStore.getState().discardRecording();
    mockServer.events.removeAllListeners();
  });

  it("녹음 기록을 받는 동안에는 카드가 불러오는 중으로 표시된다", () => {
    respondRecordings(async () => {
      await delay("infinite");
      return HttpResponse.json(recordingsResponse);
    });

    renderCard();

    expect(getCard()).toHaveAttribute("aria-busy", "true");
  });

  describe("행 (STT-R32 · R51 · R34)", () => {
    it("녹음마다 한 행을 서버가 준 순서대로 두고, 제목에는 마우스를 올리면 볼 수 있는 전체 제목을 둔다", async () => {
      renderCard();

      const rows = await within(getCard()).findAllByRole("listitem");

      expect(rows).toHaveLength(firstPageItems.length);
      rows.forEach((row, index) => {
        expect(
          within(row).getByTitle(firstPageItems[index].title),
        ).toBeVisible();
      });
    });

    it("완료 행은 처리 상태 대신 녹음 날짜(연도 포함)와 녹음 길이 칩을 보여 준다", async () => {
      renderCard();

      const row = await findRow(COMPLETED_ITEM.recordingId);
      const completed = expected.items.find(
        ({ recordingId }) => recordingId === COMPLETED_ITEM.recordingId,
      )!;

      expect(
        within(row).getByText(formatDate(completed.startedAt)),
      ).toBeInTheDocument();
      expect(
        within(row).getByText(
          formatDurationFromSeconds(completed.recordingDurationSeconds),
        ),
      ).toBeInTheDocument();
      Object.values(STATUS_TEXT).forEach((text) =>
        expect(within(row).queryByText(text)).not.toBeInTheDocument(),
      );
    });

    it.each([
      ["문서 정리 중", PROCESSING_ITEM],
      ["전사 실패", TRANSCRIPTION_FAILED_ITEM],
      ["문서 생성 실패", MULTI_JOB_FAILED_ITEM],
      ["정리할 내용 없음", NO_CONTENT_ITEM],
    ])("%s 행은 날짜와 길이 대신 처리 상태를 보여 준다", async (_, item) => {
      renderCard();

      const row = await findRow(item.recordingId);

      expect(
        within(row).getByText(
          STATUS_TEXT[item.processingStatus as keyof typeof STATUS_TEXT],
        ),
      ).toBeInTheDocument();
      expect(
        within(row).queryByText(formatDate(item.startedAt)),
      ).not.toBeInTheDocument();
    });

    it("문서 정리 중 행의 「녹음 보기」는 누를 수 없다", async () => {
      renderCard();

      const row = await findRow(PROCESSING_ITEM.recordingId);
      const viewRecording = within(row).getByRole("link", {
        name: VIEW_RECORDING,
      });

      expect(viewRecording).toHaveAttribute("aria-disabled", "true");
      expect(viewRecording).not.toHaveAttribute("href");
    });

    it.each([
      ["정리할 내용 없음", NO_CONTENT_ITEM],
      ["완료", COMPLETED_ITEM],
      ["다시 시도할 수 없는 실패", NOT_RETRYABLE_FAILED_ITEM],
    ])(
      "%s 행의 「녹음 보기」를 누르면 그 녹음의 녹음 문서로 간다",
      async (_, item) => {
        renderCard();

        const row = await findRow(item.recordingId);
        expect(
          within(row).queryByRole("button", { name: RETRY }),
        ).not.toBeInTheDocument();

        fireEvent.click(
          within(row).getByRole("link", { name: VIEW_RECORDING }),
        );

        expect(
          await screen.findByText(`녹음 문서 화면 ${item.recordingId}`),
        ).toBeInTheDocument();
      },
    );

    it("다시 시도할 수 있는 실패 행에는 「녹음 보기」 대신 「다시 시도」를 둔다", async () => {
      renderCard();

      for (const item of [TRANSCRIPTION_FAILED_ITEM, MULTI_JOB_FAILED_ITEM]) {
        const row = await findRow(item.recordingId);

        expect(
          within(row).getByRole("button", { name: RETRY }),
        ).toBeInTheDocument();
        expect(
          within(row).queryByRole("link", { name: VIEW_RECORDING }),
        ).not.toBeInTheDocument();
      }
    });
  });

  describe("행 다시 시도 (STT-R34 · R42)", () => {
    it("전사 실패 행에서 「다시 시도」를 누르면 전사 다시 시도를 한 번 보내고, 목록을 다시 받아 그 행이 문서 정리 중으로 바뀐다", async () => {
      const getRetryCount = countRequests(
        "post",
        `*${RECORDING_TRANSCRIPTION_RETRY_API_PATH(WORKSPACE_ID, TRANSCRIPTION_FAILED_ITEM.recordingId)}`,
      );
      renderCard();

      const row = await findRow(TRANSCRIPTION_FAILED_ITEM.recordingId);
      fireEvent.click(within(row).getByRole("button", { name: RETRY }));

      expect(
        await within(row).findByText(STATUS_TEXT.PROCESSING),
      ).toBeInTheDocument();
      expect(getRetryCount()).toBe(1);
      expect(
        within(row).queryByRole("button", { name: RETRY }),
      ).not.toBeInTheDocument();
    });

    it("문서 생성 실패 행에서 「다시 시도」를 누르면 작업마다 문서 생성 작업 재시도를 보내고 그 행이 문서 정리 중으로 바뀐다", async () => {
      const jobIds = MULTI_JOB_FAILED_ITEM.retryableJobIds;
      const getRetryCounts = jobIds.map((jobId) =>
        countRequests("post", jobRetryUrl(jobId)),
      );
      renderCard();

      const row = await findRow(MULTI_JOB_FAILED_ITEM.recordingId);
      fireEvent.click(within(row).getByRole("button", { name: RETRY }));

      expect(
        await within(row).findByText(STATUS_TEXT.PROCESSING),
      ).toBeInTheDocument();
      expect(getRetryCounts.map((getRetryCount) => getRetryCount())).toEqual(
        jobIds.map(() => 1),
      );
    });

    it("다시 시도가 거절되면 목록을 다시 받아 상태를 맞추고, 요청을 저절로 다시 보내지 않는다", async () => {
      const [jobId] = MULTI_JOB_FAILED_ITEM.retryableJobIds;
      mockServer.use(
        ...MULTI_JOB_FAILED_ITEM.retryableJobIds.map((failedJobId) =>
          http.post(jobRetryUrl(failedJobId), () =>
            HttpResponse.json(
              { code: "RETRY_NOT_ALLOWED", message: "재시도할 수 없습니다" },
              { status: 409 },
            ),
          ),
        ),
      );
      const getRetryCount = countRequests("post", jobRetryUrl(jobId));
      const getListCount = countRequests("get", RECORDINGS_URL);
      renderCard();

      const row = await findRow(MULTI_JOB_FAILED_ITEM.recordingId);
      const listCountBeforeRetry = getListCount();
      fireEvent.click(within(row).getByRole("button", { name: RETRY }));

      await waitFor(() =>
        expect(getListCount()).toBeGreaterThan(listCountBeforeRetry),
      );
      expect(getRetryCount()).toBe(1);
      expect(screen.queryByText(RETRY_FAILED_TOAST)).not.toBeInTheDocument();
    });
  });

  describe("목록 (STT-R31 · R33)", () => {
    it("목록 끝에 닿으면 다음 묶음을 받아 이어 보여 주고, 묶음 사이에 겹친 녹음은 한 행만 둔다", async () => {
      const [first, overlapped, last] = recordingsResponse.items;
      const requestedCursors: (string | null)[] = [];
      respondRecordings(({ request }) => {
        const cursor = new URL(request.url).searchParams.get("cursor");
        requestedCursors.push(cursor);

        return HttpResponse.json(
          cursor === null
            ? { items: [first, overlapped], nextCursor: "next" }
            : { items: [overlapped, last], nextCursor: null },
        );
      });
      renderCard();

      expect(await within(getCard()).findAllByRole("listitem")).toHaveLength(2);

      reachEnd();

      await waitFor(() =>
        expect(within(getCard()).getAllByRole("listitem")).toHaveLength(3),
      );
      expect(requestedCursors).toEqual([null, "next"]);
      expect(within(getCard()).getByTitle(last.title)).toBeInTheDocument();
    });

    it("맨 위 줄과 녹음 목록이 모두 없으면 빈 상태 문구를 보여 준다", async () => {
      respondNoCurrentRecording();
      respondItems([]);

      renderCard();

      expect(await screen.findByText(EMPTY_TEXT)).toBeInTheDocument();
    });

    it("녹음 기록을 불러오지 못하면 카드 안에 안내와 다시 시도를 두고, 다시 시도는 목록만 다시 받는다 (ERR-09 · HOME-R6)", async () => {
      respondRecordings(() => new HttpResponse(null, { status: 500 }));
      renderCard();

      expect(
        await within(getCard()).findByText(LOAD_FAILED_TEXT),
      ).toBeInTheDocument();

      respondItems([PROCESSING_ITEM]);
      fireEvent.click(within(getCard()).getByRole("button", { name: RETRY }));

      expect(
        await within(getCard()).findByText(STATUS_TEXT.PROCESSING),
      ).toBeInTheDocument();
    });

    it("다음 묶음을 받지 못해도 카드 안 안내로 알리고, 다시 시도로 다음 묶음을 받는다", async () => {
      const [first, second] = recordingsResponse.items;
      let isNextPageFailing = true;
      respondRecordings(({ request }) => {
        const cursor = new URL(request.url).searchParams.get("cursor");

        if (cursor === null) {
          return HttpResponse.json({ items: [first], nextCursor: "next" });
        }

        return isNextPageFailing
          ? new HttpResponse(null, { status: 500 })
          : HttpResponse.json({ items: [second], nextCursor: null });
      });
      renderCard();

      await within(getCard()).findAllByRole("listitem");
      reachEnd();

      expect(
        await within(getCard()).findByText(LOAD_FAILED_TEXT),
      ).toBeInTheDocument();

      isNextPageFailing = false;
      fireEvent.click(within(getCard()).getByRole("button", { name: RETRY }));

      await waitFor(() =>
        expect(within(getCard()).getAllByRole("listitem")).toHaveLength(2),
      );
    });
  });

  describe("상태 갱신", () => {
    it("문서 정리 중 행이 있는 동안 3초마다 목록을 다시 받아, 정리가 끝나면 결과 상태로 바꾸고 다시 받기를 멈춘다", async () => {
      let isProcessing = true;
      respondRecordings(() =>
        HttpResponse.json({
          items: [
            isProcessing
              ? PROCESSING_ITEM
              : { ...PROCESSING_ITEM, processingStatus: "COMPLETED" },
          ],
          nextCursor: null,
        }),
      );
      const getListCount = countRequests("get", RECORDINGS_URL);
      vi.useFakeTimers();
      renderCard();
      await advanceTimers(0);

      expect(screen.getByText(STATUS_TEXT.PROCESSING)).toBeInTheDocument();

      isProcessing = false;
      await advanceTimers(POLL_INTERVAL_MS);

      expect(
        screen.getByText(formatDate(PROCESSING_ITEM.startedAt)),
      ).toBeInTheDocument();
      const listCountAfterCompleted = getListCount();

      await advanceTimers(POLL_INTERVAL_MS * 3);

      expect(getListCount()).toBe(listCountAfterCompleted);
    });

    it("다시 받기가 4xx로 실패하면 다시 받기를 멈추고 받은 행을 그대로 보여 준다", async () => {
      let status = 200;
      respondRecordings(() =>
        status === 200
          ? HttpResponse.json({ items: [PROCESSING_ITEM], nextCursor: null })
          : new HttpResponse(null, { status }),
      );
      const getListCount = countRequests("get", RECORDINGS_URL);
      vi.useFakeTimers();
      renderCard();
      await advanceTimers(0);

      status = 400;
      await advanceTimers(POLL_INTERVAL_MS);
      const listCountAfterRejected = getListCount();

      await advanceTimers(POLL_INTERVAL_MS * 3);

      expect(getListCount()).toBe(listCountAfterRejected);
      expect(screen.getByText(STATUS_TEXT.PROCESSING)).toBeInTheDocument();
    });

    it("문서 정리 중 행이 없으면 목록을 주기적으로 다시 받지 않는다", async () => {
      respondItems([COMPLETED_ITEM]);
      const getListCount = countRequests("get", RECORDINGS_URL);
      vi.useFakeTimers();
      renderCard();
      await advanceTimers(0);
      const listCountAfterLoaded = getListCount();

      await advanceTimers(POLL_INTERVAL_MS * 3);

      expect(getListCount()).toBe(listCountAfterLoaded);
    });
  });

  describe("맨 위 줄 (STT-R24 · R28)", () => {
    it("다른 탭에서 녹음 중이면 녹음 이름과 녹음 중 · 시간을 보여 주고, 「녹음 화면으로」는 누를 수 없다", async () => {
      // 목록 행에도 같은 대체 이름이 있어, 맨 위 줄의 이름만 보이게 목록을 비워요
      respondItems([]);
      vi.useFakeTimers();
      renderCard();
      await advanceTimers(0);

      expect(screen.getByText(RECORDING_TITLE)).toBeInTheDocument();
      expect(
        screen.getByText(
          `녹음 중 · ${formatRecordingTime(expectedCurrent.elapsedMillis / MS_PER_SECOND)}`,
        ),
      ).toBeInTheDocument();
      expect(screen.getByText(OTHER_TAB_HINT)).toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: OPEN_RECORDING }),
      ).toBeDisabled();
    });

    it("이 탭에서 녹음 중이면 「녹음 화면으로」를 눌러 녹음 화면으로 간다", async () => {
      await startRecordingInThisTab();
      const { router } = renderCard();

      await act(async () => {
        fireEvent.click(
          await screen.findByRole("button", { name: OPEN_RECORDING }),
        );
      });

      expect(router.state.location.pathname).toBe(RECORDING_PATH);
      expect(screen.queryByText(OTHER_TAB_HINT)).not.toBeInTheDocument();
    });

    it("현재 녹음 조회가 끝난 녹음(문서 정리 중)을 답하면 맨 위 줄을 두지 않는다", async () => {
      mockServer.use(
        http.get(CURRENT_URL, () =>
          HttpResponse.json({
            ...currentRecordingResponse,
            status: "PROCESSING",
          }),
        ),
      );

      renderCard();

      await within(getCard()).findAllByRole("listitem");
      expect(
        screen.queryByRole("button", { name: OPEN_RECORDING }),
      ).not.toBeInTheDocument();
    });

    it("녹음 목록이 없어도 맨 위 줄이 있으면 빈 상태 문구를 보여 주지 않는다", async () => {
      respondItems([]);

      renderCard();

      expect(await screen.findByText(RECORDING_TITLE)).toBeInTheDocument();
      expect(screen.queryByText(EMPTY_TEXT)).not.toBeInTheDocument();
    });
  });
});
