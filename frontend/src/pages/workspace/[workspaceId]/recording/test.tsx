import { mockServer } from "@api/mock/server";
import { ThemeProvider } from "@emotion/react";
import { DialogProvider } from "@provider/context/dialogContext";
import { theme } from "@provider/themeProvider";
import { useRecordingStore } from "@store/recordingStore";
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
import { http, HttpResponse } from "msw";
import { createMemoryRouter, Outlet, RouterProvider } from "react-router";
import { afterEach, describe, expect, it } from "vitest";

import RecordingPage from ".";

const WORKSPACE_ID = "1";
const RECORDING_PATH = getRouterPath({
  routeKey: "RECORDING",
  params: { workspaceId: WORKSPACE_ID },
});
const HOME_PATH = getRouterPath({
  routeKey: "WORKSPACE_HOME",
  params: { workspaceId: WORKSPACE_ID },
});

const renderRecordingPage = () => {
  const router = createMemoryRouter(
    [
      {
        element: <Outlet />,
        children: [
          { path: PATH_ROUTE.WORKSPACE_HOME, element: <p>홈 화면</p> },
          { path: PATH_ROUTE.RECORDING, element: <RecordingPage /> },
        ],
      },
    ],
    { initialEntries: [HOME_PATH, RECORDING_PATH], initialIndex: 1 },
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

/** 녹음은 독에서 마이크를 받은 뒤 시작하므로, 녹음 화면에 들어오기 전에 미리 시작해 둬요. */
const startRecording = async () => {
  await act(async () => {
    await useRecordingStore.getState().connectMicrophone();
    useRecordingStore.getState().startRecording({
      workspaceId: Number(WORKSPACE_ID),
      recordingId: 10,
    });
  });
};

/** 녹음 바의 「녹음 끝내기」를 누르고 확인 창에서 「녹음 끝내기」를 골라요 */
const confirmEndRecording = async () => {
  await act(async () => {
    fireEvent.click(screen.getByRole("button", { name: "녹음 끝내기" }));
  });
  const dialog = screen.getByRole("dialog", { name: "녹음을 끝낼까요?" });

  await act(async () => {
    fireEvent.click(
      within(dialog).getByRole("button", { name: "녹음 끝내기" }),
    );
  });
};

describe("RecordingPage", () => {
  afterEach(() => {
    // 전역 저장소라 테스트끼리 녹음이 새지 않도록 처음 상태로 되돌려요
    useRecordingStore.getState().discardRecording();
    mockServer.events.removeAllListeners();
  });

  it("진행 중인 녹음 없이 들어오면 홈으로 보내고, 뒤로 가기로 돌아오지 않는다", async () => {
    const { router } = renderRecordingPage();

    await act(async () => {});

    expect(router.state.location.pathname).toBe(HOME_PATH);
    expect(router.state.historyAction).toBe("REPLACE");
  });

  it("녹음 중이면 녹음 화면에 머문다", async () => {
    await startRecording();
    const { router } = renderRecordingPage();

    await act(async () => {});

    expect(router.state.location.pathname).toBe(RECORDING_PATH);
  });

  it("확인 창에서 녹음 끝내기를 고르면 종료 요청과 업로드 완료 확인을 마치고 홈으로 나간다", async () => {
    const count = { end: 0, complete: 0 };
    mockServer.events.on("request:start", ({ request }) => {
      if (request.url.endsWith("/end")) count.end += 1;
      if (request.url.endsWith("/audio-upload-complete")) count.complete += 1;
    });
    await startRecording();
    const { router } = renderRecordingPage();

    await confirmEndRecording();

    await waitFor(() => expect(router.state.location.pathname).toBe(HOME_PATH));
    expect(count).toEqual({ end: 1, complete: 1 });
  });

  describe("최종 오디오 업로드", () => {
    const UPLOAD_URL_PATH =
      "*/api/v1/workspaces/:workspaceId/recordings/:recordingId/audio-upload-url";
    const UPLOAD_COMPLETE_PATH =
      "*/api/v1/workspaces/:workspaceId/recordings/:recordingId/audio-upload-complete";

    /** 업로드 URL 발급과 완료 확인 요청 수를 세요 */
    const countUploadRequests = () => {
      const count = { issue: 0, complete: 0 };
      mockServer.events.on("request:start", ({ request }) => {
        if (request.url.endsWith("/audio-upload-url")) count.issue += 1;
        if (request.url.endsWith("/audio-upload-complete")) count.complete += 1;
      });

      return count;
    };

    const endRecording = async () => {
      await startRecording();
      const { router } = renderRecordingPage();

      await confirmEndRecording();
      await waitFor(() =>
        expect(router.state.location.pathname).toBe(HOME_PATH),
      );
    };

    it("PUT을 마치면 업로드 완료를 확인받는다", async () => {
      const count = countUploadRequests();

      await endRecording();

      expect(count).toEqual({ issue: 1, complete: 1 });
    });

    it("일시 실패면 URL 발급부터 다시 시도해 완료를 확인받는다", async () => {
      const count = countUploadRequests();
      mockServer.use(
        http.post(
          UPLOAD_COMPLETE_PATH,
          () =>
            HttpResponse.json(
              { code: "AUDIO_UPLOAD_NOT_COMPLETED" },
              { status: 409 },
            ),
          { once: true },
        ),
      );

      await endRecording();

      expect(count).toEqual({ issue: 2, complete: 2 });
    });

    it("일시 실패가 계속되면 3번까지만 다시 시도한다", async () => {
      const count = countUploadRequests();
      mockServer.use(
        http.post(
          UPLOAD_URL_PATH,
          () => new HttpResponse(null, { status: 503 }),
        ),
      );

      await endRecording();

      expect(count).toEqual({ issue: 4, complete: 0 });
    });

    it("4xx면 다시 시도하지 않는다", async () => {
      const count = countUploadRequests();
      mockServer.use(
        http.post(UPLOAD_URL_PATH, () =>
          HttpResponse.json({ code: "INVALID_REQUEST" }, { status: 400 }),
        ),
      );

      await endRecording();

      expect(count).toEqual({ issue: 1, complete: 0 });
    });
  });
});
