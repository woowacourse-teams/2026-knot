import { GetRecordingResponseDto } from "@api/dto/recording";
import { documentGenerationJobRetryResponse } from "@api/mock/responses/documentGenerationJob";
import {
  recordingAudioSaveResponse,
  recordingDetailsResponse,
} from "@api/mock/responses/recording";
import type {
  RecordingAudioSaveResponse,
  RecordingDetailResponse,
  RecordingDocumentGenerationResponse,
} from "@api/mock/types/recording";
import { mockServer } from "@api/mock/server";
import { TOAST_MESSAGES } from "@constants/toastMessages";
import { ThemeProvider } from "@emotion/react";
import { ToastProvider } from "@provider/context/toastContext";
import { setupSentryEvents } from "@provider/sentry/mock";
import { theme } from "@provider/themeProvider";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
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
import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { http, HttpResponse } from "msw";
import { createMemoryRouter, Outlet, RouterProvider } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import RecordingDocuments from ".";

vi.mock("@utils/captureAnalyticsEvent", () => ({
  captureAnalyticsEvent: vi.fn(),
}));

const WORKSPACE_ID = 1;
// 상태가 서로 다른 mock 녹음: 정리 중 · 내용 없음 · 문서 만들기 실패 · 전사 실패 · 정리 완료.
// 서버 응답에는 녹음 ID가 없어서, 녹음 ID(`recordingId`)와 응답(`detail`)이 한 쌍으로 있어요
const [
  organizingRecording,
  noContentRecording,
  failedRecording,
  transcriptionFailedRecording,
  completedRecording,
] = recordingDetailsResponse;
// 경로 파라미터 자리에 무엇이 와도 잡도록 fetch 상수 대신 패턴을 적어요
const RECORDING_REQUEST =
  "*/api/v1/workspaces/:workspaceId/recordings/:recordingId";
const RETRY_REQUEST =
  "*/api/v1/workspaces/:workspaceId/document-generation-jobs/:jobId/retry";
const AUDIO_SAVE_REQUEST = `${RECORDING_REQUEST}/audio-save`;
const AUDIO_SAVE_RETRY_REQUEST = `${AUDIO_SAVE_REQUEST}/retry`;
const TRANSCRIPTION_RETRY_REQUEST = `${RECORDING_REQUEST}/transcription/retry`;
const POLL_INTERVAL_MS = 3000;
// mock 녹음의 어느 작업과도 겹치지 않는 문서 생성 작업 id
const ANOTHER_JOB_ID = 188;
// 전사 실패 mock 녹음의 시도와 다른 전사 시도 id. 다른 곳에서 전사를 다시 시도한 경우예요
const ANOTHER_TRANSCRIPTION_ATTEMPT_ID = "8f4b2c1e-3a5d-4e6f-9b7a-0c1d2e3f4a5b";
// 문서 만들기에 실패한 mock 녹음의 실패 작업 id. 다시 시도 mock 응답이 가리키는 작업이에요
const FAILED_JOB_ID = documentGenerationJobRetryResponse.jobId;
// 실패한 작업이 둘인 문서 생성 결과예요. 문서는 주제마다 다른 작업이 만들어서, 한 녹음에서 여러 작업이 실패할 수 있어요
const twoJobsFailedGeneration = {
  status: "FAILED",
  succeededCount: 0,
  failedJobIds: [FAILED_JOB_ID, ANOTHER_JOB_ID],
} satisfies RecordingDocumentGenerationResponse;
const twoJobsFailedDetail: RecordingDetailResponse = {
  ...failedRecording.detail,
  documentGeneration: twoJobsFailedGeneration,
};

// 다시 시도를 접수했을 때의 응답
const acceptRetry = (jobId: number) =>
  HttpResponse.json(
    { ...documentGenerationJobRetryResponse, jobId },
    { status: 202 },
  );

const ORGANIZING_TITLE = "회의 내용을 바탕으로 문서를 정리하고 있어요";
const FAILED_TITLE = "문서를 만들지 못했어요";
const TRANSCRIPTION_FAILED_TITLE = "녹음을 글로 옮기지 못했어요";
const NO_CONTENT_TITLE = "문서로 만들 내용이 없었어요";
const LOAD_FAILED_TITLE = "문서를 불러오지 못했어요";
const RECORDING_KEPT_NOTICE = "녹음은 보관해 두었어요.";
const AUDIO_SAVE_CHECK_FAILED_NOTICE =
  "녹음 저장 상태를 확인하지 못했어요. 잠시 후 다시 시도해 주세요.";

const getRecordingDocumentPath = (recordingId: number) =>
  getRouterPath({
    routeKey: "RECORDING_DOCUMENT",
    params: {
      workspaceId: String(WORKSPACE_ID),
      recordingId: String(recordingId),
    },
  });

const getRecordingDocumentsPath = (recordingId: number | string) =>
  getRouterPath({
    routeKey: "RECORDING_DOCUMENTS",
    params: {
      workspaceId: String(WORKSPACE_ID),
      recordingId: String(recordingId),
    },
  });

const renderRecordingDocuments = (recordingId: number | string) => {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  // 이동을 확인할 수 있게 홈 · 녹음 · 녹음 문서 · 로그인 경로에 표시만 하는 화면을 둬요
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
            path: PATH_ROUTE.RECORDING_DOCUMENTS,
            element: <RecordingDocuments />,
          },
          { path: PATH_ROUTE.WORKSPACE_HOME, element: <p>워크스페이스 홈</p> },
          { path: PATH_ROUTE.RECORDING, element: <p>녹음 화면</p> },
          {
            path: PATH_ROUTE.RECORDING_DOCUMENT,
            element: <p>녹음 문서 화면</p>,
          },
          { path: PATH_ROUTE.LOGIN, element: <p>로그인 화면</p> },
        ],
      },
    ],
    { initialEntries: [getRecordingDocumentsPath(recordingId)] },
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

// 다시 시도할 수 없는 실패 화면이에요. 녹음을 보관했다는 설명과 「다시 시도」 없이 「홈으로」만 있어요
const expectFailedScreenWithoutRetry = async (title = FAILED_TITLE) => {
  expect(
    await screen.findByRole("button", { name: "홈으로" }),
  ).toBeInTheDocument();
  expect(
    screen.getByRole("heading", { level: 2, name: title }),
  ).toBeInTheDocument();
  expect(
    screen.queryByRole("button", { name: "다시 시도" }),
  ).not.toBeInTheDocument();
  expect(screen.queryByText(RECORDING_KEPT_NOTICE)).not.toBeInTheDocument();
};

describe("RecordingDocuments", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("문서를 정리하는 중이면 정리 중이라고 알리고 버튼은 두지 않는다", async () => {
    renderRecordingDocuments(organizingRecording.recordingId);

    expect(
      await screen.findByRole("heading", { level: 2, name: ORGANIZING_TITLE }),
    ).toBeInTheDocument();
    expect(
      screen.getByText("주제별로 문서를 나누는 중이에요."),
    ).toBeInTheDocument();
    expect(screen.getByText("조금만 기다려 주세요.")).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("문서로 만들 내용이 없었으면 그 결과를 알리고, 홈으로를 누르면 워크스페이스 홈으로 간다", async () => {
    renderRecordingDocuments(noContentRecording.recordingId);

    expect(
      await screen.findByRole("heading", { level: 2, name: NO_CONTENT_TITLE }),
    ).toBeInTheDocument();
    expect(
      screen.getByText("대화가 너무 짧거나 정리할 논의를 찾지 못했어요."),
    ).toBeInTheDocument();
    expect(
      screen.getByText("녹음은 따로 저장하지 않았어요."),
    ).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "홈으로" }));

    expect(await screen.findByText("워크스페이스 홈")).toBeInTheDocument();
  });

  it("문서 만들기에 실패했으면 녹음을 보관했다고 알리고 다시 시도 버튼을 보여준다", async () => {
    renderRecordingDocuments(failedRecording.recordingId);

    expect(
      await screen.findByRole("heading", { level: 2, name: FAILED_TITLE }),
    ).toBeInTheDocument();
    expect(screen.getByText(RECORDING_KEPT_NOTICE)).toBeInTheDocument();
    expect(
      screen.getByText(
        "다시 시도하거나, 홈의 진행 중인 녹음에서 나중에 다시 시도할 수 있어요.",
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "다시 시도" }),
    ).toBeInTheDocument();
  });

  it("다시 시도를 누르면 문서 만들기를 다시 요청하고, 접수되면 정리 중 화면으로 돌아간다", async () => {
    let transcriptionRetryCount = 0;
    mockServer.use(
      http.post(TRANSCRIPTION_RETRY_REQUEST, () => {
        transcriptionRetryCount += 1;
        return new HttpResponse(null, { status: 500 });
      }),
    );
    renderRecordingDocuments(failedRecording.recordingId);

    fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));

    // 기본 mock은 다시 시도를 접수하면 그 녹음의 상태를 정리 중으로 바꿔 돌려줘요
    expect(
      await screen.findByRole("heading", { level: 2, name: ORGANIZING_TITLE }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    // 문서 만들기 실패는 전사가 아니라 문서 만들기만 다시 시도해요
    expect(transcriptionRetryCount).toBe(0);
  });

  it("문서 만들기에 실패했지만 다시 시도할 작업이 없으면 설명과 다시 시도 없이 홈으로만 보여준다", async () => {
    mockServer.use(
      http.get(RECORDING_REQUEST, () =>
        HttpResponse.json({
          ...failedRecording.detail,
          // 실패 작업의 보존 기한이 지나면 서버는 실패 결과만 남기고 작업 ID를 비워요
          documentGeneration: {
            ...failedRecording.detail.documentGeneration,
            failedJobIds: [],
          },
        }),
      ),
    );
    renderRecordingDocuments(failedRecording.recordingId);

    await expectFailedScreenWithoutRetry();

    fireEvent.click(screen.getByRole("button", { name: "홈으로" }));

    expect(await screen.findByText("워크스페이스 홈")).toBeInTheDocument();
  });

  it("문서가 일부만 만들어진 실패도 녹음 문서로 보내지 않고, 실패했다고 알리며 다시 시도 버튼을 보여준다", async () => {
    mockServer.use(
      http.get(RECORDING_REQUEST, () =>
        HttpResponse.json({
          ...failedRecording.detail,
          documentGeneration: {
            ...failedRecording.detail.documentGeneration,
            succeededCount: 2,
          },
        }),
      ),
    );
    renderRecordingDocuments(failedRecording.recordingId);

    expect(
      await screen.findByRole("heading", { level: 2, name: FAILED_TITLE }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "다시 시도" }),
    ).toBeInTheDocument();
    expect(screen.queryByText("녹음 문서 화면")).not.toBeInTheDocument();
  });

  it.each([
    {
      reason: "오디오 업로드를 아직 확인하지 못했으면",
      detail: {
        ...organizingRecording.detail,
        audioUploadStatus: "RESERVED",
        transcriptionAttemptId: null,
        transcriptionStatus: null,
        documentGeneration: null,
      },
    },
    {
      reason: "전사하는 중이면",
      detail: {
        ...organizingRecording.detail,
        transcriptionStatus: "PROCESSING",
        documentGeneration: null,
      },
    },
    {
      reason: "문서 만들기가 대기 중이면",
      detail: {
        ...organizingRecording.detail,
        documentGeneration: {
          ...organizingRecording.detail.documentGeneration,
          status: "QUEUED",
        },
      },
    },
  ])("끝낸 녹음의 $reason 정리 중이라고 알린다", async ({ detail }) => {
    mockServer.use(
      http.get(RECORDING_REQUEST, () => HttpResponse.json(detail)),
    );
    renderRecordingDocuments(organizingRecording.recordingId);

    expect(
      await screen.findByRole("heading", { level: 2, name: ORGANIZING_TITLE }),
    ).toBeInTheDocument();
  });

  it("전사 상태와 문서 생성 결과가 없는 응답을 받으면 정리 중이라고 알리고 계속 조회한다", async () => {
    let requestCount = 0;
    // 전사와 문서 생성 결과를 아직 주지 않는 서버의 응답이에요. 세션 상태와 업로드 상태만 있어요
    const {
      transcriptionAttemptId: _transcriptionAttemptId,
      transcriptionStatus: _transcriptionStatus,
      transcriptionFailureCode: _transcriptionFailureCode,
      documentGeneration: _documentGeneration,
      ...sessionOnlyDetail
    } = organizingRecording.detail;
    mockServer.use(
      http.get(RECORDING_REQUEST, () => {
        requestCount += 1;
        return HttpResponse.json(sessionOnlyDetail);
      }),
    );
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderRecordingDocuments(organizingRecording.recordingId);

    expect(
      await screen.findByRole("heading", { level: 2, name: ORGANIZING_TITLE }),
    ).toBeInTheDocument();
    expect(requestCount).toBe(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    });

    await waitFor(() => expect(requestCount).toBe(2));
    expect(
      screen.getByRole("heading", { level: 2, name: ORGANIZING_TITLE }),
    ).toBeInTheDocument();
  });

  it("정리 중에는 3초마다 다시 조회하고, 결과가 정해지면 화면을 바꾸고 더 조회하지 않는다", async () => {
    let requestCount = 0;
    mockServer.use(
      http.get(RECORDING_REQUEST, () => {
        requestCount += 1;
        // 두 번째 조회부터는 문서로 만들 내용이 없었다는 결과를 돌려줘요
        return HttpResponse.json(
          requestCount === 1
            ? organizingRecording.detail
            : noContentRecording.detail,
        );
      }),
    );
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderRecordingDocuments(organizingRecording.recordingId);

    await screen.findByRole("heading", { level: 2, name: ORGANIZING_TITLE });
    expect(requestCount).toBe(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    });

    expect(
      await screen.findByRole("heading", { level: 2, name: NO_CONTENT_TITLE }),
    ).toBeInTheDocument();
    expect(requestCount).toBe(2);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 2);
    });

    expect(requestCount).toBe(2);
  });

  it.each([403, 404])(
    "정리 중에 녹음이 없어지거나 볼 수 없게 되면(%i) 문서를 불러오지 못했다고 알리고 더 조회하지 않는다",
    async (status) => {
      let requestCount = 0;
      mockServer.use(
        http.get(RECORDING_REQUEST, () => {
          requestCount += 1;
          // 두 번째 조회부터는 녹음이 없어졌거나 볼 수 없게 된 응답을 돌려줘요
          return requestCount === 1
            ? HttpResponse.json(organizingRecording.detail)
            : new HttpResponse(null, { status });
        }),
      );
      vi.useFakeTimers({ shouldAdvanceTime: true });
      renderRecordingDocuments(organizingRecording.recordingId);

      await screen.findByRole("heading", { level: 2, name: ORGANIZING_TITLE });

      await act(async () => {
        await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
      });

      expect(
        await screen.findByRole("heading", {
          level: 2,
          name: LOAD_FAILED_TITLE,
        }),
      ).toBeInTheDocument();
      expect(requestCount).toBe(2);

      await act(async () => {
        await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 2);
      });

      expect(requestCount).toBe(2);
    },
  );

  it("정리 중에 다시 조회가 서버 오류로 실패하면 정리 중 화면을 유지하고 계속 조회한다", async () => {
    let requestCount = 0;
    mockServer.use(
      http.get(RECORDING_REQUEST, () => {
        requestCount += 1;
        return requestCount === 1
          ? HttpResponse.json(organizingRecording.detail)
          : new HttpResponse(null, { status: 500 });
      }),
    );
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderRecordingDocuments(organizingRecording.recordingId);

    await screen.findByRole("heading", { level: 2, name: ORGANIZING_TITLE });

    await act(async () => {
      await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    });
    await waitFor(() => expect(requestCount).toBe(2));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    });
    await waitFor(() => expect(requestCount).toBe(3));

    expect(
      screen.getByRole("heading", { level: 2, name: ORGANIZING_TITLE }),
    ).toBeInTheDocument();
  });

  it("문서 정리가 끝났으면 그 녹음의 녹음 문서로 보내고, 뒤로 가도 정리 화면으로 돌아오지 않게 지금 기록을 바꾼다", async () => {
    const { router } = renderRecordingDocuments(completedRecording.recordingId);

    expect(await screen.findByText("녹음 문서 화면")).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(
      getRecordingDocumentPath(completedRecording.recordingId),
    );
    expect(router.state.historyAction).toBe("REPLACE");
  });

  it("정리 중에 머물다가 정리가 끝나면 그 녹음의 녹음 문서로 보낸다", async () => {
    let requestCount = 0;
    mockServer.use(
      http.get(RECORDING_REQUEST, () => {
        requestCount += 1;
        // 두 번째 조회부터는 정리가 끝났다는 결과를 돌려줘요
        return HttpResponse.json(
          requestCount === 1
            ? organizingRecording.detail
            : completedRecording.detail,
        );
      }),
    );
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const { router } = renderRecordingDocuments(
      organizingRecording.recordingId,
    );

    await screen.findByRole("heading", { level: 2, name: ORGANIZING_TITLE });

    await act(async () => {
      await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    });

    expect(await screen.findByText("녹음 문서 화면")).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(
      getRecordingDocumentPath(organizingRecording.recordingId),
    );
  });

  it.each(["RECORDING", "PAUSED"] as const)(
    "아직 녹음 중이면(%s) 지금 기록을 바꿔 녹음 화면으로 보낸다",
    async (status) => {
      mockServer.use(
        http.get(RECORDING_REQUEST, () =>
          HttpResponse.json({
            ...organizingRecording.detail,
            status,
            endedAt: null,
            endReason: null,
            audioUploadStatus: null,
            transcriptionAttemptId: null,
            transcriptionStatus: null,
            documentGeneration: null,
          }),
        ),
      );
      const { router } = renderRecordingDocuments(
        organizingRecording.recordingId,
      );

      expect(await screen.findByText("녹음 화면")).toBeInTheDocument();
      expect(router.state.historyAction).toBe("REPLACE");
    },
  );

  it("없는 녹음이면 문서를 불러오지 못했다고 알리고, 다시 시도를 누르면 다시 조회한다", async () => {
    let requestCount = 0;
    mockServer.use(
      http.get(RECORDING_REQUEST, () => {
        requestCount += 1;
        return new HttpResponse(null, { status: 404 });
      }),
    );
    renderRecordingDocuments(organizingRecording.recordingId);

    expect(
      await screen.findByRole("heading", { level: 2, name: LOAD_FAILED_TITLE }),
    ).toBeInTheDocument();
    expect(requestCount).toBe(1);

    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));

    await waitFor(() => expect(requestCount).toBe(2));
    expect(
      screen.getByRole("heading", { level: 2, name: LOAD_FAILED_TITLE }),
    ).toBeInTheDocument();
  });

  it.each([400, 403])(
    "%i 응답도 문서를 불러오지 못했다고 알린다",
    async (status) => {
      mockServer.use(
        http.get(RECORDING_REQUEST, () => new HttpResponse(null, { status })),
      );
      renderRecordingDocuments(organizingRecording.recordingId);

      expect(
        await screen.findByRole("heading", {
          level: 2,
          name: LOAD_FAILED_TITLE,
        }),
      ).toBeInTheDocument();
    },
  );

  it.each([
    // 명세에 없는 상태예요. 서버에 상태가 늘었는데 화면이 아직 모르는 경우를 흉내 내요
    { reason: "화면이 모르는 세션 상태를 주면", status: "ARCHIVED" },
    { reason: "서버가 버린 녹음이면(DISCARDED)", status: "DISCARDED" },
  ])("$reason 문서를 불러오지 못했다고 알린다", async ({ status }) => {
    mockServer.use(
      http.get(RECORDING_REQUEST, () =>
        HttpResponse.json({ ...organizingRecording.detail, status }),
      ),
    );
    renderRecordingDocuments(organizingRecording.recordingId);

    expect(
      await screen.findByRole("heading", {
        level: 2,
        name: LOAD_FAILED_TITLE,
      }),
    ).toBeInTheDocument();
  });

  it("서버가 화면이 모르는 문서 생성 상태를 주면 문서를 불러오지 못했다고 알린다", async () => {
    mockServer.use(
      http.get(RECORDING_REQUEST, () =>
        HttpResponse.json({
          ...organizingRecording.detail,
          documentGeneration: {
            ...organizingRecording.detail.documentGeneration,
            status: "CANCELLED",
          },
        }),
      ),
    );
    renderRecordingDocuments(organizingRecording.recordingId);

    expect(
      await screen.findByRole("heading", { level: 2, name: LOAD_FAILED_TITLE }),
    ).toBeInTheDocument();
  });

  it("주소의 녹음 id가 숫자가 아니어도 확인하지 않고 그대로 요청하고, 서버가 거절하면 문서를 불러오지 못했다고 알린다", async () => {
    let requestCount = 0;
    mockServer.use(
      http.get(RECORDING_REQUEST, () => {
        requestCount += 1;
        // 서버는 숫자가 아닌 녹음 id를 잘못된 요청으로 거절해요
        return new HttpResponse(null, { status: 400 });
      }),
    );
    renderRecordingDocuments("abc");

    expect(
      await screen.findByRole("heading", { level: 2, name: LOAD_FAILED_TITLE }),
    ).toBeInTheDocument();
    expect(requestCount).toBe(1);
  });

  it("서버 오류로 처음 조회에 실패하면 문서를 불러오지 못했다고 알리고, 다시 시도를 누르면 상태를 보여준다", async () => {
    mockServer.use(
      http.get(
        RECORDING_REQUEST,
        () => new HttpResponse(null, { status: 500 }),
        { once: true },
      ),
    );
    renderRecordingDocuments(organizingRecording.recordingId);

    expect(
      await screen.findByRole("heading", { level: 2, name: LOAD_FAILED_TITLE }),
    ).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));

    expect(
      await screen.findByRole("heading", { level: 2, name: ORGANIZING_TITLE }),
    ).toBeInTheDocument();
  });

  it("서버 오류로 처음 조회에 실패한 뒤에는 저절로 다시 조회하지 않고 실패 화면을 유지한다", async () => {
    let requestCount = 0;
    mockServer.use(
      http.get(RECORDING_REQUEST, () => {
        requestCount += 1;
        return new HttpResponse(null, { status: 500 });
      }),
    );
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderRecordingDocuments(organizingRecording.recordingId);

    await screen.findByRole("heading", { level: 2, name: LOAD_FAILED_TITLE });
    expect(requestCount).toBe(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 2);
    });

    expect(requestCount).toBe(1);
    expect(
      screen.getByRole("heading", { level: 2, name: LOAD_FAILED_TITLE }),
    ).toBeInTheDocument();
  });

  it("로그인이 풀렸으면(401) 로그인 화면으로 보낸다", async () => {
    mockServer.use(
      http.get(
        RECORDING_REQUEST,
        () => new HttpResponse(null, { status: 401 }),
      ),
    );
    renderRecordingDocuments(organizingRecording.recordingId);

    expect(await screen.findByText("로그인 화면")).toBeInTheDocument();
  });

  it.each([409, 404])(
    "다시 시도가 거절되면(%i) 설명과 다시 시도 없이 홈으로만 보여준다",
    async (status) => {
      mockServer.use(
        http.post(RETRY_REQUEST, () => new HttpResponse(null, { status })),
      );
      renderRecordingDocuments(failedRecording.recordingId);

      fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));

      await expectFailedScreenWithoutRetry();
    },
  );

  it("다른 녹음의 주소로 가면 앞 녹음에서 거절된 다시 시도를 남기지 않고 다시 시도 버튼을 보여준다", async () => {
    // 문서 만들기에 실패한 또 다른 녹음이에요. 다시 시도할 작업도 달라요
    const anotherFailedRecording = {
      recordingId: failedRecording.recordingId + 100,
      detail: {
        ...failedRecording.detail,
        documentGeneration: {
          ...failedRecording.detail.documentGeneration,
          failedJobIds: [ANOTHER_JOB_ID],
        },
      },
    };
    mockServer.use(
      http.post(RETRY_REQUEST, () => new HttpResponse(null, { status: 404 })),
      http.get(RECORDING_REQUEST, ({ params }) =>
        HttpResponse.json(
          Number(params.recordingId) === anotherFailedRecording.recordingId
            ? anotherFailedRecording.detail
            : failedRecording.detail,
        ),
      ),
    );
    const { router } = renderRecordingDocuments(failedRecording.recordingId);

    fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));
    await screen.findByRole("button", { name: "홈으로" });
    expect(
      screen.queryByRole("button", { name: "다시 시도" }),
    ).not.toBeInTheDocument();

    await act(async () => {
      await router.navigate(
        getRecordingDocumentsPath(anotherFailedRecording.recordingId),
      );
    });

    expect(
      await screen.findByRole("button", { name: "다시 시도" }),
    ).toBeInTheDocument();
    expect(screen.getByText(RECORDING_KEPT_NOTICE)).toBeInTheDocument();
  });

  it.each([
    {
      condition: "이미 정리 중이면 정리 중 화면으로 바꾼다",
      latestRecording: organizingRecording,
      expectedScreen: () =>
        screen.findByRole("heading", { level: 2, name: ORGANIZING_TITLE }),
    },
    {
      condition: "이미 정리가 끝났으면 그 녹음의 녹음 문서로 보낸다",
      latestRecording: completedRecording,
      expectedScreen: () => screen.findByText("녹음 문서 화면"),
    },
  ])(
    "다시 시도가 409로 거절되면 녹음 상태를 다시 조회하고, 서버에서 $condition",
    async ({ latestRecording, expectedScreen }) => {
      const { recordingId } = failedRecording;
      let isRetryRejected = false;
      mockServer.use(
        http.post(RETRY_REQUEST, () => {
          isRetryRejected = true;
          return new HttpResponse(null, { status: 409 });
        }),
        // 다른 곳에서 먼저 다시 시도해, 서버의 녹음은 화면이 아는 실패 상태가 아니에요
        http.get(RECORDING_REQUEST, () =>
          HttpResponse.json(
            (isRetryRejected ? latestRecording : failedRecording).detail,
          ),
        ),
      );
      renderRecordingDocuments(recordingId);

      fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));

      expect(await expectedScreen()).toBeInTheDocument();
    },
  );

  it("다시 시도가 409로 거절된 뒤 정리 중이 됐다가 또 실패하면 다시 시도 버튼을 다시 보여준다", async () => {
    let latestRecording: typeof failedRecording | typeof organizingRecording =
      failedRecording;
    mockServer.use(
      http.post(RETRY_REQUEST, () => {
        latestRecording = organizingRecording;
        return new HttpResponse(null, { status: 409 });
      }),
      http.get(RECORDING_REQUEST, () =>
        HttpResponse.json(latestRecording.detail),
      ),
    );
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderRecordingDocuments(failedRecording.recordingId);

    fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));
    await screen.findByRole("heading", { level: 2, name: ORGANIZING_TITLE });

    // 서버에서 그 작업이 또 실패해, 다음 조회부터 다시 실패 상태를 받아요
    latestRecording = failedRecording;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    });

    expect(
      await screen.findByRole("button", { name: "다시 시도" }),
    ).toBeInTheDocument();
    expect(screen.getByText(RECORDING_KEPT_NOTICE)).toBeInTheDocument();
  });

  it("다시 시도가 서버 오류로 실패하면 버튼을 남겨 다시 누를 수 있게 한다", async () => {
    let retryCount = 0;
    mockServer.use(
      http.post(RETRY_REQUEST, () => {
        retryCount += 1;
        return new HttpResponse(null, { status: 500 });
      }),
    );
    renderRecordingDocuments(failedRecording.recordingId);

    fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));
    await waitFor(() => expect(retryCount).toBe(1));

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "다시 시도" })).toBeEnabled(),
    );
    expect(screen.getByText(RECORDING_KEPT_NOTICE)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));

    await waitFor(() => expect(retryCount).toBe(2));
  });

  it("다시 시도에서 로그인이 풀렸으면(401) 로그인 화면으로 보낸다", async () => {
    mockServer.use(
      http.post(RETRY_REQUEST, () => new HttpResponse(null, { status: 401 })),
    );
    renderRecordingDocuments(failedRecording.recordingId);

    fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));

    expect(await screen.findByText("로그인 화면")).toBeInTheDocument();
  });

  it("실패한 작업이 여러 개면 다시 시도 한 번으로 작업마다 요청하고, 접수되면 정리 중 화면으로 돌아간다", async () => {
    const retriedJobIds: number[] = [];
    let isRetried = false;
    mockServer.use(
      http.post(RETRY_REQUEST, ({ params }) => {
        retriedJobIds.push(Number(params.jobId));
        isRetried = true;
        return acceptRetry(Number(params.jobId));
      }),
      // 다시 시도를 접수한 뒤로는 서버가 다시 정리 중이라고 답해요
      http.get(RECORDING_REQUEST, () =>
        HttpResponse.json(
          isRetried ? organizingRecording.detail : twoJobsFailedDetail,
        ),
      ),
    );
    renderRecordingDocuments(failedRecording.recordingId);

    fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));

    expect(
      await screen.findByRole("heading", { level: 2, name: ORGANIZING_TITLE }),
    ).toBeInTheDocument();
    expect(retriedJobIds).toHaveLength(2);
    expect(new Set(retriedJobIds)).toEqual(
      new Set([FAILED_JOB_ID, ANOTHER_JOB_ID]),
    );
  });

  it("실패한 작업 가운데 일부만 접수되고 나머지는 거절돼도, 녹음을 다시 조회해 정리 중 화면으로 돌아간다", async () => {
    const retriedJobIds: number[] = [];
    let isRetried = false;
    mockServer.use(
      http.post(RETRY_REQUEST, ({ params }) => {
        const jobId = Number(params.jobId);
        retriedJobIds.push(jobId);

        if (jobId === ANOTHER_JOB_ID) {
          return new HttpResponse(null, { status: 409 });
        }

        isRetried = true;
        return acceptRetry(jobId);
      }),
      http.get(RECORDING_REQUEST, () =>
        HttpResponse.json(
          isRetried ? organizingRecording.detail : twoJobsFailedDetail,
        ),
      ),
    );
    renderRecordingDocuments(failedRecording.recordingId);

    fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));

    expect(
      await screen.findByRole("heading", { level: 2, name: ORGANIZING_TITLE }),
    ).toBeInTheDocument();
    expect(retriedJobIds).toHaveLength(2);
  });

  it("실패한 작업이 모두 거절되면(409 · 404) 설명과 다시 시도 없이 홈으로만 보여준다", async () => {
    const retriedJobIds: number[] = [];
    mockServer.use(
      http.post(RETRY_REQUEST, ({ params }) => {
        const jobId = Number(params.jobId);
        retriedJobIds.push(jobId);

        return new HttpResponse(null, {
          status: jobId === ANOTHER_JOB_ID ? 404 : 409,
        });
      }),
      http.get(RECORDING_REQUEST, () => HttpResponse.json(twoJobsFailedDetail)),
    );
    renderRecordingDocuments(failedRecording.recordingId);

    fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));

    await expectFailedScreenWithoutRetry();
    expect(retriedJobIds).toHaveLength(2);
  });

  it("한 작업은 거절되고 다른 작업은 서버 오류로 실패하면, 버튼을 남겨 다시 누를 수 있게 한다", async () => {
    let retryCount = 0;
    mockServer.use(
      http.post(RETRY_REQUEST, ({ params }) => {
        retryCount += 1;

        return new HttpResponse(null, {
          status: Number(params.jobId) === ANOTHER_JOB_ID ? 500 : 409,
        });
      }),
      http.get(RECORDING_REQUEST, () => HttpResponse.json(twoJobsFailedDetail)),
    );
    renderRecordingDocuments(failedRecording.recordingId);

    fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));
    await waitFor(() => expect(retryCount).toBe(2));

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "다시 시도" })).toBeEnabled(),
    );
    expect(screen.getByText(RECORDING_KEPT_NOTICE)).toBeInTheDocument();
  });

  it("다시 시도가 거절된 작업만 실패로 남으면, 다시 시도 없이 홈으로만 보여준다", async () => {
    let latestDetail: RecordingDetailResponse = twoJobsFailedDetail;
    mockServer.use(
      http.post(RETRY_REQUEST, ({ params }) => {
        const jobId = Number(params.jobId);

        // 다시 시도할 수 있는 횟수를 다 쓴 작업은 서버가 거절해요
        if (jobId === FAILED_JOB_ID) {
          return new HttpResponse(null, { status: 409 });
        }

        // 접수된 작업이 대기 중이라 서버는 정리 중이라고 답하고, 거절된 작업은 실패 작업으로 그대로 남아요
        latestDetail = {
          ...twoJobsFailedDetail,
          documentGeneration: {
            ...twoJobsFailedGeneration,
            status: "QUEUED",
            failedJobIds: [FAILED_JOB_ID],
          },
        };
        return acceptRetry(jobId);
      }),
      http.get(RECORDING_REQUEST, () => HttpResponse.json(latestDetail)),
    );
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderRecordingDocuments(failedRecording.recordingId);

    fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));
    await screen.findByRole("heading", { level: 2, name: ORGANIZING_TITLE });

    // 접수된 작업은 문서를 만들었고, 거절된 작업만 실패로 남았어요
    latestDetail = {
      ...twoJobsFailedDetail,
      documentGeneration: {
        ...twoJobsFailedGeneration,
        succeededCount: 1,
        failedJobIds: [FAILED_JOB_ID],
      },
    };
    await act(async () => {
      await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    });

    await expectFailedScreenWithoutRetry();
  });

  it("작업마다 보낸 다시 시도가 모두 끝날 때까지 버튼을 다시 누를 수 없다", async () => {
    let finishedCount = 0;
    let releaseSlowRetry = () => {};
    const slowRetry = new Promise<void>((resolve) => {
      releaseSlowRetry = resolve;
    });
    mockServer.use(
      http.post(RETRY_REQUEST, async ({ params }) => {
        // 한 작업의 응답만 늦게 와요
        if (Number(params.jobId) === ANOTHER_JOB_ID) await slowRetry;

        finishedCount += 1;
        return new HttpResponse(null, { status: 500 });
      }),
      http.get(RECORDING_REQUEST, () => HttpResponse.json(twoJobsFailedDetail)),
    );
    renderRecordingDocuments(failedRecording.recordingId);

    const retryButton = await screen.findByRole("button", {
      name: "다시 시도",
    });
    fireEvent.click(retryButton);
    await waitFor(() => expect(finishedCount).toBe(1));

    expect(retryButton).toBeDisabled();

    releaseSlowRetry();

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "다시 시도" })).toBeEnabled(),
    );
    expect(finishedCount).toBe(2);
  });

  it("409로 거절된 뒤 녹음 상태를 다시 조회하는 동안에는 버튼을 다시 누를 수 없다", async () => {
    let recordingRequestCount = 0;
    let releaseRecheck = () => {};
    const recheck = new Promise<void>((resolve) => {
      releaseRecheck = resolve;
    });
    mockServer.use(
      // 한 작업은 거절되고 다른 작업은 서버 오류라, 다시 시도할 작업이 남아 버튼이 사라지지 않는 경우예요
      http.post(
        RETRY_REQUEST,
        ({ params }) =>
          new HttpResponse(null, {
            status: Number(params.jobId) === ANOTHER_JOB_ID ? 500 : 409,
          }),
      ),
      http.get(RECORDING_REQUEST, async () => {
        recordingRequestCount += 1;
        // 409 뒤의 조회(두 번째 조회)만 늦게 답해요
        if (recordingRequestCount === 2) await recheck;

        return HttpResponse.json(twoJobsFailedDetail);
      }),
    );
    renderRecordingDocuments(failedRecording.recordingId);

    const retryButton = await screen.findByRole("button", {
      name: "다시 시도",
    });
    fireEvent.click(retryButton);
    await waitFor(() => expect(recordingRequestCount).toBe(2));
    // 다시 시도 요청이 끝난 화면이 그려질 시간을 줘요
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 50));
    });

    expect(retryButton).toBeDisabled();

    releaseRecheck();

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "다시 시도" })).toBeEnabled(),
    );
  });

  it("409로 거절된 작업 가운데 하나라도 서버에서 실패 상태가 아니면 거절 기록을 지워, 그 작업이 또 실패했을 때 다시 시도 버튼을 보여준다", async () => {
    let latestDetail: RecordingDetailResponse = twoJobsFailedDetail;
    mockServer.use(
      http.post(RETRY_REQUEST, () => {
        // 한 작업은 다시 시도할 수 있는 횟수를 다 써서 실패로 남아 있고, 다른 작업은 다른 곳에서 먼저 다시 시도해 대기 중이에요
        latestDetail = {
          ...twoJobsFailedDetail,
          documentGeneration: {
            ...twoJobsFailedGeneration,
            status: "QUEUED",
            failedJobIds: [FAILED_JOB_ID],
          },
        };
        return new HttpResponse(null, { status: 409 });
      }),
      http.get(RECORDING_REQUEST, () => HttpResponse.json(latestDetail)),
    );
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderRecordingDocuments(failedRecording.recordingId);

    fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));
    await screen.findByRole("heading", { level: 2, name: ORGANIZING_TITLE });

    // 대기 중이던 작업이 또 실패해, 두 작업이 모두 실패 작업으로 돌아왔어요
    latestDetail = twoJobsFailedDetail;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    });

    expect(
      await screen.findByRole("button", { name: "다시 시도" }),
    ).toBeInTheDocument();
    expect(screen.getByText(RECORDING_KEPT_NOTICE)).toBeInTheDocument();
  });

  describe("다시 시도가 접수되지 않았을 때의 안내 (STT-R22)", () => {
    const RETRY_FAILED = TOAST_MESSAGES.RETRY_REQUEST_FAILED.message;

    /** 응답을 처리할 틈을 준 뒤에도 토스트가 없는지 봐요 */
    const expectNoRetryFailedToast = async () => {
      await act(() => new Promise((resolve) => setTimeout(resolve, 50)));
      expect(screen.queryByText(RETRY_FAILED)).not.toBeInTheDocument();
    };

    it("문서 다시 시도가 서버 오류로 접수되지 않으면 오류 토스트를 띄운다", async () => {
      mockServer.use(
        http.post(RETRY_REQUEST, () => new HttpResponse(null, { status: 500 })),
      );
      renderRecordingDocuments(failedRecording.recordingId);

      fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));

      expect(await screen.findByText(RETRY_FAILED)).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "다시 시도" })).toBeEnabled();
    });

    it("한 작업은 거절되고 다른 작업은 서버 오류로 실패해 아무 작업도 접수되지 않으면 오류 토스트를 띄운다", async () => {
      mockServer.use(
        http.post(
          RETRY_REQUEST,
          ({ params }) =>
            new HttpResponse(null, {
              status: Number(params.jobId) === ANOTHER_JOB_ID ? 500 : 409,
            }),
        ),
        http.get(RECORDING_REQUEST, () =>
          HttpResponse.json(twoJobsFailedDetail),
        ),
      );
      renderRecordingDocuments(failedRecording.recordingId);

      fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));

      expect(await screen.findByText(RETRY_FAILED)).toBeInTheDocument();
    });

    it("작업이 모두 거절되면(409 · 404) 오류 토스트 없이 홈으로만 보여준다", async () => {
      mockServer.use(
        http.post(
          RETRY_REQUEST,
          ({ params }) =>
            new HttpResponse(null, {
              status: Number(params.jobId) === ANOTHER_JOB_ID ? 404 : 409,
            }),
        ),
        http.get(RECORDING_REQUEST, () =>
          HttpResponse.json(twoJobsFailedDetail),
        ),
      );
      renderRecordingDocuments(failedRecording.recordingId);

      fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));

      await expectFailedScreenWithoutRetry();
      await expectNoRetryFailedToast();
    });

    it("일부 작업이라도 접수되면 오류 토스트를 띄우지 않고 정리 중 화면으로 돌아간다", async () => {
      let isRetried = false;
      mockServer.use(
        http.post(RETRY_REQUEST, ({ params }) => {
          const jobId = Number(params.jobId);
          if (jobId === ANOTHER_JOB_ID) {
            return new HttpResponse(null, { status: 500 });
          }

          isRetried = true;
          return acceptRetry(jobId);
        }),
        http.get(RECORDING_REQUEST, () =>
          HttpResponse.json(
            isRetried ? organizingRecording.detail : twoJobsFailedDetail,
          ),
        ),
      );
      renderRecordingDocuments(failedRecording.recordingId);

      fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));

      expect(
        await screen.findByRole("heading", {
          level: 2,
          name: ORGANIZING_TITLE,
        }),
      ).toBeInTheDocument();
      await expectNoRetryFailedToast();
    });

    it("문서 다시 시도에서 로그인이 풀렸으면(401) 오류 토스트 없이 로그인 화면으로 보낸다", async () => {
      mockServer.use(
        http.post(RETRY_REQUEST, () => new HttpResponse(null, { status: 401 })),
      );
      renderRecordingDocuments(failedRecording.recordingId);

      fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));

      expect(await screen.findByText("로그인 화면")).toBeInTheDocument();
      await expectNoRetryFailedToast();
    });

    it("전사 다시 시도가 서버 오류로 접수되지 않으면 오류 토스트를 띄운다", async () => {
      vi.spyOn(console, "error").mockImplementation(() => {});
      mockServer.use(
        http.post(
          TRANSCRIPTION_RETRY_REQUEST,
          () => new HttpResponse(null, { status: 500 }),
        ),
      );
      renderRecordingDocuments(transcriptionFailedRecording.recordingId);

      fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));

      expect(await screen.findByText(RETRY_FAILED)).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "다시 시도" })).toBeEnabled();
    });

    it.each([
      [409, "TRANSCRIPTION_RETRY_NOT_ALLOWED"],
      [404, "RECORDING_AUDIO_NOT_FOUND"],
    ])(
      "전사 다시 시도가 거절되면(%i) 오류 토스트를 띄우지 않는다",
      async (status, code) => {
        vi.spyOn(console, "error").mockImplementation(() => {});
        mockServer.use(
          http.post(TRANSCRIPTION_RETRY_REQUEST, () =>
            HttpResponse.json({ code, message: "" }, { status }),
          ),
        );
        renderRecordingDocuments(transcriptionFailedRecording.recordingId);

        fireEvent.click(
          await screen.findByRole("button", { name: "다시 시도" }),
        );

        await expectFailedScreenWithoutRetry(TRANSCRIPTION_FAILED_TITLE);
        await expectNoRetryFailedToast();
      },
    );

    it("전사 다시 시도에서 로그인이 풀렸으면(401) 오류 토스트 없이 로그인 화면으로 보낸다", async () => {
      vi.spyOn(console, "error").mockImplementation(() => {});
      mockServer.use(
        http.post(
          TRANSCRIPTION_RETRY_REQUEST,
          () => new HttpResponse(null, { status: 401 }),
        ),
      );
      renderRecordingDocuments(transcriptionFailedRecording.recordingId);

      fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));

      expect(await screen.findByText("로그인 화면")).toBeInTheDocument();
      await expectNoRetryFailedToast();
    });
  });

  describe("전사 실패", () => {
    // 전사 다시 시도가 거절됐을 때의 응답. 실제 서버처럼 본문에 오류 코드를 실어요
    const rejectTranscriptionRetry = (status: number, code: string) =>
      HttpResponse.json(
        { code, message: "전사를 다시 시도할 수 없습니다" },
        { status },
      );

    it.each([
      {
        reason: "AI 응답 시간 초과(AI_TIMEOUT)",
        detail: transcriptionFailedRecording.detail,
      },
      {
        reason: "그 밖의 이유로 실패한 PCM 조각 녹음",
        detail: {
          ...transcriptionFailedRecording.detail,
          transcriptionFailureCode: "AUDIO_STORAGE_UNAVAILABLE",
          sourceAudio: {
            quality: "COMPLETE",
            missingRanges: [],
            tailUnknown: false,
          },
        },
      },
    ] satisfies { reason: string; detail: RecordingDetailResponse }[])(
      "전사에 실패했으면($reason) 녹음을 글로 옮기지 못했다고 알리고, 녹음을 보관했다는 설명과 다시 시도 버튼을 보여준다",
      async ({ detail }) => {
        mockServer.use(
          http.get(RECORDING_REQUEST, () => HttpResponse.json(detail)),
        );
        renderRecordingDocuments(transcriptionFailedRecording.recordingId);

        expect(
          await screen.findByRole("heading", {
            level: 2,
            name: TRANSCRIPTION_FAILED_TITLE,
          }),
        ).toBeInTheDocument();
        expect(screen.getByText(RECORDING_KEPT_NOTICE)).toBeInTheDocument();
        expect(
          screen.getByRole("button", { name: "다시 시도" }),
        ).toBeInTheDocument();
      },
    );

    it("다시 시도를 누르면 문서 만들기가 아니라 전사를 다시 요청하고, 접수되면 정리 중 화면으로 돌아간다", async () => {
      let documentRetryCount = 0;
      let transcriptionRetryCount = 0;
      mockServer.use(
        http.post(RETRY_REQUEST, () => {
          documentRetryCount += 1;
          return new HttpResponse(null, { status: 500 });
        }),
        // 요청만 세고 아무것도 돌려주지 않아 기본 mock 핸들러가 이어서 접수해요
        http.post(TRANSCRIPTION_RETRY_REQUEST, () => {
          transcriptionRetryCount += 1;
        }),
      );
      renderRecordingDocuments(transcriptionFailedRecording.recordingId);

      fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));

      // 기본 mock은 전사 다시 시도를 접수하면 그 녹음을 전사 중으로 바꿔 돌려줘요
      expect(
        await screen.findByRole("heading", {
          level: 2,
          name: ORGANIZING_TITLE,
        }),
      ).toBeInTheDocument();
      expect(screen.queryByRole("button")).not.toBeInTheDocument();
      expect(transcriptionRetryCount).toBe(1);
      expect(documentRetryCount).toBe(0);
    });

    it("전사 다시 시도의 응답과 바뀐 녹음 상태를 받을 때까지 버튼을 다시 누를 수 없다", async () => {
      let releaseRetry = () => {};
      const slowRetry = new Promise<void>((resolve) => {
        releaseRetry = resolve;
      });
      mockServer.use(
        // 응답만 늦게 와요. 아무것도 돌려주지 않아 기본 mock 핸들러가 이어서 접수해요
        http.post(TRANSCRIPTION_RETRY_REQUEST, async () => {
          await slowRetry;
        }),
      );
      renderRecordingDocuments(transcriptionFailedRecording.recordingId);

      const retryButton = await screen.findByRole("button", {
        name: "다시 시도",
      });
      fireEvent.click(retryButton);

      await waitFor(() => expect(retryButton).toBeDisabled());

      releaseRetry();

      expect(
        await screen.findByRole("heading", {
          level: 2,
          name: ORGANIZING_TITLE,
        }),
      ).toBeInTheDocument();
    });

    it.each([
      { status: 409, code: "AUDIO_UPLOAD_NOT_COMPLETED" },
      { status: 409, code: "TRANSCRIPTION_RETRY_NOT_ALLOWED" },
      { status: 409, code: "TRANSCRIPTION_DISABLED" },
      { status: 404, code: "AUDIO_UPLOAD_NOT_FOUND" },
    ])(
      "전사 다시 시도가 거절되고($status $code) 다시 조회한 녹음도 같은 시도로 실패면, 같은 제목에 설명과 다시 시도 없이 홈으로만 보여준다",
      async ({ status, code }) => {
        const consoleError = vi
          .spyOn(console, "error")
          .mockImplementation(() => {});
        mockServer.use(
          http.post(TRANSCRIPTION_RETRY_REQUEST, () =>
            rejectTranscriptionRetry(status, code),
          ),
        );
        renderRecordingDocuments(transcriptionFailedRecording.recordingId);

        fireEvent.click(
          await screen.findByRole("button", { name: "다시 시도" }),
        );

        await expectFailedScreenWithoutRetry(TRANSCRIPTION_FAILED_TITLE);

        consoleError.mockRestore();
      },
    );

    it("전사 다시 시도가 409로 거절됐지만 다시 조회한 녹음이 이미 전사 중이면 정리 중 화면으로 바뀐다", async () => {
      let latestDetail: RecordingDetailResponse =
        transcriptionFailedRecording.detail;
      mockServer.use(
        http.post(TRANSCRIPTION_RETRY_REQUEST, () => {
          // 다른 곳에서 먼저 다시 시도해, 서버에서는 새 시도로 전사 중이에요
          latestDetail = {
            ...transcriptionFailedRecording.detail,
            transcriptionAttemptId: ANOTHER_TRANSCRIPTION_ATTEMPT_ID,
            transcriptionStatus: "PROCESSING",
            transcriptionFailureCode: null,
          };
          return rejectTranscriptionRetry(
            409,
            "TRANSCRIPTION_RETRY_NOT_ALLOWED",
          );
        }),
        http.get(RECORDING_REQUEST, () => HttpResponse.json(latestDetail)),
      );
      vi.spyOn(console, "error").mockImplementation(() => {});
      renderRecordingDocuments(transcriptionFailedRecording.recordingId);

      fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));

      expect(
        await screen.findByRole("heading", {
          level: 2,
          name: ORGANIZING_TITLE,
        }),
      ).toBeInTheDocument();
      vi.mocked(console.error).mockRestore();
    });

    it("전사 다시 시도가 거절된 뒤 녹음 상태를 다시 조회하는 동안에는 버튼을 다시 누를 수 없다", async () => {
      let recordingRequestCount = 0;
      let releaseRecheck = () => {};
      const recheck = new Promise<void>((resolve) => {
        releaseRecheck = resolve;
      });
      mockServer.use(
        http.post(TRANSCRIPTION_RETRY_REQUEST, () =>
          rejectTranscriptionRetry(409, "AUDIO_UPLOAD_NOT_COMPLETED"),
        ),
        http.get(RECORDING_REQUEST, async () => {
          recordingRequestCount += 1;
          // 거절 뒤의 조회(두 번째 조회)만 늦게 답해요
          if (recordingRequestCount === 2) await recheck;

          return HttpResponse.json(transcriptionFailedRecording.detail);
        }),
      );
      vi.spyOn(console, "error").mockImplementation(() => {});
      renderRecordingDocuments(transcriptionFailedRecording.recordingId);

      const retryButton = await screen.findByRole("button", {
        name: "다시 시도",
      });
      fireEvent.click(retryButton);
      await waitFor(() => expect(recordingRequestCount).toBe(2));
      // 다시 시도 요청이 끝난 화면이 그려질 시간을 줘요
      await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 50));
      });

      expect(retryButton).toBeDisabled();

      releaseRecheck();

      await expectFailedScreenWithoutRetry(TRANSCRIPTION_FAILED_TITLE);
      vi.mocked(console.error).mockRestore();
    });

    it("전사 다시 시도가 거절된 뒤 다른 곳의 다시 시도가 또 실패해 새 시도로 바뀌면, 창을 다시 볼 때 다시 시도 버튼을 다시 보여준다", async () => {
      let latestDetail: RecordingDetailResponse =
        transcriptionFailedRecording.detail;
      mockServer.use(
        http.post(TRANSCRIPTION_RETRY_REQUEST, () =>
          rejectTranscriptionRetry(409, "TRANSCRIPTION_RETRY_NOT_ALLOWED"),
        ),
        http.get(RECORDING_REQUEST, () => HttpResponse.json(latestDetail)),
      );
      vi.spyOn(console, "error").mockImplementation(() => {});
      renderRecordingDocuments(transcriptionFailedRecording.recordingId);

      fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));
      await expectFailedScreenWithoutRetry(TRANSCRIPTION_FAILED_TITLE);

      // 다른 곳에서 다시 시도한 전사가 새 시도로 또 실패했어요
      latestDetail = {
        ...transcriptionFailedRecording.detail,
        transcriptionAttemptId: ANOTHER_TRANSCRIPTION_ATTEMPT_ID,
      };
      // 실패 상태에서는 3초 조회가 멈춰 있어, 창을 다시 볼 때의 조회로 새 상태를 받아요
      act(() => {
        focusManager.setFocused(false);
        focusManager.setFocused(true);
      });

      expect(
        await screen.findByRole("button", { name: "다시 시도" }),
      ).toBeInTheDocument();
      expect(screen.getByText(RECORDING_KEPT_NOTICE)).toBeInTheDocument();
      focusManager.setFocused(undefined);
      vi.mocked(console.error).mockRestore();
    });

    it("전사 다시 시도가 거절된 뒤 다시 조회가 서버 오류로 실패해도, 같은 제목에 설명과 다시 시도 없이 홈으로만 보여준다", async () => {
      let recordingRequestCount = 0;
      mockServer.use(
        http.post(TRANSCRIPTION_RETRY_REQUEST, () =>
          rejectTranscriptionRetry(409, "AUDIO_UPLOAD_NOT_COMPLETED"),
        ),
        http.get(RECORDING_REQUEST, () => {
          recordingRequestCount += 1;
          // 거절 뒤의 조회(두 번째 조회)만 실패해요
          return recordingRequestCount === 2
            ? new HttpResponse(null, { status: 500 })
            : HttpResponse.json(transcriptionFailedRecording.detail);
        }),
      );
      vi.spyOn(console, "error").mockImplementation(() => {});
      renderRecordingDocuments(transcriptionFailedRecording.recordingId);

      fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));

      await expectFailedScreenWithoutRetry(TRANSCRIPTION_FAILED_TITLE);
      expect(recordingRequestCount).toBe(2);
      vi.mocked(console.error).mockRestore();
    });

    it("전사 다시 시도가 거절됐지만 서버가 같은 시도를 이어서 돌리는 중이었다면, 그 시도가 또 실패했을 때 다시 시도 버튼을 보여준다", async () => {
      let latestDetail: RecordingDetailResponse =
        transcriptionFailedRecording.detail;
      mockServer.use(
        http.post(TRANSCRIPTION_RETRY_REQUEST, () => {
          // 다른 곳에서 먼저 다시 시도했고, 결과를 알 수 없던 시도라 서버가 새 시도를 만들지 않고 같은 시도를 이어서 돌려요
          latestDetail = {
            ...transcriptionFailedRecording.detail,
            transcriptionStatus: "PROCESSING",
            transcriptionFailureCode: null,
          };
          return rejectTranscriptionRetry(
            409,
            "TRANSCRIPTION_RETRY_NOT_ALLOWED",
          );
        }),
        http.get(RECORDING_REQUEST, () => HttpResponse.json(latestDetail)),
      );
      vi.spyOn(console, "error").mockImplementation(() => {});
      vi.useFakeTimers({ shouldAdvanceTime: true });
      renderRecordingDocuments(transcriptionFailedRecording.recordingId);

      fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));
      await screen.findByRole("heading", { level: 2, name: ORGANIZING_TITLE });

      // 이어서 돌린 같은 시도가 또 실패했어요
      latestDetail = transcriptionFailedRecording.detail;
      await act(async () => {
        await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
      });

      expect(
        await screen.findByRole("button", { name: "다시 시도" }),
      ).toBeInTheDocument();
      expect(screen.getByText(RECORDING_KEPT_NOTICE)).toBeInTheDocument();
      vi.mocked(console.error).mockRestore();
    });

    it.each([
      { status: 500, code: "INTERNAL_SERVER_ERROR" },
      { status: 500, code: "AI_NOT_READY" },
    ])(
      "전사 다시 시도가 서버 오류로 실패하면($status $code) 오류 코드를 콘솔에 남기고 버튼을 유지해 다시 누를 수 있게 한다",
      async ({ status, code }) => {
        // 거절은 안내 없이 콘솔에만 남기므로, 테스트 출력에 섞이지 않게 막아요
        const consoleError = vi
          .spyOn(console, "error")
          .mockImplementation(() => {});
        let retryCount = 0;
        mockServer.use(
          http.post(TRANSCRIPTION_RETRY_REQUEST, () => {
            retryCount += 1;
            return rejectTranscriptionRetry(status, code);
          }),
        );
        renderRecordingDocuments(transcriptionFailedRecording.recordingId);

        fireEvent.click(
          await screen.findByRole("button", { name: "다시 시도" }),
        );

        await waitFor(() =>
          expect(consoleError).toHaveBeenCalledWith(
            expect.any(String),
            expect.objectContaining({ code }),
          ),
        );
        await waitFor(() =>
          expect(
            screen.getByRole("button", { name: "다시 시도" }),
          ).toBeEnabled(),
        );
        expect(screen.getByText(RECORDING_KEPT_NOTICE)).toBeInTheDocument();

        fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));

        await waitFor(() => expect(retryCount).toBe(2));

        consoleError.mockRestore();
      },
    );

    it("전사 다시 시도에서 로그인이 풀렸으면(401) 로그인 화면으로 보낸다", async () => {
      mockServer.use(
        http.post(
          TRANSCRIPTION_RETRY_REQUEST,
          () => new HttpResponse(null, { status: 401 }),
        ),
      );
      renderRecordingDocuments(transcriptionFailedRecording.recordingId);

      fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));

      expect(await screen.findByText("로그인 화면")).toBeInTheDocument();
    });
  });

  describe("끝낸 녹음의 오디오 저장", () => {
    // 끝냈지만 전사가 시작되기 전의 녹음이에요. PCM 조각 녹음은 이 동안 서버가 조각을 모아 저장하고, 녹음 상태만으로는 PCM인지 알 수 없어요
    const awaitingAudioSaveDetail: RecordingDetailResponse = {
      ...organizingRecording.detail,
      transcriptionAttemptId: null,
      transcriptionStatus: null,
      documentGeneration: null,
    };

    const respondAudioSave = (overrides: Partial<RecordingAudioSaveResponse>) =>
      HttpResponse.json({ ...recordingAudioSaveResponse, ...overrides });

    // 녹음 상태는 전사 전으로 두고 저장 상태 응답만 바꿔요. 두 조회의 횟수를 세어 돌려줘요
    const mockAwaitingAudioSave = (respond: () => Response) => {
      const counts = { recording: 0, audioSave: 0 };
      mockServer.use(
        http.get(RECORDING_REQUEST, () => {
          counts.recording += 1;
          return HttpResponse.json(awaitingAudioSaveDetail);
        }),
        http.get(AUDIO_SAVE_REQUEST, () => {
          counts.audioSave += 1;
          return respond();
        }),
      );

      return counts;
    };

    it.each(["DRAINING", "FINALIZING"] as const)(
      "전사가 시작되기 전에 저장 상태가 %s이면 정리 중이라고 알린다",
      async (audioSaveStatus) => {
        const counts = mockAwaitingAudioSave(() =>
          respondAudioSave({ audioSaveStatus }),
        );
        renderRecordingDocuments(organizingRecording.recordingId);

        await screen.findByRole("heading", {
          level: 2,
          name: ORGANIZING_TITLE,
        });
        await waitFor(() => expect(counts.audioSave).toBe(1));

        expect(
          screen.getByRole("heading", { level: 2, name: ORGANIZING_TITLE }),
        ).toBeInTheDocument();
        expect(screen.queryByRole("button")).not.toBeInTheDocument();
      },
    );

    it("저장된 음성이 없어 저장이 실패했으면(AUDIO_NO_CONTENT) 문서로 만들 내용이 없었다고 알린다", async () => {
      mockAwaitingAudioSave(() =>
        respondAudioSave({
          audioSaveStatus: "FAILED",
          failureCode: "AUDIO_NO_CONTENT",
          canRetry: false,
          quality: null,
        }),
      );
      renderRecordingDocuments(organizingRecording.recordingId);

      expect(
        await screen.findByRole("heading", {
          level: 2,
          name: NO_CONTENT_TITLE,
        }),
      ).toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: "홈으로" }),
      ).toBeInTheDocument();
    });

    it.each([
      {
        reason: "저장소 문제처럼 그 밖의 이유로 실패했으면",
        audioSave: {
          audioSaveStatus: "FAILED",
          failureCode: "AUDIO_STORAGE_UNAVAILABLE",
          canRetry: true,
        },
      },
      {
        reason: "폐기됐으면",
        audioSave: {
          audioSaveStatus: "DISCARDED",
          failureCode: null,
          canRetry: false,
        },
      },
    ] satisfies {
      reason: string;
      audioSave: Partial<RecordingAudioSaveResponse>;
    }[])(
      "저장이 $reason 다시 시도하지 않고 실패 이유를 콘솔에 남긴 뒤 지금 기록을 바꿔 워크스페이스 홈으로 보낸다",
      async ({ audioSave }) => {
        // 실패는 안내 없이 콘솔에만 남기므로, 테스트 출력에 섞이지 않게 막아요
        const consoleError = vi
          .spyOn(console, "error")
          .mockImplementation(() => {});
        let retryRequestCount = 0;
        mockAwaitingAudioSave(() => respondAudioSave(audioSave));
        mockServer.use(
          http.post(AUDIO_SAVE_RETRY_REQUEST, () => {
            retryRequestCount += 1;
            return HttpResponse.json(recordingAudioSaveResponse, {
              status: 202,
            });
          }),
        );
        const { router } = renderRecordingDocuments(
          organizingRecording.recordingId,
        );

        expect(await screen.findByText("워크스페이스 홈")).toBeInTheDocument();
        expect(router.state.historyAction).toBe("REPLACE");
        expect(consoleError).toHaveBeenCalledWith(
          expect.any(String),
          expect.objectContaining({ failureCode: audioSave.failureCode }),
        );
        expect(retryRequestCount).toBe(0);

        consoleError.mockRestore();
      },
    );

    it.each([
      {
        reason: "저장이 끝났으면(READY)",
        respond: () => HttpResponse.json(recordingAudioSaveResponse),
      },
      {
        reason: "WebM 녹음이라 저장 상태가 없으면(409 AUDIO_MODE_CONFLICT)",
        respond: () =>
          HttpResponse.json(
            {
              code: "AUDIO_MODE_CONFLICT",
              message: "PCM 조각 녹음이 아닙니다.",
            },
            { status: 409 },
          ),
      },
    ])(
      "$reason 저장 상태는 더 조회하지 않고 녹음 상태만 계속 조회한다",
      async ({ respond }) => {
        const counts = mockAwaitingAudioSave(respond);
        vi.useFakeTimers({ shouldAdvanceTime: true });
        renderRecordingDocuments(organizingRecording.recordingId);

        await screen.findByRole("heading", {
          level: 2,
          name: ORGANIZING_TITLE,
        });
        await waitFor(() => expect(counts.audioSave).toBe(1));

        await act(async () => {
          await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
        });

        await waitFor(() => expect(counts.recording).toBe(2));
        expect(counts.audioSave).toBe(1);
        expect(
          screen.getByRole("heading", { level: 2, name: ORGANIZING_TITLE }),
        ).toBeInTheDocument();
      },
    );

    it("전사가 시작됐으면 저장 상태를 조회하지 않는다", async () => {
      let audioSaveRequestCount = 0;
      mockServer.use(
        http.get(RECORDING_REQUEST, () =>
          HttpResponse.json({
            ...organizingRecording.detail,
            transcriptionStatus: "PROCESSING",
            documentGeneration: null,
          }),
        ),
        http.get(AUDIO_SAVE_REQUEST, () => {
          audioSaveRequestCount += 1;
          return HttpResponse.json(recordingAudioSaveResponse);
        }),
      );
      vi.useFakeTimers({ shouldAdvanceTime: true });
      renderRecordingDocuments(organizingRecording.recordingId);

      await screen.findByRole("heading", { level: 2, name: ORGANIZING_TITLE });
      await act(async () => {
        await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
      });

      expect(audioSaveRequestCount).toBe(0);
    });

    it.each([
      ["서버 오류(503)", () => new HttpResponse(null, { status: 503 })],
      ["네트워크 오류", () => HttpResponse.error()],
    ])(
      "저장 상태 조회가 %s로 실패하면 정리 중 대신 그 자리에 확인하지 못했다는 안내와 다시 시도를 두고, 같은 간격으로 계속 조회한다",
      async (_, respond) => {
        const counts = mockAwaitingAudioSave(respond);
        vi.useFakeTimers({ shouldAdvanceTime: true });
        renderRecordingDocuments(organizingRecording.recordingId);

        expect(await screen.findByRole("alert")).toHaveTextContent(
          AUDIO_SAVE_CHECK_FAILED_NOTICE,
        );
        expect(
          screen.getByRole("button", { name: "다시 시도" }),
        ).toBeInTheDocument();
        expect(
          screen.queryByRole("heading", { level: 2, name: ORGANIZING_TITLE }),
        ).not.toBeInTheDocument();
        expect(counts.audioSave).toBe(1);

        await act(async () => {
          await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
        });

        await waitFor(() => expect(counts.audioSave).toBe(2));
        expect(screen.queryByText("워크스페이스 홈")).not.toBeInTheDocument();
      },
    );

    it("저장 상태를 다시 받으면 안내를 거두고 정리 중으로 돌아간다", async () => {
      let isAudioSaveDown = true;
      mockAwaitingAudioSave(() =>
        isAudioSaveDown
          ? new HttpResponse(null, { status: 503 })
          : respondAudioSave({ audioSaveStatus: "DRAINING" }),
      );
      vi.useFakeTimers({ shouldAdvanceTime: true });
      renderRecordingDocuments(organizingRecording.recordingId);
      await screen.findByRole("alert");

      isAudioSaveDown = false;
      await act(async () => {
        await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
      });

      expect(
        await screen.findByRole("heading", { level: 2, name: ORGANIZING_TITLE }),
      ).toBeInTheDocument();
      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    });

    it("안내의 다시 시도를 누르면 저장 상태만 바로 다시 조회한다", async () => {
      let isAudioSaveDown = true;
      const counts = mockAwaitingAudioSave(() =>
        isAudioSaveDown
          ? new HttpResponse(null, { status: 503 })
          : respondAudioSave({ audioSaveStatus: "DRAINING" }),
      );
      renderRecordingDocuments(organizingRecording.recordingId);
      await screen.findByRole("alert");
      const recordingCountBeforeRetry = counts.recording;

      isAudioSaveDown = false;
      fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));

      expect(
        await screen.findByRole("heading", { level: 2, name: ORGANIZING_TITLE }),
      ).toBeInTheDocument();
      expect(counts.audioSave).toBe(2);
      expect(counts.recording).toBe(recordingCountBeforeRetry);
    });

    it("WebM 녹음이라 저장 상태가 없으면(409) 안내 없이 정리 중을 유지한다", async () => {
      const counts = mockAwaitingAudioSave(() =>
        HttpResponse.json(
          { code: "AUDIO_MODE_CONFLICT", message: "PCM 조각 녹음이 아닙니다." },
          { status: 409 },
        ),
      );
      renderRecordingDocuments(organizingRecording.recordingId);

      await screen.findByRole("heading", { level: 2, name: ORGANIZING_TITLE });
      await waitFor(() => expect(counts.audioSave).toBe(1));

      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
      expect(
        screen.getByRole("heading", { level: 2, name: ORGANIZING_TITLE }),
      ).toBeInTheDocument();
    });
  });
});

// 작업마다 보낸 다시 시도의 실패는 결과값으로 바뀌어 뮤테이션이 성공으로 끝나므로, react-query가 보고하지 않아요
describe("RecordingDocuments의 다시 시도 실패 보고", () => {
  const { flushEvents } = setupSentryEvents();

  it("다시 시도가 서버 오류로 실패하면 버튼은 지금처럼 남기고 그 요청 오류를 한 번 보고한다", async () => {
    let retryCount = 0;
    mockServer.use(
      http.post(RETRY_REQUEST, () => {
        retryCount += 1;
        return new HttpResponse(null, { status: 500 });
      }),
    );
    renderRecordingDocuments(failedRecording.recordingId);

    fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));
    await waitFor(() => expect(retryCount).toBe(1));

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "다시 시도" })).toBeEnabled(),
    );
    const events = await flushEvents();
    expect(events).toHaveLength(1);
    expect(events[0].tags).toMatchObject({ feature: "document" });
    expect(events[0].fingerprint).toEqual([
      "POST",
      "/api/v1/workspaces/:id/document-generation-jobs/:id/retry",
      "500",
    ]);
  });

  it("한 작업은 409로 거절되고 다른 작업은 서버 오류로 실패하면 서버 오류만 보고한다", async () => {
    let retryCount = 0;
    mockServer.use(
      http.post(RETRY_REQUEST, ({ params }) => {
        retryCount += 1;

        return new HttpResponse(null, {
          status: Number(params.jobId) === ANOTHER_JOB_ID ? 500 : 409,
        });
      }),
      http.get(RECORDING_REQUEST, () => HttpResponse.json(twoJobsFailedDetail)),
    );
    renderRecordingDocuments(failedRecording.recordingId);

    fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));
    await waitFor(() => expect(retryCount).toBe(2));

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "다시 시도" })).toBeEnabled(),
    );
    const events = await flushEvents();
    expect(events).toHaveLength(1);
    expect(events[0].fingerprint).toContain("500");
  });

  // 다시 시도 횟수를 다 썼거나 이미 처리된 작업이라, 화면이 다시 시도 없이 홈으로만 보여줘요
  it("다시 시도가 409로 거절되면 보고하지 않는다", async () => {
    mockServer.use(
      http.post(RETRY_REQUEST, () => new HttpResponse(null, { status: 409 })),
    );
    renderRecordingDocuments(failedRecording.recordingId);

    fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));

    await expectFailedScreenWithoutRetry();
    expect(await flushEvents()).toHaveLength(0);
  });
});

describe("RecordingDocuments의 정리 결과 기록", () => {
  // 앞 describe의 테스트에서도 정리 결과가 기록되므로, 테스트마다 기록을 비우고 시작해요
  beforeEach(() => {
    vi.mocked(captureAnalyticsEvent).mockClear();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  // 녹음 조회에 차례로 응답해요. 마지막 응답은 그 뒤로도 계속 돌려줘요
  const respondRecordingInOrder = (...details: RecordingDetailResponse[]) => {
    let requestCount = 0;
    mockServer.use(
      http.get(RECORDING_REQUEST, () => {
        const detail = details[Math.min(requestCount, details.length - 1)];
        requestCount += 1;
        return HttpResponse.json(detail);
      }),
    );
  };

  const waitNextPoll = async () => {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    });
  };

  const recordingTarget = {
    recording_id: organizingRecording.recordingId,
    workspace_id: WORKSPACE_ID,
  };

  it.each([
    {
      reason: "문서가 만들어지면 완료와 만들어진 문서 수를",
      detail: completedRecording.detail,
      properties: {
        result: "completed",
        document_count: new GetRecordingResponseDto(completedRecording.detail)
          .documentGeneration?.succeededCount,
      },
    },
    {
      reason: "문서로 만들 내용이 없으면 내용 없음을",
      detail: noContentRecording.detail,
      properties: { result: "no_content" },
    },
    {
      reason: "문서 만들기에 실패하면 문서 만들기 단계의 실패를",
      detail: failedRecording.detail,
      properties: { result: "failed", failure_stage: "document_generation" },
    },
    {
      reason: "전사에 실패하면 전사 단계의 실패를",
      detail: transcriptionFailedRecording.detail,
      properties: { result: "failed", failure_stage: "transcription" },
    },
  ])(
    "정리 중을 보던 중에 $reason 한 번 보낸다",
    async ({ detail, properties }) => {
      respondRecordingInOrder(organizingRecording.detail, detail);
      vi.useFakeTimers({ shouldAdvanceTime: true });
      renderRecordingDocuments(organizingRecording.recordingId);

      await screen.findByRole("heading", { level: 2, name: ORGANIZING_TITLE });
      expect(captureAnalyticsEvent).not.toHaveBeenCalled();

      await waitNextPoll();

      await waitFor(() =>
        expect(captureAnalyticsEvent).toHaveBeenCalledTimes(1),
      );
      expect(captureAnalyticsEvent).toHaveBeenCalledWith({
        event: "recording_documents_result",
        properties: { ...properties, ...recordingTarget },
      });
    },
  );

  it.each([
    {
      reason: "정리가 끝난",
      recording: completedRecording,
      text: "녹음 문서 화면",
    },
    {
      reason: "문서 만들기에 실패한",
      recording: failedRecording,
      text: FAILED_TITLE,
    },
    {
      reason: "내용이 없던",
      recording: noContentRecording,
      text: NO_CONTENT_TITLE,
    },
  ])("이미 $reason 녹음을 열면 보내지 않는다", async ({ recording, text }) => {
    renderRecordingDocuments(recording.recordingId);

    expect(await screen.findByText(text)).toBeInTheDocument();
    expect(captureAnalyticsEvent).not.toHaveBeenCalled();
  });

  it("실패 화면에서 다시 시도해 정리 중을 다시 본 뒤 정리가 끝나면 그 결과를 보낸다", async () => {
    respondRecordingInOrder(
      failedRecording.detail,
      organizingRecording.detail,
      completedRecording.detail,
    );
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderRecordingDocuments(organizingRecording.recordingId);

    // 다시 시도 결과도 함께 기록되므로, 정리 결과만 골라 봐요
    const getResultEvents = () =>
      vi
        .mocked(captureAnalyticsEvent)
        .mock.calls.map(([event]) => event)
        .filter(({ event }) => event === "recording_documents_result");

    fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));
    await screen.findByRole("heading", { level: 2, name: ORGANIZING_TITLE });
    expect(getResultEvents()).toHaveLength(0);

    await waitNextPoll();

    await waitFor(() => expect(getResultEvents()).toHaveLength(1));
    expect(getResultEvents()[0]).toEqual({
      event: "recording_documents_result",
      properties: expect.objectContaining({ result: "completed" }),
    });
  });

  it("끝낸 녹음의 오디오 저장이 끝내 실패하면 저장 실패를 보낸다", async () => {
    // 저장 실패는 안내 없이 콘솔에만 남기므로, 테스트 출력에 섞이지 않게 막아요
    vi.spyOn(console, "error").mockImplementation(() => {});
    // 끝냈지만 전사가 시작되기 전이라 저장 상태를 함께 조회하는 녹음이에요
    respondRecordingInOrder({
      ...organizingRecording.detail,
      transcriptionAttemptId: null,
      transcriptionStatus: null,
      documentGeneration: null,
    });
    mockServer.use(
      http.get(AUDIO_SAVE_REQUEST, () =>
        HttpResponse.json({
          ...recordingAudioSaveResponse,
          audioSaveStatus: "FAILED",
          failureCode: "AUDIO_STORAGE_UNAVAILABLE",
        }),
      ),
    );

    renderRecordingDocuments(organizingRecording.recordingId);

    expect(await screen.findByText("워크스페이스 홈")).toBeInTheDocument();
    expect(captureAnalyticsEvent).toHaveBeenCalledTimes(1);
    expect(captureAnalyticsEvent).toHaveBeenCalledWith({
      event: "recording_documents_result",
      properties: { result: "audio_save_failed", ...recordingTarget },
    });
  });
});

describe("RecordingDocuments의 다시 시도 결과 기록", () => {
  // 앞 describe의 테스트에서도 사용 기록이 남으므로, 테스트마다 기록을 비우고 시작해요
  beforeEach(() => {
    vi.mocked(captureAnalyticsEvent).mockClear();
    // 전사 다시 시도의 거절 · 실패는 콘솔에 남기므로, 테스트 출력에 섞이지 않게 막아요
    vi.spyOn(console, "error").mockImplementation(() => {});
  });

  afterEach(() => {
    vi.mocked(console.error).mockRestore();
  });

  // 정리 결과도 함께 기록될 수 있어서, 다시 시도 결과만 골라 봐요
  const getRetryEvents = () =>
    vi
      .mocked(captureAnalyticsEvent)
      .mock.calls.map(([event]) => event)
      .filter(({ event }) => event === "recording_documents_retry");

  const reject = (status: number) => () => new HttpResponse(null, { status });

  describe("문서 만들기", () => {
    // 작업 id마다 다시 시도 응답을 정해요
    const respondRetryByJob = (responses: Record<number, () => Response>) =>
      mockServer.use(
        http.post(RETRY_REQUEST, ({ params }) =>
          responses[Number(params.jobId)](),
        ),
      );

    it.each([
      {
        reason: "접수되면 접수를",
        detail: failedRecording.detail,
        responses: { [FAILED_JOB_ID]: () => acceptRetry(FAILED_JOB_ID) },
        result: "accepted",
      },
      {
        reason: "409로 거절되면 거절을",
        detail: failedRecording.detail,
        responses: { [FAILED_JOB_ID]: reject(409) },
        result: "rejected",
      },
      {
        reason: "404로 거절되면 거절을",
        detail: failedRecording.detail,
        responses: { [FAILED_JOB_ID]: reject(404) },
        result: "rejected",
      },
      {
        reason: "서버 오류로 실패하면 오류를",
        detail: failedRecording.detail,
        responses: { [FAILED_JOB_ID]: reject(500) },
        result: "error",
      },
      {
        reason: "네트워크 오류로 실패하면 오류를",
        detail: failedRecording.detail,
        responses: { [FAILED_JOB_ID]: () => HttpResponse.error() },
        result: "error",
      },
      {
        reason: "작업 가운데 하나라도 접수되면 접수를",
        detail: twoJobsFailedDetail,
        responses: {
          [FAILED_JOB_ID]: () => acceptRetry(FAILED_JOB_ID),
          [ANOTHER_JOB_ID]: reject(409),
        },
        result: "accepted",
      },
      {
        reason: "작업이 모두 거절되면 거절을",
        detail: twoJobsFailedDetail,
        responses: {
          [FAILED_JOB_ID]: reject(409),
          [ANOTHER_JOB_ID]: reject(404),
        },
        result: "rejected",
      },
      {
        reason: "접수된 작업 없이 거절과 서버 오류가 섞이면 오류를",
        detail: twoJobsFailedDetail,
        responses: {
          [FAILED_JOB_ID]: reject(409),
          [ANOTHER_JOB_ID]: reject(500),
        },
        result: "error",
      },
    ] satisfies {
      reason: string;
      detail: RecordingDetailResponse;
      responses: Record<number, () => Response>;
      result: string;
    }[])(
      "다시 시도가 $reason 한 번 보낸다",
      async ({ detail, responses, result }) => {
        mockServer.use(
          http.get(RECORDING_REQUEST, () => HttpResponse.json(detail)),
        );
        respondRetryByJob(responses);
        renderRecordingDocuments(failedRecording.recordingId);

        fireEvent.click(
          await screen.findByRole("button", { name: "다시 시도" }),
        );

        await waitFor(() => expect(getRetryEvents()).toHaveLength(1));
        expect(getRetryEvents()[0]).toEqual({
          event: "recording_documents_retry",
          properties: {
            stage: "document_generation",
            result,
            recording_id: failedRecording.recordingId,
            workspace_id: WORKSPACE_ID,
          },
        });
      },
    );

    it("다시 시도에서 로그인이 풀렸으면(401) 보내지 않는다", async () => {
      respondRetryByJob({ [FAILED_JOB_ID]: reject(401) });
      renderRecordingDocuments(failedRecording.recordingId);

      fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));

      expect(await screen.findByText("로그인 화면")).toBeInTheDocument();
      expect(getRetryEvents()).toHaveLength(0);
    });
  });

  describe("전사", () => {
    it.each([
      { reason: "접수되면 접수를", respond: undefined, result: "accepted" },
      {
        reason: "409로 거절되면 거절을",
        respond: reject(409),
        result: "rejected",
      },
      {
        reason: "404로 거절되면 거절을",
        respond: reject(404),
        result: "rejected",
      },
      {
        reason: "서버 오류로 실패하면 오류를",
        respond: reject(500),
        result: "error",
      },
      {
        reason: "네트워크 오류로 실패하면 오류를",
        respond: () => HttpResponse.error(),
        result: "error",
      },
    ])("다시 시도가 $reason 한 번 보낸다", async ({ respond, result }) => {
      // 접수는 기본 mock 응답을 써요
      if (respond) {
        mockServer.use(http.post(TRANSCRIPTION_RETRY_REQUEST, respond));
      }
      renderRecordingDocuments(transcriptionFailedRecording.recordingId);

      fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));

      await waitFor(() => expect(getRetryEvents()).toHaveLength(1));
      expect(getRetryEvents()[0]).toEqual({
        event: "recording_documents_retry",
        properties: {
          stage: "transcription",
          result,
          recording_id: transcriptionFailedRecording.recordingId,
          workspace_id: WORKSPACE_ID,
        },
      });
    });

    it("다시 시도에서 로그인이 풀렸으면(401) 보내지 않는다", async () => {
      mockServer.use(http.post(TRANSCRIPTION_RETRY_REQUEST, reject(401)));
      renderRecordingDocuments(transcriptionFailedRecording.recordingId);

      fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));

      expect(await screen.findByText("로그인 화면")).toBeInTheDocument();
      expect(getRetryEvents()).toHaveLength(0);
    });
  });
});
