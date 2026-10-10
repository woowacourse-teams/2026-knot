import { recordingEndResponse } from "@api/mock/responses/recording";
import { mockServer } from "@api/mock/server";
import { ThemeProvider } from "@emotion/react";
import { DialogProvider } from "@provider/context/dialogContext";
import { theme } from "@provider/themeProvider";
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
import { createMemoryRouter, RouterProvider } from "react-router";
import { useRecordingStore } from "@store/recordingStore";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import WorkspaceDock from ".";
import { DOCK_HINT_MAX_SEEN_COUNT, DOCK_HINT_TEXT } from "./constants/dockHint";

const WORKSPACE_ID = "1";
const HOME_PATH = getRouterPath({
  routeKey: "WORKSPACE_HOME",
  params: { workspaceId: WORKSPACE_ID },
});
const CHAT_PATH = getRouterPath({
  routeKey: "CHAT",
  params: { workspaceId: WORKSPACE_ID },
});
const RECORDING_PATH = getRouterPath({
  routeKey: "RECORDING",
  params: { workspaceId: WORKSPACE_ID },
});
const QUESTION = "지난주 회의에서 정해진 것만 뽑아 줘";

/**
 * 실제로는 두 화면이 공유하는 레이아웃에 놓이므로, 홈·탐색을 함께 덮어 화면이 바뀌어도 같은 독이 남게 해요.
 */
const renderDock = (initialPath = HOME_PATH) => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const router = createMemoryRouter(
    [
      {
        path: `${PATH_ROUTE.WORKSPACE_HOME}/*`,
        element: <WorkspaceDock />,
      },
    ],
    { initialEntries: [initialPath] },
  );

  const { unmount } = render(
    <ThemeProvider theme={theme}>
      <QueryClientProvider client={queryClient}>
        <DialogProvider>
          <RouterProvider router={router} />
        </DialogProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  );

  return { router, unmount };
};

const expandDock = () => {
  fireEvent.click(screen.getByRole("button", { name: "무엇이든 요청하기" }));

  return screen.getByRole("textbox", { name: "무엇이든 요청하세요" });
};

describe("WorkspaceDock", () => {
  // 안내를 몇 번, 이번 방문에 보여줬는지 브라우저에 남기므로 테스트마다 지워요
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  it("처음에는 접혀 있어 입력창 대신 버튼만 보여준다", () => {
    renderDock();

    expect(
      screen.getByRole("button", { name: "무엇이든 요청하기" }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });

  it("누르면 질문 입력창으로 펼쳐진다", () => {
    renderDock();

    const field = expandDock();

    expect(field).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "보내기" })).toBeInTheDocument();
  });

  it("내용이 없으면 보낼 수 없다", () => {
    renderDock();
    const field = expandDock();

    expect(screen.getByRole("button", { name: "보내기" })).toBeDisabled();

    fireEvent.change(field, { target: { value: "   " } });

    expect(screen.getByRole("button", { name: "보내기" })).toBeDisabled();
  });

  it("질문을 보내면 탐색 화면으로 이동하며 그 질문을 들고 간다", async () => {
    const { router } = renderDock();
    const field = expandDock();

    fireEvent.change(field, { target: { value: QUESTION } });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "보내기" }));
    });

    expect(router.state.location.pathname).toBe(CHAT_PATH);
    expect(router.state.location.state).toEqual({ question: QUESTION });
  });

  it("보내고 나면 옮겨 간 탐색 화면에서 입력창이 비워진 채 열려 있다", async () => {
    renderDock();
    const field = expandDock();

    fireEvent.change(field, { target: { value: QUESTION } });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "보내기" }));
    });

    expect(
      screen.getByRole("textbox", { name: "무엇이든 요청하세요" }),
    ).toHaveValue("");
  });

  it("탐색 화면에서 보내면 그 화면에 머물고 입력창만 비워진다", async () => {
    const { router } = renderDock(CHAT_PATH);
    const field = screen.getByRole("textbox", { name: "무엇이든 요청하세요" });

    fireEvent.change(field, { target: { value: QUESTION } });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "보내기" }));
    });

    expect(router.state.location.pathname).toBe(CHAT_PATH);
    expect(field).toHaveValue("");
  });

  it("탐색 화면에서는 처음부터 입력창이 열려 있다", () => {
    renderDock(CHAT_PATH);

    expect(
      screen.getByRole("textbox", { name: "무엇이든 요청하세요" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "무엇이든 요청하기" }),
    ).not.toBeInTheDocument();
  });

  it("글자 키를 누르면 입력창이 열리면서 그 글자부터 담긴다", () => {
    renderDock();

    fireEvent.keyDown(document.body, { key: "회" });

    expect(
      screen.getByRole("textbox", { name: "무엇이든 요청하세요" }),
    ).toHaveValue("회");
  });

  it("가로챈 글자 뒤에 이어 적도록 커서를 맨 끝에 둔다", () => {
    renderDock();

    fireEvent.keyDown(document.body, { key: "ㅇ" });

    const field = screen.getByRole("textbox", {
      name: "무엇이든 요청하세요",
    }) as HTMLTextAreaElement;

    // 커서가 앞에 남아 있으면 이어 친 글자가 "ㅇ" 앞에 끼어들어요
    expect(field.selectionStart).toBe(field.value.length);
    expect(field.selectionEnd).toBe(field.value.length);
  });

  it("단축키 조합이나 글자가 아닌 키는 가로채지 않는다", () => {
    renderDock();

    fireEvent.keyDown(document.body, { key: "k", metaKey: true });
    fireEvent.keyDown(document.body, { key: "Tab" });
    fireEvent.keyDown(document.body, { key: "ArrowDown" });

    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });

  it("이미 입력창에 적고 있을 때는 가로채지 않는다", () => {
    renderDock();
    const field = expandDock();

    fireEvent.change(field, { target: { value: QUESTION } });
    fireEvent.keyDown(field, { key: "a" });

    expect(field).toHaveValue(QUESTION);
  });

  it("독 안내는 처음 세 번 방문할 때까지만 보여준다", () => {
    for (let visit = 1; visit <= DOCK_HINT_MAX_SEEN_COUNT; visit += 1) {
      const { unmount } = renderDock();

      expect(screen.getByText(DOCK_HINT_TEXT)).toBeInTheDocument();

      unmount();
      sessionStorage.clear(); // 탭을 닫고 다시 들어온 셈이에요
    }

    renderDock();

    expect(screen.queryByText(DOCK_HINT_TEXT)).not.toBeInTheDocument();
  });

  it("같은 방문 안에서 다시 그려도 방문 횟수를 더 쓰지 않는다", () => {
    // 새로고침이나 홈·탐색 오가기로 독이 다시 그려져도 같은 방문이에요
    for (let render = 1; render <= DOCK_HINT_MAX_SEEN_COUNT + 2; render += 1) {
      const { unmount } = renderDock();

      expect(screen.getByText(DOCK_HINT_TEXT)).toBeInTheDocument();

      unmount();
    }

    sessionStorage.clear();
    renderDock();

    expect(screen.getByText(DOCK_HINT_TEXT)).toBeInTheDocument();
  });

  it("독을 열면 안내는 사라진다", () => {
    renderDock();

    expect(screen.getByText(DOCK_HINT_TEXT)).toBeInTheDocument();

    expandDock();

    expect(screen.queryByText(DOCK_HINT_TEXT)).not.toBeInTheDocument();
  });

  it("이미 열려 있는 탐색 화면에서는 안내를 보여주지 않는다", () => {
    renderDock(CHAT_PATH);

    expect(screen.queryByText(DOCK_HINT_TEXT)).not.toBeInTheDocument();
  });

  it("독 바깥을 누르면 접히고, 적던 글은 다시 열 때 그대로 있다", () => {
    renderDock();
    const field = expandDock();

    fireEvent.change(field, { target: { value: QUESTION } });
    fireEvent.pointerDown(document.body);

    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();

    expect(expandDock()).toHaveValue(QUESTION);
  });

  it("독 안을 누르는 것으로는 접히지 않는다", () => {
    renderDock();
    const field = expandDock();

    fireEvent.pointerDown(field);

    expect(field).toBeInTheDocument();
  });

  it("늘 펼쳐 두는 탐색 화면은 바깥을 눌러도 접히지 않는다", () => {
    renderDock(CHAT_PATH);

    fireEvent.pointerDown(document.body);

    expect(
      screen.getByRole("textbox", { name: "무엇이든 요청하세요" }),
    ).toBeInTheDocument();
  });

  it("펼치면 커서가 입력창에 놓인다", () => {
    renderDock();

    expect(expandDock()).toHaveFocus();
  });

  it("탐색 이동은 push라 뒤로 가기 때 원래 화면으로 돌아온다", async () => {
    const { router } = renderDock();
    const field = expandDock();

    fireEvent.change(field, { target: { value: QUESTION } });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "보내기" }));
    });
    await act(async () => {
      await router.navigate(-1);
    });

    expect(router.state.location.pathname).toBe(HOME_PATH);
  });

  describe("회의 녹음 마이크", () => {
    const MIC_UNAVAILABLE = "마이크를 사용할 수 없어요";

    afterEach(() => {
      // 전역 저장소라 테스트끼리 녹음이 새지 않도록 처음 상태로 되돌려요
      useRecordingStore.getState().discardRecording();
      mockServer.events.removeAllListeners();
    });

    const clickMic = async (name = "회의 녹음 시작") => {
      await act(async () => {
        fireEvent.click(screen.getByRole("button", { name }));
      });
    };

    /** 독에서 시작한 것처럼 마이크를 받고 서버 녹음 세션으로 녹음을 시작해 둬요 */
    const startRecording = async () => {
      await act(async () => {
        await useRecordingStore.getState().connectMicrophone();
        useRecordingStore.getState().startRecording({
          workspaceId: Number(WORKSPACE_ID),
          recordingId: 10,
        });
      });
    };

    const denyMicrophoneOnce = () =>
      vi
        .spyOn(navigator.mediaDevices, "getUserMedia")
        .mockRejectedValueOnce(new DOMException("", "NotAllowedError"));

    const END_RECORDING_DIALOG = "녹음을 끝낼까요?";

    /** 끝내기 확인 창에서 버튼을 골라요. 「녹음 끝내기」는 녹음 칩의 중지에도 있어 창 안에서 찾아요. */
    const chooseInEndRecordingDialog = async (name: string) => {
      const dialog = screen.getByRole("dialog", { name: END_RECORDING_DIALOG });

      await act(async () => {
        fireEvent.click(within(dialog).getByRole("button", { name }));
      });
    };

    /** 녹음 칩의 중지를 누르고 확인 창에서 「녹음 끝내기」를 골라요 */
    const stopRecording = async () => {
      await clickMic("녹음 끝내기");
      await chooseInEndRecordingDialog("녹음 끝내기");
    };

    it("접힌 독과 펼친 독 모두에 마이크가 있다", () => {
      renderDock();

      expect(
        screen.getByRole("button", { name: "회의 녹음 시작" }),
      ).toBeInTheDocument();

      expandDock();

      expect(
        screen.getByRole("button", { name: "회의 녹음 시작" }),
      ).toBeInTheDocument();
    });

    it("마이크를 누르면 권한을 받아 녹음을 시작하고 녹음 화면으로 간다", async () => {
      const getUserMedia = vi.spyOn(navigator.mediaDevices, "getUserMedia");
      const { router } = renderDock();

      await clickMic();

      expect(getUserMedia).toHaveBeenCalledWith({ audio: true });
      expect(useRecordingStore.getState().status).toBe("recording");
      expect(router.state.location.pathname).toBe(RECORDING_PATH);
    });

    it("녹음 화면에서는 마이크를 숨긴다", () => {
      renderDock(RECORDING_PATH);

      expect(
        screen.queryByRole("button", { name: "회의 녹음 시작" }),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: "녹음 화면으로 이동" }),
      ).not.toBeInTheDocument();
    });

    it("권한을 받지 못하면 모달을 띄우고 제자리에 남는다", async () => {
      denyMicrophoneOnce();
      const { router } = renderDock();

      await clickMic();

      expect(
        screen.getByRole("dialog", { name: MIC_UNAVAILABLE }),
      ).toBeInTheDocument();
      expect(useRecordingStore.getState().status).toBe("idle");
      expect(router.state.location.pathname).toBe(HOME_PATH);
    });

    it("webm으로 녹음할 수 없는 브라우저면 권한을 묻지 않고 제자리에 남는다", async () => {
      vi.spyOn(MediaRecorder, "isTypeSupported").mockReturnValueOnce(false);
      const getUserMedia = vi.spyOn(navigator.mediaDevices, "getUserMedia");
      const { router } = renderDock();

      await clickMic();

      expect(getUserMedia).not.toHaveBeenCalled();
      expect(useRecordingStore.getState().status).toBe("idle");
      expect(router.state.location.pathname).toBe(HOME_PATH);
    });

    it("모달의 닫기는 모달만 닫고 제자리에 남는다", async () => {
      denyMicrophoneOnce();
      const { router } = renderDock();
      await clickMic();

      fireEvent.click(screen.getByRole("button", { name: "닫기" }));

      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(router.state.location.pathname).toBe(HOME_PATH);
    });

    it("ESC를 눌러도 닫기와 같다", async () => {
      denyMicrophoneOnce();
      renderDock();
      await clickMic();

      fireEvent.keyDown(document, { key: "Escape" });

      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });

    it("모달의 다시 시도로 권한을 받으면 녹음 화면으로 간다", async () => {
      denyMicrophoneOnce();
      const { router } = renderDock();
      await clickMic();

      await clickMic("다시 시도");

      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(router.state.location.pathname).toBe(RECORDING_PATH);
    });

    it("이미 녹음 중이면 권한을 다시 묻지 않고 녹음 화면으로 간다", async () => {
      await startRecording();
      const getUserMedia = vi.spyOn(navigator.mediaDevices, "getUserMedia");
      const { router } = renderDock();

      await clickMic("녹음 화면으로 이동");

      expect(getUserMedia).not.toHaveBeenCalled();
      expect(router.state.location.pathname).toBe(RECORDING_PATH);
    });

    describe("녹음 화면이 아닌 곳에서 녹음 중이면", () => {
      beforeEach(async () => {
        await startRecording();
      });

      it("접힌 독과 펼친 독 모두 마이크 대신 녹음한 시간을 보여준다", () => {
        renderDock();

        expect(
          screen.queryByRole("button", { name: "회의 녹음 시작" }),
        ).not.toBeInTheDocument();
        expect(
          screen.getByRole("button", { name: "녹음 화면으로 이동" }),
        ).toHaveTextContent("00:00");

        expandDock();

        expect(
          screen.queryByRole("button", { name: "회의 녹음 시작" }),
        ).not.toBeInTheDocument();
        expect(
          screen.getByRole("button", { name: "녹음 화면으로 이동" }),
        ).toHaveTextContent("00:00");
      });

      it("중지 버튼은 접힌 독에만 있다", () => {
        renderDock();

        expect(
          screen.getByRole("button", { name: "녹음 끝내기" }),
        ).toBeInTheDocument();

        expandDock();

        expect(
          screen.queryByRole("button", { name: "녹음 끝내기" }),
        ).not.toBeInTheDocument();
      });

      it("중지를 누르면 확인 창을 띄우고 아직 끝내지 않는다", async () => {
        let endRequestCount = 0;
        mockServer.events.on("request:start", ({ request }) => {
          if (request.url.endsWith("/end")) endRequestCount += 1;
        });
        renderDock();

        await clickMic("녹음 끝내기");

        expect(
          screen.getByRole("dialog", { name: END_RECORDING_DIALOG }),
        ).toBeInTheDocument();
        expect(useRecordingStore.getState().status).toBe("recording");
        expect(endRequestCount).toBe(0);
      });

      it("확인 창의 계속 녹음은 창만 닫고 녹음 칩을 그대로 둔다", async () => {
        renderDock();

        await clickMic("녹음 끝내기");
        await chooseInEndRecordingDialog("계속 녹음");

        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
        expect(useRecordingStore.getState().status).toBe("recording");
        expect(
          screen.getByRole("button", { name: "녹음 화면으로 이동" }),
        ).toBeInTheDocument();
      });

      it("확인 창에서 녹음 끝내기를 고르면 녹음을 끝내고 제자리에서 마이크로 돌아간다", async () => {
        const { router } = renderDock();

        await stopRecording();

        await waitFor(() =>
          expect(useRecordingStore.getState().status).toBe("idle"),
        );
        expect(router.state.location.pathname).toBe(HOME_PATH);
        expect(
          screen.getByRole("button", { name: "회의 녹음 시작" }),
        ).toBeInTheDocument();
      });

      it("녹음 화면에서는 녹음 칩도 숨긴다", () => {
        renderDock(RECORDING_PATH);

        expect(
          screen.queryByRole("button", { name: "녹음 화면으로 이동" }),
        ).not.toBeInTheDocument();
        expect(
          screen.queryByRole("button", { name: "녹음 끝내기" }),
        ).not.toBeInTheDocument();
      });
    });

    describe("녹음을 끝내는 요청이 오가는 중이면", () => {
      const END_PATH =
        "*/api/v1/workspaces/:workspaceId/recordings/:recordingId/end";

      beforeEach(async () => {
        await act(async () => {
          await useRecordingStore.getState().connectMicrophone();
          useRecordingStore.getState().startRecording({
            workspaceId: Number(WORKSPACE_ID),
            recordingId: 10,
          });
        });
      });

      it("중지를 다시 눌러도 확인 창을 열지 않고 종료 요청은 한 번만 보낸다", async () => {
        let endRequestCount = 0;
        let respondEnd = () => {};
        mockServer.use(
          http.post(END_PATH, async () => {
            endRequestCount += 1;
            await new Promise<void>((resolve) => {
              respondEnd = resolve;
            });

            return HttpResponse.json(recordingEndResponse);
          }),
        );
        renderDock();

        await stopRecording();
        await waitFor(() => expect(endRequestCount).toBe(1));
        await clickMic("녹음 끝내기");

        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

        await act(async () => respondEnd());

        await waitFor(() =>
          expect(useRecordingStore.getState().status).toBe("idle"),
        );
        expect(endRequestCount).toBe(1);
      });

      it("종료 요청이 실패하면 녹음을 그대로 두고 다시 끝낼 수 있다", async () => {
        let endRequestCount = 0;
        mockServer.use(
          http.post(END_PATH, () => {
            endRequestCount += 1;

            return new HttpResponse(null, { status: 500 });
          }),
        );
        vi.spyOn(console, "error").mockImplementation(() => {});
        renderDock();

        await stopRecording();
        await waitFor(() => expect(endRequestCount).toBe(1));
        await waitFor(() =>
          expect(useRecordingStore.getState().isEnding).toBe(false),
        );
        expect(useRecordingStore.getState().status).toBe("recording");

        await stopRecording();

        await waitFor(() => expect(endRequestCount).toBe(2));
      });
    });

    describe("녹음 중 마이크가 끊기면", () => {
      const startAndDisconnect = async () => {
        const getUserMedia = vi.spyOn(navigator.mediaDevices, "getUserMedia");
        await startRecording();
        const stream: MediaStream = await getUserMedia.mock.results[0].value;
        renderDock();

        act(() => {
          const [track] = stream.getTracks();
          track.stop();
          track.dispatchEvent(new Event("ended"));
        });

        return { getUserMedia };
      };

      it("일시정지하고 모달을 띄운다", async () => {
        await startAndDisconnect();

        expect(useRecordingStore.getState().status).toBe("paused");
        expect(
          screen.getByRole("dialog", { name: MIC_UNAVAILABLE }),
        ).toBeInTheDocument();
      });

      it("닫기를 누르면 일시정지를 유지한다", async () => {
        await startAndDisconnect();

        fireEvent.click(screen.getByRole("button", { name: "닫기" }));

        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
        expect(useRecordingStore.getState().status).toBe("paused");
      });

      it("다시 시도로 마이크를 받으면 녹음을 이어 간다", async () => {
        const { getUserMedia } = await startAndDisconnect();

        await clickMic("다시 시도");

        expect(getUserMedia).toHaveBeenCalledTimes(2);
        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
        expect(useRecordingStore.getState().status).toBe("recording");
      });
    });
  });
});
