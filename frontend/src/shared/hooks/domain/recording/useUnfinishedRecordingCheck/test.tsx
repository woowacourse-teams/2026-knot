import { recordingDetailsResponse } from "@api/mock/responses/recording";
import { mockServer } from "@api/mock/server";
import { TOAST_MESSAGES } from "@constants/toastMessages";
import { ThemeProvider } from "@emotion/react";
import { ToastProvider } from "@provider/context/toastContext";
import { theme } from "@provider/themeProvider";
import {
  deleteRecordingChunks,
  readRecordingChunks,
  saveRecordingChunk,
} from "@store/recordingStore/recordingChunkArchive";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import {
  clearRecordingStartProof,
  getRecordingResumeProof,
  getRecordingStartProof,
  saveRecordingResumeProof,
} from "@utils/recordingControlProof";
import {
  getUnfinishedRecording,
  rememberUnfinishedRecording,
} from "@utils/unfinishedRecording";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";

import useUnfinishedRecordingCheck from ".";

const WORKSPACE_ID = 1;
const RECORDING_ID = 10;
const RECORDING = { workspaceId: WORKSPACE_ID, recordingId: RECORDING_ID };
const DETAIL_PATH = "*/api/v1/workspaces/:workspaceId/recordings/:recordingId";
const [detail] = recordingDetailsResponse;

const respondDetail = (status: string, endReason: string | null = null) =>
  mockServer.use(
    http.get(DETAIL_PATH, () =>
      HttpResponse.json({ ...detail, status, endReason }),
    ),
  );

function Checker() {
  useUnfinishedRecordingCheck();

  return null;
}

const renderCheck = () =>
  render(
    <ThemeProvider theme={theme}>
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter>
          <ToastProvider>
            <Checker />
          </ToastProvider>
        </MemoryRouter>
      </QueryClientProvider>
    </ThemeProvider>,
  );

const leaveUnfinishedRecording = async () => {
  rememberUnfinishedRecording(RECORDING);
  getRecordingStartProof();
  saveRecordingResumeProof({ ...RECORDING, audioMode: "PCM16_CHUNKS_V1" });
  await saveRecordingChunk({
    ...RECORDING,
    sequence: 1,
    pcm: new ArrayBuffer(2),
  });
};

describe("useUnfinishedRecordingCheck", () => {
  afterEach(async () => {
    vi.restoreAllMocks();
    localStorage.clear();
    clearRecordingStartProof();
    await deleteRecordingChunks(RECORDING_ID);
  });

  it("연결 끊김으로 끝난 녹음이면 토스트로 알리고 남은 조각과 증명을 지운다 (STT-R26)", async () => {
    await leaveUnfinishedRecording();
    respondDetail("ENDED", "CONNECTION_EXPIRED");

    renderCheck();

    expect(
      await screen.findByText(TOAST_MESSAGES.RECORDING_CONNECTION_LOST.message),
    ).toBeInTheDocument();
    expect(
      await screen.findByText(
        TOAST_MESSAGES.RECORDING_CHUNKS_NOT_UPLOADED.message,
      ),
    ).toBeInTheDocument();
    expect(getUnfinishedRecording()).toBeNull();
    expect(getRecordingResumeProof()).toBeNull();
    await waitFor(async () =>
      expect(await readRecordingChunks(RECORDING_ID)).toEqual([]),
    );
  });

  it("지금 보내고 있는 시작 증명은 지우지 않는다", async () => {
    await leaveUnfinishedRecording();
    const { controlToken } = getRecordingStartProof();
    respondDetail("ENDED", "USER_ENDED");

    renderCheck();

    await waitFor(() => expect(getRecordingResumeProof()).toBeNull());
    expect(getRecordingStartProof().controlToken).toBe(controlToken);
  });

  it("사용자가 끝낸 녹음이면 알리지 않고 지운다", async () => {
    await leaveUnfinishedRecording();
    respondDetail("ENDED", "USER_ENDED");

    renderCheck();

    await waitFor(() => expect(getUnfinishedRecording()).toBeNull());
    expect(
      screen.queryByText(TOAST_MESSAGES.RECORDING_CONNECTION_LOST.message),
    ).not.toBeInTheDocument();
  });

  it("아직 녹음 중이면 이어 갈 수 있게 그대로 둔다", async () => {
    await leaveUnfinishedRecording();
    const requested = vi.fn();
    mockServer.use(
      http.get(DETAIL_PATH, () => {
        requested();

        return HttpResponse.json({ ...detail, status: "RECORDING" });
      }),
    );

    renderCheck();

    await waitFor(() => expect(requested).toHaveBeenCalled());
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(getUnfinishedRecording()).toEqual(RECORDING);
    expect(getRecordingResumeProof()).not.toBeNull();
  });

  it("더는 볼 수 없는 녹음이면 지운다", async () => {
    await leaveUnfinishedRecording();
    mockServer.use(
      http.get(DETAIL_PATH, () =>
        HttpResponse.json({ code: "RECORDING_NOT_FOUND" }, { status: 404 }),
      ),
    );

    renderCheck();

    await waitFor(() => expect(getUnfinishedRecording()).toBeNull());
  });

  it("조회가 일시 실패하면 다음에 다시 확인하도록 남겨 둔다", async () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    await leaveUnfinishedRecording();
    const requested = vi.fn();
    mockServer.use(
      http.get(DETAIL_PATH, () => {
        requested();

        return new HttpResponse(null, { status: 503 });
      }),
    );

    renderCheck();

    await waitFor(() => expect(requested).toHaveBeenCalled());
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(getUnfinishedRecording()).toEqual(RECORDING);
  });
});
