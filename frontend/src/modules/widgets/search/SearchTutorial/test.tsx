import { mockServer } from "@api/mock/server";
import { SEARCH_TUTORIAL_MOCK_CONVERSATION, SEARCH_TUTORIAL_QUESTION_KINDS } from "@constants/searchTutorial";
import { ThemeProvider } from "@emotion/react";
import { ToastProvider } from "@provider/context/toastContext";
import { theme } from "@provider/themeProvider";
import { getRouterPath } from "@routes/PATH_ROUTE";
import { useSearchTutorialStore } from "@store/searchTutorialStore";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import SearchConversation from "@widgets/search/SearchConversation";
import { http, HttpResponse } from "msw";
import { createMemoryRouter, Outlet, RouterProvider } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import SearchTutorial from ".";
import { COACH_COPY, DOCUMENT_COACH_TITLE } from "./constants/searchTutorial";
import { toLocalDate } from "./utils/searchTutorialRecord";

// 기다리는 시간을 줄여 흐름만 확인해요
vi.mock("./constants/searchTutorial", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./constants/searchTutorial")>()),
  SEARCH_TUTORIAL_SETTLE_MS: 10,
  SEARCH_TUTORIAL_DOCUMENT_DWELL_MS: 50,
  SEARCH_TUTORIAL_TYPING_INTERVAL_MS: 1,
  SEARCH_TUTORIAL_SEND_DELAY_MS: 10,
}));

const WORKSPACE_ID = "1";
const MEMBER_ID = 1; // meResponse의 회원
const RECORD_KEY = `knot.searchTutorial.${MEMBER_ID}`;
const HOME_PATH = getRouterPath({ routeKey: "WORKSPACE_HOME", params: { workspaceId: WORKSPACE_ID } });
const RECORDING_DOCUMENT_PATH = getRouterPath({
  routeKey: "RECORDING_DOCUMENT",
  params: { workspaceId: WORKSPACE_ID, recordingId: "10" },
});

/** 튜토리얼이 밝히는 대상. 실제 GNB와 독 대신 자리만 둬요 */
const Targets = ({ isChat = false }: { isChat?: boolean }) => (
  <>
    <button type="button" data-search-tutorial="search-tab">
      탐색
    </button>
    {isChat ? <div data-search-tutorial="dock-input" /> : <span data-search-tutorial="dock-chat" />}
  </>
);

const renderTutorial = (initialPath = HOME_PATH) => {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(
    [
      {
        path: "/workspace/:workspaceId",
        element: (
          <ToastProvider>
            <Outlet />
            <SearchTutorial />
          </ToastProvider>
        ),
        children: [
          { index: true, element: <Targets /> },
          { path: "recordings/:recordingId/document", element: <Targets /> },
          {
            path: "chat",
            element: (
              <>
                <Targets isChat />
                <SearchConversation />
              </>
            ),
          },
        ],
      },
    ],
    { initialEntries: [initialPath] },
  );

  render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={theme}>
        <RouterProvider router={router} />
      </ThemeProvider>
    </QueryClientProvider>,
  );

  return { router };
};

const yesterday = () => {
  const date = new Date();
  date.setDate(date.getDate() - 1);

  return toLocalDate(date);
};

beforeEach(() => {
  // jsdom은 크기를 재지 않아요. 밝힐 대상만 화면에 보이는 크기를 줘요
  vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function (this: HTMLElement) {
    const isTarget = this.dataset.searchTutorial !== undefined;
    const size = isTarget ? { width: 120, height: 40 } : { width: 0, height: 0 };

    return { top: 100, left: 100, x: 100, y: 100, right: 220, bottom: 140, ...size, toJSON: () => ({}) } as DOMRect;
  });
});

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
  useSearchTutorialStore.getState().end();
});

describe("SearchTutorial", () => {
  it("완료된 회의 문서가 있는 워크스페이스에 처음 들어오면 1단계 안내 카드를 띄운다", async () => {
    renderTutorial();

    expect(await screen.findByText(COACH_COPY[1].title)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "다음에 볼게요" })).toBeInTheDocument();
    expect(screen.getByText("독의 채팅 버튼으로도 열 수 있어요")).toBeInTheDocument();
  });

  it("워크스페이스에 문서가 없으면 띄우지 않는다", async () => {
    mockServer.use(
      http.get("*/api/v1/workspaces/:workspaceId/documents", () =>
        HttpResponse.json({ topics: [], items: [], nextCursor: null }),
      ),
    );
    renderTutorial();

    await new Promise((resolve) => setTimeout(resolve, 200));
    expect(screen.queryByText(COACH_COPY[1].title)).not.toBeInTheDocument();
  });

  it("이미 끝낸 멤버에게는 띄우지 않는다", async () => {
    localStorage.setItem(RECORD_KEY, JSON.stringify({ state: "done" }));
    renderTutorial();

    await new Promise((resolve) => setTimeout(resolve, 200));
    expect(screen.queryByText(COACH_COPY[1].title)).not.toBeInTheDocument();
  });

  it("다음에 볼게요를 누르면 오늘 날짜로 미루고 닫는다", async () => {
    renderTutorial();

    fireEvent.click(await screen.findByRole("button", { name: "다음에 볼게요" }));

    expect(screen.queryByText(COACH_COPY[1].title)).not.toBeInTheDocument();
    expect(JSON.parse(localStorage.getItem(RECORD_KEY) ?? "null")).toEqual({
      state: "snoozed",
      at: toLocalDate(new Date()),
    });
  });

  it("미룬 다음 날 두 번째로 뜰 때는 미루기 대신 닫기만 두고, 닫으면 다시 띄우지 않는다", async () => {
    localStorage.setItem(RECORD_KEY, JSON.stringify({ state: "snoozed", at: yesterday() }));
    renderTutorial();

    expect(await screen.findByRole("button", { name: "닫기" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "다음에 볼게요" })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "닫기" }));
    expect(JSON.parse(localStorage.getItem(RECORD_KEY) ?? "null")).toEqual({ state: "done" });
  });

  it("녹음 문서 화면에서는 문서를 살짝 확인한 뒤 문서 화면용 제목으로 띄운다", async () => {
    renderTutorial(RECORDING_DOCUMENT_PATH);

    expect(screen.queryByText(DOCUMENT_COACH_TITLE)).not.toBeInTheDocument();
    expect(await screen.findByText(DOCUMENT_COACH_TITLE)).toBeInTheDocument();
  });

  it("보여 주세요를 누르면 탐색에서 예시 질문과 예시 답변을 보여 주고, 질문 종류를 누르면 실제 질문을 보내고 끝난다", async () => {
    const questionRequests: string[] = [];
    mockServer.events.on("request:start", ({ request }) => {
      if (request.method === "POST" && request.url.includes("/search/conversations")) {
        questionRequests.push(request.url);
      }
    });
    const { router } = renderTutorial();

    fireEvent.click(await screen.findByRole("button", { name: "보여 주세요" }));

    // 2단계: 탐색으로 옮겨 예시 질문을 대신 넣어요
    await waitFor(() => expect(router.state.location.pathname).toBe(`/workspace/${WORKSPACE_ID}/chat`));
    expect(await screen.findByText(COACH_COPY[2].title)).toBeInTheDocument();
    expect(screen.getByText("예시 대화라 대화 목록에 남지 않아요")).toBeInTheDocument();

    // 3단계: 서버에 보내지 않은 예시 대화를 그리고, 다 쓰면 다음을 보여 줘요
    expect(await screen.findByText(SEARCH_TUTORIAL_MOCK_CONVERSATION.question)).toBeInTheDocument();
    expect(await screen.findByText(COACH_COPY[3].title, {}, { timeout: 3000 })).toBeInTheDocument();
    expect(screen.getByText(SEARCH_TUTORIAL_MOCK_CONVERSATION.answer)).toBeInTheDocument();
    expect(questionRequests).toHaveLength(0);

    // 4단계: 질문 종류 카드를 누르면 그 질문을 실제로 보내고 끝나요
    fireEvent.click(screen.getByRole("button", { name: "다음" }));
    const [firstKind] = SEARCH_TUTORIAL_QUESTION_KINDS;
    fireEvent.click(await screen.findByRole("button", { name: new RegExp(firstKind.question) }));

    await waitFor(() => expect(questionRequests.length).toBeGreaterThan(0));
    expect(screen.queryByTestId("search-tutorial")).not.toBeInTheDocument();
    expect(JSON.parse(localStorage.getItem(RECORD_KEY) ?? "null")).toEqual({ state: "done" });
  });

  it("2단계에서 건너뛰기를 누르면 끝내고 다시 띄우지 않는다", async () => {
    renderTutorial();

    fireEvent.click(await screen.findByRole("button", { name: "보여 주세요" }));
    fireEvent.click(await screen.findByRole("button", { name: "건너뛰기" }));

    expect(screen.queryByTestId("search-tutorial")).not.toBeInTheDocument();
    expect(JSON.parse(localStorage.getItem(RECORD_KEY) ?? "null")).toEqual({ state: "done" });
  });

  it("탐색 빈 대화의 탐색 사용법 보기로 끝낸 튜토리얼을 다시 볼 수 있다", async () => {
    localStorage.setItem(RECORD_KEY, JSON.stringify({ state: "done" }));
    renderTutorial(getRouterPath({ routeKey: "CHAT", params: { workspaceId: WORKSPACE_ID } }));

    fireEvent.click(await screen.findByRole("button", { name: "탐색 사용법 보기" }));

    expect(await screen.findByText(COACH_COPY[1].title)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "탐색 사용법 보기" })).not.toBeInTheDocument();
  });
});
