import { act, render, screen } from "@testing-library/react";
import { useEffect, useState } from "react";
import { createMemoryRouter, Outlet, RouterProvider } from "react-router";
import { describe, expect, it } from "vitest";

import RouteAnnouncer from ".";

/** 워크스페이스 입장 확인처럼, 불러오기를 마친 뒤에야 화면과 제목을 그리는 화면 */
function LateTitleScreen() {
  const [isReady, setIsReady] = useState(false);

  useEffect(() => {
    const timer = setTimeout(() => setIsReady(true));
    return () => clearTimeout(timer);
  }, []);

  return isReady ? <title>홈 - knot</title> : null;
}

const renderRoutes = (initialPath: string) => {
  const router = createMemoryRouter(
    [
      {
        element: (
          <>
            <Outlet />
            <RouteAnnouncer />
          </>
        ),
        children: [
          { path: "/home", element: <title>홈 - knot</title> },
          { path: "/recording", element: <title>녹음 - knot</title> },
          { path: "/create", element: <title>워크스페이스 만들기 - knot</title> },
          { path: "/late", element: <LateTitleScreen /> },
        ],
      },
    ],
    { initialEntries: [initialPath] },
  );
  render(<RouterProvider router={router} />);

  return { router };
};

describe("RouteAnnouncer", () => {
  it("처음 들어온 화면의 제목은 낭독기가 스스로 읽으므로 적지 않는다", () => {
    renderRoutes("/home");

    expect(screen.getByRole("status")).toBeEmptyDOMElement();
  });

  it("화면을 옮기면 새 화면의 제목을 상태 영역에 적는다", async () => {
    const { router } = renderRoutes("/home");

    await act(() => router.navigate("/recording"));
    expect(screen.getByRole("status")).toHaveTextContent("녹음 - knot");

    await act(() => router.navigate("/home"));
    expect(screen.getByRole("status")).toHaveTextContent("홈 - knot");
  });

  it("주소의 쿼리만 바뀌면 같은 화면이므로 적지 않는다", async () => {
    const { router } = renderRoutes("/home");

    await act(() => router.navigate("/home?messageId=1"));

    expect(screen.getByRole("status")).toBeEmptyDOMElement();
  });

  it("새 화면이 불러오기를 마친 뒤 제목을 그리면 그 제목을 적는다", async () => {
    const { router } = renderRoutes("/create");

    await act(() => router.navigate("/late"));

    expect(await screen.findByText("홈 - knot")).toBe(
      screen.getByRole("status"),
    );
  });
});
