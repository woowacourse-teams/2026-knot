import { PATH_ROUTE } from "@routes/PATH_ROUTE";
import { routes } from "@routes/routes";
import type { CaptureResult } from "posthog-js";
import { generatePath, type RouteObject } from "react-router";
import { describe, expect, it } from "vitest";

import { attachAnalyticsWorkspace } from ".";

/** 그 경로에서 잡힌 것처럼 PostHog가 `$pathname`을 넣은 이벤트를 만들어요 */
const toPageview = (pathname: string): CaptureResult => ({
  uuid: "01a14f68-29c4-7ada-a1f8-5996005a8697",
  event: "$pageview",
  properties: { $pathname: pathname },
});

/** 라우트와 그 아래 라우트의 주소를 모두 모아요 */
const collectPaths = (route: RouteObject): string[] => [
  ...(route.path ? [route.path] : []),
  ...(route.children ?? []).flatMap(collectPaths),
];

/** 라우트와 그 아래 라우트를 모두 모아요 */
const collectRoutes = (route: RouteObject): RouteObject[] => [
  route,
  ...(route.children ?? []).flatMap(collectRoutes),
];

const allPaths = routes.flatMap(collectPaths).filter((path) => path !== "*");
const isWorkspaceLayoutRoute = (route: RouteObject) =>
  route.children?.some(({ path }) => path === PATH_ROUTE.WORKSPACE_HOME);
const workspaceLayoutPaths = collectPaths(
  routes.flatMap(collectRoutes).find(isWorkspaceLayoutRoute) as RouteObject,
);

/** 주소의 변수를 채워요. 워크스페이스는 3이에요 */
const toPathname = (path: string) =>
  generatePath(path, {
    workspaceId: "3",
    sessionId: "5",
    recordingId: "7",
    documentId: "9",
    token: "invite-token",
  });

describe("attachAnalyticsWorkspace", () => {
  // 라우트에서 주소를 모아, 레이아웃 아래 화면을 더하거나 옮기면 이 테스트가 알려 줘요
  it.each(workspaceLayoutPaths.map(toPathname))(
    "워크스페이스 레이아웃 안의 주소(%s)에서 잡힌 이벤트에는 그 workspace_id를 붙이고 나머지 속성은 그대로 둔다",
    (pathname) => {
      expect(attachAnalyticsWorkspace(toPageview(pathname)).properties).toEqual(
        { $pathname: pathname, workspace_id: 3 },
      );
    },
  );

  it.each(
    allPaths
      .filter((path) => !workspaceLayoutPaths.includes(path))
      .map(toPathname),
  )(
    "워크스페이스 레이아웃 밖의 주소(%s)에서 잡힌 이벤트는 그대로 보낸다",
    (pathname) => {
      const event = toPageview(pathname);

      expect(attachAnalyticsWorkspace(event)).toBe(event);
    },
  );

  it("이벤트에 직접 넣은 workspace_id가 있으면 그 값을 남긴다", () => {
    const event = toPageview("/workspace/3");
    event.properties.workspace_id = 8;

    expect(attachAnalyticsWorkspace(event).properties.workspace_id).toBe(8);
  });

  it("경로가 없는 이벤트는 그대로 보낸다", () => {
    const event = { ...toPageview("/workspace/3"), properties: {} };

    expect(attachAnalyticsWorkspace(event)).toBe(event);
  });
});
