import { ThemeProvider } from "@emotion/react";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { StrictMode, useEffect, useLayoutEffect, useState } from "react";
import {
  BrowserRouter,
  createMemoryRouter,
  MemoryRouter,
  Navigate,
  Outlet,
  Route,
  Routes,
  RouterProvider,
  useBlocker,
  useNavigate,
} from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { theme } from "@provider/themeProvider";
import AppProviders from "@provider/AppProviders";

import { ToastProvider, ToastViewport, usePinnedToast, useToast } from ".";

function RedirectToastTrigger() {
  const { navigateWithToast } = useToast();

  return (
    <button
      type="button"
      onClick={() =>
        navigateWithToast({
          to: "/guarded",
          toast: { variant: "caution", message: "이동 안내" },
        })
      }
    >
      가드 화면으로 이동
    </button>
  );
}

function LayoutRedirect() {
  const navigate = useNavigate();
  useLayoutEffect(() => {
    navigate("/target", { replace: true });
  }, [navigate]);
  return null;
}

describe("토스트를 전달한 화면의 즉시 리다이렉트", () => {
  for (const isStrictMode of [false, true]) {
    for (const isLayoutRedirect of [false, true]) {
      it(`가드의 이동을 유지한다 (StrictMode=${isStrictMode}, layout=${isLayoutRedirect})`, async () => {
        window.history.replaceState(null, "", "/start");
        const app = (
          <ThemeProvider theme={theme}>
            <BrowserRouter>
              <ToastProvider>
                <Routes>
                  <Route path="/start" element={<RedirectToastTrigger />} />
                  <Route
                    path="/guarded"
                    element={
                      isLayoutRedirect ? (
                        <LayoutRedirect />
                      ) : (
                        <Navigate to="/target" replace />
                      )
                    }
                  />
                  <Route path="/target" element={<p>최종 도착 화면</p>} />
                </Routes>
              </ToastProvider>
            </BrowserRouter>
          </ThemeProvider>
        );
        render(isStrictMode ? <StrictMode>{app}</StrictMode> : app);

        await act(async () => {
          fireEvent.click(
            screen.getByRole("button", { name: "가드 화면으로 이동" }),
          );
        });

        expect(window.location.pathname).toBe("/target");
        expect(screen.getByText("최종 도착 화면")).toBeInTheDocument();
        expect(window.history.state.usr).toBeNull();
      });
    }
  }
});

const SAVED = "녹음을 저장했어요";
const COPIED = "초대 링크를 복사했어요";
const SYNCED = "노션과 동기화했어요";
const RENAMED = "대화 이름을 바꿨어요";

const SUCCESS_DURATION_MS = 5000;
const CAUTION_DURATION_MS = 8000;
const ERROR_DURATION_MS = 8000;
const LEAVE_DURATION_MS = 250;

function ToastTriggerPage() {
  const { show } = useToast();

  return (
    <>
      {[SAVED, COPIED, SYNCED, RENAMED].map((message) => (
        <button
          key={message}
          type="button"
          onClick={() => show({ variant: "success", message })}
        >
          {message} 알리기
        </button>
      ))}
      <button
        type="button"
        onClick={() => show({ variant: "caution", message: SAVED })}
      >
        {SAVED} 주의로 알리기
      </button>
      <button
        type="button"
        onClick={() => show({ variant: "error", message: SAVED })}
      >
        {SAVED} 오류로 알리기
      </button>
    </>
  );
}

const renderToastTriggerPage = () =>
  render(
    <ThemeProvider theme={theme}>
      <MemoryRouter>
        <ToastProvider>
          <ToastTriggerPage />
        </ToastProvider>
      </MemoryRouter>
    </ThemeProvider>,
  );

const clickShow = (message: string) =>
  fireEvent.click(screen.getByRole("button", { name: `${message} 알리기` }));

const advanceTimers = (ms: number) => {
  act(() => {
    vi.advanceTimersByTime(ms);
  });
};

// 목록 상자에 아직 role이 없어 testid로 찾아요
const getToastList = () => screen.getByTestId("toast-list");

const getToastSlots = () => Array.from(getToastList().children);

// 사라지는 중인 토스트는 낭독기에서 숨겨 둬서, 떠 있는 토스트와 aria-hidden으로 구분해요
const isLeaving = (slot: Element) =>
  slot.getAttribute("aria-hidden") === "true";

const getShownMessages = () =>
  getToastSlots()
    .filter((slot) => !isLeaving(slot))
    .map((slot) => slot.textContent);

const getLeavingMessages = () =>
  getToastSlots()
    .filter(isLeaving)
    .map((slot) => slot.textContent);

const PINNED_LOST = { variant: "caution", message: "끊김" } as const;
const PINNED_ENDING = { variant: "caution", message: "끝내는 중" } as const;
const CLOSE_TOAST = "닫기";

/** 고정 토스트 값을 버튼으로 바꿔요. 화면을 옮겨도 남도록 라우터 바깥쪽에 둬요 */
function PinnedToastControls({ pinKey = "connection" }: { pinKey?: string }) {
  const [toast, setToast] = useState<
    typeof PINNED_LOST | typeof PINNED_ENDING | null
  >(null);

  usePinnedToast({ pinKey, toast });

  return (
    <>
      <button type="button" onClick={() => setToast(PINNED_LOST)}>
        {pinKey} 끊김 고정
      </button>
      <button type="button" onClick={() => setToast(PINNED_ENDING)}>
        {pinKey} 끝내는 중 고정
      </button>
      <button type="button" onClick={() => setToast(null)}>
        {pinKey} 고정 풀기
      </button>
    </>
  );
}

const renderPinnedToastPage = () =>
  render(
    <ThemeProvider theme={theme}>
      <MemoryRouter>
        <ToastProvider>
          <ToastTriggerPage />
          <PinnedToastControls />
          <PinnedToastControls pinKey="otherTab" />
        </ToastProvider>
      </MemoryRouter>
    </ThemeProvider>,
  );

const clickButton = (name: string) =>
  fireEvent.click(screen.getByRole("button", { name }));

const closePinnedToast = () => {
  clickButton(CLOSE_TOAST);
  advanceTimers(LEAVE_DURATION_MS);
};

describe("ToastProvider", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("[ERR-R5] show로 띄운 토스트가 보인다", () => {
    renderToastTriggerPage();

    clickShow(SAVED);

    expect(within(getToastList()).getByText(SAVED)).toBeVisible();
  });

  it("[공통 UI 규칙·여러 개] 4개를 띄우면 3개만 보이고 가장 먼저 띄운 토스트가 사라진다", () => {
    renderToastTriggerPage();

    clickShow(SAVED);
    clickShow(COPIED);
    clickShow(SYNCED);
    clickShow(RENAMED);

    expect(getShownMessages()).toHaveLength(3);
    expect(within(getToastList()).queryByText(SAVED)).not.toBeInTheDocument();
    // 밀려난 토스트의 시간도 정리돼서 떠 있는 3개의 시간만 남아요
    expect(vi.getTimerCount()).toBe(3);
  });

  it("[공통 UI 규칙·여러 개] 가장 최근 토스트가 맨 아래(독에 가장 가까이)에 놓인다", () => {
    renderToastTriggerPage();

    clickShow(SAVED);
    clickShow(COPIED);
    clickShow(SYNCED);

    expect(getShownMessages()).toEqual([SAVED, COPIED, SYNCED]);
  });

  it("[공통 UI 규칙·여러 개] 같은 종류·문구를 다시 띄우면 쌓이지 않고 하나만 보인다", () => {
    renderToastTriggerPage();

    clickShow(SAVED);
    clickShow(SAVED);

    expect(within(getToastList()).getAllByText(SAVED)).toHaveLength(1);
  });

  it("[공통 UI 규칙·여러 개] 같은 종류·문구를 다시 띄워도 떠 있는 토스트는 제자리에 있다", () => {
    renderToastTriggerPage();

    clickShow(SAVED);
    clickShow(COPIED);
    clickShow(SAVED);

    expect(getShownMessages()).toEqual([SAVED, COPIED]);
  });

  it("[공통 UI 규칙·여러 개] 문구가 같아도 종류가 다르면 따로 쌓인다", () => {
    renderToastTriggerPage();

    clickShow(SAVED);
    fireEvent.click(
      screen.getByRole("button", { name: `${SAVED} 오류로 알리기` }),
    );

    expect(within(getToastList()).getAllByText(SAVED)).toHaveLength(2);
  });

  it("[공통 UI 규칙·떠 있는 시간] 정상 토스트는 5초 동안 보이고 5초가 지나면 사라진다", () => {
    renderToastTriggerPage();

    clickShow(SAVED);
    advanceTimers(SUCCESS_DURATION_MS - 1);

    expect(within(getToastList()).getByText(SAVED)).toBeVisible();

    advanceTimers(1);

    expect(getShownMessages()).not.toContain(SAVED);
  });

  it("[공통 UI 규칙·떠 있는 시간] 주의 토스트는 8초가 지나면 사라진다", () => {
    renderToastTriggerPage();

    fireEvent.click(
      screen.getByRole("button", { name: `${SAVED} 주의로 알리기` }),
    );
    advanceTimers(CAUTION_DURATION_MS - 1);

    expect(within(getToastList()).getByText(SAVED)).toBeVisible();

    advanceTimers(1);

    expect(getShownMessages()).not.toContain(SAVED);
  });

  it("[공통 UI 규칙·떠 있는 시간] 오류 토스트는 8초가 지나면 사라진다", () => {
    renderToastTriggerPage();

    fireEvent.click(
      screen.getByRole("button", { name: `${SAVED} 오류로 알리기` }),
    );
    advanceTimers(ERROR_DURATION_MS - 1);

    expect(within(getToastList()).getByText(SAVED)).toBeVisible();

    advanceTimers(1);

    expect(getShownMessages()).not.toContain(SAVED);
  });

  it("[공통 UI 규칙·여러 개] 시차를 두고 띄운 토스트는 각자 시간이 지나면 따로 사라진다", () => {
    renderToastTriggerPage();

    clickShow(SAVED);
    advanceTimers(2000);
    clickShow(COPIED);
    advanceTimers(SUCCESS_DURATION_MS - 2000);

    expect(getShownMessages()).toEqual([COPIED]);

    advanceTimers(2000);

    expect(getShownMessages()).toHaveLength(0);
  });

  it("[공통 UI 규칙·여러 개] 같은 토스트를 다시 띄우면 시간이 처음부터 다시 간다", () => {
    renderToastTriggerPage();

    clickShow(SAVED);
    advanceTimers(3000);
    clickShow(SAVED);
    advanceTimers(SUCCESS_DURATION_MS - 1);

    expect(within(getToastList()).getByText(SAVED)).toBeVisible();

    advanceTimers(1);

    expect(getShownMessages()).not.toContain(SAVED);
  });

  it("[공통 UI 규칙·떠 있는 시간] 시간이 다 된 토스트는 사라지는 동안 남아 있다가 목록에서 빠진다", () => {
    renderToastTriggerPage();

    clickShow(SAVED);
    advanceTimers(SUCCESS_DURATION_MS);

    expect(getLeavingMessages()).toEqual([SAVED]);

    advanceTimers(LEAVE_DURATION_MS - 1);

    expect(getLeavingMessages()).toEqual([SAVED]);

    advanceTimers(1);

    expect(within(getToastList()).queryByText(SAVED)).not.toBeInTheDocument();
  });

  it("[공통 UI 규칙·여러 개] 사라지는 중인 토스트는 최대 3개에 세지 않는다", () => {
    renderToastTriggerPage();

    clickShow(SAVED);
    advanceTimers(SUCCESS_DURATION_MS);
    clickShow(COPIED);
    clickShow(SYNCED);
    clickShow(RENAMED);

    expect(getShownMessages()).toEqual([COPIED, SYNCED, RENAMED]);
    expect(getLeavingMessages()).toEqual([SAVED]);
  });

  it("[공통 UI 규칙·여러 개] 사라지는 중에 같은 토스트를 띄우면 새 토스트로 쌓인다", () => {
    renderToastTriggerPage();

    clickShow(SAVED);
    advanceTimers(SUCCESS_DURATION_MS);
    clickShow(SAVED);

    expect(getLeavingMessages()).toEqual([SAVED]);
    expect(getShownMessages()).toEqual([SAVED]);

    advanceTimers(LEAVE_DURATION_MS);

    expect(getShownMessages()).toEqual([SAVED]);
    expect(getLeavingMessages()).toHaveLength(0);
  });

  it("[공통 UI 규칙·접근성] 토스트가 없어도 감싸는 영역은 미리 렌더링된다", () => {
    renderToastTriggerPage();

    expect(getToastList()).toBeInTheDocument();
    expect(getShownMessages()).toHaveLength(0);
  });

  it("프로바이더 밖에서 useToast를 부르면 안내 오류가 난다", () => {
    // 기대한 오류를 React가 콘솔에 한 번 더 찍어서 출력만 막아요
    vi.spyOn(console, "error").mockImplementation(() => {});

    expect(() => render(<ToastTriggerPage />)).toThrow(
      "useToast는 ToastProvider 안에서만 쓸 수 있어요",
    );
  });
});

const WORKSPACE_HOME_PATH = "/workspace/1";
const WORKSPACE_CHAT_PATH = "/workspace/1/chat";
const BLOCKED_PATH = "/workspace/1/blocked";
const LOGIN_PATH = "/login";
const NOTICE_PATH = "/notice";
const SAME_TOAST_NOTICE_PATH = "/same-toast-notice";

const WORKSPACE_HOME_TEXT = "워크스페이스 홈";
const WORKSPACE_CHAT_TEXT = "탐색";
const LOGIN_TEXT = "로그인";
const SESSION_EXPIRED = "로그인이 만료됐어요. 다시 로그인해 주세요";
const WORKSPACE_MOVED = "워크스페이스를 옮겼어요";

/** 독 위에 토스트 자리를 둔 레이아웃 흉내 */
function DockLayout() {
  return (
    <>
      <Outlet />
      <div data-testid="dock-area">
        <ToastViewport placement="inline" />
      </div>
    </>
  );
}

/** 독이 없는 레이아웃 흉내 */
function PlainLayout() {
  return <Outlet />;
}

function RoutePage({ title }: { title: string }) {
  const { show, navigateWithToast } = useToast();
  const navigate = useNavigate();

  return (
    <>
      <p>{title}</p>
      <button
        type="button"
        onClick={() => show({ variant: "success", message: SAVED })}
      >
        {SAVED} 알리기
      </button>
      <button
        type="button"
        onClick={() =>
          navigateWithToast({
            to: WORKSPACE_HOME_PATH,
            toast: { variant: "success", message: WORKSPACE_MOVED },
          })
        }
      >
        옮겼다고 알리며 홈으로 이동
      </button>
      <button type="button" onClick={() => navigate(WORKSPACE_CHAT_PATH)}>
        탐색으로 이동
      </button>
      <button type="button" onClick={() => navigate(LOGIN_PATH)}>
        로그인으로 이동
      </button>
      <button type="button" onClick={() => navigate(NOTICE_PATH)}>
        안내 화면으로 이동
      </button>
      <button
        type="button"
        onClick={() =>
          navigateWithToast({
            to: LOGIN_PATH,
            toast: { variant: "caution", message: SESSION_EXPIRED },
          })
        }
      >
        만료를 알리며 로그인으로 이동
      </button>
      <button
        type="button"
        onClick={() =>
          navigateWithToast({
            to: LOGIN_PATH,
            toast: { variant: "caution", message: SESSION_EXPIRED },
            replace: true,
          })
        }
      >
        만료를 알리며 로그인으로 기록을 바꿔 이동
      </button>
    </>
  );
}

/** 들어오자마자 스스로 토스트를 띄우는 화면 */
function NoticePage() {
  const { show } = useToast();

  useEffect(() => {
    show({ variant: "success", message: WORKSPACE_MOVED });
  }, [show]);

  return <p>안내</p>;
}

function SameToastNoticePage() {
  const { show } = useToast();

  useLayoutEffect(() => {
    show({ variant: "success", message: SAVED });
  }, [show]);

  return <p>저장 안내</p>;
}

function BlockedPage() {
  useBlocker(true);

  return <RoutePage title="이동 확인 중" />;
}

const createToastRouter = (initialPath: string) => {
  const layouts = [
    {
      handle: { hasDock: true },
      element: <DockLayout />,
      children: [
        {
          path: WORKSPACE_HOME_PATH,
          element: <RoutePage title={WORKSPACE_HOME_TEXT} />,
        },
        {
          path: WORKSPACE_CHAT_PATH,
          element: <RoutePage title={WORKSPACE_CHAT_TEXT} />,
        },
        { path: BLOCKED_PATH, element: <BlockedPage /> },
      ],
    },
    {
      element: <PlainLayout />,
      children: [
        { path: LOGIN_PATH, element: <RoutePage title={LOGIN_TEXT} /> },
        { path: NOTICE_PATH, element: <NoticePage /> },
        {
          path: SAME_TOAST_NOTICE_PATH,
          element: <SameToastNoticePage />,
        },
      ],
    },
  ];

  return createMemoryRouter(
    [
      {
        element: (
          <AppProviders routes={layouts}>
            <PinnedToastControls />
            <Outlet />
          </AppProviders>
        ),
        children: layouts,
      },
    ],
    { initialEntries: [initialPath] },
  );
};

type ToastRouter = ReturnType<typeof createToastRouter>;

// 앱 진입점(index.tsx)처럼 StrictMode로 감싸, 개발 모드의 이중 실행에서도 같은 결과인지 봐요
const renderRouter = (router: ToastRouter) =>
  render(
    <StrictMode>
      <ThemeProvider theme={theme}>
        <RouterProvider router={router} />
      </ThemeProvider>
    </StrictMode>,
  );

const renderRoutes = (initialPath: string) => {
  const router = createToastRouter(initialPath);

  return { router, ...renderRouter(router) };
};

// 라우터의 이동은 비동기로 반영돼서 act 안에서 끝날 때까지 기다려요
const clickAndSettle = async (name: string) => {
  await act(async () => {
    fireEvent.click(screen.getByRole("button", { name }));
  });
};

describe("고정 토스트", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("시간이 지나도 사라지지 않고, X를 누르면 닫힌다", () => {
    renderPinnedToastPage();

    clickButton("connection 끊김 고정");
    advanceTimers(CAUTION_DURATION_MS * 10);

    expect(getShownMessages()).toEqual([PINNED_LOST.message]);

    closePinnedToast();

    expect(getShownMessages()).toHaveLength(0);
    expect(getLeavingMessages()).toHaveLength(0);
  });

  it("X만 클릭을 받고, 목록이 덮은 자리의 클릭은 가리지 않는다", () => {
    renderPinnedToastPage();

    clickButton("connection 끊김 고정");

    expect(getToastList()).toHaveStyle({ pointerEvents: "none" });
    expect(screen.getByRole("button", { name: CLOSE_TOAST })).toHaveStyle({
      pointerEvents: "auto",
    });
  });

  it("일반 토스트에는 X가 없다", () => {
    renderPinnedToastPage();

    clickShow(SAVED);

    expect(
      screen.queryByRole("button", { name: CLOSE_TOAST }),
    ).not.toBeInTheDocument();
  });

  it("낭독기가 읽도록 알림 역할을 준다", () => {
    renderPinnedToastPage();

    clickButton("connection 끊김 고정");

    expect(screen.getByRole("status")).toHaveTextContent(PINNED_LOST.message);
  });

  it("일반 토스트가 쌓여 4번째가 와도 밀려나지 않고, 가장 오래된 일반 토스트가 빠진다", () => {
    renderPinnedToastPage();

    clickButton("connection 끊김 고정");
    clickShow(SAVED);
    clickShow(COPIED);
    clickShow(SYNCED);

    expect(getShownMessages()).toEqual([PINNED_LOST.message, COPIED, SYNCED]);
  });

  it("일반 토스트가 3개 떠 있을 때 띄워도 떠 있는 토스트는 3개다", () => {
    renderPinnedToastPage();

    clickShow(SAVED);
    clickShow(COPIED);
    clickShow(SYNCED);
    clickButton("connection 끊김 고정");

    expect(getShownMessages()).toEqual([COPIED, SYNCED, PINNED_LOST.message]);
  });

  it("값이 바뀌면 쌓지 않고 문구를 바꾼다", () => {
    renderPinnedToastPage();

    clickButton("connection 끊김 고정");
    clickButton("connection 끝내는 중 고정");

    expect(getShownMessages()).toEqual([PINNED_ENDING.message]);
    expect(getLeavingMessages()).toHaveLength(0);
  });

  it("X로 닫은 뒤 값이 그대로면 다시 띄우지 않고, 값이 바뀌면 다시 띄운다", () => {
    renderPinnedToastPage();

    clickButton("connection 끊김 고정");
    closePinnedToast();
    clickButton("connection 끊김 고정");

    expect(getShownMessages()).toHaveLength(0);

    clickButton("connection 끝내는 중 고정");

    expect(getShownMessages()).toEqual([PINNED_ENDING.message]);
  });

  it("값이 없어지면 닫히고, 다시 생기면 띄운다", () => {
    renderPinnedToastPage();

    clickButton("connection 끊김 고정");
    clickButton("connection 고정 풀기");
    advanceTimers(LEAVE_DURATION_MS);

    expect(getShownMessages()).toHaveLength(0);
    expect(getLeavingMessages()).toHaveLength(0);

    clickButton("connection 끊김 고정");

    expect(getShownMessages()).toEqual([PINNED_LOST.message]);
  });

  it("X로 닫은 뒤에도 값이 없어졌다 다시 생기면 띄운다", () => {
    renderPinnedToastPage();

    clickButton("connection 끊김 고정");
    closePinnedToast();
    clickButton("connection 고정 풀기");
    clickButton("connection 끊김 고정");

    expect(getShownMessages()).toEqual([PINNED_LOST.message]);
  });

  it("이름이 다른 고정 토스트의 값이 없어져도 남는다", () => {
    renderPinnedToastPage();

    clickButton("connection 끊김 고정");
    clickButton("otherTab 끝내는 중 고정");
    clickButton("otherTab 고정 풀기");
    advanceTimers(LEAVE_DURATION_MS);

    expect(getShownMessages()).toEqual([PINNED_LOST.message]);
  });
});

describe("ToastProvider 화면 이동", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("[공통 UI 규칙·위치] 독 위 자리가 없으면 토스트 목록은 하단 가운데 영역에 그려진다", async () => {
    renderRoutes(LOGIN_PATH);

    await clickAndSettle(`${SAVED} 알리기`);

    expect(within(getToastList()).getByText(SAVED)).toBeVisible();
    expect(getToastList()).toHaveStyle({
      position: "fixed",
      bottom: "104px",
    });
  });

  it("[공통 UI 규칙·위치] 독 위 자리가 있으면 토스트 목록은 그 자리에 그려진다", async () => {
    renderRoutes(WORKSPACE_HOME_PATH);

    await clickAndSettle(`${SAVED} 알리기`);

    expect(screen.getByTestId("dock-area")).toContainElement(getToastList());
    expect(within(getToastList()).getByText(SAVED)).toBeVisible();
  });

  it("[공통 UI 규칙·화면이 바뀌면] 독이 있는 화면에서 없는 화면으로 바뀌면 이전 토스트를 모두 지운다", async () => {
    renderRoutes(WORKSPACE_HOME_PATH);

    await clickAndSettle(`${SAVED} 알리기`);
    await clickAndSettle("로그인으로 이동");

    expect(screen.getByText(LOGIN_TEXT)).toBeInTheDocument();
    expect(within(getToastList()).queryByText(SAVED)).not.toBeInTheDocument();
  });

  it("[공통 UI 규칙·화면이 바뀌면] 독이 없는 화면에서 있는 화면으로 바뀌어도 이전 토스트를 모두 지운다", async () => {
    const { router } = renderRoutes(LOGIN_PATH);

    await clickAndSettle(`${SAVED} 알리기`);
    await act(() => router.navigate(WORKSPACE_HOME_PATH));

    expect(screen.getByText(WORKSPACE_HOME_TEXT)).toBeInTheDocument();
    expect(within(getToastList()).queryByText(SAVED)).not.toBeInTheDocument();
  });

  it("고정 토스트는 독 유무가 바뀌어도 남고, 일반 토스트만 지워진다", async () => {
    const { router } = renderRoutes(WORKSPACE_HOME_PATH);

    await clickAndSettle("connection 끊김 고정");
    await clickAndSettle(`${SAVED} 알리기`);
    await clickAndSettle("로그인으로 이동");

    expect(screen.getByText(LOGIN_TEXT)).toBeInTheDocument();
    expect(within(getToastList()).getByText(PINNED_LOST.message)).toBeVisible();
    expect(within(getToastList()).queryByText(SAVED)).not.toBeInTheDocument();

    await act(() => router.navigate(WORKSPACE_HOME_PATH));

    expect(screen.getByTestId("dock-area")).toContainElement(getToastList());
    expect(within(getToastList()).getByText(PINNED_LOST.message)).toBeVisible();
  });

  it("[공통 UI 규칙·화면이 바뀌면] 같은 레이아웃 안에서 이동하면 토스트가 남는다", async () => {
    renderRoutes(WORKSPACE_HOME_PATH);

    await clickAndSettle(`${SAVED} 알리기`);
    await clickAndSettle("탐색으로 이동");

    expect(screen.getByText(WORKSPACE_CHAT_TEXT)).toBeInTheDocument();
    expect(within(getToastList()).getByText(SAVED)).toBeVisible();
  });

  it("독 유무가 같으면 navigateWithToast도 이전 알림을 유지한다", async () => {
    renderRoutes(WORKSPACE_CHAT_PATH);
    await clickAndSettle(`${SAVED} 알리기`);
    await clickAndSettle("옮겼다고 알리며 홈으로 이동");

    expect(getShownMessages()).toEqual([SAVED, WORKSPACE_MOVED]);
  });

  it("쿼리만 바뀌면 이전 알림의 시간을 이어서 센다", async () => {
    const { router } = renderRoutes(WORKSPACE_HOME_PATH);
    await clickAndSettle(`${SAVED} 알리기`);
    advanceTimers(2000);

    await act(() => router.navigate(WORKSPACE_HOME_PATH + "?messageId=1"));
    advanceTimers(SUCCESS_DURATION_MS - 2000 - 1);
    expect(getShownMessages()).toEqual([SAVED]);
    advanceTimers(1);
    expect(getShownMessages()).toHaveLength(0);
  });

  it("이동이 차단되면 독 없는 화면으로 이동하려 해도 이전 알림을 유지한다", async () => {
    const { router } = renderRoutes(BLOCKED_PATH);
    await clickAndSettle(`${SAVED} 알리기`);
    await clickAndSettle("로그인으로 이동");

    expect(router.state.location.pathname).toBe(BLOCKED_PATH);
    expect(getShownMessages()).toEqual([SAVED]);
  });

  it("[공통 UI 규칙·화면이 바뀌면] navigateWithToast로 이동하면 도착한 화면에서 그 토스트가 보인다", async () => {
    renderRoutes(WORKSPACE_HOME_PATH);

    await clickAndSettle(`${SAVED} 알리기`);
    await clickAndSettle("만료를 알리며 로그인으로 이동");

    expect(screen.getByText(LOGIN_TEXT)).toBeInTheDocument();
    expect(getShownMessages()).toEqual([SESSION_EXPIRED]);
  });

  it("[공통 UI 규칙·화면이 바뀌면] 독이 없는 화면에서 navigateWithToast로 독이 있는 화면에 가도 그 토스트가 보인다", async () => {
    renderRoutes(LOGIN_PATH);

    await clickAndSettle("옮겼다고 알리며 홈으로 이동");

    expect(screen.getByText(WORKSPACE_HOME_TEXT)).toBeInTheDocument();
    expect(screen.getByTestId("dock-area")).toContainElement(getToastList());
    expect(getShownMessages()).toEqual([WORKSPACE_MOVED]);
  });

  it("[공통 UI 규칙·화면이 바뀌면] 레이아웃이 바뀐 직후 도착 화면이 띄운 토스트는 지워지지 않는다", async () => {
    renderRoutes(WORKSPACE_HOME_PATH);

    await clickAndSettle(`${SAVED} 알리기`);
    await clickAndSettle("안내 화면으로 이동");

    expect(getShownMessages()).toEqual([WORKSPACE_MOVED]);
  });

  it("이전 토스트 삭제 전에 도착 화면이 같은 토스트를 다시 요청하면 남는다", async () => {
    const { router } = renderRoutes(WORKSPACE_HOME_PATH);

    await clickAndSettle(`${SAVED} 알리기`);
    await act(() => router.navigate(SAME_TOAST_NOTICE_PATH));

    expect(screen.getByText("저장 안내")).toBeInTheDocument();
    expect(getShownMessages()).toEqual([SAVED]);
    advanceTimers(SUCCESS_DURATION_MS);
    expect(getShownMessages()).toHaveLength(0);
  });

  it("이동 기록의 토스트를 소비해도 함께 전달한 state와 URL은 보존한다", async () => {
    const { router } = renderRoutes(WORKSPACE_HOME_PATH);
    const otherState = { nickname: "knot", returnTo: WORKSPACE_HOME_PATH };

    await act(() =>
      router.navigate(`${LOGIN_PATH}?from=workspace#notice`, {
        state: {
          ...otherState,
          toast: { variant: "caution", message: SESSION_EXPIRED },
        },
      }),
    );

    expect(getShownMessages()).toEqual([SESSION_EXPIRED]);
    expect(router.state.location.state).toEqual(otherState);
    expect(router.state.location.search).toBe("?from=workspace");
    expect(router.state.location.hash).toBe("#notice");

    advanceTimers(CAUTION_DURATION_MS + LEAVE_DURATION_MS);
    await act(() => router.navigate(-1));
    await act(() => router.navigate(1));

    expect(router.state.location.state).toEqual(otherState);
    expect(getShownMessages()).toHaveLength(0);
  });

  it("navigateWithToast로 넘긴 토스트는 새로고침·뒤로가기로 다시 뜨지 않는다", async () => {
    const { router, unmount } = renderRoutes(WORKSPACE_HOME_PATH);

    await clickAndSettle("만료를 알리며 로그인으로 이동");
    advanceTimers(CAUTION_DURATION_MS + LEAVE_DURATION_MS);

    expect(getShownMessages()).toHaveLength(0);

    // 뒤로 갔다가 다시 앞으로 와도 그 기록에 토스트가 남아 있지 않아요
    await act(() => router.navigate(-1));
    await act(() => router.navigate(1));

    expect(screen.getByText(LOGIN_TEXT)).toBeInTheDocument();
    expect(getShownMessages()).toHaveLength(0);

    // 같은 기록으로 앱을 다시 그려 새로고침을 흉내 내요
    unmount();
    renderRouter(router);

    expect(screen.getByText(LOGIN_TEXT)).toBeInTheDocument();
    expect(getShownMessages()).toHaveLength(0);
  });

  it("navigateWithToast에 replace를 주면 이동한 기록이 바뀌어 뒤로가기로 이전 화면에 돌아가지 않는다", async () => {
    const { router } = renderRoutes(WORKSPACE_HOME_PATH);

    await clickAndSettle("탐색으로 이동");
    await clickAndSettle("만료를 알리며 로그인으로 기록을 바꿔 이동");

    expect(screen.getByText(LOGIN_TEXT)).toBeInTheDocument();
    expect(getShownMessages()).toEqual([SESSION_EXPIRED]);

    // 탐색 기록이 로그인으로 바뀌어, 뒤로 가면 탐색을 건너뛰고 그 전 화면으로 가요
    await act(() => router.navigate(-1));

    expect(screen.getByText(WORKSPACE_HOME_TEXT)).toBeInTheDocument();
  });
});
