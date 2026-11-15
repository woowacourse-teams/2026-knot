import {
  GetOwnershipTransferCandidatesResponseDto,
  GetWorkspaceResponseDto,
  GetWorkspacesResponseDto,
} from "@api/dto/workspace";
import { mockServer } from "@api/mock/server";
import { TOAST_MESSAGES } from "@constants/toastMessages";
import {
  ownershipTransferCandidatesResponse,
  workspaceDetailResponse,
  workspacesResponse,
} from "@api/mock/responses/workspace";
import type { WorkspaceDetailResponse } from "@api/mock/types/workspace";
import { ThemeProvider } from "@emotion/react";
import { DialogProvider } from "@provider/context/dialogContext";
import { ToastProvider } from "@provider/context/toastContext";
import { theme } from "@provider/themeProvider";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import { useRecordingStore } from "@store/recordingStore";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import {
  clearRecordingStartProof,
  getRecordingStartProof,
} from "@utils/recordingControlProof";
import { delay, http, HttpResponse } from "msw";
import { createMemoryRouter, Outlet, RouterProvider } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";

import WorkspaceSwitcher from ".";

vi.mock("@utils/captureAnalyticsEvent", () => ({
  captureAnalyticsEvent: vi.fn(),
}));

const {
  workspaces: [currentWorkspace, otherWorkspace],
} = new GetWorkspacesResponseDto(workspacesResponse);

const HOME_PATH = getRouterPath({
  routeKey: "WORKSPACE_HOME",
  params: { workspaceId: String(currentWorkspace.id) },
});
const OTHER_HOME_PATH = getRouterPath({
  routeKey: "WORKSPACE_HOME",
  params: { workspaceId: String(otherWorkspace.id) },
});
const END_RECORDING_DIALOG = "녹음을 끝내고 이동할까요?";
const LEAVE_DIALOG = "워크스페이스에서 나갈까요?";
const TRANSFER_DIALOG = "누구에게 owner를 넘길까요?";
const DELETE_DIALOG = "워크스페이스를 삭제할까요?";
const LEAVE_API = "*/api/v1/workspaces/:workspaceId/members/me";
const TRANSFER_API = "*/api/v1/workspaces/:workspaceId/ownership-transfers";
const CANDIDATES_API =
  "*/api/v1/workspaces/:workspaceId/ownership-transfer-candidates";
const CANDIDATES_LOAD_FAILED_TEXT =
  "멤버 목록을 불러오지 못했어요. 다시 시도해 주세요.";
const expectedWorkspace = new GetWorkspaceResponseDto(workspaceDetailResponse);
const { candidates } = new GetOwnershipTransferCandidatesResponseDto(
  ownershipTransferCandidatesResponse,
);
const [firstMember, secondMember] = candidates;

const renderSwitcher = () => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const router = createMemoryRouter(
    [
      {
        element: (
          <ToastProvider>
            <Outlet />
          </ToastProvider>
        ),
        children: [
          { path: PATH_ROUTE.WORKSPACE_HOME, element: <WorkspaceSwitcher /> },
          { path: PATH_ROUTE.WORKSPACE_CREATE, element: null },
          { path: PATH_ROUTE.HOME, element: null },
        ],
      },
    ],
    { initialEntries: [HOME_PATH] },
  );

  render(
    <ThemeProvider theme={theme}>
      <QueryClientProvider client={queryClient}>
        <DialogProvider>
          <RouterProvider router={router} />
        </DialogProvider>
      </QueryClientProvider>
    </ThemeProvider>,
  );

  return { router, queryClient };
};

const getMenuTrigger = () =>
  screen.getByRole("button", { name: "워크스페이스 메뉴" });

/** 메뉴를 열고 목록이 그려질 때까지 기다려요 */
const openMenu = async () => {
  fireEvent.click(getMenuTrigger());

  return screen.findByRole("region", { name: "워크스페이스 메뉴" });
};

const findWorkspaceItem = (name: string) =>
  screen.findByRole("button", { name });

describe("WorkspaceSwitcher", () => {
  afterEach(() => {
    // 전역 저장소라 테스트끼리 녹음이 새지 않도록 처음 상태로 되돌려요
    useRecordingStore.getState().discardRecording();
    clearRecordingStartProof();
    mockServer.events.removeAllListeners();
    vi.mocked(captureAnalyticsEvent).mockClear();
  });

  /** 녹음은 독에서 증명을 만들어 시작하므로, 같은 순서로 미리 시작해 둬요 */
  const startRecording = async () => {
    getRecordingStartProof();

    await act(async () => {
      await useRecordingStore.getState().connectMicrophone();
      useRecordingStore.getState().startRecording({
        workspaceId: currentWorkspace.id,
        recordingId: 10,
      });
    });
  };

  it("워크스페이스 이름을 누르면 메뉴를 열고, 다시 누르면 닫는다", async () => {
    renderSwitcher();

    expect(getMenuTrigger()).toHaveAttribute("aria-expanded", "false");

    const menu = await openMenu();

    expect(getMenuTrigger()).toHaveAttribute("aria-expanded", "true");
    expect(within(menu).getByText("워크스페이스")).toBeInTheDocument();
    expect(
      within(menu).getByRole("button", { name: "새 워크스페이스 만들기" }),
    ).toBeInTheDocument();
    expect(
      within(menu).getByRole("button", { name: "워크스페이스 나가기" }),
    ).toBeInTheDocument();

    fireEvent.click(getMenuTrigger());

    expect(
      screen.queryByRole("region", { name: "워크스페이스 메뉴" }),
    ).not.toBeInTheDocument();
  });

  it("내 워크스페이스 목록을 보여주고 지금 워크스페이스를 표시한다", async () => {
    renderSwitcher();
    await openMenu();

    expect(await findWorkspaceItem(currentWorkspace.name)).toHaveAttribute(
      "aria-current",
      "true",
    );
    expect(await findWorkspaceItem(otherWorkspace.name)).not.toHaveAttribute(
      "aria-current",
    );
  });

  it("바깥을 누르면 메뉴를 닫는다", async () => {
    renderSwitcher();
    await openMenu();

    fireEvent.pointerDown(document.body);

    expect(
      screen.queryByRole("region", { name: "워크스페이스 메뉴" }),
    ).not.toBeInTheDocument();
  });

  it("ESC를 누르면 메뉴를 닫는다", async () => {
    renderSwitcher();
    await openMenu();

    fireEvent.keyDown(document, { key: "Escape" });

    expect(
      screen.queryByRole("region", { name: "워크스페이스 메뉴" }),
    ).not.toBeInTheDocument();
  });

  it("다른 워크스페이스를 고르면 그 워크스페이스 홈으로 이동하고 메뉴를 닫는다", async () => {
    const { router } = renderSwitcher();
    await openMenu();

    fireEvent.click(await findWorkspaceItem(otherWorkspace.name));

    expect(router.state.location.pathname).toBe(OTHER_HOME_PATH);
    expect(
      screen.queryByRole("region", { name: "워크스페이스 메뉴" }),
    ).not.toBeInTheDocument();
  });

  it("다른 워크스페이스로 옮기면 「워크스페이스를 옮겼어요」 토스트를 띄운다 (WS-09)", async () => {
    renderSwitcher();
    await openMenu();

    fireEvent.click(await findWorkspaceItem(otherWorkspace.name));

    expect(
      await screen.findByText(TOAST_MESSAGES.WORKSPACE_CHANGED.message),
    ).toBeInTheDocument();
  });

  it("지금 워크스페이스를 고르면 이동하지 않고 메뉴만 닫는다", async () => {
    const { router } = renderSwitcher();
    await openMenu();

    fireEvent.click(await findWorkspaceItem(currentWorkspace.name));

    expect(router.state.location.pathname).toBe(HOME_PATH);
    expect(
      screen.queryByRole("region", { name: "워크스페이스 메뉴" }),
    ).not.toBeInTheDocument();
  });

  it("새 워크스페이스 만들기를 고르면 생성 화면으로 이동한다", async () => {
    const { router } = renderSwitcher();
    await openMenu();

    fireEvent.click(
      screen.getByRole("button", { name: "새 워크스페이스 만들기" }),
    );

    expect(router.state.location.pathname).toBe(PATH_ROUTE.WORKSPACE_CREATE);
  });

  describe("녹음 중이면", () => {
    it("다른 워크스페이스를 고를 때 녹음을 끝낼지 묻고 이동하지 않는다", async () => {
      await startRecording();
      const { router } = renderSwitcher();
      await openMenu();

      fireEvent.click(await findWorkspaceItem(otherWorkspace.name));

      expect(
        screen.getByRole("dialog", { name: END_RECORDING_DIALOG }),
      ).toBeInTheDocument();
      expect(router.state.location.pathname).toBe(HOME_PATH);
    });

    it("일시정지 중이어도 묻는다", async () => {
      await startRecording();
      await act(() => useRecordingStore.getState().pauseRecording());
      renderSwitcher();
      await openMenu();

      fireEvent.click(await findWorkspaceItem(otherWorkspace.name));

      expect(
        screen.getByRole("dialog", { name: END_RECORDING_DIALOG }),
      ).toBeInTheDocument();
    });

    it("끝내고 이동을 누르면 녹음을 끝내 올린 뒤 그 워크스페이스로 이동한다", async () => {
      const count = { end: 0, complete: 0 };
      mockServer.events.on("request:start", ({ request }) => {
        if (request.url.endsWith("/end")) count.end += 1;
        if (request.url.endsWith("/audio-upload-complete")) count.complete += 1;
      });
      await startRecording();
      const { router } = renderSwitcher();
      await openMenu();
      fireEvent.click(await findWorkspaceItem(otherWorkspace.name));

      fireEvent.click(screen.getByRole("button", { name: "끝내고 이동" }));

      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      await waitFor(() =>
        expect(router.state.location.pathname).toBe(OTHER_HOME_PATH),
      );
      expect(useRecordingStore.getState().status).toBe("idle");
      expect(count).toEqual({ end: 1, complete: 1 });
      expect(
        await screen.findByText(TOAST_MESSAGES.WORKSPACE_CHANGED.message),
      ).toBeInTheDocument();
    });

    it("끝내고 이동으로 끝낸 녹음은 워크스페이스를 옮기기 전 확인 창에서 끝냈다고 사용 기록을 남긴다", async () => {
      await startRecording();
      const { router } = renderSwitcher();
      await openMenu();
      fireEvent.click(await findWorkspaceItem(otherWorkspace.name));

      fireEvent.click(screen.getByRole("button", { name: "끝내고 이동" }));

      await waitFor(() =>
        expect(router.state.location.pathname).toBe(OTHER_HOME_PATH),
      );
      expect(captureAnalyticsEvent).toHaveBeenCalledWith({
        event: "recording_ended",
        properties: expect.objectContaining({
          surface: "workspace_move",
          recording_id: 10,
          workspace_id: currentWorkspace.id,
        }),
      });
    });

    it("끝내고 이동에서 오디오를 끝내 올리지 못하면 그대로 이동하며 주의 토스트로 알린다", async () => {
      mockServer.use(
        http.post(
          "*/api/v1/workspaces/:workspaceId/recordings/:recordingId/audio-upload-url",
          () => new HttpResponse(null, { status: 500 }),
        ),
      );
      vi.spyOn(console, "error").mockImplementation(() => {});
      await startRecording();
      const { router } = renderSwitcher();
      await openMenu();
      fireEvent.click(await findWorkspaceItem(otherWorkspace.name));

      fireEvent.click(screen.getByRole("button", { name: "끝내고 이동" }));

      await waitFor(() =>
        expect(router.state.location.pathname).toBe(OTHER_HOME_PATH),
      );
      expect(
        await screen.findByText(TOAST_MESSAGES.RECORDING_UPLOAD_FAILED.message),
      ).toBeInTheDocument();
    });

    it("종료 요청이 실패하면 이동하지 않고 녹음을 이어 가며 오류 토스트로 알린다 (ERR-21)", async () => {
      mockServer.use(
        http.post(
          "*/api/v1/workspaces/:workspaceId/recordings/:recordingId/end",
          () => new HttpResponse(null, { status: 500 }),
        ),
      );
      vi.spyOn(console, "error").mockImplementation(() => {});
      await startRecording();
      const { router } = renderSwitcher();
      await openMenu();
      fireEvent.click(await findWorkspaceItem(otherWorkspace.name));

      fireEvent.click(screen.getByRole("button", { name: "끝내고 이동" }));

      await waitFor(() =>
        expect(useRecordingStore.getState().isEnding).toBe(false),
      );
      expect(useRecordingStore.getState().status).toBe("recording");
      expect(router.state.location.pathname).toBe(HOME_PATH);
      expect(
        await screen.findByText(TOAST_MESSAGES.RECORDING_END_FAILED.message),
      ).toBeInTheDocument();
    });

    it("취소를 누르면 녹음과 화면을 그대로 둔다", async () => {
      await startRecording();
      const { router } = renderSwitcher();
      await openMenu();
      fireEvent.click(await findWorkspaceItem(otherWorkspace.name));

      fireEvent.click(screen.getByRole("button", { name: "취소" }));

      expect(useRecordingStore.getState().status).toBe("recording");
      expect(router.state.location.pathname).toBe(HOME_PATH);
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });

    it("새 워크스페이스 만들기도 묻고, 끝내고 이동하면 생성 화면으로 간다", async () => {
      await startRecording();
      const { router } = renderSwitcher();
      await openMenu();

      fireEvent.click(
        screen.getByRole("button", { name: "새 워크스페이스 만들기" }),
      );
      expect(router.state.location.pathname).toBe(HOME_PATH);

      fireEvent.click(screen.getByRole("button", { name: "끝내고 이동" }));

      await waitFor(() =>
        expect(router.state.location.pathname).toBe(
          PATH_ROUTE.WORKSPACE_CREATE,
        ),
      );
      expect(useRecordingStore.getState().status).toBe("idle");
    });

    it("지금 워크스페이스를 고르면 묻지 않고 메뉴만 닫는다", async () => {
      await startRecording();
      renderSwitcher();
      await openMenu();

      fireEvent.click(await findWorkspaceItem(currentWorkspace.name));

      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(useRecordingStore.getState().status).toBe("recording");
    });
  });

  describe("워크스페이스 나가기를 고르면", () => {
    /** 워크스페이스 조회 응답의 내 역할과 멤버 수를 이 테스트에서만 바꿔요 */
    const respondWorkspace = (workspace: Partial<WorkspaceDetailResponse>) =>
      mockServer.use(
        http.get("*/api/v1/workspaces/:workspaceId", () =>
          HttpResponse.json({ ...workspaceDetailResponse, ...workspace }),
        ),
      );

    /** 메뉴를 열고 나가기를 눌러요 */
    const clickLeave = async () => {
      // 어떤 창을 띄울지는 워크스페이스 조회 결과로 정하므로 이름이 보일 때까지 기다려요
      await screen.findByText(expectedWorkspace.name);
      await openMenu();

      fireEvent.click(
        screen.getByRole("button", { name: "워크스페이스 나가기" }),
      );
    };

    /** 멤버 선택 창을 찾아요. 승계 후보는 창이 뜬 뒤에 받아 오므로 목록이 그려질 때까지 기다려요 */
    const findTransferDialog = async () => {
      const dialog = screen.getByRole("dialog", { name: TRANSFER_DIALOG });
      await within(dialog).findByRole("radio", { name: firstMember.nickname });

      return dialog;
    };

    /** 다른 멤버가 있는 owner로 멤버 선택 창을 띄워요 */
    const openTransferDialog = async () => {
      renderSwitcher();
      await clickLeave();

      return findTransferDialog();
    };

    it("일반 멤버에게는 나가기 확인 창을 띄우고 메뉴를 닫는다", async () => {
      respondWorkspace({ myRole: "MEMBER" });
      renderSwitcher();

      await clickLeave();

      const dialog = screen.getByRole("dialog", { name: LEAVE_DIALOG });
      expect(dialog).toHaveTextContent("다시 들어오려면 초대 코드가 필요해요.");
      expect(
        within(dialog).getByRole("button", { name: "나가기" }),
      ).toBeInTheDocument();
      expect(
        screen.queryByRole("region", { name: "워크스페이스 메뉴" }),
      ).not.toBeInTheDocument();
    });

    it("혼자 남은 워크스페이스에서는 워크스페이스도 함께 삭제된다고 알린다", async () => {
      respondWorkspace({ activeMemberCount: 1 });
      renderSwitcher();

      await clickLeave();

      expect(
        screen.getByRole("dialog", { name: LEAVE_DIALOG }),
      ).toHaveTextContent("남은 멤버가 없어 워크스페이스도 함께 삭제돼요.");
    });

    it("다른 멤버가 있는 owner에게는 나를 뺀 멤버 목록을 띄우고, 고르기 전에는 넘기고 나가기를 막는다", async () => {
      const dialog = await openTransferDialog();

      expect(within(dialog).getAllByRole("radio")).toHaveLength(
        candidates.length,
      );
      expect(
        within(dialog).getByRole("radio", { name: firstMember.nickname }),
      ).not.toBeChecked();
      expect(
        within(dialog).getByRole("button", { name: "넘기고 나가기" }),
      ).toBeDisabled();
    });

    it.each([
      ["서버 오류(500)", () => new HttpResponse(null, { status: 500 })],
      ["네트워크 오류", () => HttpResponse.error()],
    ])(
      "%s로 넘길 멤버를 불러오지 못하면 창 안의 목록 자리에 실패 안내와 다시 시도를 보여준다",
      async (_, respond) => {
        mockServer.use(http.get(CANDIDATES_API, respond));
        renderSwitcher();
        await clickLeave();

        const dialog = screen.getByRole("dialog", { name: TRANSFER_DIALOG });
        expect(await within(dialog).findByRole("alert")).toHaveTextContent(
          CANDIDATES_LOAD_FAILED_TEXT,
        );
        expect(
          within(dialog).getByRole("button", { name: "다시 시도" }),
        ).toBeInTheDocument();
        expect(within(dialog).queryByRole("radio")).not.toBeInTheDocument();
        expect(
          within(dialog).getByRole("button", { name: "넘기고 나가기" }),
        ).toBeDisabled();
        // 넘길 멤버를 못 받아도 삭제하기와 취소는 그대로 쓸 수 있어요
        expect(
          within(dialog).getByRole("button", { name: "워크스페이스 삭제하기" }),
        ).toBeInTheDocument();
      },
    );

    it("넘길 멤버의 다시 시도를 누르면 멤버만 다시 받아 목록을 보여준다", async () => {
      let attempts = 0;
      mockServer.use(
        http.get(CANDIDATES_API, () => {
          attempts += 1;
          if (attempts === 1) return new HttpResponse(null, { status: 500 });

          return HttpResponse.json(ownershipTransferCandidatesResponse);
        }),
      );
      renderSwitcher();
      await clickLeave();
      const dialog = screen.getByRole("dialog", { name: TRANSFER_DIALOG });

      fireEvent.click(
        await within(dialog).findByRole("button", { name: "다시 시도" }),
      );

      expect(
        await within(dialog).findByRole("radio", {
          name: firstMember.nickname,
        }),
      ).toBeInTheDocument();
      expect(within(dialog).queryByRole("alert")).not.toBeInTheDocument();
      expect(attempts).toBe(2);
    });

    it("멤버를 고르면 넘기고 나가기를 누를 수 있다", async () => {
      const dialog = await openTransferDialog();

      fireEvent.click(
        within(dialog).getByRole("radio", { name: secondMember.nickname }),
      );

      expect(
        within(dialog).getByRole("radio", { name: secondMember.nickname }),
      ).toBeChecked();
      expect(
        within(dialog).getByRole("button", { name: "넘기고 나가기" }),
      ).toBeEnabled();
    });

    it("워크스페이스 삭제하기를 누르면 삭제 확인 창으로 바뀐다", async () => {
      const dialog = await openTransferDialog();

      fireEvent.click(
        within(dialog).getByRole("button", { name: "워크스페이스 삭제하기" }),
      );

      expect(
        screen.getByRole("dialog", { name: DELETE_DIALOG }),
      ).toHaveTextContent("문서와 녹음도 함께 사라져요.");
      expect(
        screen.queryByRole("dialog", { name: TRANSFER_DIALOG }),
      ).not.toBeInTheDocument();
    });

    it("삭제 확인 창에서 취소하면 고른 멤버가 남은 멤버 선택 창으로 돌아온다", async () => {
      const dialog = await openTransferDialog();
      fireEvent.click(
        within(dialog).getByRole("radio", { name: firstMember.nickname }),
      );
      fireEvent.click(
        within(dialog).getByRole("button", { name: "워크스페이스 삭제하기" }),
      );

      fireEvent.click(screen.getByRole("button", { name: "취소" }));

      expect(
        screen.queryByRole("dialog", { name: DELETE_DIALOG }),
      ).not.toBeInTheDocument();
      expect(
        within(screen.getByRole("dialog", { name: TRANSFER_DIALOG })).getByRole(
          "radio",
          { name: firstMember.nickname },
        ),
      ).toBeChecked();
    });

    it("삭제 확인 창에서 ESC를 눌러도 멤버 선택 창으로 돌아온다", async () => {
      const dialog = await openTransferDialog();
      fireEvent.click(
        within(dialog).getByRole("button", { name: "워크스페이스 삭제하기" }),
      );

      fireEvent.keyDown(document, { key: "Escape" });

      expect(
        screen.getByRole("dialog", { name: TRANSFER_DIALOG }),
      ).toBeInTheDocument();
    });

    it("멤버를 고르기 전에는 바깥을 눌러 멤버 선택 창을 닫을 수 있다", async () => {
      const dialog = await openTransferDialog();

      fireEvent.click(dialog.parentElement!);

      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });

    it("멤버를 고른 뒤에는 바깥을 눌러도 멤버 선택 창을 닫지 않는다", async () => {
      const dialog = await openTransferDialog();
      fireEvent.click(
        within(dialog).getByRole("radio", { name: firstMember.nickname }),
      );

      fireEvent.click(dialog.parentElement!);

      expect(
        screen.getByRole("dialog", { name: TRANSFER_DIALOG }),
      ).toBeInTheDocument();
    });

    it("멤버 선택 창에서 ESC를 누르면 멤버를 골랐어도 창을 닫는다", async () => {
      const dialog = await openTransferDialog();
      fireEvent.click(
        within(dialog).getByRole("radio", { name: firstMember.nickname }),
      );

      fireEvent.keyDown(document, { key: "Escape" });

      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });

    it("나가기 확인 창에서 취소하면 창만 닫는다", async () => {
      respondWorkspace({ myRole: "MEMBER" });
      const { router } = renderSwitcher();
      await clickLeave();

      fireEvent.click(screen.getByRole("button", { name: "취소" }));

      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(router.state.location.pathname).toBe(HOME_PATH);
    });

    it("나가기를 누르면 탈퇴를 요청하고, 성공하면 창을 닫고 진입 분기로 이동한다", async () => {
      const leave = vi.fn();
      mockServer.use(
        http.delete(LEAVE_API, () => {
          leave();
          return new HttpResponse(null, { status: 204 });
        }),
      );
      respondWorkspace({ myRole: "MEMBER" });
      const { router } = renderSwitcher();
      await clickLeave();

      fireEvent.click(screen.getByRole("button", { name: "나가기" }));

      await waitFor(() =>
        expect(router.state.location.pathname).toBe(PATH_ROUTE.HOME),
      );
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(leave).toHaveBeenCalledTimes(1);
    });

    it("나가기 요청이 실패하면 창을 닫지 않고 오류 토스트를 띄운다 (Dialog 규칙 4번)", async () => {
      mockServer.use(
        http.delete(LEAVE_API, () => new HttpResponse(null, { status: 500 })),
      );
      respondWorkspace({ myRole: "MEMBER" });
      const { router } = renderSwitcher();
      await clickLeave();

      fireEvent.click(screen.getByRole("button", { name: "나가기" }));

      expect(
        await screen.findByText(TOAST_MESSAGES.WORKSPACE_LEAVE_FAILED.message),
      ).toBeInTheDocument();
      expect(
        screen.getByRole("dialog", { name: LEAVE_DIALOG }),
      ).toBeInTheDocument();
      expect(router.state.location.pathname).toBe(HOME_PATH);
    });

    it("나가기 요청을 기다리는 동안 다시 눌러도 탈퇴는 한 번만 요청한다", async () => {
      const leave = vi.fn();
      mockServer.use(
        http.delete(LEAVE_API, async () => {
          leave();
          await delay(50);
          return new HttpResponse(null, { status: 500 });
        }),
      );
      respondWorkspace({ myRole: "MEMBER" });
      renderSwitcher();
      await clickLeave();

      fireEvent.click(screen.getByRole("button", { name: "나가기" }));
      fireEvent.click(screen.getByRole("button", { name: "나가기" }));

      await screen.findByText(TOAST_MESSAGES.WORKSPACE_LEAVE_FAILED.message);
      expect(leave).toHaveBeenCalledTimes(1);

      // 실패한 뒤에는 창이 남아 있어 다시 시도할 수 있어요
      fireEvent.click(screen.getByRole("button", { name: "나가기" }));
      await waitFor(() => expect(leave).toHaveBeenCalledTimes(2));
    });

    it("혼자 남은 워크스페이스에서 나가기를 눌러도 탈퇴를 요청한 뒤 진입 분기로 이동한다", async () => {
      respondWorkspace({ activeMemberCount: 1 });
      const { router } = renderSwitcher();
      await clickLeave();

      fireEvent.click(screen.getByRole("button", { name: "나가기" }));

      await waitFor(() =>
        expect(router.state.location.pathname).toBe(PATH_ROUTE.HOME),
      );
    });

    it("멤버를 골라 넘기고 나가기를 누르면 고른 멤버로 승계를 요청하고, 성공하면 창을 닫고 진입 분기로 이동한다", async () => {
      const transfer = vi.fn();
      const leave = vi.fn();
      mockServer.use(
        http.post(TRANSFER_API, async ({ request }) => {
          transfer(await request.json());
          return new HttpResponse(null, { status: 204 });
        }),
        http.delete(LEAVE_API, () => {
          leave();
          return new HttpResponse(null, { status: 204 });
        }),
      );
      const { router } = renderSwitcher();
      await clickLeave();
      const dialog = await findTransferDialog();
      fireEvent.click(
        within(dialog).getByRole("radio", { name: secondMember.nickname }),
      );

      fireEvent.click(
        within(dialog).getByRole("button", { name: "넘기고 나가기" }),
      );

      await waitFor(() =>
        expect(router.state.location.pathname).toBe(PATH_ROUTE.HOME),
      );
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(transfer).toHaveBeenCalledExactlyOnceWith({
        successorMemberId: secondMember.memberId,
      });
      // 승계 요청이 탈퇴까지 처리하므로 탈퇴를 따로 요청하지 않아요
      expect(leave).not.toHaveBeenCalled();
    });

    it("승계 요청이 실패하면 멤버 선택 창을 닫지 않고 오류 토스트를 띄운다 (Dialog 규칙 4번)", async () => {
      mockServer.use(
        http.post(TRANSFER_API, () => new HttpResponse(null, { status: 500 })),
      );
      const { router } = renderSwitcher();
      await clickLeave();
      const dialog = await findTransferDialog();
      fireEvent.click(
        within(dialog).getByRole("radio", { name: secondMember.nickname }),
      );

      fireEvent.click(
        within(dialog).getByRole("button", { name: "넘기고 나가기" }),
      );

      expect(
        await screen.findByText(
          TOAST_MESSAGES.OWNERSHIP_TRANSFER_FAILED.message,
        ),
      ).toBeInTheDocument();
      expect(
        screen.getByRole("dialog", { name: TRANSFER_DIALOG }),
      ).toBeInTheDocument();
      // 고른 멤버도 그대로 남아 바로 다시 누를 수 있어요
      expect(
        within(dialog).getByRole("radio", { name: secondMember.nickname }),
      ).toBeChecked();
      expect(router.state.location.pathname).toBe(HOME_PATH);
    });

    it("삭제 확인 창에서 삭제를 누르면 요청 없이 창만 닫는다", async () => {
      const { router } = renderSwitcher();
      await clickLeave();
      const dialog = await findTransferDialog();
      fireEvent.click(
        within(dialog).getByRole("button", { name: "워크스페이스 삭제하기" }),
      );

      fireEvent.click(screen.getByRole("button", { name: "삭제" }));

      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(router.state.location.pathname).toBe(HOME_PATH);
    });

    describe("녹음 중이면", () => {
      it("나가기를 누르면 탈퇴한 뒤 이 탭의 녹음을 버린다", async () => {
        respondWorkspace({ myRole: "MEMBER" });
        await startRecording();
        renderSwitcher();
        await clickLeave();

        fireEvent.click(screen.getByRole("button", { name: "나가기" }));

        await waitFor(() =>
          expect(useRecordingStore.getState().status).toBe("idle"),
        );
      });

      it("일시정지 중이어도 녹음을 버린다", async () => {
        respondWorkspace({ myRole: "MEMBER" });
        await startRecording();
        await act(() => useRecordingStore.getState().pauseRecording());
        renderSwitcher();
        await clickLeave();

        fireEvent.click(screen.getByRole("button", { name: "나가기" }));

        await waitFor(() =>
          expect(useRecordingStore.getState().status).toBe("idle"),
        );
      });

      it("탈퇴 요청이 실패하면 이동하지 않고 녹음을 그대로 둔다", async () => {
        mockServer.use(
          http.delete(LEAVE_API, () =>
            HttpResponse.json(
              { code: "WORKSPACE_OWNER_TRANSFER_REQUIRED", message: "" },
              { status: 409 },
            ),
          ),
        );
        respondWorkspace({ myRole: "MEMBER" });
        await startRecording();
        const { router, queryClient } = renderSwitcher();
        await clickLeave();

        fireEvent.click(screen.getByRole("button", { name: "나가기" }));

        // 누르자마자 시작한 탈퇴 요청이 실패로 끝날 때까지 기다려요
        expect(queryClient.isMutating()).toBe(1);
        await waitFor(() => expect(queryClient.isMutating()).toBe(0));
        expect(useRecordingStore.getState().status).toBe("recording");
        expect(router.state.location.pathname).toBe(HOME_PATH);
      });

      it("넘기고 나가기를 눌러도 녹음을 버린다", async () => {
        await startRecording();
        const dialog = await openTransferDialog();
        fireEvent.click(
          within(dialog).getByRole("radio", { name: firstMember.nickname }),
        );

        fireEvent.click(
          within(dialog).getByRole("button", { name: "넘기고 나가기" }),
        );

        await waitFor(() =>
          expect(useRecordingStore.getState().status).toBe("idle"),
        );
      });

      it("워크스페이스를 삭제해도 녹음을 버린다", async () => {
        await startRecording();
        const dialog = await openTransferDialog();
        fireEvent.click(
          within(dialog).getByRole("button", { name: "워크스페이스 삭제하기" }),
        );

        fireEvent.click(screen.getByRole("button", { name: "삭제" }));

        expect(useRecordingStore.getState().status).toBe("idle");
      });

      it("취소하면 녹음을 그대로 둔다", async () => {
        respondWorkspace({ myRole: "MEMBER" });
        await startRecording();
        renderSwitcher();
        await clickLeave();

        fireEvent.click(screen.getByRole("button", { name: "취소" }));

        expect(useRecordingStore.getState().status).toBe("recording");
      });
    });
  });
});
