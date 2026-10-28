import { ThemeProvider } from "@emotion/react";
import { theme } from "@provider/themeProvider";
import { PATH_ROUTE } from "@routes/PATH_ROUTE";
import { fireEvent, render, screen } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { describe, expect, it } from "vitest";

import LoginFailedPage from ".";

const LOGIN_TEXT = "로그인 화면";

const renderLoginFailedPage = () => {
  const router = createMemoryRouter(
    [
      { path: PATH_ROUTE.LOGIN, element: <p>{LOGIN_TEXT}</p> },
      { path: PATH_ROUTE.LOGIN_FAILED, element: <LoginFailedPage /> },
    ],
    { initialEntries: [PATH_ROUTE.LOGIN_FAILED] },
  );

  render(
    <ThemeProvider theme={theme}>
      <RouterProvider router={router} />
    </ThemeProvider>,
  );

  return { router };
};

describe("LoginFailedPage (ERR-15)", () => {
  it("제목과 설명을 알리고, 내부 오류 코드 없이 「로그인 화면으로」 버튼 하나를 둔다(AUTH-R7)", () => {
    renderLoginFailedPage();

    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { level: 1, name: "로그인하지 못했어요" }),
    ).toBeInTheDocument();
    expect(screen.getByText("로그인 중 문제가 생겼어요.")).toBeInTheDocument();
    expect(screen.getByText("잠시 후 다시 시도해 주세요.")).toBeInTheDocument();
    expect(screen.getAllByRole("button")).toHaveLength(1);
  });

  it("「로그인 화면으로」 버튼은 제목과 설명을 버튼의 설명으로 잇는다(AUTH-R8)", () => {
    renderLoginFailedPage();

    expect(
      screen.getByRole("button", { name: "로그인 화면으로" }),
    ).toHaveAccessibleDescription(
      "로그인하지 못했어요 로그인 중 문제가 생겼어요. 잠시 후 다시 시도해 주세요.",
    );
  });

  it("「로그인 화면으로」를 누르면 기록을 바꿔 로그인 화면으로 보낸다(AUTH-R8)", async () => {
    const { router } = renderLoginFailedPage();

    fireEvent.click(screen.getByRole("button", { name: "로그인 화면으로" }));

    expect(await screen.findByText(LOGIN_TEXT)).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(PATH_ROUTE.LOGIN);
    expect(router.state.historyAction).toBe("REPLACE");
  });
});
