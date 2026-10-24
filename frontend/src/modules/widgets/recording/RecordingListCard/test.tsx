import { GetMeResponseDto } from "@api/dto/auth";
import {
  GetCurrentRecordingResponseDto,
  GetRecordingResponseDto,
} from "@api/dto/recording";
import { AUTH_ME_API_PATH } from "@api/fetch/api/v1/auth/me";
import { DOCUMENT_GENERATION_JOB_RETRY_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]/documentGenerationJobs/[jobId]/retry";
import { RECORDING_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]";
import { RECORDING_TRANSCRIPTION_RETRY_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/transcription/retry";
import { CURRENT_RECORDING_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/current";
import { meResponse } from "@api/mock/responses/auth";
import { documentGenerationJobRetryResponse } from "@api/mock/responses/documentGenerationJob";
import {
  currentRecordingResponse,
  recordingDetailsResponse,
} from "@api/mock/responses/recording";
import { mockServer } from "@api/mock/server";
import { resetRecordingMockState } from "@api/mock/state/recording";
import { ThemeProvider } from "@emotion/react";
import { ToastProvider } from "@provider/context/toastContext";
import { theme } from "@provider/themeProvider";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import { useRecordingStore } from "@store/recordingStore";
import {
  focusManager,
  QueryClient,
  QueryClientProvider,
} from "@tanstack/react-query";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { formatRecordingTime } from "@utils/formatRecordingTime";
import { delay, http, HttpResponse } from "msw";
import { createMemoryRouter, Outlet, RouterProvider } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";

import RecordingListCard from ".";

const WORKSPACE_ID = 1;
const HOME_PATH = getRouterPath({
  routeKey: "WORKSPACE_HOME",
  params: { workspaceId: String(WORKSPACE_ID) },
});
const RECORDING_PATH = getRouterPath({
  routeKey: "RECORDING",
  params: { workspaceId: String(WORKSPACE_ID) },
});
const CURRENT_URL = `*${CURRENT_RECORDING_API_PATH(WORKSPACE_ID)}`;

const expectedRecording = new GetCurrentRecordingResponseDto(
  currentRecordingResponse,
);
const RECORDING_DOCUMENTS_PATH = getRouterPath({
  routeKey: "RECORDING_DOCUMENTS",
  params: {
    workspaceId: String(WORKSPACE_ID),
    recordingId: String(expectedRecording.recordingId),
  },
});
const RECORDING_TITLE = `${new GetMeResponseDto(meResponse).nickname} 님의 녹음`;
const EMPTY_TEXT = "진행 중인 녹음이 없어요";
const LOAD_FAILED_TEXT = "녹음 상태를 불러오지 못했어요. 다시 시도해 주세요.";
const OPEN_RECORDING = "녹음 화면으로";
const ORGANIZING_TEXT = "문서 정리 중";
const ORGANIZING_HINT = "끝나면 확인이 필요한 문서에 올라와요";
const OPEN_RECORDING_DOCUMENTS = "보기";
const MS_PER_SECOND = 1000;
// 문서 정리 중인 녹음을 다시 조회하는 간격
const ORGANIZING_POLL_INTERVAL_MS = 3000;

const RETRY = "다시 시도";
const RETRY_FAILED_TOAST =
  "다시 시도하지 못했어요. 잠시 후 다시 시도해 주세요.";
const TRANSCRIPTION_FAILED_TEXT = "녹음을 글로 옮기지 못했어요";
const TRANSCRIPTION_FAILED_HINT = "녹음은 30일 동안 보관해요";
const DOCUMENT_FAILED_TEXT = "문서를 만들지 못했어요";
const DOCUMENT_FAILED_HINT = "녹음과 원문은 7일 동안 보관해요";
type FailedStatus = "TRANSCRIPTION_FAILED" | "DOCUMENT_FAILED";

// 녹음 상세 mock에서 전사가 실패한 녹음. 전사 다시 시도 mock은 이 녹음만 접수해요
const TRANSCRIPTION_FAILED_RECORDING = recordingDetailsResponse.find(
  ({ detail }) => detail.transcriptionStatus === "FAILED",
)!;
// 녹음 상세 mock에서 문서 만들기가 실패한 녹음. 실패 작업이 하나이고 다시 시도 mock이 접수하는 작업이에요
const DOCUMENT_FAILED_RECORDING = recordingDetailsResponse.find(
  ({ detail }) =>
    detail.documentGeneration?.status === "FAILED" &&
    detail.documentGeneration.failedJobIds[0] ===
      documentGenerationJobRetryResponse.jobId,
)!;
// 실패한 작업이 둘 이상인 녹음. 「모두」 다시 접수하는지와 일부만 접수된 경우는 작업이 둘 이상이어야 드러나요
const MULTI_JOB_FAILED_RECORDING = recordingDetailsResponse.find(
  ({ detail }) => (detail.documentGeneration?.failedJobIds.length ?? 0) > 1,
)!;

/** 녹음 상세 mock의 녹음을 앱이 받는 모양(응답 DTO)으로 바꿔 실패한 작업 ID를 꺼내요 */
const expectedFailedJobIds = ({
  detail,
}: (typeof recordingDetailsResponse)[number]) =>
  new GetRecordingResponseDto(detail).documentGeneration!.failedJobIds;

interface FailedRecordingResponseParams {
  status: FailedStatus;
  /** 실패한 녹음의 ID. 생략하면 그 실패 상태의 기본 녹음이에요 */
  recordingId?: number;
  isRetryable?: boolean;
  retryableJobIds?: number[];
}

/** 현재 녹음 조회가 돌려줄, 정리에 실패한 녹음의 서버 응답 */
const failedRecordingResponse = ({
  status,
  recordingId = status === "TRANSCRIPTION_FAILED"
    ? TRANSCRIPTION_FAILED_RECORDING.recordingId
    : DOCUMENT_FAILED_RECORDING.recordingId,
  isRetryable = true,
  retryableJobIds = status === "DOCUMENT_FAILED" && isRetryable
    ? expectedFailedJobIds(
        recordingDetailsResponse.find(
          (recording) => recording.recordingId === recordingId,
        )!,
      )
    : [],
}: FailedRecordingResponseParams) => ({
  ...currentRecordingResponse,
  recordingId,
  status,
  retryable: isRetryable,
  retryableJobIds,
});

// 요청 주소의 ID는 컴포넌트가 받는 모양(응답 DTO)에서 가져와요
const expectedTranscriptionFailed = new GetCurrentRecordingResponseDto(
  failedRecordingResponse({ status: "TRANSCRIPTION_FAILED" }),
);
const expectedDocumentFailed = new GetCurrentRecordingResponseDto(
  failedRecordingResponse({ status: "DOCUMENT_FAILED" }),
);
const [EXPECTED_FAILED_JOB_ID] = expectedFailedJobIds(
  DOCUMENT_FAILED_RECORDING,
);
const MULTI_FAILED_JOB_IDS = expectedFailedJobIds(MULTI_JOB_FAILED_RECORDING);

const TRANSCRIPTION_RETRY_URL = `*${RECORDING_TRANSCRIPTION_RETRY_API_PATH(WORKSPACE_ID, expectedTranscriptionFailed.recordingId)}`;
const jobRetryUrl = (jobId: number) =>
  `*${DOCUMENT_GENERATION_JOB_RETRY_API_PATH(WORKSPACE_ID, jobId)}`;
const DOCUMENT_JOB_RETRY_URL = jobRetryUrl(EXPECTED_FAILED_JOB_ID);

/** 응답의 누적 시간에 초를 더한 시간 표시예요 */
const elapsedTimeAfter = (seconds: number) =>
  formatRecordingTime(
    expectedRecording.elapsedMillis / MS_PER_SECOND + seconds,
  );

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
          { path: PATH_ROUTE.WORKSPACE_HOME, element: <RecordingListCard /> },
          { path: PATH_ROUTE.RECORDING, element: null },
          { path: PATH_ROUTE.RECORDING_DOCUMENTS, element: null },
          { path: PATH_ROUTE.LOGIN, element: null },
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

/** 현재 녹음 조회가 이 응답으로 답하게 해요 */
const respondCurrentRecording = (
  response: () => Response | Promise<Response>,
) => {
  mockServer.use(http.get(CURRENT_URL, response));
};

const respondNoRecording = () =>
  respondCurrentRecording(() => new HttpResponse(null, { status: 204 }));

const respondStatus = (status: "RECORDING" | "PAUSED" | "PROCESSING") =>
  respondCurrentRecording(() =>
    HttpResponse.json({ ...currentRecordingResponse, status }),
  );

/** 현재 녹음 조회가 실패한 녹음으로 답하게 해요. 녹음 ID는 녹음 상세 mock의 같은 실패 녹음이에요 */
const respondFailedRecording = (params: FailedRecordingResponseParams) =>
  respondCurrentRecording(() =>
    HttpResponse.json(failedRecordingResponse(params)),
  );

/**
 * 현재 녹음 조회가 처음엔 실패한 녹음으로, 다시 시도가 접수된 뒤에는 정리 중으로 답하게 해요.
 * 서버는 다시 접수한 녹음을 정리 중으로 돌려주지만, 현재 녹음 mock은 다시 시도 기록을 보지 않아 순서로 흉내 내요
 */
const respondFailedThenOrganizing = (params: FailedRecordingResponseParams) => {
  let requestCount = 0;
  respondCurrentRecording(() => {
    requestCount += 1;
    const response = failedRecordingResponse(params);

    return HttpResponse.json(
      requestCount === 1 ? response : { ...response, status: "PROCESSING" },
    );
  });

  return () => requestCount;
};

/** 문서 생성 작업의 다시 시도가 이 상태 코드로 실패하게 해요 */
const respondJobRetryError = (jobId: number, status: 409 | 500) =>
  mockServer.use(
    http.post(jobRetryUrl(jobId), () =>
      HttpResponse.json(
        status === 409
          ? { code: "RETRY_NOT_ALLOWED", message: "재시도할 수 없습니다" }
          : { code: "INTERNAL_SERVER_ERROR", message: "서버 오류" },
        { status },
      ),
    ),
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
      recordingId: expectedRecording.recordingId,
    });
  });
};

describe("RecordingListCard", () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    // 전역 저장소라 테스트끼리 녹음이 새지 않도록 처음 상태로 되돌려요
    useRecordingStore.getState().discardRecording();
    // 다시 시도 기록이 mock 상태에 남아 다음 테스트의 거절 여부가 달라지지 않게 지워요
    resetRecordingMockState();
    mockServer.events.removeAllListeners();
  });

  it("현재 녹음을 조회하는 동안에는 카드가 불러오는 중으로 표시된다", () => {
    respondCurrentRecording(async () => {
      await delay("infinite");
      return new HttpResponse(null, { status: 204 });
    });

    renderCard();

    expect(
      screen.getByRole("region", { name: "진행 중인 녹음" }),
    ).toHaveAttribute("aria-busy", "true");
  });

  it("내 닉네임을 조회하는 동안에는 녹음이 있어도 녹음 칸 대신 불러오는 중으로 표시된다", async () => {
    mockServer.use(
      http.get(`*${AUTH_ME_API_PATH}`, async () => {
        await delay("infinite");
        return HttpResponse.json(meResponse);
      }),
    );
    vi.useFakeTimers();
    renderCard();
    await advanceTimers(0); // 현재 녹음 조회 응답을 흘려보내요

    expect(
      screen.getByRole("region", { name: "진행 중인 녹음" }),
    ).toHaveAttribute("aria-busy", "true");
    expect(
      screen.queryByText(`녹음 중 · ${elapsedTimeAfter(0)}`),
    ).not.toBeInTheDocument();
  });

  it("진행 중인 녹음이 없으면(204) 빈 상태 문구를 보여 주고 버튼은 두지 않는다", async () => {
    respondNoRecording();

    renderCard();

    expect(await screen.findByText(EMPTY_TEXT)).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: OPEN_RECORDING }),
    ).not.toBeInTheDocument();
  });

  it("녹음 중이면 녹음 중 상태·경과 시간과 내 닉네임의 녹음 이름을 보여 준다", async () => {
    renderCard();

    expect(
      await screen.findByText(`녹음 중 · ${elapsedTimeAfter(0)}`),
    ).toBeInTheDocument();
    expect(await screen.findByText(RECORDING_TITLE)).toBeInTheDocument();
  });

  it("녹음 중이면 응답을 받은 뒤 흐른 시간만큼 경과 시간이 매초 늘어난다", async () => {
    vi.useFakeTimers();
    renderCard();
    await advanceTimers(0); // 조회 응답을 흘려보내요

    expect(
      screen.getByText(`녹음 중 · ${elapsedTimeAfter(0)}`),
    ).toBeInTheDocument();

    await advanceTimers(3 * MS_PER_SECOND);

    expect(
      screen.getByText(`녹음 중 · ${elapsedTimeAfter(3)}`),
    ).toBeInTheDocument();
  });

  it("일시정지면 일시정지 상태와 멈춘 경과 시간을 보여 준다", async () => {
    respondStatus("PAUSED");
    vi.useFakeTimers();
    renderCard();
    await advanceTimers(0); // 조회 응답을 흘려보내요

    await advanceTimers(3 * MS_PER_SECOND);

    expect(
      screen.getByText(`일시정지 · ${elapsedTimeAfter(0)}`),
    ).toBeInTheDocument();
  });

  it("문서를 정리하는 중이면 녹음 시간 없이 문서 정리 중과 녹음 이름, 안내, 「보기」를 보여 준다", async () => {
    respondStatus("PROCESSING");

    renderCard();

    // 문구가 정확히 같아야 해서 「문서 정리 중 · 12:00」처럼 시간이 붙으면 찾지 못해요
    expect(await screen.findByText(ORGANIZING_TEXT)).toBeInTheDocument();
    expect(screen.getByText(RECORDING_TITLE)).toBeInTheDocument();
    expect(screen.getByText(ORGANIZING_HINT)).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: OPEN_RECORDING_DOCUMENTS }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: OPEN_RECORDING }),
    ).not.toBeInTheDocument();
  });

  it("문서 정리 중에 「보기」를 누르면 그 녹음의 문서 정리 화면으로 간다", async () => {
    respondStatus("PROCESSING");
    const { router } = renderCard();

    await act(async () => {
      fireEvent.click(
        await screen.findByRole("button", { name: OPEN_RECORDING_DOCUMENTS }),
      );
    });

    expect(router.state.location.pathname).toBe(RECORDING_DOCUMENTS_PATH);
  });

  it("문서 정리 중이면 현재 녹음을 다시 조회하다가, 정리가 끝나 표시할 녹음이 없어지면(204) 빈 상태로 돌아가고 다시 조회를 멈춘다", async () => {
    let requestCount = 0;
    respondCurrentRecording(() => {
      requestCount += 1;
      if (requestCount === 1) {
        return HttpResponse.json({
          ...currentRecordingResponse,
          status: "PROCESSING",
        });
      }
      return new HttpResponse(null, { status: 204 });
    });
    vi.useFakeTimers();
    renderCard();
    await advanceTimers(0); // 조회 응답을 흘려보내요

    expect(screen.getByText(ORGANIZING_TEXT)).toBeInTheDocument();

    // 다시 조회한 응답이 도착할 때까지 간격보다 조금 더 진행해요
    await advanceTimers(ORGANIZING_POLL_INTERVAL_MS + 100);

    expect(screen.getByText(EMPTY_TEXT)).toBeInTheDocument();

    await advanceTimers(ORGANIZING_POLL_INTERVAL_MS * 3);

    expect(requestCount).toBe(2);
  });

  it("문서 정리 중에 다시 조회가 다시 물어도 결과가 같은 오류(4xx)로 실패하면 다시 조회를 멈추고, 받은 정리 중 상태를 그대로 보여 준다", async () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    let requestCount = 0;
    respondCurrentRecording(() => {
      requestCount += 1;
      if (requestCount === 1) {
        return HttpResponse.json({
          ...currentRecordingResponse,
          status: "PROCESSING",
        });
      }
      return HttpResponse.json(null, { status: 403 });
    });
    vi.useFakeTimers();
    renderCard();
    await advanceTimers(0); // 조회 응답을 흘려보내요

    // 다시 조회한 응답이 도착할 때까지 간격보다 조금 더 진행해요
    await advanceTimers(ORGANIZING_POLL_INTERVAL_MS + 100);
    await advanceTimers(ORGANIZING_POLL_INTERVAL_MS * 3);

    expect(requestCount).toBe(2);
    expect(screen.getByText(ORGANIZING_TEXT)).toBeInTheDocument();
  });

  it("문서 정리 중에 다시 조회가 서버 오류(5xx)로 실패하면 잠깐의 문제일 수 있어 계속 다시 조회한다", async () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    let requestCount = 0;
    respondCurrentRecording(() => {
      requestCount += 1;
      if (requestCount === 1) {
        return HttpResponse.json({
          ...currentRecordingResponse,
          status: "PROCESSING",
        });
      }
      return HttpResponse.json(null, { status: 500 });
    });
    vi.useFakeTimers();
    renderCard();
    await advanceTimers(0); // 조회 응답을 흘려보내요

    await advanceTimers(ORGANIZING_POLL_INTERVAL_MS * 3 + 100);

    expect(requestCount).toBe(4);
  });

  it("녹음 중이면 현재 녹음을 주기적으로 다시 조회하지 않는다", async () => {
    let requestCount = 0;
    respondCurrentRecording(() => {
      requestCount += 1;
      return HttpResponse.json(currentRecordingResponse);
    });
    vi.useFakeTimers();
    renderCard();
    await advanceTimers(0); // 조회 응답을 흘려보내요

    await advanceTimers(ORGANIZING_POLL_INTERVAL_MS * 3);

    expect(requestCount).toBe(1);
  });

  it("이 탭에서 녹음 중이면 앞 녹음의 문서를 정리하는 중이어도 이 탭의 녹음을 보여 준다", async () => {
    respondStatus("PROCESSING");
    await startRecordingInThisTab();
    vi.useFakeTimers();

    renderCard();
    // 이 탭의 녹음 칸은 현재 녹음 조회를 기다리지 않고 보여요. 정리 중 응답까지 흘려보낸 뒤에 확인해요
    await advanceTimers(0);

    expect(
      screen.getByRole("button", { name: OPEN_RECORDING }),
    ).toBeInTheDocument();
    expect(screen.queryByText(ORGANIZING_TEXT)).not.toBeInTheDocument();
  });

  it("조회에 실패하면 빈 상태 대신 카드 안에 실패 안내와 다시 시도를 보여 주고, 오류는 콘솔에도 남긴다 (ERR-09)", async () => {
    const consoleError = vi
      .spyOn(console, "error")
      .mockImplementation(() => {});
    respondCurrentRecording(() => HttpResponse.json(null, { status: 500 }));

    renderCard();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      LOAD_FAILED_TEXT,
    );
    expect(
      screen.getByRole("heading", { name: "진행 중인 녹음" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "다시 시도" }),
    ).toBeInTheDocument();
    expect(screen.queryByText(EMPTY_TEXT)).not.toBeInTheDocument();
    expect(consoleError).toHaveBeenCalled();
  });

  it("다시 시도를 누르면 현재 녹음만 다시 조회해 보여 준다", async () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    let attempts = 0;
    respondCurrentRecording(() => {
      attempts += 1;
      if (attempts === 1) return HttpResponse.json(null, { status: 500 });

      return HttpResponse.json(currentRecordingResponse);
    });

    renderCard();
    fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));

    expect(await screen.findByText(RECORDING_TITLE)).toBeInTheDocument();
    expect(screen.queryByText(LOAD_FAILED_TEXT)).not.toBeInTheDocument();
    expect(attempts).toBe(2);
  });

  it("한 번 받은 뒤 다시 조회만 실패하면 실패 안내 대신 받은 녹음을 그대로 보여 준다", async () => {
    const consoleError = vi
      .spyOn(console, "error")
      .mockImplementation(() => {});
    renderCard();
    expect(await screen.findByText(RECORDING_TITLE)).toBeInTheDocument();

    respondCurrentRecording(() => HttpResponse.json(null, { status: 500 }));
    try {
      act(() => {
        focusManager.setFocused(false);
        focusManager.setFocused(true);
      });
      // 실패를 그린 뒤에 콘솔에 남기므로, 남겼으면 실패한 상태가 화면에 반영된 거예요
      await waitFor(() => expect(consoleError).toHaveBeenCalled());

      expect(screen.getByText(RECORDING_TITLE)).toBeInTheDocument();
      expect(screen.queryByText(LOAD_FAILED_TEXT)).not.toBeInTheDocument();
    } finally {
      focusManager.setFocused(undefined);
    }
  });

  it("이 탭에서 시작한 녹음이면 조회에 실패해도 실패 안내 대신 이 탭의 녹음을 보여 준다", async () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    respondCurrentRecording(() => HttpResponse.json(null, { status: 500 }));
    await startRecordingInThisTab();

    renderCard();

    expect(
      await screen.findByRole("button", { name: OPEN_RECORDING }),
    ).toBeInTheDocument();
    expect(screen.queryByText(LOAD_FAILED_TEXT)).not.toBeInTheDocument();
  });

  it("다른 탭에서 시작한 녹음이면 「녹음 화면으로」 버튼을 숨긴다", async () => {
    renderCard();

    expect(await screen.findByText(RECORDING_TITLE)).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: OPEN_RECORDING }),
    ).not.toBeInTheDocument();
  });

  it("이 탭에서 시작한 녹음이면 「녹음 화면으로」를 눌러 녹음 화면으로 간다", async () => {
    await startRecordingInThisTab();
    const { router } = renderCard();

    await act(async () => {
      fireEvent.click(
        await screen.findByRole("button", { name: OPEN_RECORDING }),
      );
    });

    expect(router.state.location.pathname).toBe(RECORDING_PATH);
  });

  it("이 탭에서 시작한 녹음이면 서버 응답이 아니라 이 탭의 상태와 시간을 보여 준다", async () => {
    // 서버가 아직 녹음을 모르는 것처럼 답해도 이 탭의 녹음을 보여 줘요
    respondNoRecording();
    await startRecordingInThisTab();
    renderCard();

    expect(
      await screen.findByText(`녹음 중 · ${formatRecordingTime(0)}`),
    ).toBeInTheDocument();

    await act(async () => {
      useRecordingStore.getState().pauseRecording();
    });

    expect(
      screen.getByText(`일시정지 · ${formatRecordingTime(0)}`),
    ).toBeInTheDocument();
  });

  it("창으로 돌아오면 현재 녹음을 다시 조회한다", async () => {
    renderCard();
    expect(await screen.findByText(RECORDING_TITLE)).toBeInTheDocument();

    respondNoRecording();
    act(() => {
      focusManager.setFocused(false);
      focusManager.setFocused(true);
    });

    await waitFor(() =>
      expect(screen.getByText(EMPTY_TEXT)).toBeInTheDocument(),
    );
    focusManager.setFocused(undefined);
  });

  describe("정리에 실패한 녹음 (STT-R24 · STT-R22)", () => {
    it("전사에 실패했으면 시간 없이 녹음을 글로 옮기지 못했다는 상태와 보관 안내, 녹음 이름, 「다시 시도」를 보여 준다", async () => {
      respondFailedRecording({ status: "TRANSCRIPTION_FAILED" });

      renderCard();

      // 문구가 정확히 같아야 해서 시간이 붙으면 찾지 못해요
      expect(
        await screen.findByText(TRANSCRIPTION_FAILED_TEXT),
      ).toBeInTheDocument();
      expect(screen.getByText(TRANSCRIPTION_FAILED_HINT)).toBeInTheDocument();
      expect(screen.getByText(RECORDING_TITLE)).toBeInTheDocument();
      expect(screen.getByRole("button", { name: RETRY })).toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: OPEN_RECORDING_DOCUMENTS }),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: OPEN_RECORDING }),
      ).not.toBeInTheDocument();
    });

    it("문서 만들기에 실패했으면 문서를 만들지 못했다는 상태와 보관 안내, 「다시 시도」를 보여 준다", async () => {
      respondFailedRecording({ status: "DOCUMENT_FAILED" });

      renderCard();

      expect(await screen.findByText(DOCUMENT_FAILED_TEXT)).toBeInTheDocument();
      expect(screen.getByText(DOCUMENT_FAILED_HINT)).toBeInTheDocument();
      expect(screen.getByText(RECORDING_TITLE)).toBeInTheDocument();
      expect(screen.getByRole("button", { name: RETRY })).toBeInTheDocument();
    });

    it.each(["TRANSCRIPTION_FAILED", "DOCUMENT_FAILED"] as const)(
      "다시 시도할 수 없는 실패(%s · retryable=false)는 실패 제목을 유지하고 버튼과 보관 안내를 숨긴다",
      async (status) => {
        respondFailedRecording({ status, isRetryable: false });

        renderCard();

        expect(
          await screen.findByText(
            status === "TRANSCRIPTION_FAILED"
              ? TRANSCRIPTION_FAILED_TEXT
              : DOCUMENT_FAILED_TEXT,
          ),
        ).toBeInTheDocument();
        expect(screen.queryByText(EMPTY_TEXT)).not.toBeInTheDocument();
        expect(
          screen.queryByText(TRANSCRIPTION_FAILED_HINT),
        ).not.toBeInTheDocument();
        expect(
          screen.queryByText(DOCUMENT_FAILED_HINT),
        ).not.toBeInTheDocument();
        expect(
          screen.queryByRole("button", { name: RETRY }),
        ).not.toBeInTheDocument();
      },
    );

    it("실패한 녹음은 현재 녹음을 주기적으로 다시 조회하지 않는다", async () => {
      let requestCount = 0;
      respondCurrentRecording(() => {
        requestCount += 1;

        return HttpResponse.json(
          failedRecordingResponse({ status: "TRANSCRIPTION_FAILED" }),
        );
      });
      vi.useFakeTimers();
      renderCard();
      await advanceTimers(0); // 조회 응답을 흘려보내요

      await advanceTimers(ORGANIZING_POLL_INTERVAL_MS * 3);

      expect(requestCount).toBe(1);
    });

    it("이 탭에서 녹음 중이면 앞 녹음이 실패했어도 이 탭의 녹음을 보여 준다", async () => {
      respondFailedRecording({ status: "TRANSCRIPTION_FAILED" });
      await startRecordingInThisTab();

      renderCard();

      expect(
        await screen.findByRole("button", { name: OPEN_RECORDING }),
      ).toBeInTheDocument();
      expect(
        screen.queryByText(TRANSCRIPTION_FAILED_TEXT),
      ).not.toBeInTheDocument();
    });

    it("전사 실패에서 「다시 시도」를 누르면 그 녹음의 전사를 다시 접수하고, 접수되면 현재 녹음을 다시 조회해 문서 정리 중으로 돌아간다", async () => {
      respondFailedThenOrganizing({ status: "TRANSCRIPTION_FAILED" });
      const getRetryCount = countRequests("post", TRANSCRIPTION_RETRY_URL);
      renderCard();

      fireEvent.click(await screen.findByRole("button", { name: RETRY }));

      expect(await screen.findByText(ORGANIZING_TEXT)).toBeInTheDocument();
      expect(getRetryCount()).toBe(1);
      expect(
        screen.getByRole("button", { name: OPEN_RECORDING_DOCUMENTS }),
      ).toBeInTheDocument();
    });

    it("문서 만들기 실패에서 「다시 시도」를 누르면 현재 응답의 가능한 작업을 접수하고 문서 정리 중으로 돌아간다", async () => {
      respondFailedThenOrganizing({
        status: "DOCUMENT_FAILED",
        recordingId: MULTI_JOB_FAILED_RECORDING.recordingId,
      });
      const getRetryCounts = MULTI_FAILED_JOB_IDS.map((jobId) =>
        countRequests("post", jobRetryUrl(jobId)),
      );
      renderCard();

      fireEvent.click(await screen.findByRole("button", { name: RETRY }));

      expect(await screen.findByText(ORGANIZING_TEXT)).toBeInTheDocument();
      expect(getRetryCounts.map((getRetryCount) => getRetryCount())).toEqual(
        MULTI_FAILED_JOB_IDS.map(() => 1),
      );
    });

    it("현재 응답의 재시도 가능한 작업만 접수하고 녹음 상세나 나머지 실패 작업을 요청하지 않는다", async () => {
      const [retryableJobId, exhaustedJobId] = MULTI_FAILED_JOB_IDS;
      respondFailedThenOrganizing({
        status: "DOCUMENT_FAILED",
        recordingId: MULTI_JOB_FAILED_RECORDING.recordingId,
        retryableJobIds: [retryableJobId],
      });
      const getDetailCount = countRequests(
        "get",
        `*${RECORDING_API_PATH(WORKSPACE_ID, MULTI_JOB_FAILED_RECORDING.recordingId)}`,
      );
      const getAcceptedCount = countRequests(
        "post",
        jobRetryUrl(retryableJobId),
      );
      const getExhaustedCount = countRequests(
        "post",
        jobRetryUrl(exhaustedJobId),
      );
      renderCard();

      fireEvent.click(await screen.findByRole("button", { name: RETRY }));

      expect(await screen.findByText(ORGANIZING_TEXT)).toBeInTheDocument();
      expect(getAcceptedCount()).toBe(1);
      expect(getExhaustedCount()).toBe(0);
      expect(getDetailCount()).toBe(0);
    });

    it("문서 만들기 다시 시도에서 일부 작업만 접수돼도 오류 토스트 없이 문서 정리 중으로 돌아간다", async () => {
      respondFailedThenOrganizing({
        status: "DOCUMENT_FAILED",
        recordingId: MULTI_JOB_FAILED_RECORDING.recordingId,
      });
      const [, serverErrorJobId] = MULTI_FAILED_JOB_IDS;
      respondJobRetryError(serverErrorJobId, 500);
      renderCard();

      fireEvent.click(await screen.findByRole("button", { name: RETRY }));

      expect(await screen.findByText(ORGANIZING_TEXT)).toBeInTheDocument();
      expect(screen.queryByText(RETRY_FAILED_TOAST)).not.toBeInTheDocument();
    });

    it("문서 만들기 다시 시도가 하나도 접수되지 않고 거절이 아닌 실패(5xx)가 섞여 있으면 오류 토스트를 띄우고 버튼은 그대로 둔다", async () => {
      respondFailedRecording({
        status: "DOCUMENT_FAILED",
        recordingId: MULTI_JOB_FAILED_RECORDING.recordingId,
      });
      const [rejectedJobId, serverErrorJobId] = MULTI_FAILED_JOB_IDS;
      respondJobRetryError(rejectedJobId, 409);
      respondJobRetryError(serverErrorJobId, 500);
      renderCard();

      fireEvent.click(await screen.findByRole("button", { name: RETRY }));

      expect(await screen.findByText(RETRY_FAILED_TOAST)).toBeInTheDocument();
      expect(screen.getByRole("button", { name: RETRY })).toBeEnabled();
    });

    it("다시 시도의 응답을 기다리는 동안에는 버튼을 다시 누를 수 없다", async () => {
      respondFailedThenOrganizing({ status: "TRANSCRIPTION_FAILED" });
      const getRetryCount = countRequests("post", TRANSCRIPTION_RETRY_URL);
      mockServer.use(
        http.post(TRANSCRIPTION_RETRY_URL, async () => {
          await delay("infinite");
          return new HttpResponse(null, { status: 202 });
        }),
      );
      renderCard();
      const retryButton = await screen.findByRole("button", { name: RETRY });

      fireEvent.click(retryButton);
      await waitFor(() =>
        expect(retryButton).toHaveAttribute("aria-busy", "true"),
      );
      fireEvent.click(retryButton);

      expect(retryButton).toBeDisabled();
      expect(getRetryCount()).toBe(1);
    });

    it.each([
      ["409", "TRANSCRIPTION_RETRY_NOT_ALLOWED", 409],
      ["404", "RECORDING_NOT_FOUND", 404],
    ])(
      "다시 시도가 거절되면(%s) 오류 토스트 없이 현재 녹음을 다시 조회한다",
      async (_, code, status) => {
        vi.spyOn(console, "error").mockImplementation(() => {});
        respondFailedRecording({ status: "TRANSCRIPTION_FAILED" });
        const getCurrentCount = countRequests("get", CURRENT_URL);
        mockServer.use(
          http.post(TRANSCRIPTION_RETRY_URL, () =>
            HttpResponse.json(
              { code, message: "전사를 다시 시도할 수 없습니다" },
              { status },
            ),
          ),
        );
        renderCard();
        const retryButton = await screen.findByRole("button", { name: RETRY });

        fireEvent.click(retryButton);

        await waitFor(() => expect(getCurrentCount()).toBe(2));
        await waitFor(() => expect(retryButton).toBeEnabled());
        expect(screen.queryByText(RETRY_FAILED_TOAST)).not.toBeInTheDocument();
        expect(screen.getByText(TRANSCRIPTION_FAILED_TEXT)).toBeInTheDocument();
      },
    );

    it("다시 시도가 서버 오류로 접수되지 않으면 오류 토스트를 띄우고 버튼은 그대로 둔다", async () => {
      vi.spyOn(console, "error").mockImplementation(() => {});
      respondFailedRecording({ status: "TRANSCRIPTION_FAILED" });
      mockServer.use(
        http.post(TRANSCRIPTION_RETRY_URL, () =>
          HttpResponse.json(
            { code: "INTERNAL_SERVER_ERROR", message: "서버 오류" },
            { status: 500 },
          ),
        ),
      );
      renderCard();

      fireEvent.click(await screen.findByRole("button", { name: RETRY }));

      expect(await screen.findByText(RETRY_FAILED_TOAST)).toBeInTheDocument();
      expect(screen.getByRole("button", { name: RETRY })).toBeEnabled();
    });

    it("문서 만들기 다시 시도가 모두 거절되면 오류 토스트 없이 현재 녹음을 다시 조회한다", async () => {
      respondFailedRecording({
        status: "DOCUMENT_FAILED",
        recordingId: MULTI_JOB_FAILED_RECORDING.recordingId,
      });
      const getCurrentCount = countRequests("get", CURRENT_URL);
      MULTI_FAILED_JOB_IDS.forEach((jobId) => respondJobRetryError(jobId, 409));
      renderCard();

      fireEvent.click(await screen.findByRole("button", { name: RETRY }));

      await waitFor(() => expect(getCurrentCount()).toBe(2));
      expect(screen.queryByText(RETRY_FAILED_TOAST)).not.toBeInTheDocument();
    });

    it("재시도 가능한 작업 배열이 비면 POST 없이 현재 상태만 다시 확인한다", async () => {
      respondFailedRecording({
        status: "DOCUMENT_FAILED",
        retryableJobIds: [],
      });
      const getCurrentCount = countRequests("get", CURRENT_URL);
      const getRetryCount = countRequests("post", DOCUMENT_JOB_RETRY_URL);
      renderCard();

      fireEvent.click(await screen.findByRole("button", { name: RETRY }));

      await waitFor(() => expect(getCurrentCount()).toBe(2));
      expect(getRetryCount()).toBe(0);
    });

    it("재시도 응답이 유실돼도 상태를 다시 확인하고 같은 POST를 자동 반복하지 않는다", async () => {
      respondFailedThenOrganizing({ status: "TRANSCRIPTION_FAILED" });
      const getRetryCount = countRequests("post", TRANSCRIPTION_RETRY_URL);
      mockServer.use(
        http.post(
          TRANSCRIPTION_RETRY_URL,
          () => new HttpResponse(null, { status: 500 }),
        ),
      );
      renderCard();

      fireEvent.click(await screen.findByRole("button", { name: RETRY }));

      expect(await screen.findByText(ORGANIZING_TEXT)).toBeInTheDocument();
      expect(getRetryCount()).toBe(1);
    });

    it("문서 작업 접수와 상태 확인이 끝날 때까지 연속 클릭을 차단한다", async () => {
      respondFailedThenOrganizing({ status: "DOCUMENT_FAILED" });
      const getRetryCount = countRequests("post", DOCUMENT_JOB_RETRY_URL);
      mockServer.use(
        http.post(DOCUMENT_JOB_RETRY_URL, async () => {
          await delay("infinite");
          return new HttpResponse(null, { status: 202 });
        }),
      );
      renderCard();
      const button = await screen.findByRole("button", { name: RETRY });

      fireEvent.click(button);
      fireEvent.click(button);

      await waitFor(() => expect(getRetryCount()).toBe(1));
      expect(button).toBeDisabled();
    });

    it("처리가 완료되면 서버가 다시 선택한 이전 실패를 표시한다", async () => {
      let count = 0;
      respondCurrentRecording(() =>
        HttpResponse.json(
          ++count === 1
            ? { ...currentRecordingResponse, status: "PROCESSING" }
            : failedRecordingResponse({
                status: "TRANSCRIPTION_FAILED",
                isRetryable: false,
              }),
        ),
      );
      vi.useFakeTimers();
      renderCard();
      await advanceTimers(0);

      await advanceTimers(ORGANIZING_POLL_INTERVAL_MS + 100);

      expect(screen.getByText(TRANSCRIPTION_FAILED_TEXT)).toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: RETRY }),
      ).not.toBeInTheDocument();
      expect(screen.queryByText(EMPTY_TEXT)).not.toBeInTheDocument();
    });

    it("다시 시도에서 로그인이 풀렸으면(401) 오류 토스트 없이 로그인 화면으로 보낸다", async () => {
      vi.spyOn(console, "error").mockImplementation(() => {});
      respondFailedRecording({ status: "TRANSCRIPTION_FAILED" });
      mockServer.use(
        http.post(TRANSCRIPTION_RETRY_URL, () =>
          HttpResponse.json(
            { code: "UNAUTHENTICATED", message: "로그인이 필요합니다" },
            { status: 401 },
          ),
        ),
      );
      const { router } = renderCard();

      fireEvent.click(await screen.findByRole("button", { name: RETRY }));

      await waitFor(() =>
        expect(router.state.location.pathname).toBe(PATH_ROUTE.LOGIN),
      );
      expect(screen.queryByText(RETRY_FAILED_TOAST)).not.toBeInTheDocument();
    });
  });
});
