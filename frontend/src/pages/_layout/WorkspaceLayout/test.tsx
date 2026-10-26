import { GetDocumentResponseDto } from "@api/dto/document";
import { GetSearchMessagesResponseDto } from "@api/dto/search";
import { LAST_VIEWED_WORKSPACE_API_PATH } from "@api/fetch/api/v1/members/me/lastViewedWorkspace";
import { documentDetailsResponse } from "@api/mock/responses/document";
import { recordingDocumentResponse } from "@api/mock/responses/recordingDocument";
import type { RecordingDocumentResponse } from "@api/mock/types/recordingDocument";
import { WORKSPACES_API_PATH } from "@api/fetch/api/v1/workspaces";
import {
  workspaceDetailResponse,
  workspacesResponse,
} from "@api/mock/responses/workspace";
import {
  searchAnswerStreamResponse,
  searchConversationsResponse,
  searchMessagesResponse,
  searchQuestionAnswerStreamResponse,
} from "@api/mock/responses/search";
import { mockServer } from "@api/mock/server";
import { ThemeProvider } from "@emotion/react";
import CenteredLayout from "@pages/_layout/CenteredLayout";
import AppProviders from "@provider/AppProviders";
import { useDialog } from "@provider/context/dialogContext";
import { useToast } from "@provider/context/toastContext";
import { theme } from "@provider/themeProvider";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import { useSearchAnswerStore } from "@store/searchAnswerStore";
import { useTranscriptPanelStore } from "@store/transcriptPanelStore";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ANALYTICS_SCROLL_ROOT_ATTRIBUTE } from "@constants/analytics";
import { TOAST_MESSAGES } from "@constants/toastMessages";
import { saveLastConversationId } from "@utils/lastConversation";
import {
  act,
  cleanup,
  configure,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { delay, http, HttpResponse } from "msw";
import { createMemoryRouter, Outlet, RouterProvider } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import SearchConversation from "@widgets/search/SearchConversation";
import type { ReactNode } from "react";

import WorkspaceLayout from ".";
import { WORKSPACE_DOCK_RAIL_ID } from "./constants/dockRail";

const WORKSPACE_ID = 1;
const HOME_PATH = getRouterPath({
  routeKey: "WORKSPACE_HOME",
  params: { workspaceId: String(WORKSPACE_ID) },
});
const [firstDocument, secondDocument] = documentDetailsResponse.map(
  (response) => new GetDocumentResponseDto(response),
);
const DOCUMENT_PATH = getRouterPath({
  routeKey: "DOCUMENT",
  params: {
    workspaceId: String(WORKSPACE_ID),
    documentId: String(firstDocument.id),
  },
});
const OTHER_DOCUMENT_PATH = getRouterPath({
  routeKey: "DOCUMENT",
  params: {
    workspaceId: String(WORKSPACE_ID),
    documentId: String(secondDocument.id),
  },
});
const DOCUMENT_TEXT = "문서 본문";
const RECORDING_DOCUMENT_PATH = getRouterPath({
  routeKey: "RECORDING_DOCUMENT",
  params: {
    workspaceId: String(WORKSPACE_ID),
    recordingId: String(recordingDocumentResponse.recordingId),
  },
});
const RECORDING_DOCUMENT_TEXT = "녹음 문서 본문";
const TRANSCRIPT_PANEL_TITLE = "원문";
/** 원문이 닫혀 있을 때의 버튼 이름 */
const OPEN_LABEL = "원문 보기";
/** 원문이 열려 있을 때의 버튼 이름 */
const CLOSE_LABEL = "원문 닫기";
const CHAT_PATH = getRouterPath({
  routeKey: "CHAT",
  params: { workspaceId: String(WORKSPACE_ID) },
});
const CONVERSATION_ID = "10";
const CONVERSATION_PATH = getRouterPath({
  routeKey: "CHAT_SESSION",
  params: { workspaceId: String(WORKSPACE_ID), sessionId: CONVERSATION_ID },
});
const TOAST_PATH = `${HOME_PATH}/toast`;
const ELSEWHERE_PATH = "/elsewhere";
const HOME_TEXT = "홈 본문";
const CHAT_TEXT = "탐색 본문";
// 경로 파라미터가 있어 fetch 상수 대신 mock 핸들러와 같은 패턴을 적어요
const WORKSPACE_PATH_PATTERN = "*/api/v1/workspaces/:workspaceId";

const SAVED = "녹음을 저장했어요";
// 피그마 ERR-03(서버 오류), ERR-02(네트워크 오류) 화면의 제목이에요
const SERVER_ERROR_TITLE = "잠시 문제가 생겼어요";
const NETWORK_ERROR_TITLE = "연결이 잠시 끊겼어요";

function ToastTriggerPage() {
  const { show } = useToast();
  const { open } = useDialog();

  return (
    <>
      <button
        type="button"
        onClick={() => show({ variant: "success", message: SAVED })}
      >
        {SAVED} 알리기
      </button>
      <button type="button" onClick={() => open(() => <ToastDialog />)}>
        알림 모달 열기
      </button>
    </>
  );
}

function ToastDialog() {
  const { show } = useToast();

  return (
    <div role="dialog" aria-label="알림 모달">
      <button
        type="button"
        onClick={() => show({ variant: "success", message: SAVED })}
      >
        모달에서 알리기
      </button>
    </div>
  );
}

/**
 * @param chatElement - 탐색 화면 자리에 그릴 것. 탐색 흐름을 확인할 때만 실제 대화 칸을 넘겨요
 */
const renderLayout = (
  initialPath = HOME_PATH,
  chatElement: ReactNode = <p>{CHAT_TEXT}</p>,
) => {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  const layouts = [
    {
      handle: { hasDock: true },
      element: <WorkspaceLayout />,
      children: [
        { path: PATH_ROUTE.WORKSPACE_HOME, element: <p>{HOME_TEXT}</p> },
        {
          path: `${PATH_ROUTE.WORKSPACE_HOME}/toast`,
          element: <ToastTriggerPage />,
        },
        { path: PATH_ROUTE.CHAT, element: chatElement },
        { path: PATH_ROUTE.CHAT_SESSION, element: chatElement },
        { path: PATH_ROUTE.DOCUMENT, element: <p>{DOCUMENT_TEXT}</p> },
        {
          path: PATH_ROUTE.RECORDING_DOCUMENT,
          element: <p>{RECORDING_DOCUMENT_TEXT}</p>,
        },
      ],
    },
    {
      element: <CenteredLayout />,
      children: [
        { path: ELSEWHERE_PATH, element: <p>다른 화면</p> },
        { path: PATH_ROUTE.WORKSPACE, element: <p>워크스페이스 선택</p> },
        { path: PATH_ROUTE.LOGIN, element: <p>로그인</p> },
      ],
    },
  ];
  const router = createMemoryRouter(
    [
      {
        element: (
          <AppProviders routes={layouts}>
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

  return { router, queryClient };
};

const answers = new GetSearchMessagesResponseDto(
  searchMessagesResponse,
).items.filter(({ role }) => role === "ASSISTANT");
const [answerWithEvidence] = answers.filter(
  ({ evidences }) => evidences.length > 0,
);
const [answerWithoutEvidence] = answers.filter(
  ({ evidences }) => evidences.length === 0,
);

/** 레이아웃 본문(Body) 칸. 왼쪽 레일·본문·오른쪽 레일이 이 칸의 자식으로 놓여요 */
const getBody = () =>
  document.getElementById(WORKSPACE_DOCK_RAIL_ID)?.parentElement;

const overrideWorkspaceStatus = (status: number) => {
  mockServer.use(
    http.get(WORKSPACE_PATH_PATTERN, () => new HttpResponse(null, { status })),
  );
};

const holdWorkspaceResponse = () => {
  mockServer.use(
    http.get(WORKSPACE_PATH_PATTERN, async () => {
      await delay("infinite");
      return HttpResponse.json(workspaceDetailResponse);
    }),
  );
};

/** 마지막으로 본 워크스페이스 갱신 요청의 본문을 모아 호출 횟수와 값을 확인해요 */
const captureLastViewedRequests = () => {
  const bodies: unknown[] = [];

  mockServer.use(
    http.put(`*${LAST_VIEWED_WORKSPACE_API_PATH}`, async ({ request }) => {
      bodies.push(await request.json());
      return new HttpResponse(null, { status: 204 });
    }),
  );

  return bodies;
};

const waitForPath = (
  router: ReturnType<typeof createMemoryRouter>,
  pathname: string,
) =>
  waitFor(() => {
    expect(router.state.location.pathname).toBe(pathname);
  });

const waitRealTime = async (ms: number) => {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, ms));
  });
};

const goBack = async (router: ReturnType<typeof createMemoryRouter>) => {
  await act(async () => {
    await router.navigate(-1);
  });
};

describe("WorkspaceLayout", () => {
  it("워크스페이스를 조회하는 동안에는 본문 대신 스피너를 보여준다", () => {
    holdWorkspaceResponse();
    renderLayout();

    expect(
      screen.getByRole("status", { name: "워크스페이스를 불러오고 있어요" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("main")).toHaveAttribute("aria-busy", "true");
    expect(screen.queryByText(HOME_TEXT)).not.toBeInTheDocument();
  });

  it("조회에 성공하면 본문을 보여주고 마지막으로 본 워크스페이스를 한 번 갱신한다", async () => {
    const bodies = captureLastViewedRequests();
    renderLayout();

    expect(await screen.findByText(HOME_TEXT)).toBeInTheDocument();
    expect(screen.getByRole("main")).toHaveAttribute("aria-busy", "false");
    expect(screen.queryByRole("status")).not.toBeInTheDocument();

    await waitFor(() => {
      expect(bodies).toEqual([{ workspaceId: WORKSPACE_ID }]);
    });
    await waitRealTime(100);
    expect(bodies).toHaveLength(1);
  });

  it("하위 라우트(탐색)로 들어와도 같은 판정을 거쳐 갱신한다", async () => {
    const bodies = captureLastViewedRequests();
    renderLayout(CHAT_PATH);

    expect(await screen.findByText(CHAT_TEXT)).toBeInTheDocument();
    await waitFor(() => {
      expect(bodies).toEqual([{ workspaceId: WORKSPACE_ID }]);
    });
  });

  it("갱신에 실패해도 본문은 그대로 보여준다", async () => {
    mockServer.use(
      http.put(
        `*${LAST_VIEWED_WORKSPACE_API_PATH}`,
        () => new HttpResponse(null, { status: 500 }),
      ),
    );
    const { router } = renderLayout();

    expect(await screen.findByText(HOME_TEXT)).toBeInTheDocument();
    await waitRealTime(100);

    expect(screen.getByText(HOME_TEXT)).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(HOME_PATH);
  });

  it("401이면 로그인 화면으로 replace 이동한다", async () => {
    overrideWorkspaceStatus(401);
    const { router } = renderLayout();

    await waitForPath(router, PATH_ROUTE.LOGIN);
    await goBack(router);

    expect(router.state.location.pathname).toBe(ELSEWHERE_PATH);
  });

  describe("들어갈 수 없는 워크스페이스(ERR-11, D7)", () => {
    const OTHER_WORKSPACE_ID = 2;
    const OTHER_HOME_PATH = getRouterPath({
      routeKey: "WORKSPACE_HOME",
      params: { workspaceId: String(OTHER_WORKSPACE_ID) },
    });

    /** 이 워크스페이스만 `status`로 거절하고, 다른 워크스페이스는 그대로 들어갈 수 있게 해요 */
    const denyThisWorkspace = (status: number) => {
      mockServer.use(
        http.get(WORKSPACE_PATH_PATTERN, ({ params }) =>
          params.workspaceId === String(WORKSPACE_ID)
            ? new HttpResponse(null, { status })
            : HttpResponse.json(workspaceDetailResponse),
        ),
      );
    };

    /**
     * 도착 화면이 토스트를 띄운 뒤 이동 기록의 토스트를 비울 때까지 기다려요.
     * 비우는 이동보다 뒤로 가기가 먼저 실행되면, 비우는 이동이 돌아간 기록을 덮어써요
     */
    const waitForToastConsumed = (
      router: ReturnType<typeof createMemoryRouter>,
    ) =>
      waitFor(() => {
        expect(router.state.location.state).toBeNull();
      });

    const respondLastViewedWorkspace = (lastViewedWorkspaceId: number) => {
      mockServer.use(
        http.get(`*${WORKSPACES_API_PATH}`, () =>
          HttpResponse.json({ ...workspacesResponse, lastViewedWorkspaceId }),
        ),
      );
    };

    it.each([403, 404])(
      "%i면 마지막으로 본 다른 워크스페이스 홈으로 replace 이동하고 오류 토스트를 띄운다",
      async (status) => {
        denyThisWorkspace(status);
        respondLastViewedWorkspace(OTHER_WORKSPACE_ID);
        const { router } = renderLayout();

        await waitForPath(router, OTHER_HOME_PATH);
        expect(
          await screen.findByText(
            TOAST_MESSAGES.WORKSPACE_ACCESS_DENIED.message,
          ),
        ).toBeInTheDocument();
        await waitForToastConsumed(router);
        await goBack(router);

        expect(router.state.location.pathname).toBe(ELSEWHERE_PATH);
      },
    );

    it.each([403, 404])(
      "%i인데 마지막으로 본 워크스페이스가 바로 그 워크스페이스면 선택 화면으로 replace 이동하고 오류 토스트를 띄운다",
      async (status) => {
        overrideWorkspaceStatus(status);
        respondLastViewedWorkspace(WORKSPACE_ID);
        const { router } = renderLayout();

        await waitForPath(router, PATH_ROUTE.WORKSPACE);
        expect(
          await screen.findByText(
            TOAST_MESSAGES.WORKSPACE_ACCESS_DENIED.message,
          ),
        ).toBeInTheDocument();
        await waitForToastConsumed(router);
        await goBack(router);

        expect(router.state.location.pathname).toBe(ELSEWHERE_PATH);
      },
    );

    it("워크스페이스 목록을 받지 못해도 선택 화면으로 보내고 오류 토스트를 띄운다", async () => {
      overrideWorkspaceStatus(403);
      mockServer.use(
        http.get(
          `*${WORKSPACES_API_PATH}`,
          () => new HttpResponse(null, { status: 500 }),
        ),
      );
      const { router } = renderLayout();

      await waitForPath(router, PATH_ROUTE.WORKSPACE);
      expect(
        await screen.findByText(TOAST_MESSAGES.WORKSPACE_ACCESS_DENIED.message),
      ).toBeInTheDocument();
    });

    it("이미 들어온 워크스페이스에서 내보내져 다시 조회가 403이면 마지막으로 본 다른 워크스페이스로 보낸다", async () => {
      respondLastViewedWorkspace(OTHER_WORKSPACE_ID);
      const { router, queryClient } = renderLayout();
      await screen.findByText(HOME_TEXT);

      denyThisWorkspace(403);
      await act(() => queryClient.refetchQueries());

      await waitForPath(router, OTHER_HOME_PATH);
      expect(
        await screen.findByText(TOAST_MESSAGES.WORKSPACE_ACCESS_DENIED.message),
      ).toBeInTheDocument();
    });
  });

  it("workspaceId가 정수가 아니면 조회하지 않고 워크스페이스 선택 화면으로 replace 이동한다", async () => {
    const { router } = renderLayout(
      getRouterPath({
        routeKey: "WORKSPACE_HOME",
        params: { workspaceId: "abc" },
      }),
    );

    await waitForPath(router, PATH_ROUTE.WORKSPACE);
    await goBack(router);

    expect(router.state.location.pathname).toBe(ELSEWHERE_PATH);
  });

  it("대화 목록 버튼은 탐색 화면에서만 둔다", async () => {
    renderLayout();

    expect(await screen.findByText(HOME_TEXT)).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "대화 목록" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "사이드바" }),
    ).toBeInTheDocument();
  });

  it("탐색 화면에서는 사이드바와 대화 목록을 둘 다 열 수 있다", async () => {
    renderLayout(CHAT_PATH);

    expect(await screen.findByText(CHAT_TEXT)).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "사이드바" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "대화 목록" }),
    ).toBeInTheDocument();
  });

  it("사이드바 버튼을 누르면 사이드바가 왼쪽 레일로 옮겨 가 자리를 차지한다", async () => {
    renderLayout();

    expect(await screen.findByText(HOME_TEXT)).toBeInTheDocument();
    const rail = document.getElementById(WORKSPACE_DOCK_RAIL_ID);

    expect(rail).toBeEmptyDOMElement();

    fireEvent.click(screen.getByRole("button", { name: "사이드바" }));

    expect(rail).toContainElement(
      screen.getByRole("complementary", { name: "워크스페이스 사이드바" }),
    );
  });

  it("레일은 하나만 담으므로 대화 목록을 고정하면 사이드바는 저절로 접힌다", async () => {
    renderLayout(CHAT_PATH);

    expect(await screen.findByText(CHAT_TEXT)).toBeInTheDocument();
    const sidebarTrigger = screen.getByRole("button", { name: "사이드바" });
    const chatListTrigger = screen.getByRole("button", { name: "대화 목록" });

    fireEvent.click(sidebarTrigger);

    expect(sidebarTrigger).toHaveAttribute("aria-pressed", "true");

    fireEvent.click(chatListTrigger);

    expect(chatListTrigger).toHaveAttribute("aria-pressed", "true");
    expect(sidebarTrigger).toHaveAttribute("aria-pressed", "false");
    expect(
      screen.queryByRole("complementary", { name: "워크스페이스 사이드바" }),
    ).not.toBeInTheDocument();
  });

  it("고정한 패널을 다시 누르면 레일이 비어 아무것도 고정돼 있지 않다", async () => {
    renderLayout(CHAT_PATH);

    expect(await screen.findByText(CHAT_TEXT)).toBeInTheDocument();
    const chatListTrigger = screen.getByRole("button", { name: "대화 목록" });

    fireEvent.click(chatListTrigger);
    fireEvent.click(chatListTrigger);

    expect(chatListTrigger).toHaveAttribute("aria-pressed", "false");
    expect(
      document.getElementById(WORKSPACE_DOCK_RAIL_ID),
    ).toBeEmptyDOMElement();
  });

  it("사이드바를 고정해도 GNB는 레일 바깥에 남아 화면 전체 폭을 쓴다", async () => {
    renderLayout();

    expect(await screen.findByText(HOME_TEXT)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "사이드바" }));

    const rail = document.getElementById(WORKSPACE_DOCK_RAIL_ID);

    expect(rail?.parentElement).not.toContainElement(
      screen.getByRole("banner"),
    );
  });

  it.each([401, 403, 404])(
    "%i면 이동하는 동안 에러 화면을 그리지 않는다",
    async (status) => {
      overrideWorkspaceStatus(status);
      const { router } = renderLayout();
      // 이동하기 전에 잠깐이라도 그린 제목을 모아요
      const seenTitles: string[] = [];
      const observer = new MutationObserver(() => {
        document.querySelectorAll("h1").forEach(({ textContent }) => {
          seenTitles.push(textContent ?? "");
        });
      });
      observer.observe(document.body, { childList: true, subtree: true });

      await waitFor(() => {
        expect(router.state.location.pathname).not.toBe(HOME_PATH);
      });
      observer.disconnect();

      expect(seenTitles).toEqual([]);
    },
  );

  it("서버 오류(5xx)로 조회하지 못하면 이동하지 않고, GNB와 독 없이 ERR-03 화면을 보여준다 (ERR-03)", async () => {
    overrideWorkspaceStatus(500);
    const { router } = renderLayout();

    expect(
      await screen.findByRole("heading", { name: SERVER_ERROR_TITLE }),
    ).toBeInTheDocument();
    expect(screen.getByText("서버에서 생긴 문제예요.")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "다시 시도" }),
    ).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(HOME_PATH);
    expect(screen.queryByRole("banner")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "사이드바" }),
    ).not.toBeInTheDocument();
    expect(screen.queryByText(HOME_TEXT)).not.toBeInTheDocument();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it.each([400, 409, 429])(
    "%i로 조회하지 못하면 스피너를 계속 돌리지 않고, 이동 없이 GNB와 독 없이 ERR-03 화면을 보여준다 (ERR-03)",
    async (status) => {
      overrideWorkspaceStatus(status);
      const { router } = renderLayout();

      expect(
        await screen.findByRole("heading", { name: SERVER_ERROR_TITLE }),
      ).toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: "다시 시도" }),
      ).toBeInTheDocument();
      expect(router.state.location.pathname).toBe(HOME_PATH);
      expect(screen.queryByRole("banner")).not.toBeInTheDocument();
      expect(screen.queryByText(HOME_TEXT)).not.toBeInTheDocument();
      expect(screen.queryByRole("status")).not.toBeInTheDocument();
    },
  );

  it("응답을 받지 못하면(네트워크 끊김) GNB와 독 없이 ERR-02 화면을 보여준다 (ERR-02)", async () => {
    mockServer.use(
      http.get(WORKSPACE_PATH_PATTERN, () => HttpResponse.error()),
    );
    const { router } = renderLayout();

    expect(
      await screen.findByRole("heading", { name: NETWORK_ERROR_TITLE }),
    ).toBeInTheDocument();
    expect(screen.getByText("네트워크 상태를 확인한 뒤")).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(HOME_PATH);
    expect(screen.queryByRole("banner")).not.toBeInTheDocument();
    expect(screen.queryByText(HOME_TEXT)).not.toBeInTheDocument();
  });

  it.each([
    ["서버 오류", SERVER_ERROR_TITLE, () => overrideWorkspaceStatus(503)],
    [
      "네트워크 끊김",
      NETWORK_ERROR_TITLE,
      () =>
        mockServer.use(
          http.get(WORKSPACE_PATH_PATTERN, () => HttpResponse.error()),
        ),
    ],
  ])(
    "%s 화면에서 다시 시도를 누르면 페이지를 새로고침한다",
    async (_, title, respond) => {
      respond();
      renderLayout();
      await screen.findByRole("heading", { name: title });
      // jsdom은 새로고침하지 못해요. 새로고침 요청만 받아 두는 가짜 location으로 바꿔요
      const reload = vi.fn();
      vi.stubGlobal("location", { reload });

      try {
        fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));

        expect(reload).toHaveBeenCalledTimes(1);
      } finally {
        // 실패해도 가짜 location이 다음 테스트로 넘어가지 않게 되돌려요
        vi.unstubAllGlobals();
      }
    },
  );

  it("이미 들어온 뒤 다시 조회만 실패하면 에러 화면으로 바꾸지 않는다", async () => {
    const { queryClient } = renderLayout();
    expect(await screen.findByText(HOME_TEXT)).toBeInTheDocument();

    overrideWorkspaceStatus(500);
    await act(async () => {
      await queryClient.refetchQueries();
    });
    // 실패한 상태가 화면에 반영될 때까지 기다려요
    await waitRealTime(100);

    expect(
      screen.queryByRole("heading", { name: SERVER_ERROR_TITLE }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("banner")).toBeInTheDocument();
  });

  it("탐색 대화에서 답변의 찾은 기록을 펼치면 본문 오른쪽 레일에 찾은 기록 패널을 둔다 (SEARCH-R11)", async () => {
    renderLayout(`${CONVERSATION_PATH}?messageId=${answerWithEvidence.id}`);

    expect(await screen.findByText(CHAT_TEXT)).toBeInTheDocument();
    const panel = screen.getByRole("region", { name: "찾은 기록" });
    const main = screen.getByRole("main");
    const body = getBody();

    // 본문 바깥의 형제 칸에 놓여야 본문 폭이 줄어 대화 열·독이 함께 옮겨 가요
    expect(main).not.toContainElement(panel);
    expect(body?.children).toHaveLength(3);
    expect(body?.lastElementChild).toContainElement(panel);
    expect(body?.lastElementChild).not.toContainElement(main);
  });

  // 레일은 여닫힘을 폭으로 옮겨 가도록 탐색 화면에서 늘 두므로, 닫힘은 레일이 비었는지로 봐요(폭은 jsdom이 계산하지 못해요)
  it("찾은 기록을 펼치지 않으면 오른쪽 레일을 비워 본문이 폭을 다 쓴다 (SEARCH-R11)", async () => {
    renderLayout(CONVERSATION_PATH);

    expect(await screen.findByText(CHAT_TEXT)).toBeInTheDocument();
    expect(
      screen.queryByRole("region", { name: "찾은 기록" }),
    ).not.toBeInTheDocument();
    expect(getBody()?.lastElementChild).toBeEmptyDOMElement();
  });

  it("근거가 없는 답변을 펼치면 오른쪽 레일을 비운다 (SEARCH-R11)", async () => {
    renderLayout(`${CONVERSATION_PATH}?messageId=${answerWithoutEvidence.id}`);

    expect(await screen.findByText(CHAT_TEXT)).toBeInTheDocument();
    expect(getBody()?.lastElementChild).toBeEmptyDOMElement();
  });

  it("홈 화면에서는 오른쪽 레일과 찾은 기록 버튼을 두지 않는다 (Figma GNB/Floating)", async () => {
    renderLayout(`${HOME_PATH}?messageId=${answerWithEvidence.id}`);

    expect(await screen.findByText(HOME_TEXT)).toBeInTheDocument();
    expect(getBody()?.children).toHaveLength(2);
    expect(
      screen.queryByRole("button", { name: /^찾은 기록/ }),
    ).not.toBeInTheDocument();
  });

  it("탐색 대화에서는 GNB 오른쪽에 찾은 기록 버튼을 두고, 누르면 오른쪽 레일이 열린다 (Figma GNB/Floating)", async () => {
    renderLayout(CONVERSATION_PATH);

    expect(await screen.findByText(CHAT_TEXT)).toBeInTheDocument();
    const toggle = within(screen.getByRole("banner")).getByRole("button", {
      name: "찾은 기록 보기",
    });

    await act(async () => {
      fireEvent.click(toggle);
    });

    expect(getBody()?.lastElementChild).toContainElement(
      screen.getByRole("region", { name: "찾은 기록" }),
    );
  });

  it("[공통 UI 규칙·위치] 토스트는 독과 같은 자리의 독 바로 위에 그려진다", async () => {
    renderLayout(TOAST_PATH);

    await act(async () => {
      fireEvent.click(
        await screen.findByRole("button", { name: `${SAVED} 알리기` }),
      );
    });

    const toastList = screen.getByTestId("toast-list");
    const dockButton = screen.getByRole("button", {
      name: "무엇이든 요청하기",
    });

    expect(within(toastList).getByText(SAVED)).toBeVisible();
    // 목록 바로 다음 칸에 독이 놓여요
    expect(toastList.nextElementSibling).toContainElement(dockButton);
  });

  it("홈에서 탐색으로 이동해도 같은 토스트 목록을 유지한다", async () => {
    const { router } = renderLayout(TOAST_PATH);
    fireEvent.click(
      await screen.findByRole("button", { name: `${SAVED} 알리기` }),
    );
    const list = screen.getByTestId("toast-list");

    await act(() => router.navigate(CHAT_PATH));

    expect(await screen.findByText(CHAT_TEXT)).toBeInTheDocument();
    expect(screen.getByTestId("toast-list")).toBe(list);
    expect(within(list).getByText(SAVED)).toBeVisible();
  });

  it("같은 레이아웃에서 이동한 뒤에도 열린 모달이 토스트를 띄울 수 있다", async () => {
    const { router } = renderLayout(TOAST_PATH);
    fireEvent.click(
      await screen.findByRole("button", { name: "알림 모달 열기" }),
    );

    await act(() => router.navigate(CHAT_PATH));
    expect(await screen.findByText(CHAT_TEXT)).toBeInTheDocument();
    expect(
      screen.getByRole("dialog", { name: "알림 모달" }),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "모달에서 알리기" }));
    expect(
      within(screen.getByTestId("toast-list")).getByText(SAVED),
    ).toBeVisible();
  });

  it("다른 레이아웃으로 나가도 모달은 유지하고 이전 토스트만 정리한다", async () => {
    const { router } = renderLayout(TOAST_PATH);
    fireEvent.click(
      await screen.findByRole("button", { name: "알림 모달 열기" }),
    );
    fireEvent.click(screen.getByRole("button", { name: "모달에서 알리기" }));

    await act(() => router.navigate(PATH_ROUTE.LOGIN));

    expect(
      screen.getByRole("dialog", { name: "알림 모달" }),
    ).toBeInTheDocument();
    expect(screen.getByTestId("toast-list")).toBeEmptyDOMElement();
    fireEvent.click(screen.getByRole("button", { name: "모달에서 알리기" }));
    expect(
      within(screen.getByTestId("toast-list")).getByText(SAVED),
    ).toBeVisible();
  });

  it("다른 워크스페이스를 불러오는 동안에도 같은 레이아웃의 토스트를 유지한다", async () => {
    const { router } = renderLayout(TOAST_PATH);
    fireEvent.click(
      await screen.findByRole("button", { name: `${SAVED} 알리기` }),
    );
    const list = screen.getByTestId("toast-list");
    holdWorkspaceResponse();

    await act(() => router.navigate("/workspace/2"));

    expect(screen.getByRole("main")).toHaveAttribute("aria-busy", "true");
    expect(screen.getByTestId("toast-list")).toBe(list);
    expect(within(list).getByText(SAVED)).toBeVisible();
  });

  it("다른 레이아웃으로 나갔다 돌아오면 이전 토스트가 다시 나타나지 않는다", async () => {
    const { router } = renderLayout(TOAST_PATH);
    fireEvent.click(
      await screen.findByRole("button", { name: `${SAVED} 알리기` }),
    );

    await act(() => router.navigate(PATH_ROUTE.LOGIN));
    expect(screen.getByTestId("toast-list")).toBeEmptyDOMElement();

    await act(() => router.navigate(-1));
    expect(
      await screen.findByRole("button", { name: `${SAVED} 알리기` }),
    ).toBeVisible();
    expect(screen.getByTestId("toast-list")).toBeEmptyDOMElement();
  });
});

describe("WorkspaceLayout의 워크스페이스 사용 기록", () => {
  it("스크롤되는 본문(main)에 스크롤 깊이를 재는 표시를 단다", async () => {
    renderLayout(HOME_PATH);
    await screen.findByText(HOME_TEXT);

    expect(screen.getByRole("main")).toHaveAttribute(
      ANALYTICS_SCROLL_ROOT_ATTRIBUTE,
    );
  });
});

describe("WorkspaceLayout의 본문 스크롤 위치", () => {
  /** 화면에 보이는 본문 높이(px)예요 */
  const VISIBLE_HEIGHT = 500;

  /**
   * jsdom은 화면을 그리지 않아 본문의 높이와 스크롤 위치가 늘 0이에요.
   * 내용 높이를 정해 두고, 브라우저처럼 스크롤 위치를 0과 끝 사이로 맞춰 저장해요
   */
  const mockMainScroll = (contentHeight: number) => {
    const main = screen.getByRole("main");
    let height = contentHeight;
    let top = 0;
    Object.defineProperty(main, "clientHeight", {
      configurable: true,
      get: () => VISIBLE_HEIGHT,
    });
    Object.defineProperty(main, "scrollHeight", {
      configurable: true,
      get: () => height,
    });
    Object.defineProperty(main, "scrollTop", {
      configurable: true,
      get: () => top,
      set: (value: number) => {
        top = Math.max(0, Math.min(value, height - VISIBLE_HEIGHT));
      },
    });

    return {
      main,
      /** 사용자가 본문을 그 위치까지 내려요 */
      scrollTo: (value: number) => {
        main.scrollTop = value;
        fireEvent.scroll(main);
      },
      /** 내용이 불러와져 본문 높이가 바뀌어요 */
      setContentHeight: (value: number) => {
        height = value;
      },
    };
  };

  interface MoveToParams {
    router: ReturnType<typeof createMemoryRouter>;
    path: string;
    /** 기록을 쌓지 않고 바꿔치기할지 */
    options?: { replace: boolean };
  }

  const moveTo = ({ router, path, options }: MoveToParams) =>
    act(() => router.navigate(path, options));

  const goForward = (router: ReturnType<typeof createMemoryRouter>) =>
    act(() => router.navigate(1));

  /** 홈에서 본문을 600px 내린 상태로 만들어요 */
  const renderScrolledHome = async () => {
    const rendered = renderLayout(HOME_PATH);
    await screen.findByText(HOME_TEXT);
    const scroll = mockMainScroll(2000);
    scroll.scrollTo(600);

    return { ...rendered, ...scroll };
  };

  afterEach(() => {
    vi.useRealTimers();
    configure({ reactStrictMode: false });
  });

  it.each([
    { how: "링크 · 버튼으로", options: undefined },
    { how: "기록을 바꿔치기해", options: { replace: true } },
  ])(
    "$how 새 화면에 가면 앞 화면에서 내린 위치가 아니라 맨 위에서 시작한다",
    async ({ options }) => {
      const { router, main } = await renderScrolledHome();

      await moveTo({ router, path: DOCUMENT_PATH, options });

      expect(await screen.findByText(DOCUMENT_TEXT)).toBeInTheDocument();
      expect(main.scrollTop).toBe(0);
    },
  );

  it("뒤로 · 앞으로 가기로 돌아오면 그 화면에서 보던 위치로 돌린다", async () => {
    const { router, main, scrollTo } = await renderScrolledHome();
    await moveTo({ router, path: DOCUMENT_PATH });
    await screen.findByText(DOCUMENT_TEXT);
    scrollTo(300);

    await goBack(router);

    expect(await screen.findByText(HOME_TEXT)).toBeInTheDocument();
    expect(main.scrollTop).toBe(600);

    await goForward(router);

    expect(await screen.findByText(DOCUMENT_TEXT)).toBeInTheDocument();
    expect(main.scrollTop).toBe(300);
  });

  it("뒤로 가기로 돌아왔을 때 내용이 아직 짧으면, 그 위치까지 길어진 뒤 맞춘다", async () => {
    const { router, main, setContentHeight } = await renderScrolledHome();
    await moveTo({ router, path: DOCUMENT_PATH });
    await screen.findByText(DOCUMENT_TEXT);
    // 홈 내용을 다시 불러오는 동안이라 본문이 600px까지 내려갈 만큼 길지 않아요
    setContentHeight(700);

    await goBack(router);
    await screen.findByText(HOME_TEXT);

    expect(main.scrollTop).not.toBe(600);

    setContentHeight(2000);

    await waitFor(() => expect(main.scrollTop).toBe(600));
  });

  it("정해 둔 시간 안에 내용이 길어지지 않으면 위치를 맞추지 않는다", async () => {
    const { router, main, setContentHeight } = await renderScrolledHome();
    await moveTo({ router, path: DOCUMENT_PATH });
    await screen.findByText(DOCUMENT_TEXT);
    setContentHeight(700);
    // 시계만 가짜로 두고, 화면 그리기는 실제로 흐르게 해요
    vi.useFakeTimers({ toFake: ["Date"] });

    await goBack(router);
    await screen.findByText(HOME_TEXT);
    vi.setSystemTime(Date.now() + 10_000);
    await waitRealTime(100);
    setContentHeight(2000);
    await waitRealTime(100);

    expect(main.scrollTop).not.toBe(600);
  });

  it("주소의 쿼리만 바뀌면(같은 화면에서 패널을 여닫음) 본문 위치를 그대로 둔다", async () => {
    const { router, main } = await renderScrolledHome();

    await moveTo({ router, path: `${HOME_PATH}?messageId=1` });

    expect(main.scrollTop).toBe(600);
  });
  it.each([
    { how: "쌓아", options: undefined },
    { how: "바꿔치기해", options: { replace: true } },
  ])(
    "쿼리만 $how 바꾼 화면도 다른 화면에 다녀와 뒤로 가기로 돌아오면 보던 위치로 돌린다",
    async ({ options }) => {
      const { router, main } = await renderScrolledHome();
      // 찾은 기록을 여닫을 때처럼 쿼리만 바꿔요. 라우터는 이때도 새 기록을 만들어요
      await moveTo({ router, path: `${HOME_PATH}?messageId=1`, options });
      await moveTo({ router, path: DOCUMENT_PATH });
      await screen.findByText(DOCUMENT_TEXT);

      await goBack(router);

      expect(await screen.findByText(HOME_TEXT)).toBeInTheDocument();
      expect(main.scrollTop).toBe(600);
    },
  );

  it.each([
    { mode: "보통 그릴 때", isStrictMode: false },
    // 실제 앱처럼 StrictMode로 그리면, 새로 그린 레이아웃의 효과가 개발 모드에서 한 번 더 돌아요
    { mode: "개발 모드(StrictMode)에서", isStrictMode: true },
  ])(
    "$mode 워크스페이스 밖 화면에 다녀와 뒤로 가기로 돌아오면, 레이아웃을 새로 그려도 보던 위치로 돌린다",
    async ({ isStrictMode }) => {
      configure({ reactStrictMode: isStrictMode });
      const { router } = await renderScrolledHome();
      await moveTo({ router, path: ELSEWHERE_PATH });
      await screen.findByText("다른 화면");

      await goBack(router);
      await screen.findByText(HOME_TEXT);
      // 레이아웃이 새로 그려져 본문도 새 요소예요. 새 본문의 내용 높이를 정해요
      const { main } = mockMainScroll(2000);

      await waitFor(() => expect(main.scrollTop).toBe(600));
    },
  );

  it("정해 둔 시간이 지난 뒤에 내용이 길어지면, 그때는 위치를 맞추지 않는다", async () => {
    const { router, main, setContentHeight } = await renderScrolledHome();
    await moveTo({ router, path: DOCUMENT_PATH });
    await screen.findByText(DOCUMENT_TEXT);
    setContentHeight(700);
    vi.useFakeTimers({ toFake: ["Date"] });

    await goBack(router);
    await screen.findByText(HOME_TEXT);
    // 다음 확인 전에 시간이 지나고 내용도 길어진 경우예요(탭을 옮겨 화면 그리기가 멈췄다 돌아온 때처럼)
    vi.setSystemTime(Date.now() + 10_000);
    setContentHeight(2000);
    await waitRealTime(100);

    expect(main.scrollTop).not.toBe(600);
  });
});

describe("WorkspaceLayout의 원문 패널", () => {
  /**
   * 원문을 여닫은 적이 없는 처음 상태에서 레이아웃을 그려요.
   * 열림 여부는 전역 저장소와 브라우저에 남아 테스트끼리 이어지므로, 그리기 전에 둘 다 되돌려요
   */
  const renderLayoutAsFirstVisit = (initialPath = DOCUMENT_PATH) => {
    localStorage.clear();
    useTranscriptPanelStore.setState({
      isOpenThisVisit: null,
      recordingDocumentChoice: null,
    });

    return renderLayout(initialPath);
  };

  /** 새로고침하고 다시 들어온 것처럼 화면과 이번 방문에서 정한 값을 지워요. 브라우저에 남긴 값은 그대로 둬요 */
  const startNewVisit = () => {
    cleanup();
    useTranscriptPanelStore.setState({
      isOpenThisVisit: null,
      recordingDocumentChoice: null,
    });
  };

  const respondWithRecordingDocument = (
    overrides: Partial<RecordingDocumentResponse>,
  ) =>
    mockServer.use(
      http.get(
        "*/api/v1/workspaces/:workspaceId/recordings/:recordingId/document",
        () => HttpResponse.json({ ...recordingDocumentResponse, ...overrides }),
      ),
    );

  const getTranscriptPanel = () =>
    screen.getByRole("region", { name: TRANSCRIPT_PANEL_TITLE });

  const queryTranscriptPanel = () =>
    screen.queryByRole("region", { name: TRANSCRIPT_PANEL_TITLE });

  const findToggle = (name: string) =>
    within(screen.getByRole("banner")).findByRole("button", { name });

  /**
   * 회원 정보를 받을 때까지 기다려요. GNB의 프로필에 회원의 이미지가 뜨면 받은 거예요.
   * 회원 정보를 받기 전과 닫아 둔 회원은 화면이 같아, 원문 버튼만 봐서는 받았는지 알 수 없어요
   */
  const waitForMember = () =>
    waitFor(() => {
      expect(
        screen.getByRole("img", { name: "내 프로필" }).querySelector("img"),
      ).toBeInTheDocument();
    });

  it("문서를 보는 화면에서는 GNB 오른쪽에 원문 버튼을 두고, 원문을 여닫은 적이 없는 회원에게는 오른쪽 레일에 원문 패널을 열어 둔다 (DOC-R3)", async () => {
    renderLayoutAsFirstVisit();

    expect(await screen.findByText(DOCUMENT_TEXT)).toBeInTheDocument();
    expect(await findToggle(CLOSE_LABEL)).toHaveAttribute(
      "aria-expanded",
      "true",
    );
    const panel = getTranscriptPanel();
    const main = screen.getByRole("main");
    const body = getBody();

    // 본문 바깥의 형제 칸에 놓여야 원문을 열었을 때 문서 폭이 줄어요
    expect(main).not.toContainElement(panel);
    expect(body?.children).toHaveLength(3);
    expect(body?.lastElementChild).toContainElement(panel);
  });

  // 레일은 여닫힘을 폭으로 옮겨 가도록 문서 화면에서 늘 두므로, 닫힘은 레일이 비었는지로 봐요(폭은 jsdom이 계산하지 못해요)
  it("원문 버튼을 누르면 원문 패널을 닫아 오른쪽 레일을 비우고, 다시 누르면 연다", async () => {
    renderLayoutAsFirstVisit();
    expect(await screen.findByText(DOCUMENT_TEXT)).toBeInTheDocument();

    fireEvent.click(await findToggle(CLOSE_LABEL));

    expect(queryTranscriptPanel()).not.toBeInTheDocument();
    expect(getBody()?.lastElementChild).toBeEmptyDOMElement();

    fireEvent.click(await findToggle(OPEN_LABEL));

    expect(getBody()?.lastElementChild).toContainElement(getTranscriptPanel());
  });

  it("원문을 닫아 둔 회원이 다른 문서로 다시 들어오면 원문 패널을 닫아 둔다 (DOC-R3)", async () => {
    renderLayoutAsFirstVisit();
    expect(await screen.findByText(DOCUMENT_TEXT)).toBeInTheDocument();
    fireEvent.click(await findToggle(CLOSE_LABEL));

    startNewVisit();
    renderLayout(OTHER_DOCUMENT_PATH);
    expect(await screen.findByText(DOCUMENT_TEXT)).toBeInTheDocument();
    await waitForMember();

    expect(await findToggle(OPEN_LABEL)).toHaveAttribute(
      "aria-expanded",
      "false",
    );
    expect(queryTranscriptPanel()).not.toBeInTheDocument();
  });

  it("워크스페이스를 조회하는 동안에는 원문 패널을 두지 않는다", async () => {
    holdWorkspaceResponse();
    renderLayoutAsFirstVisit();

    // 회원 정보를 받아 열림으로 정해진 뒤에도, 들어갈 수 있는 워크스페이스인지 알기 전에는 원문을 불러오지 않아요
    expect(await findToggle(CLOSE_LABEL)).toBeInTheDocument();
    expect(queryTranscriptPanel()).not.toBeInTheDocument();
  });

  it("홈 화면에서는 원문 버튼과 원문 패널 자리를 두지 않는다", async () => {
    renderLayoutAsFirstVisit(HOME_PATH);

    expect(await screen.findByText(HOME_TEXT)).toBeInTheDocument();
    await waitForMember();

    expect(
      screen.queryByRole("button", { name: /^원문/ }),
    ).not.toBeInTheDocument();
    expect(queryTranscriptPanel()).not.toBeInTheDocument();
    expect(getBody()?.children).toHaveLength(2);
  });

  it("탐색 화면에서는 원문 버튼을 두지 않는다", async () => {
    renderLayoutAsFirstVisit(CONVERSATION_PATH);

    expect(await screen.findByText(CHAT_TEXT)).toBeInTheDocument();
    await waitForMember();

    expect(
      screen.queryByRole("button", { name: /^원문/ }),
    ).not.toBeInTheDocument();
    expect(queryTranscriptPanel()).not.toBeInTheDocument();
  });

  it("녹음 문서에서는 문서 보기에서 원문을 닫아 둔 회원에게도 원문 패널을 열어 두고 녹음의 전사를 보여 준다 (STT-R39)", async () => {
    renderLayoutAsFirstVisit();
    expect(await screen.findByText(DOCUMENT_TEXT)).toBeInTheDocument();
    fireEvent.click(await findToggle(CLOSE_LABEL));

    startNewVisit();
    renderLayout(RECORDING_DOCUMENT_PATH);
    expect(
      await screen.findByText(RECORDING_DOCUMENT_TEXT),
    ).toBeInTheDocument();

    expect(await findToggle(CLOSE_LABEL)).toHaveAttribute(
      "aria-expanded",
      "true",
    );
    const panel = await screen.findByRole("region", {
      name: TRANSCRIPT_PANEL_TITLE,
    });
    expect(
      within(panel).getByText("이 녹음의 전사 전체예요."),
    ).toBeInTheDocument();
  });

  it("녹음 문서가 문서 생성 실패여도 전사가 있으면 원문 버튼과 패널을 둔다 (STT-R39)", async () => {
    respondWithRecordingDocument({
      processingStatus: "DOCUMENT_FAILED",
      retryable: true,
      retryableJobIds: [88],
    });
    renderLayoutAsFirstVisit(RECORDING_DOCUMENT_PATH);

    expect(await findToggle(CLOSE_LABEL)).toBeInTheDocument();
    expect(
      await screen.findByRole("region", { name: TRANSCRIPT_PANEL_TITLE }),
    ).toBeInTheDocument();
  });

  it.each([
    { label: "문서 정리 중", processingStatus: "PROCESSING" },
    { label: "전사 실패", processingStatus: "TRANSCRIPTION_FAILED" },
    { label: "정리할 내용 없음", processingStatus: "NO_CONTENT" },
  ] as const)(
    "녹음 문서가 $label이면 원문 버튼과 패널을 두지 않는다 (STT-R39)",
    async ({ processingStatus }) => {
      respondWithRecordingDocument({ processingStatus, meetingSummary: null });
      renderLayoutAsFirstVisit(RECORDING_DOCUMENT_PATH);

      expect(
        await screen.findByText(RECORDING_DOCUMENT_TEXT),
      ).toBeInTheDocument();
      await waitForMember();

      expect(
        screen.queryByRole("button", { name: /^원문/ }),
      ).not.toBeInTheDocument();
      expect(queryTranscriptPanel()).not.toBeInTheDocument();
    },
  );

  it("녹음 문서가 주제 분류 실패면(전사 없음 · 요약 없음) 원문 버튼과 패널을 두지 않는다 (STT-R39)", async () => {
    let transcriptRequestCount = 0;
    respondWithRecordingDocument({
      processingStatus: "DOCUMENT_FAILED",
      retryable: true,
      retryableJobIds: [88],
      meetingSummary: null,
    });
    mockServer.use(
      http.get(
        "*/api/v1/workspaces/:workspaceId/recordings/:recordingId/transcript",
        () => {
          transcriptRequestCount += 1;
          return HttpResponse.json(
            {
              code: "RECORDING_TRANSCRIPT_NOT_FOUND",
              message: "녹음 전사를 찾을 수 없습니다.",
            },
            { status: 404 },
          );
        },
      ),
    );
    renderLayoutAsFirstVisit(RECORDING_DOCUMENT_PATH);

    expect(
      await screen.findByText(RECORDING_DOCUMENT_TEXT),
    ).toBeInTheDocument();
    await waitFor(() => expect(transcriptRequestCount).toBe(1));
    await waitForMember();

    expect(
      screen.queryByRole("button", { name: /^원문/ }),
    ).not.toBeInTheDocument();
    expect(queryTranscriptPanel()).not.toBeInTheDocument();
  });
});

describe("WorkspaceLayout의 탐색 질문 흐름", () => {
  const MESSAGES_PATH =
    "*/api/v1/workspaces/:workspaceId/search/conversations/:conversationId/messages";
  const SEARCH_CONVERSATIONS_PATH =
    "*/api/v1/workspaces/:workspaceId/search/conversations";
  const SEARCH_QUESTIONS_PATH =
    "*/api/v1/workspaces/:workspaceId/search/conversations/:conversationId/questions";
  const SEARCH_ANSWER_RETRY_PATH =
    "*/api/v1/workspaces/:workspaceId/search/conversations/:conversationId/questions/:questionId/retry";
  const FAILED_TEXT = "답변을 만들지 못했어요. 잠시 후 다시 시도해 주세요.";
  const QUESTION = "DB는 뭘로 정했어?";
  const FOLLOW_UP_QUESTION = "초기 스키마는 누가 정리했어?";

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

  /** 이번 테스트에서만 메시지 조회를 넘긴 메시지로 답하고, 조회 횟수를 세요 */
  const respondMessages = (items: object[]) => {
    const requests = { count: 0 };
    mockServer.use(
      http.get(MESSAGES_PATH, ({ params }) => {
        requests.count += 1;

        return HttpResponse.json({
          ...searchMessagesResponse,
          conversationId: Number(params.conversationId),
          items,
        });
      }),
    );

    return requests;
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

  const getConversation = () => screen.getByRole("region", { name: "대화" });

  /**
   * 대화 칸에서 글자를 기다려요. 주소가 바뀌면 대화 칸이 새로 그려지므로, 기다리는 동안 대화 칸을 매번 다시 찾아요.
   * 한 번 찾은 대화 칸 안에서만 기다리면 주소 이동 직후 사라질 이전 칸을 붙잡고 끝까지 찾지 못해요.
   */
  const findInConversation = (text: string) =>
    waitFor(() => within(getConversation()).getByText(text));

  afterEach(() => {
    // 전역 저장소라 테스트끼리 답변이 새지 않게 비워요
    useSearchAnswerStore.getState().abort();
  });

  it("새 대화 화면에서 독으로 보낸 질문과 흘러드는 답변을 보여주고, 접수된 대화 주소로 옮겨 간다 (SEARCH-R1)", async () => {
    const {
      conversationId,
      questionMessageId,
      answerMessageId,
      deltas,
      evidences,
    } = searchAnswerStreamResponse;
    const answerText = deltas.join("");
    const messagesRequests = respondMessages([
      {
        ...searchMessagesResponse.items[0],
        id: questionMessageId,
        content: QUESTION,
      },
      {
        ...searchMessagesResponse.items[1],
        id: answerMessageId,
        content: answerText,
        evidences,
      },
    ]);
    const stream = respondWithControlledSse(SEARCH_CONVERSATIONS_PATH);
    const { router, queryClient } = renderLayout(
      CHAT_PATH,
      <SearchConversation />,
    );

    await sendFromDock(QUESTION);
    await stream.waitForOpen();

    expect(within(getConversation()).getByText(QUESTION)).toBeInTheDocument();

    stream.send("accepted", {
      conversationId,
      questionMessageId,
      answerMessageId,
    });
    await waitForPath(
      router,
      getRouterPath({
        routeKey: "CHAT_SESSION",
        params: {
          workspaceId: String(WORKSPACE_ID),
          sessionId: String(conversationId),
        },
      }),
    );

    stream.send("delta", { answerMessageId, text: deltas[0] });
    expect(await findInConversation(deltas[0].trim())).toBeInTheDocument();

    stream.send("delta", { answerMessageId, text: deltas[1] });
    expect(
      await findInConversation(`${deltas[0]}${deltas[1]}`.trim()),
    ).toBeInTheDocument();

    deltas
      .slice(2)
      .forEach((text) => stream.send("delta", { answerMessageId, text }));
    stream.send("evidence", { answerMessageId, items: evidences });
    stream.send("completed", { answerMessageId, status: "COMPLETED" });

    expect(
      await screen.findByRole("button", {
        name: `기록 ${evidences.length}개에서 찾았어요`,
      }),
    ).toBeInTheDocument();
    // 조회는 대화 주소로 옮겨 갈 때 한 번, 답변이 끝나 무효화한 뒤 한 번 해요
    await waitFor(() => expect(messagesRequests.count).toBe(2));
    await waitFor(() => expect(queryClient.isFetching()).toBe(0));

    expect(within(getConversation()).getAllByText(answerText)).toHaveLength(1);
    expect(within(getConversation()).getAllByText(QUESTION)).toHaveLength(1);
    expect(within(getConversation()).getAllByRole("article")).toHaveLength(1);
  });

  it("새 대화에서 보낸 첫 질문이 접수되면 답변이 끝나기 전에 대화 목록에 새 대화가 나타난다 (SEARCH-R1, COMMON-01)", async () => {
    const { conversationId, questionMessageId, answerMessageId } =
      searchAnswerStreamResponse;
    // 서버는 첫 질문을 접수한 뒤에야 대화를 만들어 목록에 넣어요
    const list = { isAccepted: false };
    mockServer.use(
      http.get(SEARCH_CONVERSATIONS_PATH, () =>
        HttpResponse.json({
          items: list.isAccepted
            ? [
                {
                  ...searchConversationsResponse.items[0],
                  id: conversationId,
                  title: QUESTION,
                },
              ]
            : [],
          nextCursor: null,
        }),
      ),
    );
    const stream = respondWithControlledSse(SEARCH_CONVERSATIONS_PATH);
    renderLayout(CHAT_PATH, <SearchConversation />);
    fireEvent.click(await screen.findByRole("button", { name: "대화 목록" }));
    const getChatList = () =>
      screen.getByRole("complementary", { name: "대화 목록" });
    expect(
      await within(getChatList()).findByText("아직 나눈 대화가 없어요"),
    ).toBeInTheDocument();

    await sendFromDock(QUESTION);
    await stream.waitForOpen();
    list.isAccepted = true;
    stream.send("accepted", {
      conversationId,
      questionMessageId,
      answerMessageId,
    });

    expect(
      await waitFor(() => within(getChatList()).getByText(QUESTION)),
    ).toBeInTheDocument();
  });

  it("후속 질문의 답변이 실패하면 그 자리에 실패 안내를 두고, 다시 시도가 접수 전에 실패해도 실패 안내를 그대로 둔다 (SEARCH-R5)", async () => {
    const { questionMessageId, answerMessageId } =
      searchQuestionAnswerStreamResponse;
    const partialAnswer = "끊기기 전까지 받은 답";
    const savedItems = searchMessagesResponse.items;
    // 서버는 접수한 질문과 실패한 답변을 저장해요. 실패한 뒤 다시 조회하면 그 턴이 들어 있어요
    const server = { hasFailed: false };
    mockServer.use(
      http.get(MESSAGES_PATH, ({ params }) =>
        HttpResponse.json({
          ...searchMessagesResponse,
          conversationId: Number(params.conversationId),
          items: server.hasFailed
            ? [
                ...savedItems,
                {
                  ...savedItems[0],
                  id: questionMessageId,
                  content: FOLLOW_UP_QUESTION,
                },
                {
                  ...savedItems[1],
                  id: answerMessageId,
                  content: partialAnswer,
                  status: "FAILED",
                  evidences: [],
                },
              ]
            : savedItems,
        }),
      ),
    );
    const stream = respondWithControlledSse(SEARCH_QUESTIONS_PATH);
    const { queryClient } = renderLayout(
      CONVERSATION_PATH,
      <SearchConversation />,
    );
    await findInConversation(savedItems[0].content);

    await sendFromDock(FOLLOW_UP_QUESTION);
    await stream.waitForOpen();
    stream.send("accepted", {
      conversationId: Number(CONVERSATION_ID),
      questionMessageId,
      answerMessageId,
    });
    stream.send("delta", { answerMessageId, text: partialAnswer });
    await findInConversation(partialAnswer);
    server.hasFailed = true;
    stream.send("failed", {
      answerMessageId,
      status: "FAILED",
      code: "LLM_STREAM_FAILED",
    });

    const getLastTurn = () => {
      const turns = within(getConversation()).getAllByRole("article");
      return turns[turns.length - 1];
    };
    await waitFor(() =>
      expect(within(getLastTurn()).getByText(FAILED_TEXT)).toBeInTheDocument(),
    );
    expect(
      within(getConversation()).queryByText(partialAnswer),
    ).not.toBeInTheDocument();

    mockServer.use(
      http.post(SEARCH_ANSWER_RETRY_PATH, () =>
        HttpResponse.json(
          {
            code: "ANSWER_ALREADY_IN_PROGRESS",
            message: "이미 답변을 만드는 중이에요",
          },
          { status: 409 },
        ),
      ),
    );
    await act(async () => {
      fireEvent.click(
        within(getLastTurn()).getByRole("button", { name: "다시 시도" }),
      );
    });
    await waitFor(() => expect(queryClient.isFetching()).toBe(0));

    const savedQuestionCount = savedItems.filter(
      ({ role }) => role === "USER",
    ).length;
    expect(within(getConversation()).getAllByRole("article")).toHaveLength(
      savedQuestionCount + 1,
    );
    expect(
      within(getLastTurn()).getByText(FOLLOW_UP_QUESTION),
    ).toBeInTheDocument();
    expect(within(getLastTurn()).getByText(FAILED_TEXT)).toBeInTheDocument();
    expect(
      within(getLastTurn()).getByRole("button", { name: "다시 시도" }),
    ).toBeInTheDocument();
  });

  it("첫 질문이 실패해 목록에 없던 대화는 다시 시도가 접수되면 답변이 끝나기 전에 대화 목록에 나타난다 (SEARCH-R5, COMMON-01)", async () => {
    const [question, answer] = searchMessagesResponse.items;
    respondMessages([
      question,
      { ...answer, content: "", status: "FAILED", evidences: [] },
    ]);
    // 서버는 첫 질문이 실패한 대화를 목록에서 숨기고, 다시 시도를 접수하면 목록에 다시 넣어요
    const list = { isAccepted: false };
    mockServer.use(
      http.get(SEARCH_CONVERSATIONS_PATH, () =>
        HttpResponse.json({
          items: list.isAccepted
            ? [
                {
                  ...searchConversationsResponse.items[0],
                  id: Number(CONVERSATION_ID),
                  title: question.content,
                },
              ]
            : [],
          nextCursor: null,
        }),
      ),
    );
    const stream = respondWithControlledSse(SEARCH_ANSWER_RETRY_PATH);
    renderLayout(CONVERSATION_PATH, <SearchConversation />);
    fireEvent.click(await screen.findByRole("button", { name: "대화 목록" }));
    const getChatList = () =>
      screen.getByRole("complementary", { name: "대화 목록" });
    expect(
      await within(getChatList()).findByText("아직 나눈 대화가 없어요"),
    ).toBeInTheDocument();

    await act(async () => {
      fireEvent.click(await screen.findByRole("button", { name: "다시 시도" }));
    });
    await stream.waitForOpen();
    list.isAccepted = true;
    stream.send("accepted", {
      conversationId: Number(CONVERSATION_ID),
      questionMessageId: question.id,
      answerMessageId: answer.id,
    });

    expect(
      await waitFor(() => within(getChatList()).getByText(question.content)),
    ).toBeInTheDocument();
  });

  describe("찾은 기록 자동 열림 (SEARCH-R10)", () => {
    const { questionMessageId, answerMessageId, deltas, evidences } =
      searchQuestionAnswerStreamResponse;
    const answerText = deltas.join("");
    const savedItems = searchMessagesResponse.items;

    const getEvidencePanel = () =>
      screen.queryByRole("region", { name: "찾은 기록" });

    /** 기존 대화에서 후속 질문을 보내 접수까지 받아요. 끝 이벤트는 테스트마다 보내요 */
    const sendFollowUpUntilAccepted = async (answerEvidences: object[]) => {
      respondMessages([
        ...savedItems,
        {
          ...savedItems[0],
          id: questionMessageId,
          content: FOLLOW_UP_QUESTION,
        },
        {
          ...savedItems[1],
          id: answerMessageId,
          content: answerText,
          evidences: answerEvidences,
        },
      ]);
      const stream = respondWithControlledSse(SEARCH_QUESTIONS_PATH);
      const rendered = renderLayout(CONVERSATION_PATH, <SearchConversation />);
      await findInConversation(savedItems[0].content);

      await sendFromDock(FOLLOW_UP_QUESTION);
      await stream.waitForOpen();
      stream.send("accepted", {
        conversationId: Number(CONVERSATION_ID),
        questionMessageId,
        answerMessageId,
      });
      deltas.forEach((text) => stream.send("delta", { answerMessageId, text }));

      return { ...rendered, stream };
    };

    it("근거가 있는 답변이 끝나면 그 답변의 찾은 기록 패널을 저절로 연다", async () => {
      const { router, stream } = await sendFollowUpUntilAccepted(evidences);
      expect(getEvidencePanel()).not.toBeInTheDocument();

      stream.send("evidence", { answerMessageId, items: evidences });
      stream.send("completed", { answerMessageId, status: "COMPLETED" });

      const panel = await waitFor(() => {
        const found = getEvidencePanel();
        expect(found).toBeInTheDocument();
        return found;
      });
      expect(router.state.location.search).toBe(
        `?messageId=${answerMessageId}`,
      );
      expect(getBody()?.lastElementChild).toContainElement(panel);
      expect(within(panel!).getByText(evidences[0].title)).toBeInTheDocument();
    });

    it("다시 받은 대화에 끝난 답변이 아직 없어도 받은 근거로 찾은 기록 패널을 연다", async () => {
      const { stream } = await sendFollowUpUntilAccepted(evidences);
      respondMessages(savedItems);

      stream.send("evidence", { answerMessageId, items: evidences });
      stream.send("completed", { answerMessageId, status: "COMPLETED" });

      const panel = await waitFor(() => {
        const found = getEvidencePanel();
        expect(found).toBeInTheDocument();
        return found;
      });
      expect(within(panel!).getByText(evidences[0].title)).toBeInTheDocument();
      expect(
        within(screen.getByRole("banner")).getByRole("button", {
          name: "찾은 기록 닫기",
        }),
      ).toBeInTheDocument();
    });

    it.each([
      {
        name: "근거 없이 끝나면",
        answerEvidences: [],
        endFrames: [["completed", { answerMessageId, status: "COMPLETED" }]],
      },
      {
        name: "근거를 받은 뒤 중지되면",
        answerEvidences: evidences,
        endFrames: [
          ["evidence", { answerMessageId, items: evidences }],
          ["stopped", { answerMessageId, status: "STOPPED" }],
        ],
      },
      {
        name: "근거를 받은 뒤 실패하면",
        answerEvidences: evidences,
        endFrames: [
          ["evidence", { answerMessageId, items: evidences }],
          [
            "failed",
            { answerMessageId, status: "FAILED", code: "LLM_STREAM_FAILED" },
          ],
        ],
      },
    ] as const)(
      "답변이 $name 찾은 기록 패널을 열지 않는다",
      async ({ answerEvidences, endFrames }) => {
        const { router, queryClient, stream } = await sendFollowUpUntilAccepted(
          [...answerEvidences],
        );

        endFrames.forEach(([event, data]) => stream.send(event, data));
        await waitFor(() =>
          expect(useSearchAnswerStore.getState().controller).toBeNull(),
        );
        await waitFor(() => expect(queryClient.isFetching()).toBe(0));

        expect(router.state.location.search).toBe("");
        expect(getEvidencePanel()).not.toBeInTheDocument();
      },
    );

    it("저절로 열린 찾은 기록을 닫으면 대화를 다시 받아도 다시 열지 않는다", async () => {
      const { router, queryClient, stream } =
        await sendFollowUpUntilAccepted(evidences);
      stream.send("evidence", { answerMessageId, items: evidences });
      stream.send("completed", { answerMessageId, status: "COMPLETED" });
      await waitFor(() => expect(getEvidencePanel()).toBeInTheDocument());

      await act(async () => {
        fireEvent.click(
          within(screen.getByRole("banner")).getByRole("button", {
            name: "찾은 기록 닫기",
          }),
        );
      });
      await act(async () => {
        await queryClient.invalidateQueries();
      });

      expect(router.state.location.search).toBe("");
      expect(getEvidencePanel()).not.toBeInTheDocument();
    });

    it("답변이 끝나기 전에 홈으로 나가면 홈에서 찾은 기록을 열지 않는다", async () => {
      const { router, stream } = await sendFollowUpUntilAccepted(evidences);

      await act(async () => {
        await router.navigate(HOME_PATH);
      });
      stream.send("evidence", { answerMessageId, items: evidences });
      stream.send("completed", { answerMessageId, status: "COMPLETED" });
      await waitFor(() =>
        expect(useSearchAnswerStore.getState().status).toBe("COMPLETED"),
      );

      expect(router.state.location.pathname).toBe(HOME_PATH);
      expect(router.state.location.search).toBe("");
    });
  });

  it("기존 대화에서 독으로 보낸 후속 질문과 답변은 저장된 턴 뒤에 이어진다 (SEARCH-R1)", async () => {
    const { questionMessageId, answerMessageId, deltas } =
      searchQuestionAnswerStreamResponse;
    const savedQuestions = searchMessagesResponse.items.filter(
      ({ role }) => role === "USER",
    );
    const stream = respondWithControlledSse(SEARCH_QUESTIONS_PATH);
    const { router } = renderLayout(CONVERSATION_PATH, <SearchConversation />);
    expect(
      await within(
        await screen.findByRole("region", { name: "대화" }),
      ).findByText(savedQuestions[0].content),
    ).toBeInTheDocument();

    await sendFromDock(FOLLOW_UP_QUESTION);
    await stream.waitForOpen();
    stream.send("accepted", {
      conversationId: Number(CONVERSATION_ID),
      questionMessageId,
      answerMessageId,
    });
    deltas.forEach((text) => stream.send("delta", { answerMessageId, text }));
    stream.send("completed", { answerMessageId, status: "COMPLETED" });

    const answerText = deltas.join("");
    expect(
      await within(getConversation()).findByText(answerText),
    ).toBeInTheDocument();

    const turns = within(getConversation()).getAllByRole("article");
    const lastTurn = turns[turns.length - 1];

    expect(turns).toHaveLength(savedQuestions.length + 1);
    expect(within(lastTurn).getByText(FOLLOW_UP_QUESTION)).toBeInTheDocument();
    expect(within(lastTurn).getByText(answerText)).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(CONVERSATION_PATH);
  });

  describe("끊긴 답변 이어 보기 (SEARCH-R8)", () => {
    const SEARCH_ANSWER_EVENTS_PATH =
      "*/api/v1/workspaces/:workspaceId/search/conversations/:conversationId/answers/:answerId/events";
    const { questionMessageId, answerMessageId } =
      searchQuestionAnswerStreamResponse;
    const savedItems = searchMessagesResponse.items;
    const savedText = "초기 스키마는 ";
    const restText = "백엔드 팀이 정리했어요.";
    const fullText = savedText + restText;

    /** 저장된 턴 뒤에, 서버가 아직 만들고 있는 후속 질문의 답변이 저장된 데까지 있어요 */
    const streamingItems = [
      ...savedItems,
      { ...savedItems[0], id: questionMessageId, content: FOLLOW_UP_QUESTION },
      {
        ...savedItems[1],
        id: answerMessageId,
        content: savedText,
        status: "STREAMING",
        evidences: [],
      },
    ];

    const snapshotFrame = {
      event: "snapshot",
      data: {
        answerId: answerMessageId,
        content: savedText,
        status: "STREAMING",
      },
    };
    const restFrames = [
      { event: "delta", data: { answerId: answerMessageId, text: restText } },
      {
        event: "completed",
        data: { answerId: answerMessageId, status: "COMPLETED" },
      },
    ];

    /** 이번 테스트에서만 재연결 요청에 넘긴 프레임을 한 번에 보내고 닫아요. 요청 횟수를 세요 */
    const respondAnswerEvents = (frames: { event: string; data: object }[]) => {
      const requests = { count: 0 };
      mockServer.use(
        http.get(SEARCH_ANSWER_EVENTS_PATH, () => {
          requests.count += 1;

          return new HttpResponse(
            frames.map(({ event, data }) => toFrame(event, data)).join(""),
            { headers: { "Content-Type": "text/event-stream" } },
          );
        }),
      );

      return requests;
    };

    const getLastTurn = () => {
      const turns = within(getConversation()).getAllByRole("article");
      return turns[turns.length - 1];
    };

    it("후속 질문의 답변이 끝 이벤트 없이 끊기면 실패 안내 대신 다시 연결해 받은 답변을 끝까지 보여준다", async () => {
      const stream = respondWithControlledSse(SEARCH_QUESTIONS_PATH);
      const reconnects = respondAnswerEvents([snapshotFrame, ...restFrames]);
      renderLayout(CONVERSATION_PATH, <SearchConversation />);
      await findInConversation(savedItems[0].content);

      await sendFromDock(FOLLOW_UP_QUESTION);
      await stream.waitForOpen();
      stream.send("accepted", {
        conversationId: Number(CONVERSATION_ID),
        questionMessageId,
        answerMessageId,
      });
      stream.send("delta", { answerMessageId, text: "초기" });
      await findInConversation("초기");
      stream.close();

      await findInConversation(fullText);
      expect(reconnects.count).toBe(1);
      expect(
        within(getConversation()).queryByText(FAILED_TEXT),
      ).not.toBeInTheDocument();
    });

    it("답변을 만들고 있는 대화를 열면 저장된 데부터 이어 받아 끝까지 보여주고, 끝나면 메시지를 다시 조회한다", async () => {
      respondMessages(streamingItems);
      respondAnswerEvents([snapshotFrame, ...restFrames]);
      // 레이아웃도 같은 메시지를 따로 조회해서 횟수 대신 순서로 봐요
      const requestOrder: string[] = [];
      mockServer.events.on("request:start", ({ request }) => {
        const { pathname } = new URL(request.url);
        if (pathname.endsWith("/events")) requestOrder.push("events");
        if (pathname.endsWith("/messages")) requestOrder.push("messages");
      });
      renderLayout(CONVERSATION_PATH, <SearchConversation />);

      await findInConversation(fullText);
      expect(
        within(getLastTurn()).getByText(FOLLOW_UP_QUESTION),
      ).toBeInTheDocument();
      expect(within(getConversation()).getAllByRole("article")).toHaveLength(
        savedItems.filter(({ role }) => role === "USER").length + 1,
      );
      await waitFor(() =>
        expect(requestOrder.lastIndexOf("messages")).toBeGreaterThan(
          requestOrder.indexOf("events"),
        ),
      );
      mockServer.events.removeAllListeners();
    });

    it("이어 받는 동안에는 새 질문을 보낼 수 없다 (SEARCH-R6)", async () => {
      respondMessages(streamingItems);
      // 재연결 스트림을 열어 두기만 해서 답변이 끝나지 않아요
      mockServer.use(
        http.get(
          SEARCH_ANSWER_EVENTS_PATH,
          () =>
            new HttpResponse(new ReadableStream(), {
              headers: { "Content-Type": "text/event-stream" },
            }),
        ),
      );
      renderLayout(CONVERSATION_PATH, <SearchConversation />);
      await findInConversation(savedText.trim());

      fireEvent.change(
        await screen.findByRole("textbox", { name: "무엇이든 요청하세요" }),
        { target: { value: QUESTION } },
      );

      await waitFor(() =>
        expect(screen.getByRole("button", { name: "보내기" })).toBeDisabled(),
      );
    });

    it("다시 연결한 답변이 실패로 끝나면 그 자리에 실패 안내와 다시 시도를 보여준다 (SEARCH-R5)", async () => {
      respondMessages(streamingItems);
      respondAnswerEvents([
        snapshotFrame,
        {
          event: "failed",
          data: {
            answerId: answerMessageId,
            status: "FAILED",
            code: "LLM_STREAM_FAILED",
          },
        },
      ]);
      renderLayout(CONVERSATION_PATH, <SearchConversation />);

      await waitFor(() =>
        expect(
          within(getLastTurn()).getByText(FAILED_TEXT),
        ).toBeInTheDocument(),
      );
      expect(
        within(getLastTurn()).getByRole("button", { name: "다시 시도" }),
      ).toBeInTheDocument();
    });

    it("다시 연결해도 끝을 받지 못하면 세 번까지만 연결하고 저장된 답변으로 둔다", async () => {
      respondMessages(streamingItems);
      const reconnects = respondAnswerEvents([snapshotFrame]);
      const { queryClient } = renderLayout(
        CONVERSATION_PATH,
        <SearchConversation />,
      );

      await waitFor(() => expect(reconnects.count).toBe(3), { timeout: 4000 });
      await waitFor(() =>
        expect(useSearchAnswerStore.getState().status).toBe("IDLE"),
      );
      await waitFor(() => expect(queryClient.isFetching()).toBe(0));

      expect(reconnects.count).toBe(3);
      expect(
        within(getLastTurn()).getByText(savedText.trim()),
      ).toBeInTheDocument();
      expect(
        within(getConversation()).queryByText(FAILED_TEXT),
      ).not.toBeInTheDocument();
    });
  });

  describe("GNB 탐색으로 돌아오기", () => {
    const OTHER_WORKSPACE_ID = "2";
    const savedQuestion = searchMessagesResponse.items[0].content;
    const emptyText = "무엇을 찾고 있나요?";

    const clickGnb = async (name: string) => {
      await act(async () => {
        fireEvent.click(
          within(screen.getByRole("banner")).getByRole("button", { name }),
        );
      });
    };

    /** 대화 목록을 열어 새 채팅을 눌러요 */
    const startNewChat = async () => {
      fireEvent.click(screen.getByRole("button", { name: "대화 목록" }));
      await act(async () => {
        fireEvent.click(await screen.findByRole("button", { name: "새 채팅" }));
      });
    };

    const getPath = (workspaceId: string, sessionId?: string) =>
      sessionId === undefined
        ? getRouterPath({ routeKey: "CHAT", params: { workspaceId } })
        : getRouterPath({
            routeKey: "CHAT_SESSION",
            params: { workspaceId, sessionId },
          });

    // 마지막으로 본 대화는 브라우저에 남아 테스트끼리 이어지므로 비워요
    afterEach(() => sessionStorage.clear());

    it("대화를 보다가 홈에 다녀오면 GNB 탐색이 그 대화로 돌아가 기록을 다시 보여준다", async () => {
      const { router } = renderLayout(
        CONVERSATION_PATH,
        <SearchConversation />,
      );
      await findInConversation(savedQuestion);

      await clickGnb("홈");
      expect(await screen.findByText(HOME_TEXT)).toBeInTheDocument();
      await clickGnb("탐색");

      expect(router.state.location.pathname).toBe(CONVERSATION_PATH);
      expect(await findInConversation(savedQuestion)).toBeInTheDocument();
    });

    it("새 채팅으로 빈 대화를 연 뒤 홈에 다녀오면 GNB 탐색이 빈 새 대화를 보여준다", async () => {
      const { router } = renderLayout(
        CONVERSATION_PATH,
        <SearchConversation />,
      );
      await findInConversation(savedQuestion);

      await startNewChat();
      await waitForPath(router, CHAT_PATH);
      await clickGnb("홈");
      expect(await screen.findByText(HOME_TEXT)).toBeInTheDocument();
      await clickGnb("탐색");

      expect(router.state.location.pathname).toBe(CHAT_PATH);
      expect(await findInConversation(emptyText)).toBeInTheDocument();
    });

    it("이 탭에서 본 대화가 없으면 GNB 탐색이 빈 새 대화를 연다", async () => {
      const { router } = renderLayout(HOME_PATH, <SearchConversation />);
      expect(await screen.findByText(HOME_TEXT)).toBeInTheDocument();

      await clickGnb("탐색");

      expect(router.state.location.pathname).toBe(CHAT_PATH);
      expect(await findInConversation(emptyText)).toBeInTheDocument();
    });

    it("워크스페이스마다 마지막으로 본 대화를 따로 기억한다", async () => {
      const { router } = renderLayout(
        CONVERSATION_PATH,
        <SearchConversation />,
      );
      await findInConversation(savedQuestion);

      await act(async () => {
        await router.navigate(
          getRouterPath({
            routeKey: "WORKSPACE_HOME",
            params: { workspaceId: OTHER_WORKSPACE_ID },
          }),
        );
      });
      expect(await screen.findByText(HOME_TEXT)).toBeInTheDocument();
      await clickGnb("탐색");

      expect(router.state.location.pathname).toBe(getPath(OTHER_WORKSPACE_ID));

      await act(async () => {
        await router.navigate(HOME_PATH);
      });
      expect(await screen.findByText(HOME_TEXT)).toBeInTheDocument();
      await clickGnb("탐색");

      expect(router.state.location.pathname).toBe(CONVERSATION_PATH);
    });

    it("새로고침해도 같은 탭에서는 GNB 탐색이 마지막으로 본 대화로 돌아간다", async () => {
      renderLayout(CONVERSATION_PATH, <SearchConversation />);
      await findInConversation(savedQuestion);

      // 새로고침한 것처럼 화면을 지우고 홈으로 다시 들어와요. 브라우저에 남긴 값은 그대로 둬요
      cleanup();
      const { router } = renderLayout(HOME_PATH, <SearchConversation />);
      expect(await screen.findByText(HOME_TEXT)).toBeInTheDocument();
      await clickGnb("탐색");

      expect(router.state.location.pathname).toBe(CONVERSATION_PATH);
      expect(await findInConversation(savedQuestion)).toBeInTheDocument();
    });

    it("마지막으로 본 대화가 사라져 불러오지 못하면 기억을 비우고 빈 새 대화를 연다", async () => {
      saveLastConversationId({
        workspaceId: String(WORKSPACE_ID),
        conversationId: Number(CONVERSATION_ID),
      });
      mockServer.use(
        http.get(MESSAGES_PATH, () =>
          HttpResponse.json(
            {
              code: "CONVERSATION_NOT_FOUND",
              message: "대화를 찾을 수 없습니다.",
            },
            { status: 404 },
          ),
        ),
      );
      const { router } = renderLayout(HOME_PATH, <SearchConversation />);
      expect(await screen.findByText(HOME_TEXT)).toBeInTheDocument();

      await clickGnb("탐색");

      await waitForPath(router, CHAT_PATH);
      expect(await findInConversation(emptyText)).toBeInTheDocument();
      // 뒤로 가기로 사라진 대화에 다시 갇히지 않게 그 기록을 바꿔 둬요
      expect(router.state.historyAction).toBe("REPLACE");

      await clickGnb("홈");
      expect(await screen.findByText(HOME_TEXT)).toBeInTheDocument();
      await clickGnb("탐색");

      expect(router.state.location.pathname).toBe(CHAT_PATH);
    });

    it("새 대화의 첫 질문이 접수되면 그 대화를 기억해, 홈에 다녀와도 받던 답변을 이어서 보여준다 (SEARCH-R1)", async () => {
      const { conversationId, questionMessageId, answerMessageId, deltas } =
        searchAnswerStreamResponse;
      const conversationPath = getPath(
        String(WORKSPACE_ID),
        String(conversationId),
      );
      respondMessages([]);
      const stream = respondWithControlledSse(SEARCH_CONVERSATIONS_PATH);
      const { router } = renderLayout(CHAT_PATH, <SearchConversation />);

      await sendFromDock(QUESTION);
      await stream.waitForOpen();
      stream.send("accepted", {
        conversationId,
        questionMessageId,
        answerMessageId,
      });
      await waitForPath(router, conversationPath);
      stream.send("delta", { answerMessageId, text: deltas[0] });
      await findInConversation(deltas[0].trim());

      await clickGnb("홈");
      expect(await screen.findByText(HOME_TEXT)).toBeInTheDocument();
      await clickGnb("탐색");

      expect(router.state.location.pathname).toBe(conversationPath);
      stream.send("delta", { answerMessageId, text: deltas[1] });
      expect(
        await findInConversation(`${deltas[0]}${deltas[1]}`.trim()),
      ).toBeInTheDocument();
    });
  });
});
