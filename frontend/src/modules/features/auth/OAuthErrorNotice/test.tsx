import { ThemeProvider } from "@emotion/react";
import { theme } from "@provider/themeProvider";
import { PATH_ROUTE } from "@routes/PATH_ROUTE";
import { render, screen } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { describe, expect, it } from "vitest";

import { OAUTH_ERROR_MESSAGE } from "./constants/oauthError";

import OAuthErrorNotice from ".";

const renderNotice = (search: string) => {
  const router = createMemoryRouter(
    [{ path: PATH_ROUTE.LOGIN, element: <OAuthErrorNotice /> }],
    { initialEntries: [`${PATH_ROUTE.LOGIN}${search}`] },
  );

  render(
    <ThemeProvider theme={theme}>
      <RouterProvider router={router} />
    </ThemeProvider>,
  );
};

describe("OAuthErrorNotice", () => {
  it("로그인에 실패해 돌아오면 실패를 알린다", () => {
    renderNotice("?error=oauth2");

    expect(screen.getByRole("alert")).toHaveTextContent(OAUTH_ERROR_MESSAGE);
  });

  it("그냥 로그인 화면을 열었을 때는 아무것도 알리지 않는다", () => {
    renderNotice("");

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("로그인 수단의 승인 화면에서 취소하고 돌아오면 아무것도 알리지 않는다(AUTH-R16)", () => {
    renderNotice("?error=oauth2_cancelled");

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("모르는 error 값에는 아무것도 알리지 않는다", () => {
    renderNotice("?error=somethingElse");

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
