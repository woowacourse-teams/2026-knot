import React from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router";
import { ThemeProvider } from "@emotion/react";

import App from "./App";
import { GlobalStyle, theme } from "./shared/provider/themeProvider";
import { QueryClientProvider } from "@tanstack/react-query";
import { queryClient } from "./shared/provider/queryClient";
import { ReactQueryDevtools } from "@tanstack/react-query-devtools";
import AppErrorBoundary from "@provider/AppErrorBoundary";
import AppProviders from "@provider/AppProviders";
import { getRootErrorHandlers, initSentry } from "@provider/sentry";
import { preloadPages } from "@routes/lazyPage";
import { routes } from "@routes/routes";
import { ANALYTICS_SCROLL_ROOT_ATTRIBUTE } from "@constants/analytics";
import { attachAnalyticsWorkspace } from "@utils/analyticsWorkspace";
import { syncAnalyticsUser } from "@utils/syncAnalyticsUser";
import posthog from "posthog-js";

// 키가 없는 환경(로컬, 테스트)에서는 사용 기록을 보내지 않아요.
// 광고 차단에 막히지 않게 PostHog 주소가 아니라 우리 주소의 중계 경로로 보내고, 프론트 Worker(src/worker)가 PostHog로 넘겨요(ADR 838).
// 이 경로는 src/worker/relay의 POSTHOG_RELAY_PATH, wrangler.jsonc의 run_worker_first와 같아야 해요.
// 개발 서버(webpack serve)에는 이 중계가 없어서, 로컬에 키를 넣어도 기록이 PostHog로 가지 않아요.
// ui_host는 툴바 · 링크가 PostHog 화면을 가리키게 하는 값이에요. 지역(us)은 Worker의 PostHog 주소와 맞춰요
if (process.env.POSTHOG_KEY) {
  posthog.init(process.env.POSTHOG_KEY, {
    api_host: "/relay/p",
    ui_host: "https://us.posthog.com",
    defaults: "2026-08-30",
    // 워크스페이스 화면의 이벤트에 workspace_id를 붙여요
    before_send: attachAnalyticsWorkspace,
    // 스크롤 깊이는 표시가 있는 본문을 먼저 재고, 없는 화면(랜딩 · 로그인)은 창 전체를 재요
    scroll_root_selector: [`[${ANALYTICS_SCROLL_ROOT_ATTRIBUTE}]`, "html"],
  });
  syncAnalyticsUser(queryClient);
}

// DSN이 없는 환경(로컬, 테스트)에서는 오류를 보고하지 않아요
initSentry({
  dsn: process.env.SENTRY_DSN,
  environment: process.env.SENTRY_ENVIRONMENT,
  release: process.env.SENTRY_RELEASE,
});

// 개발 서버에서만 Option(Alt)+클릭한 요소의 JSX 위치를 에디터에서 열어요.
// React 19에서는 DevTools 방식이 안 돼서 babel 플러그인이 붙인 속성을 읽는 jsx 방식을 써요
if (process.env.NODE_ENV === "development") {
  import("@locator/runtime").then(({ default: setupLocator }) =>
    setupLocator({ adapter: "jsx" }),
  );
}

// 정적 import하면 msw가 프로덕션 번들에 들어가므로 플래그 안에서 동적 import해요
const enableApiMocking = async () => {
  if (process.env.API_MOCKING !== "true") return;

  const { mockWorker, reactivateMockWorkerOnFocus, installMockScenarioSwitch } =
    await import("@api/mock/browser");
  installMockScenarioSwitch();
  await mockWorker.start();
  reactivateMockWorkerOnFocus();
};

const renderApp = () => {
  createRoot(
    document.getElementById("root") as HTMLElement,
    getRootErrorHandlers(),
  ).render(
    <React.StrictMode>
      <ThemeProvider theme={theme}>
        <GlobalStyle />
        <QueryClientProvider client={queryClient}>
          <BrowserRouter>
            <AppErrorBoundary>
              <AppProviders routes={routes}>
                <App />
              </AppProviders>
            </AppErrorBoundary>
          </BrowserRouter>
          <ReactQueryDevtools initialIsOpen={false} />
        </QueryClientProvider>
      </ThemeProvider>
    </React.StrictMode>,
  );
};

// 처음 연 화면의 페이지 코드를 로그인, 워크스페이스 확인 요청과 함께 받기 시작해요
preloadPages(routes, window.location);

enableApiMocking()
  .then(renderApp)
  .catch((error: unknown) => {
    // 개발 전용 경로라 워커 시작 실패를 삼키지 않고 콘솔로 드러내요
    console.error(
      "[MSW] mock 워커를 시작하지 못해 앱을 렌더하지 않았어요.",
      error,
    );
  });
