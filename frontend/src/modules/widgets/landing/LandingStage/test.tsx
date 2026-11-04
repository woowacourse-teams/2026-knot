import { ThemeProvider } from "@emotion/react";
import { theme } from "@provider/themeProvider";
import { PATH_ROUTE } from "@routes/PATH_ROUTE";
import { fireEvent, render, screen } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { describe, expect, it } from "vitest";

import LandingStage from ".";

const LOGIN_TEXT = "로그인 화면";

const renderLandingStage = () => {
  const router = createMemoryRouter(
    [
      { path: PATH_ROUTE.HOME, element: <LandingStage /> },
      { path: PATH_ROUTE.LOGIN, element: <p>{LOGIN_TEXT}</p> },
    ],
    { initialEntries: [PATH_ROUTE.HOME] },
  );

  render(
    <ThemeProvider theme={theme}>
      <RouterProvider router={router} />
    </ThemeProvider>,
  );

  return { router };
};

describe("LandingStage 시작 자리", () => {
  it("첫 장면에는 로그인 수단 버튼 대신 「시작하기」 링크 하나를 둔다", () => {
    renderLandingStage();

    // 보이지 않는 장면은 aria-hidden이라, 역할로 찾으면 지금 보이는 첫 장면의 링크만 잡혀요
    expect(screen.getAllByRole("link", { name: "시작하기" })).toHaveLength(1);
    expect(screen.queryByRole("button", { name: /로 시작하기$/ })).not.toBeInTheDocument();
  });

  it("「시작하기」를 누르면 로그인 화면으로 간다", async () => {
    const { router } = renderLandingStage();

    fireEvent.click(screen.getByRole("link", { name: "시작하기" }));

    expect(await screen.findByText(LOGIN_TEXT)).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(PATH_ROUTE.LOGIN);
  });
});
