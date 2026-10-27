import { GetDocumentRecordingsResponseDto } from "@api/dto/document";
import {
  documentRecordingsResponse,
  documentsResponse,
} from "@api/mock/responses/document";
import { ThemeProvider } from "@emotion/react";
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
import {
  createMemoryRouter,
  Outlet,
  RouterProvider,
  useLocation,
} from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import DocumentArchive from ".";

const WORKSPACE_ID = "1";
const expectedRecordings = new GetDocumentRecordingsResponseDto(
  documentRecordingsResponse,
);

// jsdom에는 IntersectionObserver가 없어요. 이 테스트는 다음 페이지를 받지 않으므로 아무것도 하지 않는 가짜를 둬요
class IdleIntersectionObserver {
  observe() {}

  unobserve() {}

  disconnect() {}
}

// 지금 주소를 화면에 적어, 탭을 누른 뒤 주소가 바뀌었는지 확인해요
function LocationProbe() {
  const { pathname } = useLocation();

  return <p data-testid="pathname">{pathname}</p>;
}

const renderDocumentArchive = (
  routeKey: "DOCUMENTS_BY_RECORDING" | "DOCUMENTS_BY_TOPIC",
) => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const router = createMemoryRouter(
    [
      {
        element: (
          <ToastProvider>
            <LocationProbe />
            <Outlet />
          </ToastProvider>
        ),
        children: [
          {
            path: PATH_ROUTE.DOCUMENTS_BY_RECORDING,
            element: <DocumentArchive />,
          },
          {
            path: PATH_ROUTE.DOCUMENTS_BY_TOPIC,
            element: <DocumentArchive />,
          },
        ],
      },
    ],
    {
      initialEntries: [
        getRouterPath({ routeKey, params: { workspaceId: WORKSPACE_ID } }),
      ],
    },
  );

  render(
    <ThemeProvider theme={theme}>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </ThemeProvider>,
  );

  return router;
};

const getTab = (name: string) => screen.getByRole("tab", { name });

const pathOf = (routeKey: "DOCUMENTS_BY_RECORDING" | "DOCUMENTS_BY_TOPIC") =>
  getRouterPath({ routeKey, params: { workspaceId: WORKSPACE_ID } });

describe("DocumentArchive", () => {
  beforeEach(() => {
    vi.stubGlobal("IntersectionObserver", IdleIntersectionObserver);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("제목 「문서」와 설명 아래에 「녹음별」, 「주제별」 탭을 이 순서로 둔다", () => {
    renderDocumentArchive("DOCUMENTS_BY_RECORDING");

    expect(
      screen.getByRole("heading", { level: 2, name: "문서" }),
    ).toBeInTheDocument();
    expect(
      screen.getByText("녹음하고 정리한 내용을 주제별로 모아둬요"),
    ).toBeInTheDocument();
    expect(
      within(screen.getByRole("tablist", { name: "문서 보기 방식" }))
        .getAllByRole("tab")
        .map(({ textContent }) => textContent),
    ).toEqual(["녹음별", "주제별"]);
  });

  it("「녹음별」 주소에서는 「녹음별」 탭이 골라져 있고, 탭이 가리키는 영역에 녹음별 목록이 보인다", async () => {
    renderDocumentArchive("DOCUMENTS_BY_RECORDING");

    expect(getTab("녹음별")).toHaveAttribute("aria-selected", "true");
    expect(getTab("주제별")).toHaveAttribute("aria-selected", "false");

    const panel = screen.getByRole("tabpanel", { name: "녹음별" });

    expect(
      await within(panel).findByRole("region", {
        name: expectedRecordings.items[0].title,
      }),
    ).toBeInTheDocument();
  });

  it("「주제별」 주소에서는 「주제별」 탭이 골라져 있고, 주제 폴더 목록이 보인다", async () => {
    renderDocumentArchive("DOCUMENTS_BY_TOPIC");

    expect(getTab("주제별")).toHaveAttribute("aria-selected", "true");

    const panel = screen.getByRole("tabpanel", { name: "주제별" });

    expect(
      await within(panel).findByRole("region", {
        name: documentsResponse.topics[0].topic,
      }),
    ).toBeInTheDocument();
  });

  it("「주제별」 탭을 누르면 주제별 주소로 가고 주제 폴더 목록으로 바뀐다", async () => {
    renderDocumentArchive("DOCUMENTS_BY_RECORDING");

    fireEvent.click(getTab("주제별"));

    expect(screen.getByTestId("pathname")).toHaveTextContent(
      pathOf("DOCUMENTS_BY_TOPIC"),
    );
    expect(getTab("주제별")).toHaveAttribute("aria-selected", "true");
    expect(
      await screen.findByRole("region", {
        name: documentsResponse.topics[0].topic,
      }),
    ).toBeInTheDocument();
  });

  it("탭을 바꾼 뒤 뒤로 가면 보던 탭으로 돌아온다", async () => {
    const router = renderDocumentArchive("DOCUMENTS_BY_RECORDING");

    fireEvent.click(getTab("주제별"));
    expect(screen.getByTestId("pathname")).toHaveTextContent(
      pathOf("DOCUMENTS_BY_TOPIC"),
    );

    await router.navigate(-1);

    await waitFor(() =>
      expect(getTab("녹음별")).toHaveAttribute("aria-selected", "true"),
    );
    expect(screen.getByTestId("pathname")).toHaveTextContent(
      pathOf("DOCUMENTS_BY_RECORDING"),
    );
  });

  it("이미 고른 탭을 다시 눌러도 주소가 바뀌지 않는다", () => {
    const router = renderDocumentArchive("DOCUMENTS_BY_RECORDING");
    // 주소가 바뀌면 location.key가 달라져요. 같은 탭을 눌러 push하지 않았는지 이 값으로 봐요
    const locationKey = router.state.location.key;

    fireEvent.click(getTab("녹음별"));

    expect(router.state.location.key).toBe(locationKey);
    expect(screen.getByTestId("pathname")).toHaveTextContent(
      pathOf("DOCUMENTS_BY_RECORDING"),
    );
  });

  it("탭에서 오른쪽 화살표를 누르면 옆 탭으로 옮기며 바로 고른다", () => {
    renderDocumentArchive("DOCUMENTS_BY_RECORDING");

    fireEvent.keyDown(getTab("녹음별"), { key: "ArrowRight" });

    expect(getTab("주제별")).toHaveAttribute("aria-selected", "true");
    expect(screen.getByTestId("pathname")).toHaveTextContent(
      pathOf("DOCUMENTS_BY_TOPIC"),
    );
  });
});
