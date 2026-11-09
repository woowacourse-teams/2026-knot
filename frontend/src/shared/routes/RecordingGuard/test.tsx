import { currentRecordingResponse } from "@api/mock/responses/recording";
import { mockServer } from "@api/mock/server";
import { useRecordingStore } from "@store/recordingStore";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import {
  clearRecordingStartProof,
  getRecordingStartProof,
  saveRecordingResumeProof,
} from "@utils/recordingControlProof";
import { http, HttpResponse } from "msw";
import { createMemoryRouter, RouterProvider } from "react-router";
import { afterEach, describe, expect, it } from "vitest";

import { getRouterPath, PATH_ROUTE } from "../PATH_ROUTE";

import RecordingGuard from ".";

const WORKSPACE_ID = "1";
const RECORDING_TEXT = "녹음 화면";
const RECORDING_PATH = getRouterPath({
  routeKey: "RECORDING",
  params: { workspaceId: WORKSPACE_ID },
});
const HOME_PATH = getRouterPath({
  routeKey: "WORKSPACE_HOME",
  params: { workspaceId: WORKSPACE_ID },
});

const renderGuard = () => {
  const router = createMemoryRouter(
    [
      { path: PATH_ROUTE.WORKSPACE_HOME, element: <p>홈 화면</p> },
      {
        element: <RecordingGuard />,
        children: [
          { path: PATH_ROUTE.RECORDING, element: <p>{RECORDING_TEXT}</p> },
        ],
      },
    ],
    { initialEntries: [HOME_PATH, RECORDING_PATH], initialIndex: 1 },
  );

  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <RouterProvider router={router} />
    </QueryClientProvider>,
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

describe("RecordingGuard", () => {
  afterEach(() => {
    // 전역 저장소라 테스트끼리 녹음이 새지 않도록 처음 상태로 되돌려요
    useRecordingStore.getState().discardRecording();
  });

  it("진행 중인 녹음 없이 들어오면 홈으로 보내고, 뒤로 가기로 돌아오지 않는다", async () => {
    const { router } = renderGuard();

    await act(async () => {});

    expect(router.state.location.pathname).toBe(HOME_PATH);
    expect(router.state.historyAction).toBe("REPLACE");
  });

  it("녹음 중이면 녹음 화면에 머문다", async () => {
    await startRecording();
    const { router } = renderGuard();

    await act(async () => {});

    expect(router.state.location.pathname).toBe(RECORDING_PATH);
    expect(screen.getByText(RECORDING_TEXT)).toBeInTheDocument();
  });

  it("들어온 뒤 녹음을 끝내도 끝낸 쪽의 이동을 덮어쓰지 않는다", async () => {
    await startRecording();
    const { router } = renderGuard();

    await act(async () => {
      useRecordingStore.getState().discardRecording();
    });

    expect(router.state.location.pathname).toBe(RECORDING_PATH);
  });

  describe("이 탭을 새로고침했으면 (STT-R26)", () => {
    const CURRENT_PATH = "*/api/v1/workspaces/:workspaceId/recordings/current";
    const leaveResumeProof = (recordingId: number) => {
      getRecordingStartProof();
      saveRecordingResumeProof({
        workspaceId: Number(WORKSPACE_ID),
        recordingId,
        audioMode: "PCM16_CHUNKS_V1",
      });
    };

    afterEach(() => clearRecordingStartProof());

    it("증명의 녹음이 아직 진행 중이면 녹음 화면을 연다", async () => {
      leaveResumeProof(currentRecordingResponse.recordingId);

      renderGuard();

      expect(await screen.findByText(RECORDING_TEXT)).toBeInTheDocument();
    });

    it("증명의 녹음이 끝났으면 홈으로 보낸다", async () => {
      leaveResumeProof(currentRecordingResponse.recordingId);
      mockServer.use(
        http.get(CURRENT_PATH, () => new HttpResponse(null, { status: 204 })),
      );

      const { router } = renderGuard();

      await waitFor(() =>
        expect(router.state.location.pathname).toBe(HOME_PATH),
      );
    });

    it("조회가 실패해도 이어 갈 길이 사라지지 않게 녹음 화면에 둔다", async () => {
      leaveResumeProof(currentRecordingResponse.recordingId);
      mockServer.use(
        http.get(CURRENT_PATH, () => new HttpResponse(null, { status: 503 })),
      );

      const { router } = renderGuard();

      expect(await screen.findByText(RECORDING_TEXT)).toBeInTheDocument();
      expect(router.state.location.pathname).toBe(RECORDING_PATH);
    });

    it("증명 없는 탭은 녹음이 진행 중이어도 홈으로 보낸다 (STT-R28)", () => {
      const { router } = renderGuard();

      expect(router.state.location.pathname).toBe(HOME_PATH);
    });
  });
});
