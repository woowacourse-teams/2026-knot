import { render, screen } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { describe, expect, it } from "vitest";

import { getRouterPath, PATH_ROUTE } from "../PATH_ROUTE";

import DocumentsRedirect from ".";

const WORKSPACE_ID = "7";
const ELSEWHERE_PATH = "/elsewhere";

const renderRedirect = () => {
  const router = createMemoryRouter(
    [
      { path: ELSEWHERE_PATH, element: <p>다른 화면</p> },
      { path: PATH_ROUTE.DOCUMENTS, element: <DocumentsRedirect /> },
      {
        path: PATH_ROUTE.DOCUMENTS_BY_RECORDING,
        element: <p>녹음별 탭</p>,
      },
    ],
    {
      initialEntries: [
        ELSEWHERE_PATH,
        getRouterPath({
          routeKey: "DOCUMENTS",
          params: { workspaceId: WORKSPACE_ID },
        }),
      ],
    },
  );

  render(<RouterProvider router={router} />);

  return router;
};

describe("DocumentsRedirect", () => {
  it("탭이 없는 문서 탭 주소에 오면 같은 워크스페이스의 「녹음별」 탭으로 보낸다", async () => {
    const router = renderRedirect();

    expect(await screen.findByText("녹음별 탭")).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(
      getRouterPath({
        routeKey: "DOCUMENTS_BY_RECORDING",
        params: { workspaceId: WORKSPACE_ID },
      }),
    );
  });

  it("히스토리를 바꿔치기해, 뒤로 가면 문서 탭 주소가 아니라 그 전 화면으로 돌아간다", async () => {
    const router = renderRedirect();

    await screen.findByText("녹음별 탭");
    await router.navigate(-1);

    expect(await screen.findByText("다른 화면")).toBeInTheDocument();
  });
});
