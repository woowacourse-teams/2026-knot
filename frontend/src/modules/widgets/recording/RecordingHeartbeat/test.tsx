import { PostRecordingHeartbeatResponseDto } from "@api/dto/recording";
import { WORKSPACES_API_PATH } from "@api/fetch/api/v1/workspaces";
import { recordingHeartbeatResponse } from "@api/mock/responses/recording";
import {
  workspaceDetailResponse,
  workspacesResponse,
} from "@api/mock/responses/workspace";
import { mockServer } from "@api/mock/server";
import { MAX_RECORDING_DURATION_MS } from "@constants/recording";
import { TOAST_MESSAGES } from "@constants/toastMessages";
import { ThemeProvider } from "@emotion/react";
import WorkspaceLayout from "@pages/_layout/WorkspaceLayout";
import AppProviders from "@provider/AppProviders";
import { DialogProvider } from "@provider/context/dialogContext";
import { ToastProvider } from "@provider/context/toastContext";
import { setupSentryEvents } from "@provider/sentry/mock";
import { theme } from "@provider/themeProvider";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import { routes } from "@routes/routes";
import { useRecordingStore } from "@store/recordingStore";
import {
  onlineManager,
  QueryClient,
  QueryClientProvider,
} from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import {
  clearRecordingStartProof,
  getRecordingControlProof,
  getRecordingStartProof,
} from "@utils/recordingControlProof";
import { http, HttpResponse, type HttpResponseResolver } from "msw";
import { createMemoryRouter, Outlet, RouterProvider } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import RecordingHeartbeat from ".";

const HEARTBEAT_INTERVAL_MS = 10 * 1000;

const WORKSPACE_ID = "1";
const RECORDING_PATH = getRouterPath({
  routeKey: "RECORDING",
  params: { workspaceId: WORKSPACE_ID },
});
const HOME_PATH = getRouterPath({
  routeKey: "WORKSPACE_HOME",
  params: { workspaceId: WORKSPACE_ID },
});

const expected = new PostRecordingHeartbeatResponseDto(
  recordingHeartbeatResponse,
);

/** 녹음은 레이아웃 안의 어느 화면에서나 이어지므로, 라우트에서처럼 화면들을 감싸 둬요. */
const renderRecordingHeartbeat = () => {
  const router = createMemoryRouter(
    [
      {
        element: (
          <ToastProvider>
            <RecordingHeartbeat>
              <Outlet />
            </RecordingHeartbeat>
          </ToastProvider>
        ),
        children: [
          { path: PATH_ROUTE.WORKSPACE_HOME, element: <p>홈 화면</p> },
          { path: PATH_ROUTE.RECORDING, element: <p>녹음 화면</p> },
        ],
      },
    ],
    { initialEntries: [RECORDING_PATH] },
  );

  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });

  render(
    <ThemeProvider theme={theme}>
      <QueryClientProvider client={queryClient}>
        <DialogProvider>
          <RouterProvider router={router} />
        </DialogProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  );

  return { router };
};

/** 녹음은 독에서 증명을 만들어 시작하므로, 같은 순서로 미리 시작해 둬요. */
const startRecording = async (recordingId = expected.recordingId) => {
  getRecordingStartProof();

  await act(async () => {
    await useRecordingStore.getState().connectMicrophone();
    useRecordingStore.getState().startRecording({
      workspaceId: Number(WORKSPACE_ID),
      recordingId,
    });
  });
};

const passHeartbeatInterval = () => {
  act(() => {
    vi.advanceTimersByTime(HEARTBEAT_INTERVAL_MS);
  });
};

/** 2시간을 기다리지 않도록, 서버 시간으로 맞출 때처럼 녹음한 시간을 상한까지 `remainingMs`만 남겨 둬요 */
const leaveUntilMaxDuration = (remainingMs: number) => {
  act(() =>
    useRecordingStore
      .getState()
      .syncElapsedTime(MAX_RECORDING_DURATION_MS - remainingMs),
  );
};

/** 남은 시간이 지나고 응답까지 처리할 틈을 줘요. 더 나간 요청이 없는지 볼 때 써요 */
const waitPastMaxDuration = () =>
  act(() => new Promise((resolve) => setTimeout(resolve, 150)));

const respondHeartbeat = (resolver: HttpResponseResolver) => {
  mockServer.use(
    http.post(
      "*/api/v1/workspaces/:workspaceId/recordings/:recordingId/heartbeat",
      resolver,
    ),
  );
};

const respondHeartbeatWithError = (status: number, code: string) => {
  // 실패는 안내 없이 콘솔에만 남기므로, 테스트 출력에 섞이지 않게 막아요
  vi.spyOn(console, "error").mockImplementation(() => {});
  respondHeartbeat(() => HttpResponse.json({ code }, { status }));
};

/** 응답을 테스트가 풀어 줄 때까지 붙잡아 둬요. 그사이 녹음이 바뀐 뒤 도착하는 늦은 응답을 만들어요 */
const holdHeartbeat = (body: object) => {
  let release = () => {};
  respondHeartbeat(async () => {
    await new Promise<void>((resolve) => {
      release = resolve;
    });

    return HttpResponse.json(body);
  });

  return { release: () => act(async () => release()) };
};

/** 보낸 요청을 경로의 마지막 마디로 순서대로 모아요 */
const collectRequests = () => {
  const requests: string[] = [];
  mockServer.events.on("request:start", ({ request }) => {
    requests.push(new URL(request.url).pathname.split("/").pop() ?? "");
  });

  return requests;
};

const UPLOAD_REQUESTS = ["audio-upload-url", "audio", "audio-upload-complete"];

describe("RecordingHeartbeat", () => {
  beforeEach(() => {
    // 요청은 실제 시간으로 흘러야 해서 신호 주기만 가짜로 돌려요.
    // waitFor도 setInterval로 다시 확인하므로, 가짜 시계가 실제 시간만큼은 따라 흐르게 해요
    vi.useFakeTimers({
      toFake: ["setInterval", "clearInterval"],
      shouldAdvanceTime: true,
    });
  });

  afterEach(() => {
    // 전역 저장소라 테스트끼리 녹음이 새지 않도록 처음 상태로 되돌려요
    useRecordingStore.getState().discardRecording();
    clearRecordingStartProof();
    mockServer.events.removeAllListeners();
    // 연결 상태는 TanStack Query 전역 값이라, 오프라인으로 끝난 테스트가 다음 테스트의 요청을 멈추지 않게 되돌려요
    onlineManager.setOnline(true);
    vi.useRealTimers();
  });

  it("10초마다 생존 신호를 보내고, 녹음한 시간을 서버의 누적 시간으로 맞춘다", async () => {
    await startRecording();
    renderRecordingHeartbeat();

    expect(screen.getByText("녹음 화면")).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(HEARTBEAT_INTERVAL_MS - 1);
    });
    expect(useRecordingStore.getState().accumulatedMs).toBe(0);

    act(() => {
      vi.advanceTimersByTime(1);
    });

    await waitFor(() =>
      expect(useRecordingStore.getState().accumulatedMs).toBe(
        expected.elapsedMillis,
      ),
    );
    expect(useRecordingStore.getState().status).toBe("recording");
  });

  it("워크스페이스 밖 화면(워크스페이스 만들기)에서도 10초마다 생존 신호를 보낸다 (STT-R5)", async () => {
    const requests = collectRequests();
    await startRecording();
    // 앱의 실제 라우트로 그려, 생존 신호가 워크스페이스 레이아웃 밖 화면까지 감싸는지 봐요
    const router = createMemoryRouter(
      [
        {
          element: (
            <AppProviders routes={routes}>
              <Outlet />
            </AppProviders>
          ),
          children: routes,
        },
      ],
      { initialEntries: [PATH_ROUTE.WORKSPACE_CREATE] },
    );
    render(
      <ThemeProvider theme={theme}>
        <QueryClientProvider
          client={
            new QueryClient({
              defaultOptions: {
                queries: { retry: false },
                mutations: { retry: false },
              },
            })
          }
        >
          <RouterProvider router={router} />
        </QueryClientProvider>
      </ThemeProvider>,
    );
    const heartbeats = () => requests.filter((path) => path === "heartbeat");

    passHeartbeatInterval();

    await waitFor(() => expect(heartbeats()).toEqual(["heartbeat"]));
    expect(router.state.location.pathname).toBe(PATH_ROUTE.WORKSPACE_CREATE);
  });

  it.each([
    [
      "연결이 돌아오면(online)",
      () => window.dispatchEvent(new Event("online")),
    ],
    [
      "탭이 다시 보이면(visibilitychange)",
      () => document.dispatchEvent(new Event("visibilitychange")),
    ],
  ])(
    "%s 10초 주기를 기다리지 않고 생존 신호를 한 번 보낸다",
    async (_, occur) => {
      const requests = collectRequests();
      await startRecording();
      renderRecordingHeartbeat();

      act(occur);

      await waitFor(() => expect(requests).toEqual(["heartbeat"]));
    },
  );

  it("탭이 가려질 때는 생존 신호를 바로 보내지 않는다", async () => {
    vi.spyOn(document, "visibilityState", "get").mockReturnValue("hidden");
    const requests = collectRequests();
    await startRecording();
    renderRecordingHeartbeat();

    act(() => {
      document.dispatchEvent(new Event("visibilitychange"));
    });

    await act(() => new Promise((resolve) => setTimeout(resolve, 50)));
    expect(requests).toEqual([]);
  });

  it("녹음 중이 아니면 연결이 돌아와도 생존 신호를 보내지 않는다", async () => {
    const requests = collectRequests();
    renderRecordingHeartbeat();

    act(() => {
      window.dispatchEvent(new Event("online"));
    });

    await act(() => new Promise((resolve) => setTimeout(resolve, 50)));
    expect(requests).toEqual([]);
  });

  it("연결이 돌아오는 일과 탭이 다시 보이는 일이 겹쳐도, 응답을 기다리는 동안에는 생존 신호를 한 번만 보낸다", async () => {
    const heartbeat = holdHeartbeat(recordingHeartbeatResponse);
    const requests = collectRequests();
    await startRecording();
    renderRecordingHeartbeat();

    act(() => {
      window.dispatchEvent(new Event("online"));
      document.dispatchEvent(new Event("visibilitychange"));
    });
    await waitFor(() => expect(requests).toEqual(["heartbeat"]));
    await heartbeat.release();

    await act(() => new Promise((resolve) => setTimeout(resolve, 50)));
    expect(requests).toEqual(["heartbeat"]);

    // 응답을 받은 뒤에는 다시 바로 보내요
    act(() => {
      window.dispatchEvent(new Event("online"));
    });
    await waitFor(() => expect(requests).toEqual(["heartbeat", "heartbeat"]));
  });

  it("오프라인 동안 실패한 신호를 멈춰 쌓아 두지 않아, 연결이 돌아와도 한꺼번에 다시 보내지 않는다", async () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    respondHeartbeat(() => HttpResponse.error());
    const requests = collectRequests();
    await startRecording();
    renderRecordingHeartbeat();

    act(() => {
      window.dispatchEvent(new Event("offline"));
    });
    passHeartbeatInterval();
    await waitFor(() => expect(requests).toEqual(["heartbeat"]));
    passHeartbeatInterval();
    await waitFor(() => expect(requests).toEqual(["heartbeat", "heartbeat"]));

    respondHeartbeat(() => HttpResponse.json(recordingHeartbeatResponse));
    act(() => {
      window.dispatchEvent(new Event("online"));
    });

    // 오프라인 동안의 두 신호는 이미 실패로 끝나, 연결이 돌아온 뒤에는 바로 보내는 한 번만 나가요
    await waitFor(() =>
      expect(requests).toEqual(["heartbeat", "heartbeat", "heartbeat"]),
    );
    await act(() => new Promise((resolve) => setTimeout(resolve, 50)));
    expect(requests).toEqual(["heartbeat", "heartbeat", "heartbeat"]);
    expect(useRecordingStore.getState().status).toBe("recording");
  });

  it("일시정지 중에도 생존 신호를 보낸다", async () => {
    const requests = collectRequests();
    await startRecording();
    await act(() => useRecordingStore.getState().pauseRecording());
    renderRecordingHeartbeat();

    passHeartbeatInterval();

    await waitFor(() => expect(requests).toEqual(["heartbeat"]));
  });

  it("끝내는 중에는 생존 신호를 보내지 않는다", async () => {
    const requests = collectRequests();
    await startRecording();
    act(() => useRecordingStore.getState().beginEnding());
    renderRecordingHeartbeat();

    passHeartbeatInterval();

    expect(requests).toEqual([]);
  });

  it("신호가 실패(500)하면 곧바로 다시 보내지 않고 녹음을 이어 가다 다음 주기에 보낸다", async () => {
    respondHeartbeatWithError(500, "INTERNAL_SERVER_ERROR");
    const requests = collectRequests();
    await startRecording();
    const { router } = renderRecordingHeartbeat();

    passHeartbeatInterval();

    await waitFor(() =>
      expect(console.error).toHaveBeenCalledWith(
        "녹음 생존 신호 요청에 실패했어요",
        expect.anything(),
      ),
    );
    expect(requests).toEqual(["heartbeat"]);
    expect(useRecordingStore.getState().status).toBe("recording");
    expect(router.state.location.pathname).toBe(RECORDING_PATH);

    passHeartbeatInterval();

    await waitFor(() => expect(requests).toEqual(["heartbeat", "heartbeat"]));
  });

  it("이 탭의 녹음 제어가 거절(403 RECORDING_CONTROL_DENIED)되면 녹음을 닫고 홈으로 가며 주의 토스트를 띄우고, 다음 주기부터 신호를 보내지 않는다", async () => {
    respondHeartbeatWithError(403, "RECORDING_CONTROL_DENIED");
    const requests = collectRequests();
    await startRecording();
    const { router } = renderRecordingHeartbeat();

    passHeartbeatInterval();

    await waitFor(() => expect(router.state.location.pathname).toBe(HOME_PATH));
    expect(
      await screen.findByText(TOAST_MESSAGES.RECORDING_CONTROL_LOST.message),
    ).toBeInTheDocument();
    expect(useRecordingStore.getState().status).toBe("idle");
    expect(getRecordingControlProof()).toBeNull();
    // 오디오를 올리지 않아요. 올릴 권한도 이 탭에 없어요. 업무 403이라 CSRF 토큰을 새로 받아 다시 보내지도 않아요
    expect(requests).toEqual(["heartbeat"]);

    passHeartbeatInterval();
    passHeartbeatInterval();

    await act(() => new Promise((resolve) => setTimeout(resolve, 50)));
    expect(requests).toEqual(["heartbeat"]);
  });

  it("서버가 끝낸 녹음(ENDED)이면 종료 요청 없이 오디오를 올리고 증명을 지운 뒤 홈으로 간다", async () => {
    respondHeartbeat(() =>
      HttpResponse.json({ ...recordingHeartbeatResponse, status: "ENDED" }),
    );
    const requests = collectRequests();
    await startRecording();
    const { router } = renderRecordingHeartbeat();

    passHeartbeatInterval();

    await waitFor(() => expect(router.state.location.pathname).toBe(HOME_PATH));
    expect(requests).toEqual(["heartbeat", ...UPLOAD_REQUESTS]);
    expect(useRecordingStore.getState().status).toBe("idle");
    expect(getRecordingControlProof()).toBeNull();
  });

  it("탭이 열려 있는 동안 서버가 연결 끊김으로 끝낸 녹음(ENDED, CONNECTION_EXPIRED)이면 오디오를 올린 뒤 홈으로 가며 주의 토스트로 알린다 (STT-R26)", async () => {
    respondHeartbeat(() =>
      HttpResponse.json({
        ...recordingHeartbeatResponse,
        status: "ENDED",
        endReason: "CONNECTION_EXPIRED",
      }),
    );
    const requests = collectRequests();
    await startRecording();
    const { router } = renderRecordingHeartbeat();

    passHeartbeatInterval();

    await waitFor(() => expect(router.state.location.pathname).toBe(HOME_PATH));
    expect(requests).toEqual(["heartbeat", ...UPLOAD_REQUESTS]);
    expect(
      await screen.findByText(TOAST_MESSAGES.RECORDING_CONNECTION_LOST.message),
    ).toBeInTheDocument();
  });

  it("연결 끊김으로 끝난 녹음의 오디오를 끝내 올리지 못하면 연결 끊김 대신 업로드 실패로 알린다", async () => {
    respondHeartbeat(() =>
      HttpResponse.json({
        ...recordingHeartbeatResponse,
        status: "ENDED",
        endReason: "CONNECTION_EXPIRED",
      }),
    );
    mockServer.use(
      http.post(
        "*/api/v1/workspaces/:workspaceId/recordings/:recordingId/audio-upload-url",
        () => HttpResponse.json({ code: "INVALID_REQUEST" }, { status: 400 }),
      ),
    );
    vi.spyOn(console, "error").mockImplementation(() => {});
    await startRecording();
    const { router } = renderRecordingHeartbeat();

    passHeartbeatInterval();

    await waitFor(() => expect(router.state.location.pathname).toBe(HOME_PATH));
    expect(
      await screen.findByText(TOAST_MESSAGES.RECORDING_UPLOAD_FAILED.message),
    ).toBeInTheDocument();
    expect(
      screen.queryByText(TOAST_MESSAGES.RECORDING_CONNECTION_LOST.message),
    ).not.toBeInTheDocument();
  });

  it.each([
    [
      "DISCARDED",
      ["heartbeat"],
      () =>
        respondHeartbeat(() =>
          HttpResponse.json({
            ...recordingHeartbeatResponse,
            status: "DISCARDED",
          }),
        ),
    ],
    [
      "403 WORKSPACE_ACCESS_DENIED",
      ["heartbeat"],
      () => respondHeartbeatWithError(403, "WORKSPACE_ACCESS_DENIED"),
    ],
    [
      "404 RECORDING_NOT_FOUND",
      ["heartbeat"],
      () => respondHeartbeatWithError(404, "RECORDING_NOT_FOUND"),
    ],
  ])(
    "서버가 버렸거나 더는 볼 수 없는 녹음(%s)이면 오디오를 올리지 않고 버린 뒤 홈으로 간다",
    async (_, sent, respond) => {
      respond();
      const requests = collectRequests();
      await startRecording();
      const { router } = renderRecordingHeartbeat();

      passHeartbeatInterval();

      await waitFor(() =>
        expect(router.state.location.pathname).toBe(HOME_PATH),
      );
      expect(requests).toEqual(sent);
      expect(useRecordingStore.getState().status).toBe("idle");
      expect(getRecordingControlProof()).toBeNull();
    },
  );

  it("녹음 중에 워크스페이스에서 내보내지면(403 WORKSPACE_ACCESS_DENIED) 녹음을 버리고 마지막으로 본 다른 워크스페이스 홈으로 보내며 오류 토스트를 띄운다 (ERR-11, D7)", async () => {
    const OTHER_WORKSPACE_ID = 2;
    const otherHomePath = getRouterPath({
      routeKey: "WORKSPACE_HOME",
      params: { workspaceId: String(OTHER_WORKSPACE_ID) },
    });
    mockServer.use(
      http.get(`*${WORKSPACES_API_PATH}`, () =>
        HttpResponse.json({
          ...workspacesResponse,
          lastViewedWorkspaceId: OTHER_WORKSPACE_ID,
        }),
      ),
    );
    await startRecording();
    // 실제 라우트처럼 레이아웃을 감싸, 레이아웃의 진입 판정이 이동과 안내를 맡는지 봐요
    const layouts = [
      {
        handle: { hasDock: true },
        element: (
          <RecordingHeartbeat>
            <WorkspaceLayout />
          </RecordingHeartbeat>
        ),
        children: [
          { path: PATH_ROUTE.WORKSPACE_HOME, element: <p>홈 화면</p> },
          { path: PATH_ROUTE.RECORDING, element: <p>녹음 화면</p> },
        ],
      },
    ];
    const router = createMemoryRouter(
      [
        {
          element: (
            <AppProviders routes={layouts}>
              <Outlet />
            </AppProviders>
          ),
          children: layouts,
        },
      ],
      { initialEntries: [RECORDING_PATH] },
    );
    render(
      <ThemeProvider theme={theme}>
        <QueryClientProvider
          client={
            new QueryClient({
              defaultOptions: {
                queries: { retry: false },
                mutations: { retry: false },
              },
            })
          }
        >
          <RouterProvider router={router} />
        </QueryClientProvider>
      </ThemeProvider>,
    );
    await screen.findByText("녹음 화면");

    respondHeartbeatWithError(403, "WORKSPACE_ACCESS_DENIED");
    mockServer.use(
      http.get("*/api/v1/workspaces/:workspaceId", ({ params }) =>
        params.workspaceId === WORKSPACE_ID
          ? HttpResponse.json(
              { code: "WORKSPACE_ACCESS_DENIED" },
              { status: 403 },
            )
          : HttpResponse.json(workspaceDetailResponse),
      ),
    );
    passHeartbeatInterval();

    await waitFor(() =>
      expect(router.state.location.pathname).toBe(otherHomePath),
    );
    expect(
      await screen.findByText(TOAST_MESSAGES.WORKSPACE_ACCESS_DENIED.message),
    ).toBeInTheDocument();
    expect(useRecordingStore.getState().status).toBe("idle");
  });

  it("끝내는 중에 늦게 도착한 응답으로는 녹음한 시간을 되돌리지 않는다", async () => {
    const heartbeat = holdHeartbeat(recordingHeartbeatResponse);
    const requests = collectRequests();
    await startRecording();
    renderRecordingHeartbeat();
    passHeartbeatInterval();
    await waitFor(() => expect(requests).toEqual(["heartbeat"]));

    act(() => useRecordingStore.getState().beginEnding());
    await heartbeat.release();

    // 응답을 처리할 틈을 준 뒤에도 그대로인지 봐요
    await act(() => new Promise((resolve) => setTimeout(resolve, 50)));
    expect(useRecordingStore.getState().accumulatedMs).toBe(0);
  });

  it("이미 닫은 녹음에 늦게 도착한 응답으로는 새로 시작한 녹음을 닫지 않는다", async () => {
    const heartbeat = holdHeartbeat({
      ...recordingHeartbeatResponse,
      status: "ENDED",
    });
    const requests = collectRequests();
    await startRecording();
    const { router } = renderRecordingHeartbeat();
    passHeartbeatInterval();
    await waitFor(() => expect(requests).toEqual(["heartbeat"]));

    act(() => useRecordingStore.getState().discardRecording());
    await startRecording(expected.recordingId + 1);
    await heartbeat.release();

    await act(() => new Promise((resolve) => setTimeout(resolve, 50)));
    expect(requests).toEqual(["heartbeat"]);
    expect(useRecordingStore.getState().status).toBe("recording");
    expect(router.state.location.pathname).toBe(RECORDING_PATH);
  });

  it("녹음한 시간이 2시간에 닿으면 10초 주기를 기다리지 않고 생존 신호를 보내, 서버가 끝낸 녹음을 올리고 홈으로 간다", async () => {
    respondHeartbeat(() =>
      HttpResponse.json({
        ...recordingHeartbeatResponse,
        status: "ENDED",
        endReason: "MAX_DURATION",
      }),
    );
    const requests = collectRequests();
    await startRecording();
    const { router } = renderRecordingHeartbeat();

    leaveUntilMaxDuration(50);

    await waitFor(() => expect(router.state.location.pathname).toBe(HOME_PATH));
    expect(requests).toEqual(["heartbeat", ...UPLOAD_REQUESTS]);
    expect(useRecordingStore.getState().status).toBe("idle");
    // 연결이 끊긴 게 아니라 연결 끊김 안내를 띄우지 않아요
    expect(
      screen.queryByText(TOAST_MESSAGES.RECORDING_CONNECTION_LOST.message),
    ).not.toBeInTheDocument();
  });

  it("일시정지 중에는 2시간 신호를 보내지 않고, 이어서 녹음하면 남은 시간 뒤에 보낸다", async () => {
    const requests = collectRequests();
    await startRecording();
    renderRecordingHeartbeat();
    await act(() => useRecordingStore.getState().pauseRecording());
    leaveUntilMaxDuration(50);

    await waitPastMaxDuration();
    expect(requests).toEqual([]);

    await act(() => useRecordingStore.getState().resumeRecording());

    await waitFor(() => expect(requests).toEqual(["heartbeat"]));
  });

  it("2시간 신호에 서버가 아직 녹음 중이라고 답하면, 서버 시간으로 맞춘 남은 시간 뒤에 다시 보낸다", async () => {
    const responses = [
      {
        ...recordingHeartbeatResponse,
        elapsedMillis: MAX_RECORDING_DURATION_MS - 50,
      },
      {
        ...recordingHeartbeatResponse,
        status: "ENDED",
        endReason: "MAX_DURATION",
      },
    ];
    respondHeartbeat(() => HttpResponse.json(responses.shift()));
    const requests = collectRequests();
    await startRecording();
    const { router } = renderRecordingHeartbeat();

    leaveUntilMaxDuration(50);

    await waitFor(() => expect(router.state.location.pathname).toBe(HOME_PATH));
    expect(requests).toEqual(["heartbeat", "heartbeat", ...UPLOAD_REQUESTS]);
  });

  it("서버가 상한을 채운 시간으로 녹음 중이라고 답해도, 신호를 쉬지 않고 보내지 않는다", async () => {
    respondHeartbeat(() =>
      HttpResponse.json({
        ...recordingHeartbeatResponse,
        elapsedMillis: MAX_RECORDING_DURATION_MS,
      }),
    );
    const requests = collectRequests();
    await startRecording();
    renderRecordingHeartbeat();

    leaveUntilMaxDuration(50);

    await waitPastMaxDuration();
    expect(requests).toEqual(["heartbeat"]);
  });

  it("서버 시간으로 맞춘 뒤 남은 시간을 넘겨서야 확인해도, 10초 주기를 기다리지 않고 신호를 보낸다", async () => {
    const requests = collectRequests();
    await startRecording();
    renderRecordingHeartbeat();

    // 맞출 때는 10ms가 남았는데, 20ms가 지나서야 남은 시간을 확인한 상황이에요
    act(() =>
      useRecordingStore.setState({
        accumulatedMs: MAX_RECORDING_DURATION_MS - 10,
        resumedAt: Date.now() - 20,
      }),
    );

    await waitFor(() => expect(requests).toEqual(["heartbeat"]));
  });

  it("2시간 신호가 실패하면 곧바로 다시 보내지 않고 녹음을 이어 간다", async () => {
    respondHeartbeatWithError(500, "INTERNAL_SERVER_ERROR");
    const requests = collectRequests();
    await startRecording();
    renderRecordingHeartbeat();

    leaveUntilMaxDuration(50);

    await waitPastMaxDuration();
    expect(requests).toEqual(["heartbeat"]);
    expect(useRecordingStore.getState().status).toBe("recording");
  });

  describe("최대 녹음 시간 15분 전 안내 (STT-R15)", () => {
    const WARNING_BEFORE_MS = 15 * 60 * 1000;
    const WARNING = TOAST_MESSAGES.RECORDING_TIME_LIMIT_APPROACHING.message;
    /** 주의 토스트가 떠 있는 시간과 사라지는 시간 */
    const CAUTION_TOAST_MS = 8000;
    const TOAST_LEAVE_MS = 250;

    beforeEach(() => {
      // 토스트가 떠 있는 시간까지 세야 해서 시계를 모두 가짜로 돌려요. 10초 주기 신호가 나가기 전에 확인을 마쳐요
      vi.useFakeTimers({
        toFake: [
          "setTimeout",
          "clearTimeout",
          "setInterval",
          "clearInterval",
          "Date",
        ],
      });
    });

    const advance = (ms: number) => {
      act(() => {
        vi.advanceTimersByTime(ms);
      });
    };

    it("남은 시간이 15분에 닿으면 생존 신호를 보내지 않고 주의 토스트를 띄운다", async () => {
      const requests = collectRequests();
      await startRecording();
      renderRecordingHeartbeat();
      leaveUntilMaxDuration(WARNING_BEFORE_MS + 1000);

      advance(999);
      expect(screen.queryByText(WARNING)).not.toBeInTheDocument();

      advance(1);
      expect(screen.getByText(WARNING)).toBeInTheDocument();
      expect(requests).toEqual([]);
    });

    it("일시정지 중에는 띄우지 않는다", async () => {
      await startRecording();
      renderRecordingHeartbeat();
      await act(() => useRecordingStore.getState().pauseRecording());
      leaveUntilMaxDuration(WARNING_BEFORE_MS + 1000);

      advance(2000);

      expect(screen.queryByText(WARNING)).not.toBeInTheDocument();
    });

    it("녹음한 시간이 다시 맞춰져 15분 전을 또 지나도 한 녹음에 한 번만 띄운다", async () => {
      await startRecording();
      renderRecordingHeartbeat();
      leaveUntilMaxDuration(WARNING_BEFORE_MS + 1000);
      advance(1000);
      expect(screen.getByText(WARNING)).toBeInTheDocument();

      // 서버 시간이 조금 늦어 15분 전보다 앞선 시간으로 다시 맞춰진 상황이에요
      leaveUntilMaxDuration(WARNING_BEFORE_MS + 1000);
      advance(1000);

      // 다시 띄웠다면 떠 있는 시간을 처음부터 다시 세서 아직 남아 있어요
      advance(CAUTION_TOAST_MS - 1000);
      advance(TOAST_LEAVE_MS);
      expect(screen.queryByText(WARNING)).not.toBeInTheDocument();
    });

    it("새로 시작한 녹음에서는 다시 띄운다", async () => {
      await startRecording();
      renderRecordingHeartbeat();
      leaveUntilMaxDuration(WARNING_BEFORE_MS + 1000);
      advance(1000);
      advance(CAUTION_TOAST_MS);
      advance(TOAST_LEAVE_MS);
      expect(screen.queryByText(WARNING)).not.toBeInTheDocument();

      act(() => useRecordingStore.getState().discardRecording());
      await startRecording(expected.recordingId + 1);
      leaveUntilMaxDuration(WARNING_BEFORE_MS + 1000);
      advance(1000);

      expect(screen.getByText(WARNING)).toBeInTheDocument();
    });
  });

  // 10초마다 실패할 때마다 보내면 장애 한 번이 이벤트 수십 건이 돼 심각도를 가늠할 수 없어요
  describe("실패 보고", () => {
    const { flushEvents } = setupSentryEvents();
    /** 다른 테스트의 실패 기록과 섞이지 않게 이 테스트만의 녹음으로 보내요 */
    const REPORTED_RECORDING_ID = expected.recordingId + 100;

    /** 응답을 받아 처리할 틈을 줘요 */
    const settleHeartbeat = () =>
      act(() => new Promise((resolve) => setTimeout(resolve, 50)));

    // SDK는 바로 앞과 같은 오류 이벤트를 버리므로, 성공 뒤의 실패는 다른 상태 코드로 봐요
    it("신호가 거듭 실패하면 연속 실패의 첫 번째만 보고하고, 성공한 뒤 다시 실패하면 처음부터 센다", async () => {
      vi.spyOn(console, "error").mockImplementation(() => {});
      const statuses = [500, 500, 500, 200, 503, 503];
      respondHeartbeat(() => {
        const status = statuses.shift();

        return status === 200
          ? HttpResponse.json(recordingHeartbeatResponse)
          : HttpResponse.json({ code: "SERVER_ERROR" }, { status });
      });
      await startRecording(REPORTED_RECORDING_ID);
      renderRecordingHeartbeat();

      for (let beat = 0; beat < 6; beat += 1) {
        passHeartbeatInterval();
        await settleHeartbeat();
      }

      const events = await flushEvents();
      expect(events).toHaveLength(2);
      expect(events.map(({ tags }) => tags?.feature)).toEqual([
        "recording",
        "recording",
      ]);
      expect(events.map(({ extra }) => extra)).toEqual([
        { consecutiveFailures: 1 },
        { consecutiveFailures: 1 },
      ]);
      // 콘솔에는 실패마다 남겨요
      expect(console.error).toHaveBeenCalledTimes(5);
    });
  });
});
