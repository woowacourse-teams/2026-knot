import { PostWorkspaceResponseDto } from "@api/dto/workspace";
import { WORKSPACES_API_PATH } from "@api/fetch/api/v1/workspaces";
import { workspaceCreateResponse } from "@api/mock/responses/workspace";
import { mockServer } from "@api/mock/server";
import { ThemeProvider } from "@emotion/react";
import { theme } from "@provider/themeProvider";
import { PATH_ROUTE } from "@routes/PATH_ROUTE";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { delay, http, HttpResponse } from "msw";
import { createMemoryRouter, RouterProvider } from "react-router";
import { describe, expect, it } from "vitest";

import CreateWorkspaceCard from ".";

const expected = new PostWorkspaceResponseDto(workspaceCreateResponse);
const VALID_NAME = "Knot 팀";
const ELSEWHERE_PATH = "/elsewhere";
const FORMAT_ERROR_MESSAGE = "한글, 영어, 숫자와 공백만 사용할 수 있어요.";
const UNKNOWN_ERROR_MESSAGE = "잠시 후 다시 시도해 주세요.";

const renderCard = () => {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  const router = createMemoryRouter(
    [
      { path: ELSEWHERE_PATH, element: <p>다른 화면</p> },
      { path: PATH_ROUTE.WORKSPACE_CREATE, element: <CreateWorkspaceCard /> },
      { path: PATH_ROUTE.WORKSPACE_INVITE, element: <p>팀원 초대</p> },
      { path: PATH_ROUTE.LOGIN, element: <p>로그인</p> },
    ],
    { initialEntries: [ELSEWHERE_PATH, PATH_ROUTE.WORKSPACE_CREATE] },
  );

  render(
    <ThemeProvider theme={theme}>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </ThemeProvider>,
  );

  const input = screen.getByRole("textbox", { name: "워크스페이스 이름" });
  const submitButton = screen.getByRole("button", {
    name: "워크스페이스 생성",
  });

  return { router, input, submitButton };
};

const typeName = (input: HTMLElement, value: string) => {
  fireEvent.change(input, { target: { value } });
};

const overrideCreateStatus = (status: number) => {
  mockServer.use(
    http.post(
      `*${WORKSPACES_API_PATH}`,
      () => new HttpResponse(null, { status }),
    ),
  );
};

const holdCreateResponse = () => {
  mockServer.use(
    http.post(`*${WORKSPACES_API_PATH}`, async () => {
      await delay("infinite");
      return HttpResponse.json(workspaceCreateResponse, { status: 201 });
    }),
  );
};

const goBack = async (router: ReturnType<typeof createMemoryRouter>) => {
  await act(async () => {
    await router.navigate(-1);
  });
};

describe("CreateWorkspaceCard", () => {
  it("유효한 이름을 제출하면 응답의 id로 팀원 초대 화면에 이동한다", async () => {
    const { router, input, submitButton } = renderCard();

    typeName(input, VALID_NAME);
    fireEvent.click(submitButton);

    await waitFor(() => {
      expect(router.state.location.pathname).toBe(
        `/workspace/${expected.id}/invite`,
      );
    });
  });

  it("요청 중에는 버튼이 로딩 상태로 잠긴다", async () => {
    holdCreateResponse();
    const { input, submitButton } = renderCard();

    typeName(input, VALID_NAME);
    fireEvent.click(submitButton);

    await waitFor(() => {
      expect(submitButton).toHaveAttribute("aria-busy", "true");
    });
    expect(submitButton).toBeDisabled();
  });

  it("400이면 입력 아래에 형식 문구를 보여주고 이동하지 않는다", async () => {
    overrideCreateStatus(400);
    const { router, input, submitButton } = renderCard();

    typeName(input, VALID_NAME);
    fireEvent.click(submitButton);

    expect(await screen.findByText(FORMAT_ERROR_MESSAGE)).toBeInTheDocument();
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(submitButton).toBeDisabled();
    expect(router.state.location.pathname).toBe(PATH_ROUTE.WORKSPACE_CREATE);
  });

  it("서버 문구는 값을 고치면 사라진다", async () => {
    overrideCreateStatus(400);
    const { input, submitButton } = renderCard();

    typeName(input, VALID_NAME);
    fireEvent.click(submitButton);
    await screen.findByText(FORMAT_ERROR_MESSAGE);
    typeName(input, `${VALID_NAME}원`);

    expect(screen.queryByText(FORMAT_ERROR_MESSAGE)).not.toBeInTheDocument();
    expect(input).toHaveAttribute("aria-invalid", "false");
    expect(submitButton).toBeEnabled();
  });

  it("그 외 실패면 잠시 후 다시 시도 문구를 보여준다", async () => {
    overrideCreateStatus(500);
    const { router, input, submitButton } = renderCard();

    typeName(input, VALID_NAME);
    fireEvent.click(submitButton);

    expect(await screen.findByText(UNKNOWN_ERROR_MESSAGE)).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(PATH_ROUTE.WORKSPACE_CREATE);
  });

  it("401이면 로그인 화면으로 replace 이동한다", async () => {
    overrideCreateStatus(401);
    const { router, input, submitButton } = renderCard();

    typeName(input, VALID_NAME);
    fireEvent.click(submitButton);

    await waitFor(() => {
      expect(router.state.location.pathname).toBe(PATH_ROUTE.LOGIN);
    });

    await goBack(router);

    expect(router.state.location.pathname).toBe(ELSEWHERE_PATH);
  });
});
