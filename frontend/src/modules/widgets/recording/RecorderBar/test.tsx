import { recordingEndResponse } from "@api/mock/responses/recording";
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
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import RecorderBar from ".";

const WORKSPACE_ID = "1";
const RECORDING_PATH = getRouterPath({
  routeKey: "RECORDING",
  params: { workspaceId: WORKSPACE_ID },
});
const HOME_PATH = getRouterPath({
  routeKey: "WORKSPACE_HOME",
  params: { workspaceId: WORKSPACE_ID },
});

/** 녹음 상태는 전역 저장소에 있으므로, 녹음 화면과 홈을 오가며 녹음이 이어지는지 봐요. */
const renderRecorderBar = () => {
  const router = createMemoryRouter(
    [
      {
        element: <Outlet />,
        children: [
          { path: PATH_ROUTE.WORKSPACE_HOME, element: <p>홈 화면</p> },
          { path: PATH_ROUTE.RECORDING, element: <RecorderBar /> },
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

/** 녹음 중에 마이크가 빠진 것처럼, 마지막으로 받은 마이크의 트랙을 끊어요. */
const disconnectMicrophone = (stream: MediaStream) => {
  act(() => {
    const [track] = stream.getTracks();
    track.stop();
    track.dispatchEvent(new Event("ended"));
  });
};

const END_RECORDING_DIALOG = "녹음을 끝낼까요?";

const getElapsedTime = () => screen.getByLabelText("녹음한 시간");

const clickEndRecording = async () => {
  await act(async () => {
    fireEvent.click(screen.getByRole("button", { name: "녹음 끝내기" }));
  });
};

/** 끝내기 확인 창에서 버튼을 골라요. 「녹음 끝내기」는 바에도 있어 창 안에서 찾아요. */
const chooseInEndRecordingDialog = async (name: string) => {
  const dialog = screen.getByRole("dialog", { name: END_RECORDING_DIALOG });

  await act(async () => {
    fireEvent.click(within(dialog).getByRole("button", { name }));
  });
};

/** 일시정지·종료 요청 수를 세요 */
const countControlRequests = () => {
  const count = { pause: 0, end: 0 };
  mockServer.events.on("request:start", ({ request }) => {
    if (request.url.endsWith("/pause")) count.pause += 1;
    if (request.url.endsWith("/end")) count.end += 1;
  });

  return count;
};

/** 녹음 시간은 1초마다 다시 그려지므로, 시계를 앞당긴 뒤 그 변화를 반영해요. */
const passSeconds = (seconds: number) => {
  act(() => {
    vi.advanceTimersByTime(seconds * 1000);
  });
};

describe("RecorderBar", () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: false });
  });

  afterEach(() => {
    // 전역 저장소라 테스트끼리 녹음이 새지 않도록 처음 상태로 되돌려요
    useRecordingStore.getState().discardRecording();
    mockServer.events.removeAllListeners();
    vi.useRealTimers();
  });

  it("독에서 시작한 녹음의 시간을 보여준다", async () => {
    await startRecording();
    renderRecorderBar();

    expect(screen.getByText("녹음 중")).toBeInTheDocument();
    expect(getElapsedTime()).toHaveTextContent("00:00");

    passSeconds(3);

    expect(getElapsedTime()).toHaveTextContent("00:03");
  });

  it("일시정지하면 시간이 멈추고, 이어서 녹음하면 멈춘 자리부터 다시 흐른다", async () => {
    await startRecording();
    renderRecorderBar();
    passSeconds(5);

    fireEvent.click(screen.getByRole("button", { name: "일시정지" }));
    passSeconds(10);

    expect(screen.getByText("일시정지")).toBeInTheDocument();
    expect(getElapsedTime()).toHaveTextContent("00:05");

    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "이어서 녹음" }));
    });
    passSeconds(2);

    expect(screen.getByText("녹음 중")).toBeInTheDocument();
    expect(getElapsedTime()).toHaveTextContent("00:07");
  });

  it("다른 화면에 다녀와도 이어지던 녹음을 보여준다", async () => {
    await startRecording();
    const { router } = renderRecorderBar();
    passSeconds(4);

    await act(async () => {
      await router.navigate(HOME_PATH);
    });
    passSeconds(6);
    await act(async () => {
      await router.navigate(RECORDING_PATH);
    });

    expect(getElapsedTime()).toHaveTextContent("00:10");
  });

  describe("녹음 끝내기", () => {
    it("누르면 확인 창을 띄우고 아직 끝내지 않는다", async () => {
      const count = countControlRequests();
      await startRecording();
      renderRecorderBar();

      await clickEndRecording();

      const dialog = screen.getByRole("dialog", { name: END_RECORDING_DIALOG });
      expect(
        within(dialog).getByText(
          "지금까지 녹음한 내용으로 바로 문서를 만들어요.",
        ),
      ).toBeInTheDocument();
      expect(
        within(dialog).getByRole("button", { name: "계속 녹음" }),
      ).toBeInTheDocument();
      expect(
        within(dialog).getByRole("button", { name: "녹음 끝내기" }),
      ).toBeInTheDocument();
      expect(screen.getByText("녹음 중")).toBeInTheDocument();
      expect(count.end).toBe(0);
    });

    it("확인 창의 계속 녹음은 창만 닫고 녹음과 시간을 이어 간다", async () => {
      const count = countControlRequests();
      await startRecording();
      renderRecorderBar();
      passSeconds(3);

      await clickEndRecording();
      await chooseInEndRecordingDialog("계속 녹음");
      passSeconds(2);

      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(screen.getByText("녹음 중")).toBeInTheDocument();
      expect(getElapsedTime()).toHaveTextContent("00:05");
      expect(count).toEqual({ pause: 0, end: 0 });
    });

    it("ESC를 눌러도 계속 녹음과 같다", async () => {
      const count = countControlRequests();
      await startRecording();
      renderRecorderBar();
      passSeconds(3);

      await clickEndRecording();
      fireEvent.keyDown(document, { key: "Escape" });
      passSeconds(2);

      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(screen.getByText("녹음 중")).toBeInTheDocument();
      expect(getElapsedTime()).toHaveTextContent("00:05");
      expect(count).toEqual({ pause: 0, end: 0 });
    });

    it("일시정지 중에 계속 녹음을 고르면 일시정지와 멈춘 시간을 그대로 둔다", async () => {
      const count = countControlRequests();
      await startRecording();
      renderRecorderBar();
      passSeconds(3);
      fireEvent.click(screen.getByRole("button", { name: "일시정지" }));

      await clickEndRecording();
      await chooseInEndRecordingDialog("계속 녹음");
      passSeconds(2);

      expect(screen.getByText("일시정지")).toBeInTheDocument();
      expect(getElapsedTime()).toHaveTextContent("00:03");
      expect(count.end).toBe(0);
    });

    it("확인 창에서 녹음 끝내기를 고르면 창을 닫고 마이크를 끈다", async () => {
      // 끝내기는 요청을 주고받은 뒤 마이크를 끄므로, 응답을 기다릴 수 있게 실제 시계로 돌려요
      vi.useRealTimers();
      const getUserMedia = vi.spyOn(navigator.mediaDevices, "getUserMedia");
      await startRecording();
      const stream = await getUserMedia.mock.results[0].value;
      renderRecorderBar();

      await clickEndRecording();
      await chooseInEndRecordingDialog("녹음 끝내기");

      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      await waitFor(() =>
        expect(stream.getTracks()[0].readyState).toBe("ended"),
      );
    });

    it("끝내는 중에 다시 눌러도 확인 창을 열지 않는다", async () => {
      vi.useRealTimers();
      let respondEnd = () => {};
      mockServer.use(
        http.post(
          "*/api/v1/workspaces/:workspaceId/recordings/:recordingId/end",
          async () => {
            await new Promise<void>((resolve) => {
              respondEnd = resolve;
            });

            return HttpResponse.json(recordingEndResponse);
          },
        ),
      );
      const count = countControlRequests();
      await startRecording();
      const { router } = renderRecorderBar();

      await clickEndRecording();
      await chooseInEndRecordingDialog("녹음 끝내기");
      await waitFor(() => expect(count.end).toBe(1));
      await clickEndRecording();

      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

      await act(async () => respondEnd());
      await waitFor(() =>
        expect(router.state.location.pathname).toBe(HOME_PATH),
      );
      expect(count.end).toBe(1);
    });
  });

  it("녹음 중에 마이크가 끊기면 저절로 일시정지한다", async () => {
    const getUserMedia = vi.spyOn(navigator.mediaDevices, "getUserMedia");
    await startRecording();
    const stream = await getUserMedia.mock.results[0].value;
    renderRecorderBar();
    passSeconds(3);

    disconnectMicrophone(stream);
    passSeconds(5);

    expect(screen.getByText("일시정지")).toBeInTheDocument();
    expect(getElapsedTime()).toHaveTextContent("00:03");
  });

  it("마이크가 끊긴 뒤 이어서 녹음하면 마이크를 다시 받아 이어 간다", async () => {
    const getUserMedia = vi.spyOn(navigator.mediaDevices, "getUserMedia");
    await startRecording();
    const stream = await getUserMedia.mock.results[0].value;
    renderRecorderBar();
    disconnectMicrophone(stream);

    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "이어서 녹음" }));
    });

    expect(getUserMedia).toHaveBeenCalledTimes(2);
    expect(screen.getByText("녹음 중")).toBeInTheDocument();
  });

  it("이어서 녹음할 때 마이크를 받지 못하면 모달을 띄우고 일시정지를 유지한다", async () => {
    const getUserMedia = vi.spyOn(navigator.mediaDevices, "getUserMedia");
    await startRecording();
    const stream = await getUserMedia.mock.results[0].value;
    renderRecorderBar();
    disconnectMicrophone(stream);
    getUserMedia.mockRejectedValueOnce(new DOMException("", "NotAllowedError"));

    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "이어서 녹음" }));
    });

    expect(
      screen.getByRole("dialog", { name: "마이크를 사용할 수 없어요" }),
    ).toBeInTheDocument();
    expect(screen.getByText("일시정지")).toBeInTheDocument();

    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    });

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByText("녹음 중")).toBeInTheDocument();
  });
});
