import { GetDocumentsResponseDto } from "@api/dto/document";
import { RECORDING_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]";
import { documentsResponse } from "@api/mock/responses/document";
import { documentGenerationJobRetryResponse } from "@api/mock/responses/documentGenerationJob";
import { recordingDetailsResponse } from "@api/mock/responses/recording";
import type {
  RecordingDetailResponse,
  RecordingDocumentGenerationResponse,
} from "@api/mock/types/recording";
import { mockServer } from "@api/mock/server";
import {
  DOCUMENT_FROM_PARAM,
  DOCUMENT_FROM_RECORDING,
} from "@constants/document";
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
} from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { createMemoryRouter, RouterProvider } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";

import RecordingDocuments from ".";

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
const DOCUMENTS_REQUEST = "*/api/v1/workspaces/:workspaceId/documents";
const POLL_INTERVAL_MS = 3000;
// mock 녹음의 어느 작업과도 겹치지 않는 문서 생성 작업 id
const ANOTHER_JOB_ID = 188;
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
const NO_CONTENT_TITLE = "문서로 만들 내용이 없었어요";
const LOAD_FAILED_TITLE = "문서를 불러오지 못했어요";
const RECORDING_KEPT_NOTICE = "녹음은 보관해 두었어요.";

// 정리가 끝난 mock 녹음에서 나온 문서. 서버가 준 순서 그대로라 첫 문서가 정리 뒤에 보내는 문서예요
const completedRecordingDocumentItems = documentsResponse.items.filter(
  ({ recordingSessionId }) =>
    recordingSessionId === completedRecording.recordingId,
);
const [firstCompletedRecordingDocument] = new GetDocumentsResponseDto({
  ...documentsResponse,
  items: completedRecordingDocumentItems,
}).items;

const getDocumentPath = (documentId: number) =>
  getRouterPath({
    routeKey: "DOCUMENT",
    params: {
      workspaceId: String(WORKSPACE_ID),
      documentId: String(documentId),
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
  // 이동을 확인할 수 있게 홈 · 녹음 · 문서 · 로그인 경로에 표시만 하는 화면을 둬요
  const router = createMemoryRouter(
    [
      {
        path: PATH_ROUTE.RECORDING_DOCUMENTS,
        element: <RecordingDocuments />,
      },
      { path: PATH_ROUTE.WORKSPACE_HOME, element: <p>워크스페이스 홈</p> },
      { path: PATH_ROUTE.RECORDING, element: <p>녹음 화면</p> },
      { path: PATH_ROUTE.DOCUMENT, element: <p>문서 화면</p> },
      { path: PATH_ROUTE.LOGIN, element: <p>로그인 화면</p> },
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
const expectFailedScreenWithoutRetry = async () => {
  expect(
    await screen.findByRole("button", { name: "홈으로" }),
  ).toBeInTheDocument();
  expect(
    screen.getByRole("heading", { level: 2, name: FAILED_TITLE }),
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
    renderRecordingDocuments(failedRecording.recordingId);

    fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));

    // 기본 mock은 다시 시도를 접수하면 그 녹음의 상태를 정리 중으로 바꿔 돌려줘요
    expect(
      await screen.findByRole("heading", { level: 2, name: ORGANIZING_TITLE }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it.each([
    { reason: "전사에 실패", recording: transcriptionFailedRecording },
    {
      reason: "문서 만들기에 실패했지만 다시 시도할 작업이 없음",
      recording: {
        ...failedRecording,
        detail: {
          ...failedRecording.detail,
          // 실패 작업의 보존 기한이 지나면 서버는 실패 결과만 남기고 작업 ID를 비워요
          documentGeneration: {
            ...failedRecording.detail.documentGeneration,
            failedJobIds: [],
          },
        },
      },
    },
  ])(
    "$reason: 설명과 다시 시도 없이 홈으로만 보여준다",
    async ({ recording }) => {
      mockServer.use(
        http.get(
          `*${RECORDING_API_PATH(WORKSPACE_ID, recording.recordingId)}`,
          () => HttpResponse.json(recording.detail),
        ),
      );
      renderRecordingDocuments(recording.recordingId);

      await expectFailedScreenWithoutRetry();

      fireEvent.click(screen.getByRole("button", { name: "홈으로" }));

      expect(await screen.findByText("워크스페이스 홈")).toBeInTheDocument();
    },
  );

  it("문서가 일부만 만들어진 실패도 문서로 보내지 않고, 실패했다고 알리며 다시 시도 버튼을 보여준다", async () => {
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
    expect(screen.queryByText("문서 화면")).not.toBeInTheDocument();
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

  it("문서 정리가 끝났으면 그 녹음의 첫 문서로 보내고, 뒤로 가도 정리 화면으로 돌아오지 않게 지금 기록을 바꾼다", async () => {
    const { router } = renderRecordingDocuments(completedRecording.recordingId);

    expect(await screen.findByText("문서 화면")).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(
      getDocumentPath(firstCompletedRecordingDocument.id),
    );
    expect(router.state.historyAction).toBe("REPLACE");
  });

  it("첫 문서로 보낼 때 정리 화면에서 넘어왔다는 쿼리를 붙여, 문서 보기가 같은 녹음의 문서를 넘기는 스테퍼를 보여주게 한다", async () => {
    const { router } = renderRecordingDocuments(completedRecording.recordingId);

    await screen.findByText("문서 화면");

    expect(
      new URLSearchParams(router.state.location.search).get(
        DOCUMENT_FROM_PARAM,
      ),
    ).toBe(DOCUMENT_FROM_RECORDING);
  });

  it("정리 중에는 문서 목록을 조회하지 않고, 정리가 끝나면 조회해 첫 문서로 보낸다", async () => {
    let recordingRequestCount = 0;
    let documentsRequestCount = 0;
    mockServer.use(
      http.get(RECORDING_REQUEST, () => {
        recordingRequestCount += 1;
        // 두 번째 조회부터는 정리가 끝났다는 결과를 돌려줘요
        return HttpResponse.json(
          recordingRequestCount === 1
            ? organizingRecording.detail
            : completedRecording.detail,
        );
      }),
      http.get(DOCUMENTS_REQUEST, () => {
        documentsRequestCount += 1;
        return HttpResponse.json({
          ...documentsResponse,
          items: completedRecordingDocumentItems,
        });
      }),
    );
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const { router } = renderRecordingDocuments(completedRecording.recordingId);

    await screen.findByRole("heading", { level: 2, name: ORGANIZING_TITLE });
    expect(documentsRequestCount).toBe(0);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    });

    expect(await screen.findByText("문서 화면")).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(
      getDocumentPath(firstCompletedRecordingDocument.id),
    );
    expect(documentsRequestCount).toBe(1);
  });

  it.each([
    {
      reason: "그 녹음에서 나온 문서가 없으면",
      documentsResponder: () =>
        HttpResponse.json({ ...documentsResponse, topics: [], items: [] }),
    },
    {
      reason: "문서 목록을 불러오지 못하면",
      documentsResponder: () => new HttpResponse(null, { status: 500 }),
    },
  ])(
    "문서 정리가 끝났는데 $reason 지금 기록을 바꿔 워크스페이스 홈으로 보낸다",
    async ({ documentsResponder }) => {
      mockServer.use(http.get(DOCUMENTS_REQUEST, documentsResponder));
      const { router } = renderRecordingDocuments(
        completedRecording.recordingId,
      );

      expect(await screen.findByText("워크스페이스 홈")).toBeInTheDocument();
      expect(router.state.historyAction).toBe("REPLACE");
    },
  );

  it("문서 정리가 끝난 뒤 문서 목록 조회에서 로그인이 풀렸으면(401) 로그인 화면으로 보낸다", async () => {
    mockServer.use(
      http.get(
        DOCUMENTS_REQUEST,
        () => new HttpResponse(null, { status: 401 }),
      ),
    );
    renderRecordingDocuments(completedRecording.recordingId);

    expect(await screen.findByText("로그인 화면")).toBeInTheDocument();
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
      condition: "이미 정리가 끝났으면 그 녹음의 문서로 보낸다",
      latestRecording: completedRecording,
      expectedScreen: () => screen.findByText("문서 화면"),
    },
  ])(
    "다시 시도가 409로 거절되면 녹음 상태를 다시 조회하고, 서버에서 $condition",
    async ({ latestRecording, expectedScreen }) => {
      // 문서 mock이 있는 녹음(정리가 끝난 mock 녹음과 같은 id)에서 다시 시도가 거절된 상황이에요
      const { recordingId } = completedRecording;
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
});
