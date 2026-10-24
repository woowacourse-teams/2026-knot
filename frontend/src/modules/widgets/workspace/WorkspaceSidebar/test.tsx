import { GetDocumentsResponseDto } from "@api/dto/document";
import { GetWorkspaceResponseDto } from "@api/dto/workspace";
import { FEEDBACK_RELAY_PATH } from "@api/fetch/relay/f";
import { documentsResponse } from "@api/mock/responses/document";
import { workspaceDetailResponse } from "@api/mock/responses/workspace";
import { mockServer } from "@api/mock/server";
import { ThemeProvider } from "@emotion/react";
import { DialogProvider } from "@provider/context/dialogContext";
import { ToastProvider } from "@provider/context/toastContext";
import { theme } from "@provider/themeProvider";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { createMemoryRouter, Outlet, RouterProvider } from "react-router";
import { afterEach, describe, expect, it } from "vitest";

import WorkspaceSidebar from ".";

// 경로 파라미터 자리에 무엇이 와도 잡도록 fetch 상수 대신 패턴을 적어요
const DOCUMENTS_REQUEST = "*/api/v1/workspaces/:workspaceId/documents";
const LOAD_FAILED_NOTICE = "목록을 불러오지 못했어요";

const expectedWorkspace = new GetWorkspaceResponseDto(workspaceDetailResponse);
const expectedDocuments = new GetDocumentsResponseDto(documentsResponse);

const WORKSPACE_ID = 1;
const HOME_PATH = getRouterPath({
  routeKey: "WORKSPACE_HOME",
  params: { workspaceId: String(WORKSPACE_ID) },
});

const renderSidebar = () => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const router = createMemoryRouter(
    [
      {
        // 앱(AppProviders)처럼 모달이 토스트 안에 있어야 모달 안에서 토스트를 띄워요
        element: (
          <ToastProvider>
            <DialogProvider>
              <Outlet />
            </DialogProvider>
          </ToastProvider>
        ),
        children: [
          { path: PATH_ROUTE.WORKSPACE_HOME, element: <WorkspaceSidebar /> },
        ],
      },
    ],
    { initialEntries: [HOME_PATH] },
  );

  render(
    <ThemeProvider theme={theme}>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </ThemeProvider>,
  );

  return screen.getByRole("complementary", { name: "워크스페이스 사이드바" });
};

describe("WorkspaceSidebar", () => {
  it("워크스페이스 이름이 적힌 메뉴 버튼과 문서 목록을 보여 준다", async () => {
    const sidebar = renderSidebar();

    expect(
      await within(sidebar).findByText(expectedWorkspace.name),
    ).toBeInTheDocument();
    expect(
      within(sidebar).getByRole("button", { name: "워크스페이스 메뉴" }),
    ).toBeInTheDocument();

    // 폴더 행의 이름은 폴더 이름과 문서 수를 이은 글이라, 폴더 이름 뒤에 문서 수만 오는 행을 찾아요
    for (const { topic } of expectedDocuments.topics) {
      expect(
        await within(sidebar).findByRole("button", {
          name: (name) =>
            name.startsWith(topic) && /^\s*\d+$/.test(name.slice(topic.length)),
        }),
      ).toBeInTheDocument();
    }
    // 불러오는 동안의 문서 목록도 이름이 같아, 폴더 행이 나온 뒤에 찾아요
    expect(
      within(sidebar).getByRole("navigation", { name: "문서" }),
    ).toBeInTheDocument();
  });

  it("문서 목록을 불러오지 못해도 워크스페이스 이름은 그대로 보여 준다", async () => {
    mockServer.use(
      http.get(
        DOCUMENTS_REQUEST,
        () => new HttpResponse(null, { status: 500 }),
      ),
    );

    const sidebar = renderSidebar();

    expect(
      within(await within(sidebar).findByRole("alert")).getByText(
        LOAD_FAILED_NOTICE,
      ),
    ).toBeInTheDocument();
    expect(
      await within(sidebar).findByText(expectedWorkspace.name),
    ).toBeInTheDocument();
  });

  describe("휴대폰의 「피드백」", () => {
    // 「피드백」은 휴대폰에서만 보이는데, 테스트 화면은 미디어 쿼리를 읽지 않아 숨겨진 채로 있어요.
    // 숨긴 요소는 접근성 이름이 비어 역할로 찾지 못해, 글자로 찾아요
    const getFeedbackButton = (sidebar: HTMLElement) =>
      within(sidebar).getByText("피드백", { selector: "button" });

    const openFeedbackSheet = () => {
      const sidebar = renderSidebar();
      const feedbackButton = getFeedbackButton(sidebar);
      // 브라우저는 누른 버튼에 포커스를 주지만 jsdom은 주지 않아 직접 옮겨요
      feedbackButton.focus();
      fireEvent.click(feedbackButton);

      return { feedbackButton };
    };

    const queryFeedbackPanel = () =>
      screen.queryByRole("dialog", { name: "피드백 보내기" });

    afterEach(() => sessionStorage.clear());

    it("「피드백」을 누르면 피드백 패널을 시트로 연다", () => {
      openFeedbackSheet();

      expect(queryFeedbackPanel()).toBeInTheDocument();
    });

    it("ESC를 누르면 시트를 닫고 「피드백」으로 포커스를 돌려준다", async () => {
      const { feedbackButton } = openFeedbackSheet();

      fireEvent.keyDown(document, { key: "Escape" });

      await waitFor(() => expect(queryFeedbackPanel()).not.toBeInTheDocument());
      expect(feedbackButton).toHaveFocus();
    });

    it("흐린 바깥을 누르면 닫고, 패널 안을 눌러서는 닫지 않는다", async () => {
      openFeedbackSheet();

      fireEvent.click(screen.getByText("어떤 의견인가요?"));
      expect(queryFeedbackPanel()).toBeInTheDocument();

      const dim = queryFeedbackPanel()?.parentElement?.parentElement;
      fireEvent.click(dim as HTMLElement);

      await waitFor(() => expect(queryFeedbackPanel()).not.toBeInTheDocument());
    });

    it("보내는 동안에는 바깥과 ESC로 닫지 않는다", async () => {
      mockServer.use(
        http.post(
          `*${FEEDBACK_RELAY_PATH}`,
          () => new Promise<never>(() => {}),
        ),
      );
      openFeedbackSheet();

      fireEvent.click(screen.getByRole("radio", { name: "오류가 있어요" }));
      fireEvent.click(screen.getByRole("button", { name: "보내기" }));
      await waitFor(() =>
        expect(screen.getByRole("button", { name: "닫기" })).toBeDisabled(),
      );

      fireEvent.keyDown(document, { key: "Escape" });
      fireEvent.click(
        queryFeedbackPanel()?.parentElement?.parentElement as HTMLElement,
      );

      expect(queryFeedbackPanel()).toBeInTheDocument();
    });
  });
});
