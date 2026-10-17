import { PostRecordingHeartbeatResponseDto } from "@api/dto/recording";
import { recordingHeartbeatResponse } from "@api/mock/responses/recording";
import { mockServer } from "@api/mock/server";
import { MAX_RECORDING_DURATION_MS } from "@constants/recording";
import { DialogProvider } from "@provider/context/dialogContext";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import { useRecordingStore } from "@store/recordingStore";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
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
          <RecordingHeartbeat>
            <Outlet />
          </RecordingHeartbeat>
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
    <QueryClientProvider client={queryClient}>
      <DialogProvider>
        <RouterProvider router={router} />
      </DialogProvider>
    </QueryClientProvider>,
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

/** 403은 CSRF 토큰이 낡았을 수 있어 httpClient가 토큰을 새로 받아 최대 두 번 더 보내요. 계속 403인 신호 한 번이 이렇게 나가요 */
const CSRF_RETRIED_HEARTBEAT = [
  "heartbeat",
  "csrf",
  "heartbeat",
  "csrf",
  "heartbeat",
];

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

  it("일시정지 중에도 생존 신호를 보낸다", async () => {
    const requests = collectRequests();
    await startRecording();
    act(() => useRecordingStore.getState().pauseRecording());
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

  it.each([
    [500, "INTERNAL_SERVER_ERROR", ["heartbeat"]],
    [403, "RECORDING_CONTROL_DENIED", CSRF_RETRIED_HEARTBEAT],
  ])(
    "신호가 실패(%i %s)하면 곧바로 다시 보내지 않고 녹음을 이어 가다 다음 주기에 보낸다",
    async (status, code, sent) => {
      respondHeartbeatWithError(status, code);
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
      expect(requests).toEqual(sent);
      expect(useRecordingStore.getState().status).toBe("recording");
      expect(router.state.location.pathname).toBe(RECORDING_PATH);

      passHeartbeatInterval();

      await waitFor(() => expect(requests).toEqual([...sent, ...sent]));
    },
  );

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
      CSRF_RETRIED_HEARTBEAT,
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
  });

  it("일시정지 중에는 2시간 신호를 보내지 않고, 이어서 녹음하면 남은 시간 뒤에 보낸다", async () => {
    const requests = collectRequests();
    await startRecording();
    renderRecordingHeartbeat();
    act(() => useRecordingStore.getState().pauseRecording());
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
});
