import { AUTH_ME_API_PATH } from "@api/fetch/api/v1/auth/me";
import { AUTH_REFRESH_API_PATH } from "@api/fetch/api/v1/auth/refresh";
import { mockServer } from "@api/mock/server";
import { authKeys } from "@api/queryKey/auth";
import { TOAST_MESSAGES } from "@constants/toastMessages";
import { ThemeProvider } from "@emotion/react";
import { ToastProvider } from "@provider/context/toastContext";
import { theme } from "@provider/themeProvider";
import AuthGuard from "@routes/AuthGuard";
import GuestGuard from "@routes/GuestGuard";
import { PATH_ROUTE } from "@routes/PATH_ROUTE";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { createMemoryRouter, Outlet, RouterProvider } from "react-router";
import { afterEach, describe, expect, it } from "vitest";

import LoginPage from ".";

const LOGIN_FAILED_TEXT = "로그인 실패 화면";

const renderLoginPage = (search = "") => {
  const router = createMemoryRouter(
    [
      {
        element: (
          <ToastProvider>
            <Outlet />
          </ToastProvider>
        ),
        children: [
          { path: PATH_ROUTE.LOGIN, element: <LoginPage /> },
          {
            path: PATH_ROUTE.LOGIN_FAILED,
            element: <p>{LOGIN_FAILED_TEXT}</p>,
          },
        ],
      },
    ],
    { initialEntries: [`${PATH_ROUTE.LOGIN}${search}`] },
  );

  render(
    <ThemeProvider theme={theme}>
      <RouterProvider router={router} />
    </ThemeProvider>,
  );

  return { router };
};

const PROTECTED_TEXT = "로그인해야 보이는 화면";

/** 앱처럼 로그인해야 보이는 화면과 로그인 화면을 가드로 감싸고, 토스트는 라우터 안의 Provider가 그려요 */
const renderApp = () => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
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
            element: <AuthGuard />,
            children: [
              { path: PATH_ROUTE.WORKSPACE, element: <p>{PROTECTED_TEXT}</p> },
            ],
          },
          {
            element: <GuestGuard />,
            children: [{ path: PATH_ROUTE.LOGIN, element: <LoginPage /> }],
          },
        ],
      },
    ],
    { initialEntries: [PATH_ROUTE.WORKSPACE] },
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

/** 로그인 확인이 401이고, 재발급도 `refreshStatus`로 실패하게 해요 */
const failMeAndRefresh = (refreshStatus = 401) => {
  mockServer.use(
    http.get(
      `*${AUTH_ME_API_PATH}`,
      () => new HttpResponse(null, { status: 401 }),
    ),
    http.post(
      `*${AUTH_REFRESH_API_PATH}`,
      () => new HttpResponse(null, { status: refreshStatus }),
    ),
  );
};

describe("LoginPage", () => {
  it("제목과 세 로그인 버튼을 보여 주고, 약관 안내는 버튼과 같은 계정 안내 아래에 둔다(TERMS-R10)", () => {
    renderLoginPage();

    expect(
      screen.getByRole("heading", {
        level: 1,
        name: "팀의 결정을 모두의 기억으로",
      }),
    ).toBeInTheDocument();

    const buttons = screen.getAllByRole("button");
    const guide = screen.getByText(
      "처음 가입한 방법으로 로그인해야 같은 계정이에요",
    );
    const terms = screen.getByText(
      "로그인하면 서비스 이용약관과 개인정보 처리방침에 동의하게 됩니다.",
    );

    expect(buttons).toHaveLength(3);
    for (const button of buttons) {
      expect(guide.compareDocumentPosition(button)).toBe(
        Node.DOCUMENT_POSITION_PRECEDING,
      );
    }
    expect(guide.compareDocumentPosition(terms)).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING,
    );
  });

  describe("로그인 실패와 취소 (AUTH-R7, AUTH-R16)", () => {
    it("로그인 처리에 실패해 돌아오면(?error=oauth2) 로그인 실패 화면으로 기록을 바꿔 보낸다", async () => {
      const { router } = renderLoginPage("?error=oauth2");

      expect(await screen.findByText(LOGIN_FAILED_TEXT)).toBeInTheDocument();
      expect(router.state.location.pathname).toBe(PATH_ROUTE.LOGIN_FAILED);
      expect(router.state.historyAction).toBe("REPLACE");
    });

    it("승인 화면에서 취소하고 돌아오면(?error=oauth2_cancelled) 안내 없이 로그인 기본 화면을 보여 준다", () => {
      const { router } = renderLoginPage("?error=oauth2_cancelled");

      expect(screen.getAllByRole("button")).toHaveLength(3);
      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
      expect(router.state.location.pathname).toBe(PATH_ROUTE.LOGIN);
    });

    it("모르는 error 값에는 로그인 기본 화면을 그대로 보여 준다", () => {
      const { router } = renderLoginPage("?error=somethingElse");

      expect(screen.getAllByRole("button")).toHaveLength(3);
      expect(router.state.location.pathname).toBe(PATH_ROUTE.LOGIN);
    });
  });

  describe("로그인 만료 안내 (ERR-07)", () => {
    afterEach(() => {
      sessionStorage.clear();
    });

    it("처음 온 비로그인 사용자는 재발급도 실패해 로그인 화면으로 와도 만료 토스트를 띄우지 않는다", async () => {
      failMeAndRefresh();
      const { router } = renderApp();

      await waitFor(() =>
        expect(router.state.location.pathname).toBe(PATH_ROUTE.LOGIN),
      );
      // 로그인 화면이 그려진 뒤 확인 요청까지 끝날 틈을 줘요
      await act(() => new Promise((resolve) => setTimeout(resolve, 50)));

      expect(
        screen.queryByText(TOAST_MESSAGES.SESSION_EXPIRED.message),
      ).not.toBeInTheDocument();
    });

    it("로그인했던 탭에서 토큰 재발급이 거절돼(401) 로그인 화면으로 보내면 만료 토스트를 띄운다", async () => {
      const { router, queryClient } = renderApp();
      await screen.findByText(PROTECTED_TEXT);

      failMeAndRefresh();
      await act(() =>
        queryClient.invalidateQueries({ queryKey: authKeys.me() }),
      );

      await waitFor(() =>
        expect(router.state.location.pathname).toBe(PATH_ROUTE.LOGIN),
      );
      expect(
        await screen.findByText(TOAST_MESSAGES.SESSION_EXPIRED.message),
      ).toBeInTheDocument();
    });

    it("재발급이 서버 오류로 실패해 로그인 화면으로 보낸 것은 만료로 보지 않아 토스트를 띄우지 않는다", async () => {
      const { router, queryClient } = renderApp();
      await screen.findByText(PROTECTED_TEXT);

      failMeAndRefresh(500);
      await act(() =>
        queryClient.invalidateQueries({ queryKey: authKeys.me() }),
      );

      await waitFor(() =>
        expect(router.state.location.pathname).toBe(PATH_ROUTE.LOGIN),
      );
      await act(() => new Promise((resolve) => setTimeout(resolve, 50)));

      expect(
        screen.queryByText(TOAST_MESSAGES.SESSION_EXPIRED.message),
      ).not.toBeInTheDocument();
    });
  });
});
