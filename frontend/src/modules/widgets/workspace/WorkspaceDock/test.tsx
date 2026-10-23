import { GetCurrentRecordingResponseDto } from "@api/dto/recording";
import { CURRENT_RECORDING_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/current";
import {
  currentRecordingResponse,
  recordingEndResponse,
  recordingStartResponse,
} from "@api/mock/responses/recording";
import { searchAnswerStreamResponse } from "@api/mock/responses/search";
import { mockServer } from "@api/mock/server";
import { TOAST_MESSAGES } from "@constants/toastMessages";
import { ThemeProvider } from "@emotion/react";
import { DialogProvider } from "@provider/context/dialogContext";
import { ToastProvider } from "@provider/context/toastContext";
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
  within,
} from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { createMemoryRouter, Outlet, RouterProvider } from "react-router";
import { useRecordingStore } from "@store/recordingStore";
import { useSearchAnswerStore } from "@store/searchAnswerStore";
import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { formatRecordingTime } from "@utils/formatRecordingTime";
import { getRecordingStartProof } from "@utils/recordingControlProof";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import WorkspaceDock from ".";
import {
  DOCK_GUIDE_COPY,
  DOCK_HINT_DELAY_MS,
  DOCK_HINT_MAX_SEEN_COUNT,
  DOCK_HINT_SEEN_COUNT_KEY,
  DOCK_HOVER_GUIDE_DELAY_MS,
  DOCK_HOVER_GUIDE_MAX_PER_VISIT,
} from "./constants/dockHint";

const DOCK_HINT_TEXT = DOCK_GUIDE_COPY.first.title;

/** 첫 사용 안내가 뜨기까지 기다리는 시간만큼 기다려요. 안 떠야 하는 경우를 볼 때 써요 */
const waitForHintDelay = () =>
  act(() => new Promise((resolve) => setTimeout(resolve, DOCK_HINT_DELAY_MS + 50)));

/** 첫 사용 안내를 이미 다 본 사용자로 만들어요 */
const markHintAsSeen = () =>
  localStorage.setItem(DOCK_HINT_SEEN_COUNT_KEY, String(DOCK_HINT_MAX_SEEN_COUNT));

// 이 파일은 WebM 녹음만 보므로, PCM 켜기(#799) 뒤에도 WebM으로 고정해요. PCM 시작은 useRecordingControl 테스트가 봐요
vi.mock("@constants/recording", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@constants/recording")>()),
  RECORDING_AUDIO_MODE: "FINAL_WEBM_V1",
}));

vi.mock("@utils/captureAnalyticsEvent", () => ({
  captureAnalyticsEvent: vi.fn(),
}));

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
const RECORDING_ID = 10;
const DOCUMENTS_PATH = getRouterPath({
  routeKey: "RECORDING_DOCUMENTS",
  params: { workspaceId: WORKSPACE_ID, recordingId: String(RECORDING_ID) },
});
const CONVERSATION_ID = "10";
const CONVERSATION_PATH = getRouterPath({
  routeKey: "CHAT_SESSION",
  params: { workspaceId: WORKSPACE_ID, sessionId: CONVERSATION_ID },
});
/** 첫 질문을 접수하면 옮겨 가는 대화 주소. 기본 mock이 accepted로 주는 대화예요 */
const ACCEPTED_CONVERSATION_PATH = getRouterPath({
  routeKey: "CHAT_SESSION",
  params: {
    workspaceId: WORKSPACE_ID,
    sessionId: String(searchAnswerStreamResponse.conversationId),
  },
});
const QUESTION = "지난주 회의에서 정해진 것만 뽑아 줘";
const SEARCH_CONVERSATIONS_PATH =
  "*/api/v1/workspaces/:workspaceId/search/conversations";
const CURRENT_RECORDING_URL = `*${CURRENT_RECORDING_API_PATH(Number(WORKSPACE_ID))}`;
/** 다른 탭 녹음 칩에 보이는 시간. 응답을 받은 직후라 누적 시간 그대로예요 */
const OTHER_TAB_ELAPSED_TIME = formatRecordingTime(
  new GetCurrentRecordingResponseDto(currentRecordingResponse).elapsedMillis /
    1000,
);

const respondNoCurrentRecording = () =>
  mockServer.use(
    http.get(
      CURRENT_RECORDING_URL,
      () => new HttpResponse(null, { status: 204 }),
    ),
  );

/** 현재 녹음 조회가 다른 탭의 녹음을 이 상태로 답하게 해요 */
const respondCurrentRecordingStatus = (
  status:
    | "RECORDING"
    | "PAUSED"
    | "PROCESSING"
    | "TRANSCRIPTION_FAILED"
    | "DOCUMENT_FAILED",
) =>
  mockServer.use(
    http.get(CURRENT_RECORDING_URL, () =>
      HttpResponse.json({
        ...currentRecordingResponse,
        status,
        // 실패 상태에서만 뜻이 있는 값. 다른 상태에서는 서버가 어떻게 주든 독의 판단이 달라지지 않아요
        retryable: true,
      }),
    ),
  );

/** 탐색 질문 요청(첫 질문·후속 질문)의 경로를 모아요 */
const collectSearchQuestionPaths = () => {
  const paths: string[] = [];
  mockServer.events.on("request:start", ({ request }) => {
    const { pathname } = new URL(request.url);
    if (request.method === "POST" && pathname.includes("/search/")) {
      paths.push(pathname);
    }
  });

  return paths;
};

/** 이번 테스트에서만 첫 질문 응답이 끝나지 않고 열려 있게 해요 */
const respondWithEndlessSearchStream = () =>
  mockServer.use(
    http.post(
      SEARCH_CONVERSATIONS_PATH,
      () =>
        new HttpResponse(new ReadableStream(), {
          headers: { "Content-Type": "text/event-stream" },
        }),
    ),
  );

/**
 * 실제로는 여러 화면이 공유하는 레이아웃에 놓이므로, 독을 레이아웃 자리에 두어 화면이 바뀌어도 같은 독이 남게 해요.
 */
const renderDock = (initialPath = HOME_PATH) => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
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
            element: <WorkspaceDock />,
            children: [
              PATH_ROUTE.WORKSPACE_HOME,
              PATH_ROUTE.CHAT,
              PATH_ROUTE.CHAT_SESSION,
              PATH_ROUTE.RECORDING,
              PATH_ROUTE.RECORDING_DOCUMENTS,
            ].map((path) => ({ path })),
          },
        ],
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

/** 펼친 독에만 보내기 버튼이 있어요. 접힌 독에도 입력창은 보이지 않게 남아 있어 입력창으로는 가를 수 없어요 */
const isDockExpanded = () =>
  screen.queryByRole("button", { name: "보내기" }) !== null;

const expandDock = () => {
  fireEvent.click(screen.getByRole("button", { name: "무엇이든 요청하기" }));

  return screen.getByRole("textbox", { name: "무엇이든 요청하세요" });
};

describe("WorkspaceDock", () => {
  // 안내를 몇 번, 이번 방문에 보여줬는지 브라우저에 남기므로 테스트마다 지워요
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    // 기본 mock은 다른 탭이 녹음 중이라고 답해 마이크가 칩으로 바뀌므로, 다른 탭 녹음이 없는 상태를 기본으로 둬요
    respondNoCurrentRecording();
  });

  afterEach(() => {
    // 전역 저장소라 테스트끼리 답변이 새지 않게 비워요
    useSearchAnswerStore.getState().abort();
    mockServer.events.removeAllListeners();
  });

  /** 펼친 입력창에 질문을 적어 보내기를 눌러요 */
  const sendQuestion = async (question = QUESTION) => {
    fireEvent.change(
      screen.getByRole("textbox", { name: "무엇이든 요청하세요" }),
      { target: { value: question } },
    );
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "보내기" }));
    });
  };

  it("처음에는 접혀 있어 버튼만 보여준다", () => {
    renderDock();

    expect(
      screen.getByRole("button", { name: "무엇이든 요청하기" }),
    ).toBeInTheDocument();
    expect(isDockExpanded()).toBe(false);
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

  it("홈에서 질문을 보내면 첫 질문으로 보내고, 접수되면 그 대화 주소로 옮겨 간다 (SEARCH-R1)", async () => {
    const paths = collectSearchQuestionPaths();
    const { router } = renderDock();
    expandDock();

    await sendQuestion();

    await waitFor(() =>
      expect(router.state.location.pathname).toBe(ACCEPTED_CONVERSATION_PATH),
    );
    expect(paths).toEqual([
      `/api/v1/workspaces/${WORKSPACE_ID}/search/conversations`,
    ]);
  });

  it("보내면 접수를 기다리지 않고 입력창을 바로 비운다 (근거 없음)", async () => {
    respondWithEndlessSearchStream();
    renderDock();
    expandDock();

    await sendQuestion();

    expect(
      screen.getByRole("textbox", { name: "무엇이든 요청하세요" }),
    ).toHaveValue("");
  });

  it("홈에서 보낸 첫 질문의 대화 이동은 push라 뒤로 가기 때 홈으로 돌아온다 (근거 없음)", async () => {
    const { router } = renderDock();
    expandDock();

    await sendQuestion();
    await waitFor(() =>
      expect(router.state.location.pathname).toBe(ACCEPTED_CONVERSATION_PATH),
    );
    await act(async () => {
      await router.navigate(-1);
    });

    expect(router.state.location.pathname).toBe(HOME_PATH);
  });

  it("탐색 새 대화 화면에서 보내면 접수된 대화 주소로 바꿔치기한다 (근거 없음)", async () => {
    const { router } = renderDock(CHAT_PATH);

    await sendQuestion();

    await waitFor(() =>
      expect(router.state.location.pathname).toBe(ACCEPTED_CONVERSATION_PATH),
    );
    expect(router.state.historyAction).toBe("REPLACE");
  });

  it("탐색 대화 화면에서 보내면 그 대화의 후속 질문으로 보내고 그 화면에 머문다 (SEARCH-R1)", async () => {
    const paths = collectSearchQuestionPaths();
    const { router } = renderDock(CONVERSATION_PATH);

    await sendQuestion();

    await waitFor(() => expect(paths).toHaveLength(1));
    expect(paths).toEqual([
      `/api/v1/workspaces/${WORKSPACE_ID}/search/conversations/${CONVERSATION_ID}/questions`,
    ]);
    expect(router.state.location.pathname).toBe(CONVERSATION_PATH);
  });

  it("접수 전에 질문이 거절되면 보낸 질문을 입력창에 되돌리고 오류 토스트를 띄운다 (SEARCH-R4)", async () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    mockServer.use(
      http.post(SEARCH_CONVERSATIONS_PATH, () =>
        HttpResponse.json(
          { code: "FORBIDDEN", message: "요청을 처리하지 못했어요" },
          { status: 403 },
        ),
      ),
    );
    renderDock(CHAT_PATH);

    await sendQuestion();

    await waitFor(() =>
      expect(
        screen.getByRole("textbox", { name: "무엇이든 요청하세요" }),
      ).toHaveValue(QUESTION),
    );
    expect(
      await screen.findByText(TOAST_MESSAGES.QUESTION_SEND_FAILED.message),
    ).toBeInTheDocument();
  });

  it("질문이 접수되면 전송 실패 토스트를 띄우지 않는다 (SEARCH-R4)", async () => {
    renderDock(CHAT_PATH);

    await sendQuestion();

    await waitFor(() =>
      expect(
        screen.getByRole("textbox", { name: "무엇이든 요청하세요" }),
      ).toHaveValue(""),
    );
    await act(() => new Promise((resolve) => setTimeout(resolve, 50)));
    expect(
      screen.queryByText(TOAST_MESSAGES.QUESTION_SEND_FAILED.message),
    ).not.toBeInTheDocument();
  });

  it("답변을 기다리거나 받는 중에는 다시 보내지 않는다 (SEARCH-R6)", async () => {
    respondWithEndlessSearchStream();
    const paths = collectSearchQuestionPaths();
    renderDock(CHAT_PATH);

    await sendQuestion();
    await waitFor(() => expect(paths).toHaveLength(1));
    fireEvent.change(
      screen.getByRole("textbox", { name: "무엇이든 요청하세요" }),
      { target: { value: "다른 질문" } },
    );
    await act(async () => {
      fireEvent.submit(screen.getByRole("textbox").closest("form")!);
    });

    expect(paths).toHaveLength(1);
    expect(screen.getByRole("button", { name: "보내기" })).toBeDisabled();
    expect(
      screen.getByRole("textbox", { name: "무엇이든 요청하세요" }),
    ).toHaveValue("다른 질문");
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

  /** 글자 키를 누르고, 그 키가 처리되는 동안 커서가 어디 있었는지와 기본 동작이 살아 있는지 돌려줘요 */
  const pressKeyOutsideField = (key: string) => {
    let focusedWhilePressing: Element | null = null;
    // document보다 늦게 받는 window에서 봐야 독이 키를 다 처리한 뒤의 자리를 볼 수 있어요
    const recordFocus = () => (focusedWhilePressing = document.activeElement);
    window.addEventListener("keydown", recordFocus);

    const isDefaultKept = fireEvent.keyDown(document.body, { key });

    window.removeEventListener("keydown", recordFocus);

    return { focusedWhilePressing, isDefaultKept };
  };

  it("커서가 입력창 밖에 있을 때 글자 키를 누르면, 그 키가 입력창에 들어가도록 누르는 사이에 커서를 옮긴다", () => {
    renderDock();
    screen.getByRole("textbox", { name: "무엇이든 요청하세요" }).blur();

    const { focusedWhilePressing, isDefaultKept } = pressKeyOutsideField("a");

    const field = screen.getByRole("textbox", { name: "무엇이든 요청하세요" });
    expect(focusedWhilePressing).toBe(field);
    expect(isDefaultKept).toBe(true);
  });

  it("적던 글이 있으면 이어 적도록 커서를 맨 끝에 둔다", () => {
    renderDock();
    const field = expandDock();
    fireEvent.change(field, { target: { value: QUESTION } });
    fireEvent.pointerDown(document.body);
    field.blur();

    pressKeyOutsideField("a");

    const reopenedField = screen.getByRole("textbox", {
      name: "무엇이든 요청하세요",
    }) as HTMLTextAreaElement;
    expect(reopenedField.selectionStart).toBe(QUESTION.length);
    expect(reopenedField.selectionEnd).toBe(QUESTION.length);
  });

  it("단축키 조합이나 글자가 아닌 키는 가로채지 않는다", () => {
    renderDock();
    const slotButton = screen.getByRole("button", {
      name: "무엇이든 요청하기",
    });
    slotButton.focus();

    fireEvent.keyDown(slotButton, { key: "k", metaKey: true });
    fireEvent.keyDown(slotButton, { key: "Tab" });
    fireEvent.keyDown(slotButton, { key: "ArrowDown" });

    expect(slotButton).toHaveFocus();
  });

  it("이미 입력창에 적고 있을 때는 가로채지 않는다", () => {
    renderDock();
    const field = expandDock();

    fireEvent.change(field, { target: { value: QUESTION } });
    fireEvent.keyDown(field, { key: "a" });

    expect(field).toHaveValue(QUESTION);
  });

  it("독 안내는 홈에서 처음 세 번 방문할 때까지만 보여준다", async () => {
    for (let visit = 1; visit <= DOCK_HINT_MAX_SEEN_COUNT; visit += 1) {
      const { unmount } = renderDock();

      expect(await screen.findByText(DOCK_HINT_TEXT)).toBeInTheDocument();

      unmount();
      sessionStorage.clear(); // 탭을 닫고 다시 들어온 셈이에요
    }

    renderDock();
    await waitForHintDelay();

    expect(screen.queryByText(DOCK_HINT_TEXT)).not.toBeInTheDocument();
  });

  it("같은 방문 안에서 다시 그려도 방문 횟수를 더 쓰지 않는다", async () => {
    // 새로고침이나 홈·탐색 오가기로 독이 다시 그려져도 같은 방문이에요
    for (let render = 1; render <= DOCK_HINT_MAX_SEEN_COUNT + 2; render += 1) {
      const { unmount } = renderDock();

      expect(await screen.findByText(DOCK_HINT_TEXT)).toBeInTheDocument();

      unmount();
    }

    sessionStorage.clear();
    renderDock();

    expect(await screen.findByText(DOCK_HINT_TEXT)).toBeInTheDocument();
  });

  it("독을 열면 안내는 사라지고 이번 방문에는 다시 뜨지 않는다", async () => {
    const { unmount } = renderDock();

    expect(await screen.findByText(DOCK_HINT_TEXT)).toBeInTheDocument();

    expandDock();

    await waitFor(() => expect(screen.queryByText(DOCK_HINT_TEXT)).not.toBeInTheDocument());

    unmount();
    renderDock();
    await waitForHintDelay();

    expect(screen.queryByText(DOCK_HINT_TEXT)).not.toBeInTheDocument();
  });

  it("다른 탭에서 녹음 중이면 안내를 띄우지 않는다", async () => {
    respondCurrentRecordingStatus("RECORDING");
    renderDock();
    await waitForHintDelay();

    expect(screen.queryByText(DOCK_HINT_TEXT)).not.toBeInTheDocument();
  });

  it("안내가 없을 때 채팅 버튼에 올리면 한 줄 안내가 떴다가 벗어나면 사라진다", async () => {
    markHintAsSeen();
    renderDock();

    const chatTarget = screen.getByRole("button", { name: "무엇이든 요청하기" }).parentElement as HTMLElement;
    fireEvent.pointerEnter(chatTarget, { pointerType: "mouse" });

    expect(await screen.findByText(DOCK_GUIDE_COPY.chat)).toBeInTheDocument();

    fireEvent.pointerLeave(chatTarget);

    // 사라지는 모션만큼 잠깐 더 그려 두었다가 떼요
    await waitFor(() => expect(screen.queryByText(DOCK_GUIDE_COPY.chat)).not.toBeInTheDocument());
  });

  it("버튼 안내는 한 방문에서 버튼마다 두 번까지만 뜬다", async () => {
    markHintAsSeen();
    renderDock();

    const chatTarget = screen.getByRole("button", { name: "무엇이든 요청하기" }).parentElement as HTMLElement;
    for (let shown = 1; shown <= DOCK_HOVER_GUIDE_MAX_PER_VISIT; shown += 1) {
      fireEvent.pointerEnter(chatTarget, { pointerType: "mouse" });
      expect(await screen.findByText(DOCK_GUIDE_COPY.chat)).toBeInTheDocument();
      fireEvent.pointerLeave(chatTarget);
      await waitFor(() => expect(screen.queryByText(DOCK_GUIDE_COPY.chat)).not.toBeInTheDocument());
    }

    fireEvent.pointerEnter(chatTarget, { pointerType: "mouse" });
    await act(() => new Promise((resolve) => setTimeout(resolve, DOCK_HOVER_GUIDE_DELAY_MS + 50)));

    expect(screen.queryByText(DOCK_GUIDE_COPY.chat)).not.toBeInTheDocument();
  });

  it("안내를 띄우는 세 번의 방문이 지나면 버튼 안내도 뜨지 않는다", async () => {
    localStorage.setItem(DOCK_HINT_SEEN_COUNT_KEY, String(DOCK_HINT_MAX_SEEN_COUNT + 1));
    renderDock();

    const chatTarget = screen.getByRole("button", { name: "무엇이든 요청하기" }).parentElement as HTMLElement;
    fireEvent.pointerEnter(chatTarget, { pointerType: "mouse" });
    await act(() => new Promise((resolve) => setTimeout(resolve, DOCK_HOVER_GUIDE_DELAY_MS + 50)));

    expect(screen.queryByText(DOCK_GUIDE_COPY.chat)).not.toBeInTheDocument();
  });

  describe("독을 연 방법 기록", () => {
    beforeEach(() => {
      vi.mocked(captureAnalyticsEvent).mockClear();
    });

    it("요청하기 버튼으로 펼치면 버튼으로 열었다고 남긴다", () => {
      renderDock();

      expandDock();

      expect(captureAnalyticsEvent).toHaveBeenCalledWith({
        event: "dock_chat_opened",
        properties: { trigger: "button" },
      });
    });

    it("접힌 독에 글자가 들어와 펼치면 글자를 쳐서 열었다고 한 번만 남긴다", () => {
      renderDock();
      const field = screen.getByRole("textbox", { name: "무엇이든 요청하세요" });

      fireEvent.change(field, { target: { value: "ㅇ" } });
      fireEvent.change(field, { target: { value: "아" } });

      expect(captureAnalyticsEvent).toHaveBeenCalledTimes(1);
      expect(captureAnalyticsEvent).toHaveBeenCalledWith({
        event: "dock_chat_opened",
        properties: { trigger: "typing" },
      });
    });

    it("늘 펼쳐 둔 탐색 화면에서 적는 것은 남기지 않는다", () => {
      renderDock(CHAT_PATH);

      fireEvent.change(
        screen.getByRole("textbox", { name: "무엇이든 요청하세요" }),
        { target: { value: QUESTION } },
      );

      expect(captureAnalyticsEvent).not.toHaveBeenCalled();
    });
  });

  describe("접힌 독에서 바로 치기", () => {
    // 접힌 독에도 입력창을 보이지 않게 남겨 두고 커서를 둬야, 한글 첫 글자부터 입력기가 조립해요

    it("커서가 아무 데도 없으면 접힌 채로 커서를 입력창에 둔다 (근거 없음)", () => {
      renderDock();

      expect(
        screen.getByRole("textbox", { name: "무엇이든 요청하세요" }),
      ).toHaveFocus();
      expect(isDockExpanded()).toBe(false);
    });

    it("빈 곳을 눌러도 펼치지 않고 커서만 입력창에 둔다 (근거 없음)", () => {
      renderDock();
      const field = screen.getByRole("textbox", {
        name: "무엇이든 요청하세요",
      });

      field.blur();
      fireEvent.click(document.body);

      expect(field).toHaveFocus();
      expect(isDockExpanded()).toBe(false);
    });

    it("글을 드래그해 고른 뒤에는 복사할 수 있게 커서를 옮기지 않는다 (근거 없음)", () => {
      renderDock();
      const field = screen.getByRole("textbox", {
        name: "무엇이든 요청하세요",
      });
      const text = document.createElement("p");
      text.textContent = "답변 내용";
      document.body.append(text);

      field.blur();
      window.getSelection()?.selectAllChildren(text);
      fireEvent.click(text);

      expect(field).not.toHaveFocus();
      text.remove();
    });

    it("글자가 들어오면 같은 입력창 그대로 펼친다 (근거 없음)", () => {
      renderDock();
      const field = screen.getByRole("textbox", {
        name: "무엇이든 요청하세요",
      });

      // 입력기가 조립 중인 "ㅇ"도 입력으로 들어와요
      fireEvent.change(field, { target: { value: "ㅇ" } });

      expect(isDockExpanded()).toBe(true);
      // 입력창이 바뀌면 조립 중이던 글자가 끊겨요
      expect(screen.getByRole("textbox", { name: "무엇이든 요청하세요" })).toBe(
        field,
      );
      expect(field).toHaveFocus();
      expect(field).toHaveValue("ㅇ");
    });

    it("접힌 채로 Enter를 눌러도 남겨 둔 글을 보내지 않는다 (근거 없음)", async () => {
      renderDock();
      const field = expandDock();
      fireEvent.change(field, { target: { value: QUESTION } });
      fireEvent.pointerDown(document.body);

      await act(async () => {
        fireEvent.keyDown(field, { key: "Enter" });
      });

      expect(field).toHaveValue(QUESTION);
    });
  });

  it("이미 열려 있는 탐색 화면에서는 안내를 보여주지 않는다", async () => {
    renderDock(CHAT_PATH);
    await waitForHintDelay();

    expect(screen.queryByText(DOCK_HINT_TEXT)).not.toBeInTheDocument();
  });

  it("독 바깥을 누르면 접히고, 적던 글은 다시 열 때 그대로 있다", () => {
    renderDock();
    const field = expandDock();

    fireEvent.change(field, { target: { value: QUESTION } });
    fireEvent.pointerDown(document.body);

    expect(isDockExpanded()).toBe(false);

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

  describe("회의 녹음 마이크", () => {
    const MIC_UNAVAILABLE = "마이크 권한이 꺼져 있어요";

    const confirmMicAlert = () => {
      const alert = screen.getByRole("alertdialog", { name: MIC_UNAVAILABLE });
      fireEvent.click(within(alert).getByRole("button", { name: "확인" }));
    };

    /** 마이크 권한 알림을 닫는 세 가지 방법 */
    const CLOSE_MIC_ALERT: [string, () => void][] = [
      ["확인", confirmMicAlert],
      ["ESC", () => fireEvent.keyDown(document, { key: "Escape" })],
      [
        "바깥 클릭",
        () => fireEvent.click(screen.getByRole("alertdialog").parentElement!),
      ],
    ];

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

    /** 독에서 시작한 것처럼 증명을 만들고 마이크를 받아 서버 녹음 세션으로 녹음을 시작해 둬요 */
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

    it("녹음이 열리기를 기다리는 동안 마이크를 기다리는 중으로 두고, 녹음 칩으로 바꾸지 않는다", async () => {
      let respond = () => {};
      const isResponded = new Promise<void>((resolve) => {
        respond = resolve;
      });
      mockServer.use(
        http.post("*/api/v1/workspaces/:workspaceId/recordings", async () => {
          await isResponded;
          return HttpResponse.json(recordingStartResponse, { status: 201 });
        }),
      );
      const { router } = renderDock();

      await clickMic();

      expect(
        screen.getByRole("button", { name: "회의 녹음 시작" }),
      ).toHaveAttribute("aria-busy", "true");
      expect(
        screen.queryByRole("button", { name: "녹음 화면으로 이동" }),
      ).not.toBeInTheDocument();

      await act(async () => respond());

      await waitFor(() =>
        expect(router.state.location.pathname).toBe(RECORDING_PATH),
      );
    });

    it("녹음이 열리면 결과와 기다린 시간을 남긴다", async () => {
      vi.mocked(captureAnalyticsEvent).mockClear();
      renderDock();

      await clickMic();

      expect(captureAnalyticsEvent).toHaveBeenCalledWith({
        event: "recording_start_result",
        properties: {
          result: "started",
          wait_seconds: expect.any(Number),
          workspace_id: Number(WORKSPACE_ID),
        },
      });
    });

    it("권한을 받지 못하면 기다림을 풀고 그 결과를 남긴다", async () => {
      vi.mocked(captureAnalyticsEvent).mockClear();
      denyMicrophoneOnce();
      renderDock();

      await clickMic();
      confirmMicAlert();

      expect(
        screen.getByRole("button", { name: "회의 녹음 시작" }),
      ).toHaveAttribute("aria-busy", "false");
      expect(captureAnalyticsEvent).toHaveBeenCalledWith(
        expect.objectContaining({
          event: "recording_start_result",
          properties: expect.objectContaining({
            result: "microphone_unavailable",
          }),
        }),
      );
    });

    it("권한을 받지 못하면 권한을 허용하는 방법을 알리고 제자리에 남는다", async () => {
      denyMicrophoneOnce();
      const { router } = renderDock();

      await clickMic();

      expect(
        screen.getByRole("alertdialog", { name: MIC_UNAVAILABLE }),
      ).toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: "다시 시도" }),
      ).not.toBeInTheDocument();
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

    it.each(CLOSE_MIC_ALERT)(
      "알림을 %s로 닫으면 알림만 닫고 제자리에 남는다",
      async (_, closeAlert) => {
        denyMicrophoneOnce();
        const { router } = renderDock();
        await clickMic();

        closeAlert();

        expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
        expect(useRecordingStore.getState().status).toBe("idle");
        expect(router.state.location.pathname).toBe(HOME_PATH);
      },
    );

    it("알림을 닫은 뒤 마이크를 다시 누르면 권한을 다시 받아 녹음 화면으로 간다", async () => {
      const getUserMedia = denyMicrophoneOnce();
      const { router } = renderDock();
      await clickMic();
      confirmMicAlert();

      await clickMic();

      expect(getUserMedia).toHaveBeenCalledTimes(2);
      expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
      expect(router.state.location.pathname).toBe(RECORDING_PATH);
    });

    it("알림을 닫은 뒤 다시 눌러도 권한을 못 받으면 같은 알림을 다시 띄운다", async () => {
      denyMicrophoneOnce().mockRejectedValueOnce(
        new DOMException("", "NotAllowedError"),
      );
      const { router } = renderDock();
      await clickMic();
      confirmMicAlert();

      await clickMic();

      expect(
        screen.getByRole("alertdialog", { name: MIC_UNAVAILABLE }),
      ).toBeInTheDocument();
      expect(useRecordingStore.getState().status).toBe("idle");
      expect(router.state.location.pathname).toBe(HOME_PATH);
    });

    it("이미 녹음 중이면 권한을 다시 묻지 않고 녹음 화면으로 간다", async () => {
      await startRecording();
      const getUserMedia = vi.spyOn(navigator.mediaDevices, "getUserMedia");
      const { router } = renderDock();

      await clickMic("녹음 화면으로 이동");

      expect(getUserMedia).not.toHaveBeenCalled();
      expect(router.state.location.pathname).toBe(RECORDING_PATH);
    });

    describe("서버에 녹음을 열 때", () => {
      const START_PATH = "*/api/v1/workspaces/:workspaceId/recordings";
      const START_REFUSED = "녹음을 시작할 수 없어요";

      type StartResponse = () => Response | Promise<Response>;

      /** 시작 요청을 차례로 응답하고, 요청마다 보낸 requestId를 모아요 */
      const respondStart = (...responses: StartResponse[]) => {
        const requestIds: string[] = [];
        mockServer.use(
          http.post(START_PATH, async ({ request }) => {
            const { requestId } = (await request.json()) as {
              requestId: string;
            };
            requestIds.push(requestId);

            return responses[requestIds.length - 1]();
          }),
        );

        return requestIds;
      };

      const conflict = (code: string) => () =>
        HttpResponse.json({ code }, { status: 409 });

      const session =
        (status: string, httpStatus = 200) =>
        () =>
          HttpResponse.json(
            { ...recordingStartResponse, status },
            { status: httpStatus },
          );

      /** 새로고침 전에 보냈던 시작 증명이 이 탭에 남아 있게 해요 */
      const leaveStartProof = () => getRecordingStartProof().requestId;

      const expectRefusedInPlace = (
        router: ReturnType<typeof renderDock>["router"],
      ) => {
        expect(
          screen.getByRole("dialog", { name: START_REFUSED }),
        ).toBeInTheDocument();
        expect(useRecordingStore.getState().status).toBe("idle");
        expect(useRecordingStore.getState().analyser).toBeNull();
        expect(router.state.location.pathname).toBe(HOME_PATH);
      };

      beforeEach(() => {
        vi.spyOn(console, "error").mockImplementation(() => {});
      });

      it.each([
        "ACTIVE_RECORDING_ALREADY_EXISTS",
        "RECORDING_START_REQUEST_CONFLICT",
      ])(
        "409(%s)면 마이크를 끄고 모달을 띄운 뒤 시작 증명을 남긴다",
        async (code) => {
          const requestIds = respondStart(conflict(code));
          const { router } = renderDock();

          await clickMic();

          await waitFor(() => expectRefusedInPlace(router));
          expect(getRecordingStartProof().requestId).toBe(requestIds[0]);
        },
      );

      it.each(["ENDED", "DISCARDED"])(
        "남은 증명의 응답이 %s면 새 증명으로 한 번 더 보내 녹음을 시작한다",
        async (status) => {
          const leftoverRequestId = leaveStartProof();
          const requestIds = respondStart(
            session(status),
            session("RECORDING", 201),
          );
          const { router } = renderDock();

          await clickMic();

          await waitFor(() =>
            expect(router.state.location.pathname).toBe(RECORDING_PATH),
          );
          expect(useRecordingStore.getState().status).toBe("recording");
          expect(requestIds).toHaveLength(2);
          expect(requestIds[0]).toBe(leftoverRequestId);
          expect(requestIds[1]).not.toBe(leftoverRequestId);
        },
      );

      it("끝난 녹음 뒤 다시 보낸 시작도 409면 모달을 띄운다", async () => {
        leaveStartProof();
        const requestIds = respondStart(
          session("ENDED"),
          conflict("ACTIVE_RECORDING_ALREADY_EXISTS"),
        );
        const { router } = renderDock();

        await clickMic();

        await waitFor(() => expectRefusedInPlace(router));
        expect(requestIds).toHaveLength(2);
      });

      it.each(["RECORDING", "PAUSED"])(
        "남은 증명의 응답이 %s면 이어받지 않고 모달을 띄운 뒤 시작 증명을 남긴다",
        async (status) => {
          const leftoverRequestId = leaveStartProof();
          respondStart(session(status));
          const { router } = renderDock();

          await clickMic();

          await waitFor(() => expectRefusedInPlace(router));
          expect(getRecordingStartProof().requestId).toBe(leftoverRequestId);
        },
      );

      it("남은 증명으로 보낸 시작이 201 RECORDING이면 새 녹음으로 시작한다", async () => {
        // 409·500으로 거절된 시작은 서버에 남지 않아, 같은 증명을 다시 보내면 새 녹음이 만들어져요
        leaveStartProof();
        respondStart(session("RECORDING", 201));
        const { router } = renderDock();

        await clickMic();

        await waitFor(() =>
          expect(router.state.location.pathname).toBe(RECORDING_PATH),
        );
        expect(useRecordingStore.getState().status).toBe("recording");
      });

      it("모달의 닫기는 모달만 닫고 제자리에 남는다", async () => {
        respondStart(conflict("ACTIVE_RECORDING_ALREADY_EXISTS"));
        const { router } = renderDock();
        await clickMic();
        await screen.findByRole("dialog", { name: START_REFUSED });

        fireEvent.click(screen.getByRole("button", { name: "닫기" }));

        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
        expect(router.state.location.pathname).toBe(HOME_PATH);
      });

      describe("시작 요청이 네트워크 문제로 실패하면 (ERR-18)", () => {
        const NETWORK_ERROR = "연결이 잠시 끊겼어요";

        const failByNetwork = () => HttpResponse.error();

        it("마이크를 끄고 연결이 끊겼다는 모달을 띄운 뒤 제자리에 남는다", async () => {
          respondStart(failByNetwork);
          const { router } = renderDock();

          await clickMic();

          const dialog = await screen.findByRole("dialog", {
            name: NETWORK_ERROR,
          });
          expect(
            within(dialog).getByText(
              "네트워크 상태를 확인하고 다시 시도해 주세요.",
            ),
          ).toBeInTheDocument();
          expect(useRecordingStore.getState().status).toBe("idle");
          expect(useRecordingStore.getState().analyser).toBeNull();
          expect(router.state.location.pathname).toBe(HOME_PATH);
        });

        it("다시 시도는 같은 시작 증명으로 다시 보내, 열리면 녹음 화면으로 간다", async () => {
          const requestIds = respondStart(
            failByNetwork,
            session("RECORDING", 201),
          );
          const { router } = renderDock();
          await clickMic();
          await screen.findByRole("dialog", { name: NETWORK_ERROR });

          await act(async () => {
            fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
          });

          await waitFor(() =>
            expect(router.state.location.pathname).toBe(RECORDING_PATH),
          );
          expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
          expect(requestIds).toHaveLength(2);
          expect(requestIds[1]).toBe(requestIds[0]);
        });

        it("닫기는 모달만 닫고 제자리에 남는다", async () => {
          const requestIds = respondStart(failByNetwork);
          const { router } = renderDock();
          await clickMic();
          await screen.findByRole("dialog", { name: NETWORK_ERROR });

          fireEvent.click(screen.getByRole("button", { name: "닫기" }));

          expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
          expect(router.state.location.pathname).toBe(HOME_PATH);
          expect(requestIds).toHaveLength(1);
        });

        it("서버 오류(500)는 네트워크 문제가 아니라 모달을 띄우지 않는다 (근거 없음)", async () => {
          const requestIds = respondStart(
            () => new HttpResponse(null, { status: 500 }),
          );
          const { router } = renderDock();

          await clickMic();

          await waitFor(() => expect(requestIds).toHaveLength(1));
          await waitFor(() =>
            expect(useRecordingStore.getState().analyser).toBeNull(),
          );
          expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
          expect(router.state.location.pathname).toBe(HOME_PATH);
        });
      });

      it("시작 요청이 오가는 중에 다시 눌러도 한 번만 보내고, 응답 뒤에는 다시 누를 수 있다", async () => {
        let respondFirst = () => {};
        const requestIds = respondStart(async () => {
          await new Promise<void>((resolve) => {
            respondFirst = resolve;
          });

          return conflict("ACTIVE_RECORDING_ALREADY_EXISTS")();
        }, conflict("ACTIVE_RECORDING_ALREADY_EXISTS"));
        renderDock();

        await clickMic();
        await waitFor(() => expect(requestIds).toHaveLength(1));
        await clickMic();
        await act(async () => respondFirst());
        await screen.findByRole("dialog", { name: START_REFUSED });

        expect(requestIds).toHaveLength(1);

        fireEvent.click(screen.getByRole("button", { name: "닫기" }));
        await clickMic();

        await waitFor(() => expect(requestIds).toHaveLength(2));
      });
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

      it("확인 창에서 녹음 끝내기를 고르면 녹음을 끝내고 그 녹음의 정리 화면으로 가며 마이크로 돌아간다", async () => {
        const { router } = renderDock();

        await stopRecording();

        await waitFor(() =>
          expect(router.state.location.pathname).toBe(DOCUMENTS_PATH),
        );
        expect(useRecordingStore.getState().status).toBe("idle");
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

      it("녹음을 끝내면 현재 녹음을 다시 조회해, 끝내기 전에 받은 녹음 중 응답을 다른 탭 녹음 칩으로 남기지 않는다 (근거 없음)", async () => {
        let currentRequestCount = 0;
        mockServer.events.on("request:start", ({ request }) => {
          if (request.url.endsWith("/recordings/current")) {
            currentRequestCount += 1;
          }
        });
        // 녹음하는 동안 받은 현재 녹음 조회는 이 탭의 녹음을 녹음 중으로 답해요
        respondCurrentRecordingStatus("RECORDING");
        renderDock();
        await waitFor(() => expect(currentRequestCount).toBe(1));

        respondNoCurrentRecording();
        await stopRecording();

        await waitFor(() => expect(currentRequestCount).toBe(2));
        expect(
          screen.getByRole("button", { name: "회의 녹음 시작" }),
        ).toBeInTheDocument();
        expect(
          screen.queryByRole("img", { name: "녹음 중" }),
        ).not.toBeInTheDocument();
      });
    });

    describe("다른 탭에서 녹음 중이면", () => {
      it("접힌 독과 펼친 독 모두 마이크 대신 빨간 점과 녹음한 시간을 보여준다 (STT-R28)", async () => {
        respondCurrentRecordingStatus("RECORDING");
        renderDock();

        expect(
          await screen.findByRole("img", { name: "녹음 중" }),
        ).toBeInTheDocument();
        expect(screen.getByText(OTHER_TAB_ELAPSED_TIME)).toBeInTheDocument();
        expect(
          screen.queryByRole("button", { name: "회의 녹음 시작" }),
        ).not.toBeInTheDocument();

        expandDock();

        expect(
          screen.getByRole("img", { name: "녹음 중" }),
        ).toBeInTheDocument();
        expect(screen.getByText(OTHER_TAB_ELAPSED_TIME)).toBeInTheDocument();
        expect(
          screen.queryByRole("button", { name: "회의 녹음 시작" }),
        ).not.toBeInTheDocument();
      });

      it("칩을 눌러 녹음 화면으로 가거나 녹음을 끝낼 수 없다 (STT-20)", async () => {
        respondCurrentRecordingStatus("RECORDING");
        renderDock();

        expect(
          await screen.findByRole("img", { name: "녹음 중" }),
        ).toBeInTheDocument();
        expect(
          screen.queryByRole("button", { name: "녹음 화면으로 이동" }),
        ).not.toBeInTheDocument();
        expect(
          screen.queryByRole("button", { name: "녹음 끝내기" }),
        ).not.toBeInTheDocument();
      });

      it("일시정지면 회색 점과 멈춘 시간을 보여준다 (STT-R28)", async () => {
        respondCurrentRecordingStatus("PAUSED");
        renderDock();

        expect(
          await screen.findByRole("img", { name: "일시정지" }),
        ).toBeInTheDocument();
        expect(screen.getByText(OTHER_TAB_ELAPSED_TIME)).toBeInTheDocument();
      });

      it("다른 탭 녹음이 끝난 뒤 창으로 돌아오면 다시 조회해 마이크로 돌아간다 (근거 없음)", async () => {
        respondCurrentRecordingStatus("RECORDING");
        renderDock();
        expect(
          await screen.findByRole("img", { name: "녹음 중" }),
        ).toBeInTheDocument();

        respondNoCurrentRecording();
        act(() => {
          focusManager.setFocused(false);
          focusManager.setFocused(true);
        });

        expect(
          await screen.findByRole("button", { name: "회의 녹음 시작" }),
        ).toBeInTheDocument();
        focusManager.setFocused(undefined);
      });

      it("현재 녹음 조회가 실패하면 마이크를 보여준다 (근거 없음)", async () => {
        const consoleError = vi
          .spyOn(console, "error")
          .mockImplementation(() => {});
        mockServer.use(
          http.get(CURRENT_RECORDING_URL, () =>
            HttpResponse.json(null, { status: 500 }),
          ),
        );
        renderDock();

        await waitFor(() => expect(consoleError).toHaveBeenCalled());
        expect(
          screen.getByRole("button", { name: "회의 녹음 시작" }),
        ).toBeInTheDocument();
        consoleError.mockRestore();
      });

      it("다른 탭 녹음이 끝나 문서를 정리하는 중이 되면 칩 대신 마이크를 보여준다 (#866)", async () => {
        respondCurrentRecordingStatus("RECORDING");
        renderDock();
        expect(
          await screen.findByRole("img", { name: "녹음 중" }),
        ).toBeInTheDocument();

        // 마이크는 응답을 받기 전에도 보여서, 칩이 보이던 상태에서 정리 중 응답으로 바뀐 뒤 마이크가 새로 나타나는지 봐요
        respondCurrentRecordingStatus("PROCESSING");
        act(() => {
          focusManager.setFocused(false);
          focusManager.setFocused(true);
        });

        expect(
          await screen.findByRole("button", { name: "회의 녹음 시작" }),
        ).toBeInTheDocument();
        expect(
          screen.queryByRole("img", { name: "녹음 중" }),
        ).not.toBeInTheDocument();
        focusManager.setFocused(undefined);
      });

      it.each(["TRANSCRIPTION_FAILED", "DOCUMENT_FAILED"] as const)(
        "다른 탭 녹음의 정리가 실패해도(%s) 이미 끝난 녹음이라 칩 대신 마이크를 보여준다 (#926)",
        async (status) => {
          respondCurrentRecordingStatus(status);

          renderDock();
          // 마이크는 응답을 받기 전에도 보여서, 실패 응답을 받은 뒤에도 칩으로 바뀌지 않는지 봐요
          await screen.findByRole("button", { name: "회의 녹음 시작" });
          await act(async () => {
            await new Promise((resolve) => setTimeout(resolve, 50));
          });

          expect(
            screen.getByRole("button", { name: "회의 녹음 시작" }),
          ).toBeInTheDocument();
          expect(
            screen.queryByRole("img", { name: "녹음 중" }),
          ).not.toBeInTheDocument();
        },
      );
    });

    describe("녹음을 끝내는 요청이 오가는 중이면", () => {
      const END_PATH =
        "*/api/v1/workspaces/:workspaceId/recordings/:recordingId/end";

      beforeEach(async () => {
        getRecordingStartProof();

        await act(async () => {
          await useRecordingStore.getState().connectMicrophone();
          useRecordingStore.getState().startRecording({
            workspaceId: Number(WORKSPACE_ID),
            recordingId: RECORDING_ID,
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

      it("일시정지하고 권한을 허용하는 방법을 알린다", async () => {
        await startAndDisconnect();

        expect(useRecordingStore.getState().status).toBe("paused");
        expect(
          screen.getByRole("alertdialog", { name: MIC_UNAVAILABLE }),
        ).toBeInTheDocument();
        expect(
          screen.queryByRole("button", { name: "다시 시도" }),
        ).not.toBeInTheDocument();
      });

      it.each(CLOSE_MIC_ALERT)(
        "알림을 %s로 닫으면 알림만 닫고 일시정지를 유지한다",
        async (_, closeAlert) => {
          await startAndDisconnect();

          closeAlert();

          expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
          expect(useRecordingStore.getState().status).toBe("paused");
        },
      );
    });
  });
});

/**
 * 캔버스를 그릴 수 있는 브라우저처럼 답하는 가짜 문맥.
 * jsdom은 캔버스를 못 그려 독이 늘 열려 있으므로, 파형으로 접히는 동작은 이 문맥을 깔고 봐요.
 */
const createFakeCanvasContext = () =>
  ({
    setTransform: () => {},
    clearRect: () => {},
    fillRect: () => {},
    beginPath: () => {},
    moveTo: () => {},
    arc: () => {},
    fill: () => {},
    stroke: () => {},
    scale: () => {},
    createRadialGradient: () => ({ addColorStop: () => {} }),
    measureText: () => ({ width: 100 }),
    fillText: () => {},
    getImageData: () => ({ data: new Uint8ClampedArray(0) }),
  }) as unknown as CanvasRenderingContext2D;

describe("파형으로 접힌 독", () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    respondNoCurrentRecording();
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(
      createFakeCanvasContext(),
    );
    // jsdom에는 Path2D가 없어 아이콘 모양을 그릴 때 쓰는 생성자만 흉내 내요
    vi.stubGlobal("Path2D", class {});
  });

  afterEach(() => {
    useSearchAnswerStore.getState().abort();
    vi.unstubAllGlobals();
  });

  /** 다른 탭에서 녹음이 이어지는 홈을 그리고, 독이 파형으로 접히기를 기다려요 */
  const renderRecordingDock = async () => {
    respondCurrentRecordingStatus("RECORDING");
    renderDock();

    return screen.findByRole("button", { name: "독 열기" });
  };

  it("녹음이 없으면 캔버스를 쓸 수 있어도 파형 없이 처음부터 독 버튼이 보인다 (Figma Dock/Bar 기본)", () => {
    renderDock();

    expect(
      screen.queryByRole("button", { name: "독 열기" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "무엇이든 요청하기" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "회의 녹음 시작" }),
    ).toBeInTheDocument();
  });

  it("다른 화면에서 녹음이 이어지면 처음엔 파형만 있고 독 버튼은 없다", async () => {
    await renderRecordingDock();

    expect(
      screen.queryByRole("button", { name: "무엇이든 요청하기" }),
    ).not.toBeInTheDocument();
  });

  it("파형을 누르면 독이 열려 요청하기 버튼이 나타난다", async () => {
    const seed = await renderRecordingDock();

    fireEvent.click(seed);

    expect(
      screen.getByRole("button", { name: "무엇이든 요청하기" }),
    ).toBeInTheDocument();
    expect(isDockExpanded()).toBe(false);
  });

  it("열린 독에서 요청하기를 누르면 그대로 펼쳐진다", async () => {
    fireEvent.click(await renderRecordingDock());

    expandDock();

    expect(isDockExpanded()).toBe(true);
  });

  it("포인터가 독을 벗어나면 잠시 뒤 파형으로 돌아간다", async () => {
    const seed = await renderRecordingDock();
    fireEvent.click(seed);
    expect(
      screen.getByRole("button", { name: "무엇이든 요청하기" }),
    ).toBeInTheDocument();

    fireEvent.pointerLeave(seed.parentElement!, { pointerType: "mouse" });

    await waitFor(
      () =>
        expect(
          screen.queryByRole("button", { name: "무엇이든 요청하기" }),
        ).not.toBeInTheDocument(),
      { timeout: 1500 },
    );
  });

  it("파형 상태에서 글자를 치면 독이 바로 펼쳐진다", async () => {
    await renderRecordingDock();

    fireEvent.change(
      screen.getByRole("textbox", { name: "무엇이든 요청하세요" }),
      { target: { value: "ㅇ" } },
    );

    expect(isDockExpanded()).toBe(true);
    // 입자 자리는 남아 있지만 열린 동안은 낭독기에서 숨겨요
    expect(
      screen.queryByRole("button", { name: "독 열기" }),
    ).not.toBeInTheDocument();
    expect(
      document.querySelector('[aria-label="독 열기"]'),
    ).toHaveAttribute("aria-hidden", "true");
  });

  it("녹음 화면에서는 파형 없이 독이 보이고 회의 녹음 버튼이 없다", () => {
    renderDock(RECORDING_PATH);

    expect(
      screen.queryByRole("button", { name: "독 열기" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "무엇이든 요청하기" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "회의 녹음 시작" }),
    ).not.toBeInTheDocument();
  });
});
