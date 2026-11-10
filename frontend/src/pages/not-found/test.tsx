import { AUTH_ME_API_PATH } from "@api/fetch/api/v1/auth/me";
import { mockServer } from "@api/mock/server";
import { ThemeProvider } from "@emotion/react";
import AppProviders from "@provider/AppProviders";
import { theme } from "@provider/themeProvider";
import { PATH_ROUTE } from "@routes/PATH_ROUTE";
import { routes } from "@routes/routes";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { delay, http, HttpResponse } from "msw";
import { createMemoryRouter, Outlet, RouterProvider } from "react-router";
import { describe, expect, it } from "vitest";

// 피그마 ERR-01(잘못된 요청) 화면의 문구예요
const NOT_FOUND_TITLE = "요청을 처리할 수 없어요";
const NOT_FOUND_DESCRIPTIONS = [
  "잘못된 주소이거나",
  "더 이상 유효하지 않은 요청이에요.",
];

/** 앱의 실제 라우트로 그려, 정의된 화면과 `*` 라우트가 부딪히지 않는지 함께 봐요 */
const renderApp = (initialPath: string) => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  // 모든 화면을 감싼 생존 신호가 토스트와 모달을 쓰므로, 앱처럼 프로바이더 안에 그려요
  const router = createMemoryRouter(
    [
      {
        element: (
          <AppProviders routes={routes}>
            <Outlet />
          </AppProviders>
        ),
        children: routes,
      },
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

describe("NotFoundPage", () => {
  it.each(["/unknown", "/workspace/1/unknown", "/invite"])(
    "정의되지 않은 주소(%s)면 빈 화면 대신 ERR-01 화면을 보여준다 (ERR-01)",
    (path) => {
      renderApp(path);

      expect(
        screen.getByRole("heading", { name: NOT_FOUND_TITLE }),
      ).toBeInTheDocument();
      NOT_FOUND_DESCRIPTIONS.forEach((description) => {
        expect(screen.getByText(description)).toBeInTheDocument();
      });
    },
  );

  it("정의된 주소는 ERR-01 화면을 보여주지 않는다", () => {
    renderApp(PATH_ROUTE.JOIN_ERROR);

    expect(screen.getByText("초대장을 열 수 없어요")).toBeInTheDocument();
    expect(screen.queryByText(NOT_FOUND_TITLE)).not.toBeInTheDocument();
  });

  it("홈으로 이동을 누르면 홈 경로로 간다", async () => {
    // 홈 경로의 로그인 확인을 붙잡아, 홈에 도착한 뒤 다른 화면으로 넘어가지 않게 해요
    mockServer.use(
      http.get(`*${AUTH_ME_API_PATH}`, async () => {
        await delay("infinite");
        return new HttpResponse(null, { status: 401 });
      }),
    );
    const { router } = renderApp("/unknown");

    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "홈으로 이동" }));
    });

    expect(router.state.location.pathname).toBe(PATH_ROUTE.HOME);
    expect(screen.queryByText(NOT_FOUND_TITLE)).not.toBeInTheDocument();
  });
});
