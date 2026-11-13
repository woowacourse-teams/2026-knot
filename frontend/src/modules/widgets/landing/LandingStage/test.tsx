import { ThemeProvider } from "@emotion/react";
import { theme } from "@provider/themeProvider";
import { PATH_ROUTE } from "@routes/PATH_ROUTE";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { createMemoryRouter, RouterProvider } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";

import LandingStage from ".";
import {
  LANDING_TECHNOLOGY_SCENES,
  type LandingScenes,
} from "./constants/landing";
import type { createLandingEngine } from "./model/createLandingEngine";

vi.mock("@utils/captureAnalyticsEvent", () => ({
  captureAnalyticsEvent: vi.fn(),
}));

// jsdom은 캔버스를 그리지 못해 실제 엔진이 시작하지 않아요. 실제 엔진처럼 시작하면 첫 장면을,
// 장면 이동을 요청하면 그 장면을 장면 바뀜으로 알리는 가짜 엔진을 둬요. 스크롤로 장면을 정하는 일은 브라우저에서만 해요
vi.mock("./model/createLandingEngine", () => ({
  createLandingEngine: ({
    listeners,
  }: Parameters<typeof createLandingEngine>[0]) => ({
    start: () => listeners.onSceneChange(0),
    stepTo: (scene: number) => listeners.onSceneChange(scene),
    selectQuestion: () => {},
    destroy: () => {},
  }),
}));

const LOGIN_TEXT = "로그인 화면";

const renderLandingStage = (scenes?: LandingScenes) => {
  const router = createMemoryRouter(
    [
      { path: PATH_ROUTE.HOME, element: <LandingStage scenes={scenes} /> },
      { path: PATH_ROUTE.LOGIN, element: <p>{LOGIN_TEXT}</p> },
    ],
    { initialEntries: [PATH_ROUTE.HOME] },
  );

  const { unmount } = render(
    <ThemeProvider theme={theme}>
      <RouterProvider router={router} />
    </ThemeProvider>,
  );

  return { router, unmount };
};

afterEach(() => {
  vi.mocked(captureAnalyticsEvent).mockClear();
});

describe("LandingStage 장면 목록", () => {
  it("장면은 소개부터 시작까지 7개이고 기술 소개 장면은 없다", () => {
    renderLandingStage();

    const steps = within(
      screen.getByRole("navigation", { name: "섹션" }),
    ).getAllByRole("button");

    expect(steps.map((step) => step.textContent)).toEqual([
      "소개",
      "문제",
      "녹음",
      "정리",
      "확인",
      "탐색",
      "시작",
    ]);
    expect(
      screen.queryByRole("region", { name: /기술/ }),
    ).not.toBeInTheDocument();
  });

  it("기술 소개 장면 묶음을 받으면 탐색과 시작 사이에 녹음 기술, 탐색 기술 장면이 들어간다", () => {
    renderLandingStage(LANDING_TECHNOLOGY_SCENES);

    const steps = within(
      screen.getByRole("navigation", { name: "섹션" }),
    ).getAllByRole("button");

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
    expect(
      document.getElementById("technology-recording-title"),
    ).toHaveTextContent("녹음은 정확하게");
    expect(
      document.getElementById("technology-search-title"),
    ).toHaveTextContent("찾는 건 빠르게");
  });
});

describe("LandingStage 시작 자리", () => {
  it("첫 장면에는 로그인 수단 버튼 대신 「시작하기」 링크 하나를 둔다", () => {
    renderLandingStage();

    // 보이지 않는 장면은 aria-hidden이라, 역할로 찾으면 지금 보이는 첫 장면의 링크만 잡혀요
    expect(screen.getAllByRole("link", { name: "시작하기" })).toHaveLength(1);
    expect(
      screen.queryByRole("button", { name: /로 시작하기$/ }),
    ).not.toBeInTheDocument();
  });

  it("「시작하기」를 누르면 로그인 화면으로 간다", async () => {
    const { router } = renderLandingStage();

    fireEvent.click(screen.getByRole("link", { name: "시작하기" }));

    expect(await screen.findByText(LOGIN_TEXT)).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(PATH_ROUTE.LOGIN);
  });
});

describe("LandingStage 장면 사용 기록", () => {
  /** 남긴 장면 이름을 남긴 순서대로 모아요 */
  const getViewedScenes = () =>
    vi
      .mocked(captureAnalyticsEvent)
      .mock.calls.map(([params]) => params)
      .filter(({ event }) => event === "landing_scene_viewed")
      .map(({ properties }) => properties);

  /** 오른쪽 장면 목록에서 그 이름의 장면을 눌러 옮겨요 */
  const goToScene = (name: string) =>
    fireEvent.click(
      screen.getByRole("button", { name: `${name} 섹션으로 이동` }),
    );

  it("처음 그려지면 첫 장면(소개)을 본 것으로 한 번 남긴다", () => {
    renderLandingStage();

    expect(getViewedScenes()).toEqual([{ scene: "intro" }]);
  });

  it("장면을 옮기면 처음 보는 장면마다 한 번씩 남기고, 다시 본 장면은 남기지 않는다", () => {
    renderLandingStage();

    goToScene("문제");
    goToScene("탐색");
    goToScene("문제");
    goToScene("소개");
    goToScene("시작");

    expect(getViewedScenes()).toEqual([
      { scene: "intro" },
      { scene: "problem" },
      { scene: "search" },
      { scene: "start" },
    ]);
  });

  it("기술 소개 페이지에서는 기술 소개 장면과 시작 장면을 각자의 이름으로 남긴다", () => {
    renderLandingStage(LANDING_TECHNOLOGY_SCENES);

    goToScene("기술: 녹음");
    goToScene("기술: 탐색");
    goToScene("시작");

    expect(getViewedScenes()).toEqual([
      { scene: "intro" },
      { scene: "recording_technology" },
      { scene: "search_technology" },
      { scene: "start" },
    ]);
  });

  it("페이지를 다시 열면 본 장면도 처음 보는 장면으로 다시 남긴다", () => {
    const { unmount } = renderLandingStage();
    goToScene("문제");
    unmount();

    renderLandingStage();
    goToScene("문제");

    expect(getViewedScenes()).toEqual([
      { scene: "intro" },
      { scene: "problem" },
      { scene: "intro" },
      { scene: "problem" },
    ]);
  });
});
