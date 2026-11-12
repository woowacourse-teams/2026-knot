import {
  GetSearchMessagesResponseDto,
  SearchStreamAcceptedDto,
  SearchStreamDeltaDto,
} from "@api/dto/search";
import {
  searchMessagesResponse,
} from "@api/mock/responses/search";
import { mockServer } from "@api/mock/server";
import { ThemeProvider } from "@emotion/react";
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
import { http, HttpResponse } from "msw";
import { createMemoryRouter, RouterProvider } from "react-router";
import { afterEach, describe, expect, it } from "vitest";

import SearchConversation from ".";

const WORKSPACE_ID = "1";
const CONVERSATION_ID = "10";
const CHAT_PATH = getRouterPath({
  routeKey: "CHAT",
  params: { workspaceId: WORKSPACE_ID },
});
const CONVERSATION_PATH = getRouterPath({
  routeKey: "CHAT_SESSION",
  params: { workspaceId: WORKSPACE_ID, sessionId: CONVERSATION_ID },
});

const MESSAGES_PATH =
  "*/api/v1/workspaces/:workspaceId/search/conversations/:conversationId/messages";

const { items: messages } = new GetSearchMessagesResponseDto(
  searchMessagesResponse,
);
const questions = messages.filter(({ role }) => role === "USER");
const answers = messages.filter(({ role }) => role === "ASSISTANT");
const answersWithEvidence = answers.filter(
  ({ evidences }) => evidences.length > 0,
);

const SEARCHING_TEXT = "답변을 준비하고 있어요…";
const FAILED_TEXT = "지금은 답변을 정리하기 어려워요. 질문을 조금 더 구체적으로 이어서 해주시면 도와드릴게요.";
const PARTIAL_ANSWER = "끊기기 전까지 받은 답";

/** 이번 테스트에서만 첫 턴의 답변을 일부 글자만 받고 실패한 답변으로 조회되게 바꿔요 */
const respondWithFailedFirstAnswer = () => {
  const [question, answer, ...rest] = searchMessagesResponse.items;
  mockServer.use(
    http.get(MESSAGES_PATH, ({ params }) =>
      HttpResponse.json({
        ...searchMessagesResponse,
        conversationId: Number(params.conversationId),
        items: [
          question,
          {
            ...answer,
            content: PARTIAL_ANSWER,
            status: "FAILED",
            evidences: [],
          },
          ...rest,
        ],
      }),
    ),
  );
};

const EMPTY_TITLE = "무엇을 찾고 있나요?";
const LOADING_LABEL = "대화를 불러오고 있어요";
const LOAD_FAILED_TEXT = "대화를 불러오지 못했어요. 다시 시도해 주세요.";
const OTHER_CONVERSATION_ID = "11";
const OTHER_CONVERSATION_PATH = getRouterPath({
  routeKey: "CHAT_SESSION",
  params: { workspaceId: WORKSPACE_ID, sessionId: OTHER_CONVERSATION_ID },
});
const OTHER_QUESTION = "옮겨 간 대화의 질문";

/**
 * 이번 테스트에서만 옮겨 갈 대화(11)의 메시지 응답을 `respond`를 부를 때까지 붙잡아 둬요.
 * 다른 대화는 기본 mock 응답 그대로예요.
 */
const holdOtherConversationMessages = () => {
  let respond = () => {};
  const responded = new Promise<void>((resolve) => {
    respond = resolve;
  });

  mockServer.use(
    http.get(MESSAGES_PATH, async ({ params }) => {
      if (params.conversationId !== OTHER_CONVERSATION_ID) {
        return HttpResponse.json(searchMessagesResponse);
      }

      await responded;
      return HttpResponse.json({
        ...searchMessagesResponse,
        conversationId: Number(OTHER_CONVERSATION_ID),
        items: [
          { ...searchMessagesResponse.items[0], content: OTHER_QUESTION },
        ],
      });
    }),
  );

  return { respond: () => act(async () => respond()) };
};

const evidenceButtonName = (count: number) => `기록 ${count}개에서 찾았어요`;

const renderConversation = (initialPath = CONVERSATION_PATH) => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const router = createMemoryRouter(
    [
      { path: PATH_ROUTE.CHAT, element: <SearchConversation /> },
      { path: PATH_ROUTE.CHAT_SESSION, element: <SearchConversation /> },
    ],
    { initialEntries: [initialPath] },
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

/** 저장소에 질문을 시작하고, 넘긴 대화로 접수된 뒤 답변 조각 하나가 도착한 상태로 만들어요 */
const streamAnswer = ({
  conversationId,
  question,
  text,
}: {
  conversationId: number | null;
  question: string;
  text: string;
}) => {
  const store = useSearchAnswerStore.getState();
  const controller = store.start({ question, conversationId });
  if (!controller) throw new Error("질문을 시작하지 못했어요");

  return {
    accept: (acceptedConversationId: number) =>
      act(() => {
        store.applyEvent(controller, {
          event: "accepted",
          data: new SearchStreamAcceptedDto({
            conversationId: acceptedConversationId,
            questionMessageId: 1001,
            answerMessageId: 1002,
          }),
        });
        store.applyEvent(controller, {
          event: "delta",
          data: new SearchStreamDeltaDto({ answerMessageId: 1002, text }),
        });
      }),
  };
};

describe("SearchConversation", () => {
  // 전역 저장소라 테스트끼리 답변이 새지 않게 비워요
  afterEach(() => {
    useSearchAnswerStore.getState().abort();
  });

  it("새 대화에서 보낸 첫 질문은 접수 전에도 질문만 있는 턴으로 보여준다 (SEARCH-R6)", () => {
    streamAnswer({
      conversationId: null,
      question: "방금 보낸 질문",
      text: "",
    });
    renderConversation(CHAT_PATH);

    const [turn] = screen.getAllByRole("article");

    expect(within(turn).getByText("방금 보낸 질문")).toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: "무엇을 찾고 있나요?" }),
    ).not.toBeInTheDocument();
  });

  it("이 대화에서 보낸 질문과 흘러드는 답변은 저장된 턴 뒤에 이어 보여준다 (SEARCH-R1)", async () => {
    const { accept } = streamAnswer({
      conversationId: Number(CONVERSATION_ID),
      question: "이어서 보낸 질문",
      text: "흘러드는 답",
    });
    accept(Number(CONVERSATION_ID));
    renderConversation();

    await screen.findByText(questions[0].content);
    const turns = screen.getAllByRole("article");
    const lastTurn = turns[turns.length - 1];

    expect(turns).toHaveLength(questions.length + 1);
    expect(within(lastTurn).getByText("이어서 보낸 질문")).toBeInTheDocument();
    expect(within(lastTurn).getByText("흘러드는 답")).toBeInTheDocument();
  });

  it("접수 전에는 진행 문구 없이 질문만 보여준다 (Figma 탐색/질문 보내는 중)", () => {
    streamAnswer({
      conversationId: null,
      question: "방금 보낸 질문",
      text: "",
    });
    renderConversation(CHAT_PATH);

    expect(screen.queryByText(SEARCHING_TEXT)).not.toBeInTheDocument();
  });

  it("접수된 뒤 답변 텍스트가 오기 전에는 답변 자리에 진행 문구를 보여준다 (SEARCH-R6, Figma Search/Answer 작성 중)", async () => {
    const { accept } = streamAnswer({
      conversationId: Number(CONVERSATION_ID),
      question: "이어서 보낸 질문",
      text: "",
    });
    accept(Number(CONVERSATION_ID));
    renderConversation();

    await screen.findByText(questions[0].content);
    const turns = screen.getAllByRole("article");

    expect(
      within(turns[turns.length - 1]).getByText(SEARCHING_TEXT),
    ).toBeInTheDocument();
  });

  it("답변 텍스트가 도착하면 진행 문구를 숨긴다 (근거 없음)", async () => {
    const { accept } = streamAnswer({
      conversationId: Number(CONVERSATION_ID),
      question: "이어서 보낸 질문",
      text: "흘러드는 답",
    });
    accept(Number(CONVERSATION_ID));
    renderConversation();

    await screen.findByText("흘러드는 답");

    expect(screen.queryByText(SEARCHING_TEXT)).not.toBeInTheDocument();
  });

  it("다른 대화에서 보낸 질문은 이 대화에 보여주지 않는다 (근거 없음)", async () => {
    const { accept } = streamAnswer({
      conversationId: null,
      question: "다른 대화의 질문",
      text: "다른 대화의 답",
    });
    accept(Number(CONVERSATION_ID) + 1);
    renderConversation();

    await screen.findByText(questions[0].content);

    expect(screen.queryByText("다른 대화의 질문")).not.toBeInTheDocument();
    expect(screen.getAllByRole("article")).toHaveLength(questions.length);
  });
  // 도메인 규칙 7. 탐색 · 대화: 빈 대화는 저장하지 않고, 첫 질문을 받은 뒤 대화가 된다
  it("질문이 없는 새 대화에서는 '무엇을 찾고 있나요?' 제목과 설명을 보여준다 (근거 없음)", () => {
    renderConversation(CHAT_PATH);

    expect(
      screen.getByRole("heading", { name: "무엇을 찾고 있나요?" }),
    ).toBeInTheDocument();
    expect(
      screen.getByText("모아 둔 문서에서 찾아 근거와 함께 답해요."),
    ).toBeInTheDocument();
  });

  it("새 대화에서는 메시지를 조회하지 않는다 (근거 없음)", async () => {
    let requestCount = 0;
    mockServer.events.on("request:start", ({ request }) => {
      if (request.url.includes("/messages")) requestCount += 1;
    });
    renderConversation(CHAT_PATH);

    await act(async () => {});

    expect(requestCount).toBe(0);
    mockServer.events.removeAllListeners();
  });

  it("주소의 대화 메시지를 조회해 그 질문을 보여준다 (근거 없음)", async () => {
    const otherQuestion = "다른 대화의 질문";
    mockServer.use(
      http.get(MESSAGES_PATH, ({ params }) =>
        HttpResponse.json({
          ...searchMessagesResponse,
          items:
            params.conversationId === CONVERSATION_ID
              ? [{ ...searchMessagesResponse.items[0], content: otherQuestion }]
              : [],
        }),
      ),
    );
    renderConversation();

    expect(await screen.findByText(otherQuestion)).toBeInTheDocument();
  });

  it("보던 대화에서 다른 대화로 옮기면 응답이 올 때까지 보던 대화를 누를 수 없게 둔 채 보여준다 (SEARCH-15)", async () => {
    const { respond } = holdOtherConversationMessages();
    const { router } = renderConversation();
    await screen.findByText(questions[0].content);

    await act(async () => {
      router.navigate(OTHER_CONVERSATION_PATH);
    });

    const conversation = screen.getByRole("region", { name: "대화" });
    expect(screen.getByText(questions[0].content)).toBeInTheDocument();
    expect(conversation).toHaveAttribute("inert");
    expect(conversation).toHaveAttribute("aria-busy", "true");
    expect(
      screen.queryByRole("heading", { name: EMPTY_TITLE }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("status", { name: LOADING_LABEL }),
    ).not.toBeInTheDocument();

    await respond();

    expect(await screen.findByText(OTHER_QUESTION)).toBeInTheDocument();
    expect(screen.queryByText(questions[0].content)).not.toBeInTheDocument();
    expect(screen.getByRole("region", { name: "대화" })).not.toHaveAttribute(
      "inert",
    );
  });

  it("보여 줄 대화 없이 지난 대화를 열면 받는 동안 시작 전 안내 대신 스켈레톤을 보여준다 (SEARCH-15)", async () => {
    const { respond } = holdOtherConversationMessages();
    renderConversation(OTHER_CONVERSATION_PATH);

    expect(
      await screen.findByRole("status", { name: LOADING_LABEL }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: EMPTY_TITLE }),
    ).not.toBeInTheDocument();

    await respond();

    expect(await screen.findByText(OTHER_QUESTION)).toBeInTheDocument();
    expect(
      screen.queryByRole("status", { name: LOADING_LABEL }),
    ).not.toBeInTheDocument();
  });

  it("답변을 만드는 중인 대화는 메시지를 받는 동안에도 스켈레톤 대신 그 턴을 보여준다 (SEARCH-15, SEARCH-04)", async () => {
    holdOtherConversationMessages();
    const { accept } = streamAnswer({
      conversationId: null,
      question: "홈에서 보낸 질문",
      text: "흘러드는 답",
    });
    accept(Number(OTHER_CONVERSATION_ID));
    renderConversation(OTHER_CONVERSATION_PATH);

    expect(await screen.findByText("흘러드는 답")).toBeInTheDocument();
    expect(
      screen.queryByRole("status", { name: LOADING_LABEL }),
    ).not.toBeInTheDocument();
  });

  it("보던 대화에서 새 대화로 옮기면 보던 대화를 남기지 않고 시작 전 안내를 보여준다 (SEARCH-15)", async () => {
    const { router } = renderConversation();
    await screen.findByText(questions[0].content);

    await act(async () => {
      router.navigate(CHAT_PATH);
    });

    expect(
      screen.getByRole("heading", { name: EMPTY_TITLE }),
    ).toBeInTheDocument();
    expect(screen.queryByText(questions[0].content)).not.toBeInTheDocument();
  });

  it("메시지를 받지 못하면 불러오기 실패 안내를 보여주고, '다시 시도'로 그 대화를 다시 불러온다 (SEARCH-15)", async () => {
    let shouldFail = true;
    mockServer.use(
      http.get(MESSAGES_PATH, () =>
        shouldFail
          ? HttpResponse.json(null, { status: 500 })
          : HttpResponse.json(searchMessagesResponse),
      ),
    );
    renderConversation();

    const alert = await screen.findByRole("alert");
    expect(within(alert).getByText(LOAD_FAILED_TEXT)).toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: EMPTY_TITLE }),
    ).not.toBeInTheDocument();

    shouldFail = false;
    await act(async () => {
      fireEvent.click(within(alert).getByRole("button", { name: "다시 시도" }));
    });

    expect(await screen.findByText(questions[0].content)).toBeInTheDocument();
    expect(screen.queryByText(LOAD_FAILED_TEXT)).not.toBeInTheDocument();
  });

  it("대화가 있으면 빈 화면 대신 질문을 순서대로 보여준다 (근거 없음)", async () => {
    renderConversation();

    const turns = await screen.findAllByRole("article");

    expect(
      screen.queryByRole("heading", { name: "무엇을 찾고 있나요?" }),
    ).not.toBeInTheDocument();

    expect(turns).toHaveLength(questions.length);
    turns.forEach((turn, index) => {
      expect(
        within(turn).getByText(questions[index].content),
      ).toBeInTheDocument();
    });
  });

  it("답변은 줄바꿈마다 문단으로 나눠 보여준다 (근거 없음)", async () => {
    renderConversation();

    const [firstParagraph, secondParagraph] = answers[0].content.split("\n");

    expect((await screen.findByText(firstParagraph)).tagName).toBe("P");
    expect(screen.getByText(secondParagraph).tagName).toBe("P");
  });

  it("근거가 있는 답변 아래에만 '기록 N개에서 찾았어요' 버튼을 둔다 (SEARCH-R2)", async () => {
    renderConversation();

    const buttons = await screen.findAllByRole("button", {
      name: /에서 찾았어요$/,
    });

    expect(buttons.map((button) => button.textContent)).toEqual(
      answersWithEvidence.map(({ evidences }) =>
        evidenceButtonName(evidences.length),
      ),
    );
  });

  it("이전 실패 답변은 미검증 글자와 재시도 버튼 없이 안내 문단으로 보여준다 (SEARCH-R5)", async () => {
    respondWithFailedFirstAnswer();
    renderConversation();
    expect(await screen.findByText(FAILED_TEXT)).toBeInTheDocument();
    const [firstTurn] = screen.getAllByRole("article");
    expect(within(firstTurn).getByText(FAILED_TEXT).tagName).toBe("P");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "다시 시도" })).not.toBeInTheDocument();
    expect(screen.queryByText(PARTIAL_ANSWER)).not.toBeInTheDocument();
  });

  it("새로 접수된 답변의 기술 오류도 안내로 표시하고 미검증 본문은 숨긴다", async () => {
    const store = useSearchAnswerStore.getState();
    const controller = store.start({ question: "일반 질문", conversationId: 10 });
    if (!controller) throw new Error("질문을 시작하지 못했어요");
    store.applyEvent(controller, {
      event: "accepted",
      data: new SearchStreamAcceptedDto({ conversationId: 10, questionMessageId: 1001, answerMessageId: 1002 }),
    });
    store.applyEvent(controller, { event: "delta", data: new SearchStreamDeltaDto({ answerMessageId: 1002, text: PARTIAL_ANSWER }) });
    store.fail(controller, new Error("private-provider-error"));
    renderConversation();
    expect(await screen.findByText(FAILED_TEXT)).toBeInTheDocument();
    expect(screen.queryByText(PARTIAL_ANSWER)).not.toBeInTheDocument();
    expect(screen.queryByText("private-provider-error")).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("'기록 N개에서 찾았어요'를 누르면 그 답변의 찾은 기록을 연다 (SEARCH-R2)", async () => {
    const { router } = renderConversation();
    const [, answer] = answersWithEvidence;
    const button = await screen.findByRole("button", {
      name: evidenceButtonName(answer.evidences.length),
    });

    expect(button).toHaveAttribute("aria-pressed", "false");

    await act(async () => {
      fireEvent.click(button);
    });

    expect(router.state.location.search).toBe(`?messageId=${answer.id}`);
    expect(button).toHaveAttribute("aria-pressed", "true");
  });

  it("다른 답변의 버튼을 누르면 찾은 기록이 그 답변으로 바뀐다 (SEARCH-R2)", async () => {
    const { router } = renderConversation();
    const [firstAnswer, secondAnswer] = answersWithEvidence;
    const firstButton = await screen.findByRole("button", {
      name: evidenceButtonName(firstAnswer.evidences.length),
    });
    const secondButton = screen.getByRole("button", {
      name: evidenceButtonName(secondAnswer.evidences.length),
    });

    await act(async () => {
      fireEvent.click(firstButton);
    });
    await act(async () => {
      fireEvent.click(secondButton);
    });

    expect(router.state.location.search).toBe(`?messageId=${secondAnswer.id}`);
    expect(firstButton).toHaveAttribute("aria-pressed", "false");
    expect(secondButton).toHaveAttribute("aria-pressed", "true");
  });
});
