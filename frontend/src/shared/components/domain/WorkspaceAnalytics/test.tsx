import { GetSearchMessagesResponseDto } from "@api/dto/search";
import { documentDetailsResponse } from "@api/mock/responses/document";
import {
  searchAnswerStreamResponse,
  searchMessagesResponse,
} from "@api/mock/responses/search";
import { mockServer } from "@api/mock/server";
import { ThemeProvider } from "@emotion/react";
import CenteredLayout from "@pages/_layout/CenteredLayout";
import WorkspaceLayout from "@pages/_layout/WorkspaceLayout";
import AppProviders from "@provider/AppProviders";
import { theme } from "@provider/themeProvider";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import { useSearchAnswerStore } from "@store/searchAnswerStore";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import SearchConversation from "@widgets/search/SearchConversation";
import { http, HttpResponse } from "msw";
import {
  createMemoryRouter,
  Outlet,
  RouterProvider,
  useLocation,
} from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import WorkspaceAnalytics from ".";

vi.mock("@utils/captureAnalyticsEvent", () => ({
  captureAnalyticsEvent: vi.fn(),
}));

const WORKSPACE_ID = 1;
const HOME_PATH = getRouterPath({
  routeKey: "WORKSPACE_HOME",
  params: { workspaceId: String(WORKSPACE_ID) },
});
const CHAT_PATH = getRouterPath({
  routeKey: "CHAT",
  params: { workspaceId: String(WORKSPACE_ID) },
});
const CONVERSATION_ID = "10";
const CONVERSATION_PATH = getRouterPath({
  routeKey: "CHAT_SESSION",
  params: { workspaceId: String(WORKSPACE_ID), sessionId: CONVERSATION_ID },
});
const DOCUMENT_PATH = getRouterPath({
  routeKey: "DOCUMENT",
  params: {
    workspaceId: String(WORKSPACE_ID),
    documentId: String(documentDetailsResponse[0].id),
  },
});
const ELSEWHERE_PATH = "/elsewhere";

const MESSAGES_PATH =
  "*/api/v1/workspaces/:workspaceId/search/conversations/:conversationId/messages";
const SEARCH_CONVERSATIONS_PATH =
  "*/api/v1/workspaces/:workspaceId/search/conversations";
const SEARCH_QUESTIONS_PATH =
  "*/api/v1/workspaces/:workspaceId/search/conversations/:conversationId/questions";
const SEARCH_ANSWER_EVENTS_PATH =
  "*/api/v1/workspaces/:workspaceId/search/conversations/:conversationId/answers/:answerId/events";
const QUESTION = "DB는 뭘로 정했어?";
const FOLLOW_UP_QUESTION = "초기 스키마는 누가 정리했어?";

/** React가 그린 화면의 주소를 보여요. 라우터의 주소는 화면을 그리기 전에 먼저 바뀌어서 따로 둬요 */
function RenderedPath() {
  const { pathname } = useLocation();

  return <output aria-label="그려진 주소">{pathname}</output>;
}

/**
 * 라우트에서처럼 워크스페이스 레이아웃을 감싸 그려요. 탐색 화면에는 실제 대화 칸을 그려요.
 * 답변을 끊는 일은 레이아웃이 하므로, 레이아웃과 함께 그려야 떠남을 확인할 수 있어요
 */
const renderWorkspaceAnalytics = (initialPath: string) => {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  const layouts = [
    {
      handle: { hasDock: true },
      element: (
        <WorkspaceAnalytics>
          <WorkspaceLayout />
        </WorkspaceAnalytics>
      ),
      children: [
        { path: PATH_ROUTE.WORKSPACE_HOME, element: <p>홈 본문</p> },
        { path: PATH_ROUTE.CHAT, element: <SearchConversation /> },
        { path: PATH_ROUTE.CHAT_SESSION, element: <SearchConversation /> },
        { path: PATH_ROUTE.DOCUMENT, element: <p>문서 본문</p> },
      ],
    },
    {
      element: <CenteredLayout />,
      children: [{ path: ELSEWHERE_PATH, element: <p>다른 화면</p> }],
    },
  ];
  const router = createMemoryRouter(
    [
      {
        element: (
          <AppProviders routes={layouts}>
            <RenderedPath />
            <Outlet />
          </AppProviders>
        ),
        children: layouts,
      },
    ],
    { initialEntries: [ELSEWHERE_PATH, initialPath] },
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

/**
 * 그 주소의 화면이 그려질 때까지 기다려요. 라우터의 주소만 보면 화면을 그리기 전에 다음 이동을 할 수 있어요.
 * 그러면 사용 기록 훅이 그 화면을 보지 못해, 느린 CI에서 떠남 기록이 빠졌어요
 */
const waitForRenderedPath = (pathname: string) =>
  waitFor(() => {
    expect(screen.getByLabelText("그려진 주소").textContent).toBe(pathname);
  });

const encoder = new TextEncoder();

/** 서버가 보내는 SSE 프레임 한 개예요 */
const toFrame = (event: string, data: object) =>
  `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;

/** 이번 테스트에서만 그 경로의 SSE 응답을 열어 두고, 테스트가 프레임을 하나씩 흘려보내게 해요 */
const respondWithControlledSse = (path: string) => {
  let stream: ReadableStreamDefaultController<Uint8Array> | undefined;
  mockServer.use(
    http.post(
      path,
      () =>
        new HttpResponse(
          new ReadableStream<Uint8Array>({
            start(controller) {
              stream = controller;
            },
          }),
          { headers: { "Content-Type": "text/event-stream" } },
        ),
    ),
  );

  return {
    waitForOpen: () => waitFor(() => expect(stream).toBeDefined()),
    send: (event: string, data: object) =>
      stream?.enqueue(encoder.encode(toFrame(event, data))),
    /** 끝 이벤트 없이 연결을 닫아요 */
    close: () => stream?.close(),
  };
};

/** 이번 테스트에서만 메시지 조회를 넘긴 메시지로 답해요 */
const respondMessages = (items: object[]) => {
  mockServer.use(
    http.get(MESSAGES_PATH, ({ params }) =>
      HttpResponse.json({
        ...searchMessagesResponse,
        conversationId: Number(params.conversationId),
        items,
      }),
    ),
  );
};

/** 독에 질문을 적어 보내기를 눌러요 */
const sendFromDock = async (question: string) => {
  fireEvent.change(
    await screen.findByRole("textbox", { name: "무엇이든 요청하세요" }),
    { target: { value: question } },
  );
  await act(async () => {
    fireEvent.click(screen.getByRole("button", { name: "보내기" }));
  });
};

/** 대화 칸에서 글자를 기다려요. 주소가 바뀌면 대화 칸이 새로 그려지므로 기다리는 동안 매번 다시 찾아요 */
const findInConversation = (text: string) =>
  waitFor(() =>
    within(screen.getByRole("region", { name: "대화" })).getByText(text),
  );

afterEach(() => {
  // 전역 저장소라 테스트끼리 답변이 새지 않게 비워요
  useSearchAnswerStore.getState().abort();
});

describe("WorkspaceAnalytics의 탐색 사용 기록", () => {
  const { conversationId, questionMessageId, answerMessageId } =
    searchAnswerStreamResponse;
  const SENT_AT = new Date("2026-10-19T03:00:00.000Z").getTime();
  /** 질문을 보낸 뒤 몇 초가 지난 때로 시계를 맞춰요 */
  const at = (seconds: number) => vi.setSystemTime(SENT_AT + seconds * 1000);

  /** 보낸 사용 기록 중 그 이름의 것만 모아요. 이름을 이벤트 목록으로 좁혀 오타가 「보내지 않는다」를 늘 통과시키지 않게 해요 */
  const getEvents = (
    name: Parameters<typeof captureAnalyticsEvent>[0]["event"],
  ) =>
    vi
      .mocked(captureAnalyticsEvent)
      .mock.calls.map(([params]) => params)
      .filter(({ event }) => event === name);

  /** 새 대화 화면에서 첫 질문을 보내 답변 받기가 열린 상태로 만들어요 */
  const sendNewQuestion = async () => {
    const stream = respondWithControlledSse(SEARCH_CONVERSATIONS_PATH);
    const rendered = renderWorkspaceAnalytics(CHAT_PATH);
    await sendFromDock(QUESTION);
    await stream.waitForOpen();

    return { ...rendered, stream };
  };

  /** 새 대화 화면에서 첫 질문을 보내고 접수된 상태로 만들어요 */
  const sendAndAcceptNewQuestion = async () => {
    const sent = await sendNewQuestion();
    sent.stream.send("accepted", {
      conversationId,
      questionMessageId,
      answerMessageId,
    });

    return sent;
  };

  beforeEach(() => {
    vi.mocked(captureAnalyticsEvent).mockClear();
  });

  describe("답변을 기다리다 떠나고 돌아온 사용 기록", () => {
    const ANSWER_CONVERSATION_PATH = getRouterPath({
      routeKey: "CHAT_SESSION",
      params: {
        workspaceId: String(WORKSPACE_ID),
        sessionId: String(conversationId),
      },
    });
    const moveTo = (
      router: ReturnType<typeof createMemoryRouter>,
      path: string,
    ) => act(() => router.navigate(path));

    /** 질문을 보낸 시각을 0초로 두고, 접수되어 그 대화 주소로 옮겨 간 상태로 만들어요 */
    const sendAndAccept = async () => {
      at(0);
      const sent = await sendAndAcceptNewQuestion();
      await waitForRenderedPath(ANSWER_CONVERSATION_PATH);

      return sent;
    };

    beforeEach(() => {
      // 시계만 가짜로 두고, 타이머는 실제로 흘러 응답을 기다릴 수 있게 해요
      vi.useFakeTimers({ toFake: ["Date"] });
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    it("답변을 기다리다 홈으로 가면 기다린 시간을, 답변이 끝난 뒤 그 대화로 돌아오면 떠나 있던 시간과 답변이 끝나 있었다는 것을 보낸다", async () => {
      const { router, stream } = await sendAndAccept();

      at(10);
      await moveTo(router, HOME_PATH);

      expect(getEvents("search_answer_left")).toEqual([
        { event: "search_answer_left", properties: { waited_seconds: 10 } },
      ]);

      at(35);
      stream.send("completed", { answerMessageId, status: "COMPLETED" });
      await waitFor(() =>
        expect(getEvents("search_answer_result")).toHaveLength(1),
      );

      at(50);
      await moveTo(router, ANSWER_CONVERSATION_PATH);

      expect(getEvents("search_answer_returned")).toEqual([
        {
          event: "search_answer_returned",
          properties: { away_seconds: 40, is_answer_ready: true },
        },
      ]);
    });

    it("답변을 받는 중에 그 대화로 돌아오면 답변이 아직 끝나지 않았다고 보낸다", async () => {
      const { router } = await sendAndAccept();

      at(10);
      await moveTo(router, DOCUMENT_PATH);
      at(22.5);
      await moveTo(router, ANSWER_CONVERSATION_PATH);

      expect(getEvents("search_answer_returned")).toEqual([
        {
          event: "search_answer_returned",
          properties: { away_seconds: 12.5, is_answer_ready: false },
        },
      ]);
    });

    it("답변이 끝난 뒤에 떠났다 돌아오면 떠난 기록도 돌아온 기록도 보내지 않는다", async () => {
      const { router, stream } = await sendAndAccept();
      stream.send("completed", { answerMessageId, status: "COMPLETED" });
      await waitFor(() =>
        expect(getEvents("search_answer_result")).toHaveLength(1),
      );

      at(10);
      await moveTo(router, HOME_PATH);
      at(20);
      await moveTo(router, ANSWER_CONVERSATION_PATH);

      expect(getEvents("search_answer_left")).toHaveLength(0);
      expect(getEvents("search_answer_returned")).toHaveLength(0);
    });

    it("답변을 기다리다 다른 대화를 열면 기다린 시간을 보내고, 끊긴 답변의 대화로 돌아와도 돌아온 기록은 보내지 않는다", async () => {
      const { router } = await sendAndAccept();

      at(10);
      await moveTo(router, CONVERSATION_PATH);
      at(30);
      await moveTo(router, ANSWER_CONVERSATION_PATH);

      expect(getEvents("search_answer_left")).toEqual([
        { event: "search_answer_left", properties: { waited_seconds: 10 } },
      ]);
      expect(getEvents("search_answer_returned")).toHaveLength(0);
    });

    it.each([
      {
        place: "다른 워크스페이스로 옮기면",
        path: getRouterPath({
          routeKey: "WORKSPACE_HOME",
          params: { workspaceId: String(WORKSPACE_ID + 1) },
        }),
      },
      { place: "워크스페이스 밖으로 나가면", path: ELSEWHERE_PATH },
    ])(
      "답변을 기다리다 $place 기다린 시간을 한 번 보낸다",
      async ({ path }) => {
        const { router } = await sendAndAccept();

        at(7);
        await moveTo(router, path);

        expect(getEvents("search_answer_left")).toEqual([
          { event: "search_answer_left", properties: { waited_seconds: 7 } },
        ]);
      },
    );

    it("답변이 끝난 뒤에 워크스페이스 밖으로 나가면 떠난 기록을 보내지 않는다", async () => {
      const { router, stream } = await sendAndAccept();
      stream.send("completed", { answerMessageId, status: "COMPLETED" });
      await waitFor(() =>
        expect(getEvents("search_answer_result")).toHaveLength(1),
      );

      await moveTo(router, ELSEWHERE_PATH);

      expect(getEvents("search_answer_left")).toHaveLength(0);
    });

    it("접수되기 전에 다른 화면으로 가면 아직 대화가 없어 떠난 기록을 보내지 않는다", async () => {
      at(0);
      const { router } = await sendNewQuestion();

      at(3);
      await moveTo(router, HOME_PATH);

      expect(getEvents("search_answer_left")).toHaveLength(0);
    });
  });

  describe("탐색 답변 결과 사용 기록", () => {
    const getResultEvents = () => getEvents("search_answer_result");

    beforeEach(() => {
      // 탐색 실패는 콘솔에 남기므로, 테스트 출력에 섞이지 않게 막아요
      vi.spyOn(console, "error").mockImplementation(() => {});
    });

    afterEach(() => {
      vi.useRealTimers();
      vi.mocked(console.error).mockRestore();
    });

    it("독에서 보낸 첫 질문의 답변이 끝나면 완료와 보낸 곳(독) · 걸린 시간 · 질문 글 · 받은 답변 글을 한 번 보낸다", async () => {
      vi.useFakeTimers({ toFake: ["Date"] });
      at(0);
      const { stream } = await sendAndAcceptNewQuestion();
      stream.send("delta", { answerMessageId, text: "DB는 " });
      stream.send("delta", { answerMessageId, text: "PostgreSQL이에요" });
      await findInConversation("DB는 PostgreSQL이에요");

      at(12.34);
      stream.send("completed", { answerMessageId, status: "COMPLETED" });

      await waitFor(() => expect(getResultEvents()).toHaveLength(1));
      expect(getResultEvents()[0]).toEqual({
        event: "search_answer_result",
        properties: {
          result: "completed",
          is_retry: false,
          entry: "dock",
          is_first_question: true,
          answer_seconds: 12.3,
          question: QUESTION,
          answer: "DB는 PostgreSQL이에요",
        },
      });
    });

    it("대화 화면에서 보낸 후속 질문의 답변이 중지되면 첫 질문이 아닌 중지로 보낸다", async () => {
      const stream = respondWithControlledSse(SEARCH_QUESTIONS_PATH);
      renderWorkspaceAnalytics(CONVERSATION_PATH);
      await sendFromDock(FOLLOW_UP_QUESTION);
      await stream.waitForOpen();
      stream.send("accepted", {
        conversationId: Number(CONVERSATION_ID),
        questionMessageId,
        answerMessageId,
      });
      stream.send("stopped", { answerMessageId, status: "STOPPED" });

      await waitFor(() => expect(getResultEvents()).toHaveLength(1));
      expect(getResultEvents()[0].properties).toMatchObject({
        result: "stopped",
        is_first_question: false,
        question: FOLLOW_UP_QUESTION,
      });
    });

    it("접수 뒤 기술 오류는 서버가 알려 준 실패 코드와 함께 실패로 측정하고 미검증 답변 글을 보내지 않는다", async () => {
      const { stream } = await sendAndAcceptNewQuestion();
      stream.send("delta", {
        answerMessageId,
        text: "끊기기 전까지 받은 답",
      });
      await findInConversation("끊기기 전까지 받은 답");
      stream.send("failed", {
        answerMessageId,
        status: "FAILED",
        code: "LLM_STREAM_FAILED",
      });

      await waitFor(() => expect(getResultEvents()).toHaveLength(1));
      expect(getResultEvents()[0].properties).toMatchObject({
        result: "failed",
        failure_code: "LLM_STREAM_FAILED",
        answer: "",
      });
    });

    it("접수 전에 질문이 거절되면 접수 전 실패와 빈 답변 글을 보낸다", async () => {
      mockServer.use(
        http.post(SEARCH_CONVERSATIONS_PATH, () =>
          HttpResponse.json(
            { code: "FORBIDDEN", message: "요청을 처리하지 못했어요" },
            { status: 403 },
          ),
        ),
      );
      renderWorkspaceAnalytics(CHAT_PATH);

      await sendFromDock(QUESTION);

      await waitFor(() => expect(getResultEvents()).toHaveLength(1));
      expect(getResultEvents()[0].properties).toMatchObject({
        result: "accept_failed",
        question: QUESTION,
        answer: "",
      });
    });

    it("끊긴 답변을 이어 받다가 서버가 그 답변을 더는 주지 않으면(4xx) 이어 받을 수 없었다고 보낸다", async () => {
      mockServer.use(
        http.get(SEARCH_ANSWER_EVENTS_PATH, () =>
          HttpResponse.json(
            { code: "NOT_FOUND", message: "답변을 찾을 수 없어요" },
            { status: 404 },
          ),
        ),
      );
      const { stream } = await sendAndAcceptNewQuestion();
      stream.send("delta", { answerMessageId, text: "끊기기 전까지 받은 답" });
      await findInConversation("끊기기 전까지 받은 답");

      stream.close();

      await waitFor(() => expect(getResultEvents()).toHaveLength(1));
      expect(getResultEvents()[0].properties).toMatchObject({
        result: "unavailable",
        entry: "dock",
        question: QUESTION,
        answer: "",
      });
      expect(getResultEvents()[0].properties).not.toHaveProperty(
        "failure_code",
      );
    });

    it("답변을 받는 중에 다른 대화를 열어 받기를 끊으면 결과를 보내지 않는다", async () => {
      const { router, stream } = await sendAndAcceptNewQuestion();
      stream.send("delta", { answerMessageId, text: "받는 중" });
      await findInConversation("받는 중");

      await act(() => router.navigate(CONVERSATION_PATH));
      await waitFor(() =>
        expect(useSearchAnswerStore.getState().status).toBe("IDLE"),
      );

      expect(getResultEvents()).toHaveLength(0);
    });

    it("첫 질문이 접수되기 전에 다른 대화를 열어 받기를 끊으면 접수 전 실패로 보내지 않는다", async () => {
      const { router } = await sendNewQuestion();

      await act(() => router.navigate(CONVERSATION_PATH));
      await waitFor(() =>
        expect(useSearchAnswerStore.getState().status).toBe("IDLE"),
      );

      expect(getResultEvents()).toHaveLength(0);
    });

    it("이전 실패 답변의 안내를 열어도 질문을 자동 재시도하거나 결과를 측정하지 않는다", async () => {
      const [question, answer] = searchMessagesResponse.items;
      respondMessages([question, { ...answer, content: "", status: "FAILED", evidences: [] }]);
      renderWorkspaceAnalytics(CONVERSATION_PATH);
      await findInConversation("지금은 답변을 정리하기 어려워요. 질문을 조금 더 구체적으로 이어서 해주시면 도와드릴게요.");
      expect(screen.queryByRole("button", { name: "다시 시도" })).not.toBeInTheDocument();
      expect(getResultEvents()).toHaveLength(0);
    });
  });
});
