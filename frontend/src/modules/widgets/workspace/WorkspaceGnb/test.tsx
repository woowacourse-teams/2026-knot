import { GetMeResponseDto } from "@api/dto/auth";
import { GetDocumentResponseDto } from "@api/dto/document";
import {
  GetWorkspaceResponseDto,
  GetWorkspacesResponseDto,
} from "@api/dto/workspace";
import { meResponse } from "@api/mock/responses/auth";
import { GetRecordingDocumentResponseDto } from "@api/dto/recordingDocument";
import { AUTH_ME_API_PATH } from "@api/fetch/api/v1/auth/me";
import { documentDetailsResponse } from "@api/mock/responses/document";
import { recordingDocumentResponse } from "@api/mock/responses/recordingDocument";
import {
  workspaceDetailResponse,
  workspacesResponse,
} from "@api/mock/responses/workspace";
import { mockServer } from "@api/mock/server";
import DockablePanel from "@composites/DockablePanel";
import {
  DOCUMENT_FROM_PARAM,
  DOCUMENT_FROM_RECORDING,
} from "@constants/document";
import { ThemeProvider } from "@emotion/react";
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
import { saveLastConversationId } from "@utils/lastConversation";
import { delay, http, HttpResponse } from "msw";
import { createBrowserRouter, Outlet, RouterProvider } from "react-router";
import { afterEach, describe, expect, it } from "vitest";

import WorkspaceGnb from ".";

const WORKSPACE_ID = "1";
const HOME_PATH = getRouterPath({
  routeKey: "WORKSPACE_HOME",
  params: { workspaceId: WORKSPACE_ID },
});
const CHAT_PATH = getRouterPath({
  routeKey: "CHAT",
  params: { workspaceId: WORKSPACE_ID },
});
const LAST_CONVERSATION_ID = 10;
const LAST_CONVERSATION_PATH = getRouterPath({
  routeKey: "CHAT_SESSION",
  params: {
    workspaceId: WORKSPACE_ID,
    sessionId: String(LAST_CONVERSATION_ID),
  },
});
// 문서 탭은 늘 「녹음별」 탭으로 열려요(DOC-R9)
const DOCUMENTS_PATH = getRouterPath({
  routeKey: "DOCUMENTS_BY_RECORDING",
  params: { workspaceId: WORKSPACE_ID },
});
const expectedMe = new GetMeResponseDto(meResponse);
const expectedWorkspace = new GetWorkspaceResponseDto(workspaceDetailResponse);
const expectedDocument = new GetDocumentResponseDto(documentDetailsResponse[0]);
const [, otherWorkspace] = new GetWorkspacesResponseDto(workspacesResponse)
  .workspaces;

// 문서 하나를 보는 화면. 위치 표시가 제목을 보여 주도록 mock에 있는 문서를 열어요
const DOCUMENT_PATH = getRouterPath({
  routeKey: "DOCUMENT",
  params: {
    workspaceId: WORKSPACE_ID,
    documentId: String(expectedDocument.id),
  },
});
const expectedRecordingDocument = new GetRecordingDocumentResponseDto(
  recordingDocumentResponse,
);
// 녹음 문서 화면. 위치 표시가 녹음 제목을 보여 주도록 mock에 있는 녹음을 열어요
const RECORDING_DOCUMENT_PATH = getRouterPath({
  routeKey: "RECORDING_DOCUMENT",
  params: {
    workspaceId: WORKSPACE_ID,
    recordingId: String(expectedRecordingDocument.recordingId),
  },
});
const RECORDING_PATH = getRouterPath({
  routeKey: "RECORDING",
  params: { workspaceId: WORKSPACE_ID },
});
const RECORDING_DOCUMENTS_PATH = getRouterPath({
  routeKey: "RECORDING_DOCUMENTS",
  params: { workspaceId: WORKSPACE_ID, recordingId: "42" },
});
const OTHER_HOME_PATH = getRouterPath({
  routeKey: "WORKSPACE_HOME",
  params: { workspaceId: String(otherWorkspace.id) },
});
// 경로 파라미터 자리에 무엇이 와도 잡도록 fetch 상수 대신 패턴을 적어요
const WORKSPACE_REQUEST = "*/api/v1/workspaces/:workspaceId";
const DOCUMENT_REQUEST =
  "*/api/v1/workspaces/:workspaceId/documents/:documentId";
const DOCK_RAIL_ID = "test-dock-rail";
const PANEL_TEXT = "패널 내용";
const LOCATION_LABEL = "지금 위치";

const RIGHT_TEXT = "오른쪽 버튼";

const renderGnb = (initialPath = HOME_PATH) => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const element = (
    <>
      <div id={DOCK_RAIL_ID} />
      <WorkspaceGnb
        left={
          <DockablePanel
            label="사이드바"
            icon={<span />}
            dockTargetId={DOCK_RAIL_ID}
          >
            <p>{PANEL_TEXT}</p>
          </DockablePanel>
        }
        right={<button type="button">{RIGHT_TEXT}</button>}
      />
    </>
  );
  // 앱처럼 브라우저 주소를 쓰는 라우터로 그려요. 지금 화면인지는 누른 순간의 브라우저 주소로 판단하기 때문이에요
  window.history.replaceState(null, "", initialPath);
  // 실제 앱처럼 GNB는 레이아웃에 남고 화면만 바뀌어요
  const router = createBrowserRouter([
    {
      element: (
        <>
          {element}
          <Outlet />
        </>
      ),
      children: [
        PATH_ROUTE.WORKSPACE_HOME,
        PATH_ROUTE.CHAT,
        PATH_ROUTE.CHAT_SESSION,
        PATH_ROUTE.DOCUMENTS_BY_RECORDING,
        PATH_ROUTE.DOCUMENTS_BY_TOPIC,
        PATH_ROUTE.DOCUMENT,
        PATH_ROUTE.RECORDING,
        PATH_ROUTE.RECORDING_DOCUMENTS,
        PATH_ROUTE.RECORDING_DOCUMENT,
      ].map((path) => ({ path, element: null })),
    },
  ]);

  render(
    <ThemeProvider theme={theme}>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </ThemeProvider>,
  );

  return {
    router,
    homeButton: screen.getByRole("button", { name: "홈" }),
    exploreButton: screen.getByRole("button", { name: "탐색" }),
    documentsButton: screen.getByRole("button", { name: "문서" }),
    panelTrigger: screen.getByRole("button", { name: "사이드바" }),
  };
};

describe("WorkspaceGnb", () => {
  // 마지막으로 본 대화는 브라우저에 남아 테스트끼리 이어지므로 비워요
  afterEach(() => sessionStorage.clear());

  it("홈 화면에서는 내비 필의 홈만 현재 화면으로 표시한다", () => {
    const { homeButton, exploreButton, documentsButton } = renderGnb();

    expect(homeButton).toHaveAttribute("aria-current", "page");
    expect(exploreButton).not.toHaveAttribute("aria-current");
    expect(documentsButton).not.toHaveAttribute("aria-current");
  });

  it("이 워크스페이스에서 본 대화가 없으면 탐색을 눌렀을 때 새 대화로 이동하고 표시가 옮겨간다", async () => {
    const { router, exploreButton } = renderGnb();

    await act(async () => {
      fireEvent.click(exploreButton);
    });

    expect(router.state.location.pathname).toBe(CHAT_PATH);
    expect(screen.getByRole("button", { name: "탐색" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    expect(screen.getByRole("button", { name: "홈" })).not.toHaveAttribute(
      "aria-current",
    );
  });

  it("이 워크스페이스에서 마지막으로 본 대화가 있으면 탐색을 눌렀을 때 그 대화로 이동한다", async () => {
    saveLastConversationId({
      workspaceId: WORKSPACE_ID,
      conversationId: LAST_CONVERSATION_ID,
    });
    const { router, exploreButton } = renderGnb();

    await act(async () => {
      fireEvent.click(exploreButton);
    });

    expect(router.state.location.pathname).toBe(LAST_CONVERSATION_PATH);
    expect(screen.getByRole("button", { name: "탐색" })).toHaveAttribute(
      "aria-current",
      "page",
    );
  });

  it("다른 워크스페이스에서 본 대화로는 이동하지 않는다", async () => {
    saveLastConversationId({
      workspaceId: "2",
      conversationId: LAST_CONVERSATION_ID,
    });
    const { router, exploreButton } = renderGnb();

    await act(async () => {
      fireEvent.click(exploreButton);
    });

    expect(router.state.location.pathname).toBe(CHAT_PATH);
  });

  it("문서를 누르면 문서 목록 화면으로 이동하고 표시가 옮겨간다", async () => {
    const { router, documentsButton } = renderGnb();

    await act(async () => {
      fireEvent.click(documentsButton);
    });

    expect(router.state.location.pathname).toBe(DOCUMENTS_PATH);
    expect(screen.getByRole("button", { name: "문서" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    expect(screen.getByRole("button", { name: "홈" })).not.toHaveAttribute(
      "aria-current",
    );
  });

  it("문서 하나를 보는 화면에서도 문서를 현재 화면으로 표시하고, 문서를 누르면 문서 목록으로 간다", async () => {
    const { router, documentsButton } = renderGnb(DOCUMENT_PATH);

    expect(documentsButton).toHaveAttribute("aria-current", "page");

    await act(async () => {
      fireEvent.click(documentsButton);
    });

    expect(router.state.location.pathname).toBe(DOCUMENTS_PATH);
  });

  it("녹음 문서 화면에서도 문서를 현재 화면으로 표시하고, 문서를 누르면 문서 목록으로 간다 (STT-R40)", async () => {
    const { router, documentsButton } = renderGnb(RECORDING_DOCUMENT_PATH);

    expect(documentsButton).toHaveAttribute("aria-current", "page");

    await act(async () => {
      fireEvent.click(documentsButton);
    });

    expect(router.state.location.pathname).toBe(DOCUMENTS_PATH);
  });

  it("문서 목록 화면에서 문서를 눌러도 이동하지 않는다", async () => {
    const { router, documentsButton } = renderGnb(DOCUMENTS_PATH);

    await act(async () => {
      fireEvent.click(documentsButton);
    });

    expect(router.state.historyAction).toBe("POP");
    expect(router.state.location.pathname).toBe(DOCUMENTS_PATH);
  });

  it("문서 목록에서 문서를 열어 주소가 바뀌고 화면은 아직 목록일 때 문서를 누르면 문서 목록으로 간다", async () => {
    const { router, documentsButton } = renderGnb(DOCUMENTS_PATH);
    // 라우터는 주소를 먼저 바꾸고 화면은 transition으로 나중에 그려요. 그 사이를 주소만 바꿔 만들어요
    window.history.pushState(null, "", DOCUMENT_PATH);

    await act(async () => {
      fireEvent.click(documentsButton);
    });

    expect(window.location.pathname).toBe(DOCUMENTS_PATH);
    expect(router.state.historyAction).toBe("PUSH");
  });

  it("홈에서 문서를 열어 주소가 바뀌고 화면은 아직 홈일 때 홈을 누르면 홈으로 돌아온다", async () => {
    const { homeButton } = renderGnb(HOME_PATH);
    // 라우터는 주소를 먼저 바꾸고 화면은 transition으로 나중에 그려요. 그 사이를 주소만 바꿔 만들어요
    window.history.pushState(null, "", DOCUMENT_PATH);

    await act(async () => {
      fireEvent.click(homeButton);
    });

    expect(window.location.pathname).toBe(HOME_PATH);
  });

  it("탭을 빠르게 연달아 누르면 마지막에 누른 탭의 화면으로 가고 그 탭만 현재 화면으로 표시한다", async () => {
    const { router, exploreButton, documentsButton, homeButton } = renderGnb();

    await act(async () => {
      fireEvent.click(exploreButton);
      fireEvent.click(documentsButton);
      fireEvent.click(homeButton);
      fireEvent.click(documentsButton);
    });

    expect(router.state.location.pathname).toBe(DOCUMENTS_PATH);
    expect(documentsButton).toHaveAttribute("aria-current", "page");
    expect(homeButton).not.toHaveAttribute("aria-current");
    expect(exploreButton).not.toHaveAttribute("aria-current");
  });

  it("탭을 눌러 화면이 바뀌어도 포커스는 누른 탭에 남는다", async () => {
    const { exploreButton } = renderGnb();

    exploreButton.focus();
    await act(async () => {
      fireEvent.click(exploreButton);
    });

    expect(exploreButton).toHaveAttribute("aria-current", "page");
    expect(exploreButton).toHaveFocus();
  });

  it("이미 있는 화면의 버튼은 눌렀다 떼도 이동하지 않고 그 버튼이 현재 화면으로 남는다", async () => {
    const { router, homeButton } = renderGnb();

    // 표시가 제자리에서 맺히는 누름과 뗌을 거쳐 실제 누름과 같은 순서로 눌러요
    await act(async () => {
      fireEvent.pointerDown(homeButton, { button: 0 });
      fireEvent.pointerUp(homeButton, { button: 0 });
      fireEvent.click(homeButton);
    });

    expect(router.state.historyAction).toBe("POP");
    expect(router.state.location.pathname).toBe(HOME_PATH);
    expect(homeButton).toHaveAttribute("aria-current", "page");
  });

  it("우측에 로그인한 회원의 프로필 이미지를 아바타로 보여준다", async () => {
    renderGnb();

    const avatar = screen.getByRole("img", { name: "내 프로필" });

    await waitFor(() => {
      expect(avatar.querySelector("img")).toHaveAttribute(
        "src",
        expectedMe.profileImageUrl,
      );
    });
  });

  it("프로필 사진이 없는 회원은 아바타에 닉네임 첫 글자를 보여준다(AUTH-R15)", async () => {
    // Google, 카카오 회원은 사진이 없거나 제공에 동의하지 않으면 사진 주소가 null로 와요
    mockServer.use(
      http.get(`*${AUTH_ME_API_PATH}`, () =>
        HttpResponse.json({ ...meResponse, profileImageUrl: null }),
      ),
    );
    renderGnb();

    const avatar = screen.getByRole("img", { name: "내 프로필" });

    await waitFor(() => {
      expect(avatar).toHaveTextContent(expectedMe.nickname.charAt(0));
    });
    expect(avatar.querySelector("img")).not.toBeInTheDocument();
  });

  it("우측에 받은 버튼을 아바타 앞에 둔다 (Figma GNB/Floating)", () => {
    renderGnb();

    const right = screen.getByRole("button", { name: RIGHT_TEXT });
    const avatar = screen.getByRole("img", { name: "내 프로필" });

    expect(right.parentElement).toBe(avatar.parentElement);
    expect(right.compareDocumentPosition(avatar)).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING,
    );
  });

  it("좌측 패널 트리거에 포인터를 얹으면 패널이 겹쳐 뜬다", () => {
    const { panelTrigger } = renderGnb();

    expect(screen.queryByText(PANEL_TEXT)).not.toBeInTheDocument();

    fireEvent.pointerOver(panelTrigger);

    expect(screen.getByText(PANEL_TEXT)).toBeInTheDocument();
    expect(panelTrigger).toHaveAttribute("aria-expanded", "true");
  });

  it("좌측 패널 트리거를 누르면 패널이 고정 자리로 옮겨 간다", () => {
    const { panelTrigger } = renderGnb();

    fireEvent.click(panelTrigger);

    expect(panelTrigger).toHaveAttribute("aria-pressed", "true");
    expect(document.getElementById(DOCK_RAIL_ID)).toContainElement(
      screen.getByText(PANEL_TEXT),
    );
  });

  it("고정된 패널은 다시 누르면 접힌다", () => {
    const { panelTrigger } = renderGnb();

    fireEvent.click(panelTrigger);
    fireEvent.click(panelTrigger);

    expect(panelTrigger).toHaveAttribute("aria-pressed", "false");
    expect(screen.queryByText(PANEL_TEXT)).not.toBeInTheDocument();
  });
});

describe("WorkspaceGnb 위치 표시", () => {
  /** GNB 왼쪽의 위치 표시. 이름과 탭 이름이 다 보일 때까지 기다려요 */
  const findLocation = async (text: string) => {
    const location = screen.getByRole("group", { name: LOCATION_LABEL });

    await waitFor(() => expect(location).toHaveTextContent(text));

    return location;
  };

  it.each([
    { tab: "홈", path: HOME_PATH },
    { tab: "탐색", path: CHAT_PATH },
    { tab: "문서", path: DOCUMENTS_PATH },
  ])(
    "[WS-QA-058] $tab 탭에서는 워크스페이스 이름 뒤에 $tab을 보여준다",
    async ({ tab, path }) => {
      renderGnb(path);

      const location = await findLocation(expectedWorkspace.name);

      expect(location).toHaveTextContent(`${expectedWorkspace.name}${tab}`);
    },
  );

  it("[WS-QA-058] 다른 워크스페이스로 옮기면 새 워크스페이스의 이름으로 바뀐다", async () => {
    // mock 조회는 id와 무관하게 같은 이름이라, 다른 워크스페이스는 목록의 이름으로 답하게 해요
    mockServer.use(
      http.get(WORKSPACE_REQUEST, ({ params }) =>
        HttpResponse.json({
          ...workspaceDetailResponse,
          name:
            params.workspaceId === String(otherWorkspace.id)
              ? otherWorkspace.name
              : workspaceDetailResponse.name,
        }),
      ),
    );
    const { router } = renderGnb();
    await findLocation(expectedWorkspace.name);

    await act(async () => {
      await router.navigate(OTHER_HOME_PATH);
    });

    const location = await findLocation(otherWorkspace.name);
    expect(location).toHaveTextContent(`${otherWorkspace.name}홈`);
  });

  it("[WS-QA-059] 문서 하나를 보는 화면에서는 워크스페이스 이름 › 문서 › 문서 제목을 보여준다", async () => {
    renderGnb(DOCUMENT_PATH);

    const location = await findLocation(expectedDocument.title);

    expect(location).toHaveTextContent(
      `${expectedWorkspace.name}문서${expectedDocument.title}`,
    );
  });

  it("녹음 문서 화면에서는 워크스페이스 이름 › 문서 › 녹음 제목을 보여준다", async () => {
    renderGnb(RECORDING_DOCUMENT_PATH);

    const location = await findLocation(expectedRecordingDocument.title);

    expect(location).toHaveTextContent(
      `${expectedWorkspace.name}문서${expectedRecordingDocument.title}`,
    );
  });

  it("문서를 불러오지 못하면 제목 자리 없이 워크스페이스 이름 › 문서까지 보여준다", async () => {
    mockServer.use(
      http.get(DOCUMENT_REQUEST, () =>
        HttpResponse.json(
          { code: "DOCUMENT_NOT_FOUND", message: "문서를 찾을 수 없습니다." },
          { status: 404 },
        ),
      ),
    );
    renderGnb(DOCUMENT_PATH);

    const location = await findLocation(expectedWorkspace.name);

    await waitFor(() =>
      expect(location.querySelectorAll("svg")).toHaveLength(1),
    );
    expect(location).toHaveTextContent(
      new RegExp(`${expectedWorkspace.name}문서$`),
    );
  });

  it.each([
    { screenName: "녹음 화면", path: RECORDING_PATH },
    { screenName: "녹음 뒤 정리 중 화면", path: RECORDING_DOCUMENTS_PATH },
    {
      screenName: "녹음 직후 문서 확인 화면",
      path: `${DOCUMENT_PATH}?${DOCUMENT_FROM_PARAM}=${DOCUMENT_FROM_RECORDING}`,
    },
  ])(
    "[WS-QA-060] $screenName처럼 탭이 아닌 화면에서는 워크스페이스 이름만 보여준다",
    async ({ path }) => {
      renderGnb(path);

      const location = await findLocation(expectedWorkspace.name);

      ["홈", "탐색", "문서"].forEach((tab) => {
        expect(within(location).queryByText(tab)).not.toBeInTheDocument();
      });
    },
  );

  it("[WS-QA-061] 워크스페이스 이름은 눌러도 이동하거나 메뉴를 열지 않는다", async () => {
    const { router } = renderGnb();
    const location = await findLocation(expectedWorkspace.name);

    fireEvent.click(within(location).getByText(expectedWorkspace.name));

    expect(within(location).queryByRole("button")).not.toBeInTheDocument();
    expect(within(location).queryByRole("link")).not.toBeInTheDocument();
    expect(router.state.historyAction).toBe("POP");
    expect(router.state.location.pathname).toBe(HOME_PATH);
  });

  it("[WS-QA-064] 불러오는 중에는 이름과 제목 자리를 비워 두고 아이콘은 첫 글자 없이 기본 모양으로 둔다", async () => {
    // 응답을 끝내 보내지 않아 불러오는 중에 머물러요
    mockServer.use(
      http.get(WORKSPACE_REQUEST, async () => {
        await delay("infinite");
        return new HttpResponse(null);
      }),
      http.get(DOCUMENT_REQUEST, async () => {
        await delay("infinite");
        return new HttpResponse(null);
      }),
    );
    renderGnb(DOCUMENT_PATH);

    const location = screen.getByRole("group", { name: LOCATION_LABEL });

    // 이름과 제목 자리는 글자 없는 회색 덩어리라, 보이는 글자는 탭 이름 「문서」뿐이에요
    expect(location).toHaveTextContent(/^문서$/);
    expect(
      within(location).getByRole("img", { hidden: true }).querySelector("svg"),
    ).toBeInTheDocument();
  });
});
