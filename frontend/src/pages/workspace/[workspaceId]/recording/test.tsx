import { mockServer } from "@api/mock/server";
import { recordingDetailsResponse, recordingEndResponse } from "@api/mock/responses/recording";
import RecordingDocuments from "@widgets/document/RecordingDocuments";
import RecordingGuard from "@routes/RecordingGuard";
import { ThemeProvider } from "@emotion/react";
import { DialogProvider } from "@provider/context/dialogContext";
import { ToastProvider } from "@provider/context/toastContext";
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
import {
  clearRecordingStartProof,
  getRecordingStartProof,
} from "@utils/recordingControlProof";
import { delay, http, HttpResponse } from "msw";
import { createMemoryRouter, Outlet, RouterProvider } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";

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
const RECORDING_ID = 10;
const DOCUMENTS_PATH = getRouterPath({
  routeKey: "RECORDING_DOCUMENTS",
  params: { workspaceId: WORKSPACE_ID, recordingId: String(RECORDING_ID) },
});

const renderRecordingPage = (withRealDocuments = false) => {
  const router = createMemoryRouter(
    [
      {
        element: (
          <ToastProvider>
            <Outlet />
          </ToastProvider>
        ),
        children: [
          { path: PATH_ROUTE.WORKSPACE_HOME, element: <p>홈 화면</p> },
          {
            path: PATH_ROUTE.RECORDING,
            element: withRealDocuments ? <RecordingGuard /> : <RecordingPage />,
            children: withRealDocuments ? [{ index: true, element: <RecordingPage /> }] : undefined,
          },
          { path: PATH_ROUTE.RECORDING_DOCUMENTS, element: withRealDocuments ? <RecordingDocuments /> : <p>정리 화면</p> },
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

/** 녹음은 독에서 증명을 만들고 마이크를 받은 뒤 시작하므로, 녹음 화면에 들어오기 전에 같은 순서로 미리 시작해 둬요. */
const startRecording = async () => {
  getRecordingStartProof();

  await act(async () => {
    await useRecordingStore.getState().connectMicrophone();
    useRecordingStore.getState().startRecording({
      workspaceId: Number(WORKSPACE_ID),
      recordingId: RECORDING_ID,
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
    clearRecordingStartProof();
    mockServer.events.removeAllListeners();
  });

  it("확인 창에서 녹음 끝내기를 고르면 종료 요청과 업로드 완료 확인을 마치고 그 녹음의 정리 화면으로 간다", async () => {
    const count = { end: 0, complete: 0 };
    mockServer.events.on("request:start", ({ request }) => {
      if (request.url.endsWith("/end")) count.end += 1;
      if (request.url.endsWith("/audio-upload-complete")) count.complete += 1;
    });
    await startRecording();
    const { router } = renderRecordingPage();

    await confirmEndRecording();

    await waitFor(() =>
      expect(router.state.location.pathname).toBe(DOCUMENTS_PATH),
    );
    expect(count).toEqual({ end: 1, complete: 1 });
  });

  it("참가자 수가 조회한 녹음 중 캐시가 남아 있어도 종료 뒤 홈 대신 대기 화면을 유지한다", async () => {
    let isEnded = false;
    mockServer.use(
      http.get("*/api/v1/workspaces/:workspaceId/recordings/:recordingId", async () => {
        if (isEnded) await delay(300);
        return HttpResponse.json({
          ...recordingDetailsResponse[0].detail,
          status: isEnded ? "ENDED" : "RECORDING",
          endedAt: isEnded ? recordingEndResponse.endedAt : null,
          transcriptionStatus: null,
          documentGeneration: null,
          speakerCount: null,
        });
      }),
      http.post("*/api/v1/workspaces/:workspaceId/recordings/:recordingId/end", () => {
        isEnded = true;
        return HttpResponse.json(recordingEndResponse);
      }),
    );
    await startRecording();
    const { router } = renderRecordingPage(true);
    await waitFor(() => expect(screen.getByRole("button", { name: "참가자 수 늘리기" })).toBeEnabled());

    await confirmEndRecording();

    await waitFor(() => expect(router.state.location.pathname).toBe(DOCUMENTS_PATH));
    expect(await screen.findByRole("heading", { name: "회의 내용을 바탕으로 문서를 정리하고 있어요" })).toBeInTheDocument();
    expect(screen.queryByText("홈 화면")).not.toBeInTheDocument();
  });

  it.each(["RECORDING_ALREADY_ENDED", "RECORDING_ALREADY_DISCARDED"])(
    "종료 요청이 이미 닫힌 녹음(409 %s)으로 거절되면 올리지 않고 홈으로 나간다",
    async (code) => {
      const count = { issue: 0 };
      mockServer.events.on("request:start", ({ request }) => {
        if (request.url.endsWith("/audio-upload-url")) count.issue += 1;
      });
      mockServer.use(
        http.post(
          "*/api/v1/workspaces/:workspaceId/recordings/:recordingId/end",
          () => HttpResponse.json({ code }, { status: 409 }),
        ),
      );
      vi.spyOn(console, "error").mockImplementation(() => {});
      await startRecording();
      const { router } = renderRecordingPage();

      await confirmEndRecording();

      await waitFor(() =>
        expect(router.state.location.pathname).toBe(HOME_PATH),
      );
      expect(count.issue).toBe(0);
    },
  );

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

    /** 녹음을 끝내고, 업로드 결과에 따라 옮겨 간 주소를 확인해요 */
    const endRecording = async (movedPath: string) => {
      await startRecording();
      const { router } = renderRecordingPage();

      await confirmEndRecording();
      await waitFor(() =>
        expect(router.state.location.pathname).toBe(movedPath),
      );
    };

    it("PUT을 마치면 업로드 완료를 확인받고 정리 화면으로 간다", async () => {
      const count = countUploadRequests();

      await endRecording(DOCUMENTS_PATH);

      expect(count).toEqual({ issue: 1, complete: 1 });
    });

    it("일시 실패면 URL 발급부터 다시 시도해 완료를 확인받고 정리 화면으로 간다", async () => {
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

      await endRecording(DOCUMENTS_PATH);

      expect(count).toEqual({ issue: 2, complete: 2 });
    });

    it("일시 실패가 계속되면 3번까지만 다시 시도하고 홈으로 나간다", async () => {
      const count = countUploadRequests();
      mockServer.use(
        http.post(
          UPLOAD_URL_PATH,
          () => new HttpResponse(null, { status: 503 }),
        ),
      );

      await endRecording(HOME_PATH);

      expect(count).toEqual({ issue: 4, complete: 0 });
    });

    it("4xx면 다시 시도하지 않고 홈으로 나간다", async () => {
      const count = countUploadRequests();
      mockServer.use(
        http.post(UPLOAD_URL_PATH, () =>
          HttpResponse.json({ code: "INVALID_REQUEST" }, { status: 400 }),
        ),
      );

      await endRecording(HOME_PATH);

      expect(count).toEqual({ issue: 1, complete: 0 });
    });
  });
});
