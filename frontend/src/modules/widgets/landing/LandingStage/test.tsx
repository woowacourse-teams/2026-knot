import { ThemeProvider } from "@emotion/react";
import { theme } from "@provider/themeProvider";
import { PATH_ROUTE } from "@routes/PATH_ROUTE";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { describe, expect, it } from "vitest";

import LandingStage from ".";
import { LANDING_TECHNOLOGY_SCENES, type LandingScenes } from "./constants/landing";

const LOGIN_TEXT = "로그인 화면";

const renderLandingStage = (scenes?: LandingScenes) => {
  const router = createMemoryRouter(
    [
      { path: PATH_ROUTE.HOME, element: <LandingStage scenes={scenes} /> },
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

describe("LandingStage 장면 목록", () => {
  it("장면은 소개부터 시작까지 7개이고 기술 소개 장면은 없다", () => {
    renderLandingStage();

    const steps = within(screen.getByRole("navigation", { name: "섹션" })).getAllByRole("button");

    expect(steps.map((step) => step.textContent)).toEqual(["소개", "문제", "녹음", "정리", "확인", "탐색", "시작"]);
    expect(screen.queryByRole("region", { name: /기술/ })).not.toBeInTheDocument();
  });

  it("기술 소개 장면 묶음을 받으면 탐색과 시작 사이에 녹음 기술, 탐색 기술 장면이 들어간다", () => {
    renderLandingStage(LANDING_TECHNOLOGY_SCENES);

    const steps = within(screen.getByRole("navigation", { name: "섹션" })).getAllByRole("button");

    expect(steps.map((step) => step.textContent)).toEqual([
      "소개",
      "문제",
      "녹음",
      "정리",
      "확인",
      "탐색",
      "기술: 녹음",
      "기술: 탐색",
      "시작",
    ]);
    // 보이지 않는 장면은 aria-hidden이라 역할로는 안 잡히고, 제목이 줄바꿈으로 나뉘어 있어 제목 ID로 두 장면이 그려졌는지 봐요
    expect(document.getElementById("technology-recording-title")).toHaveTextContent("녹음은 정확하게");
    expect(document.getElementById("technology-search-title")).toHaveTextContent("찾는 건 빠르게");
  });
});

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
