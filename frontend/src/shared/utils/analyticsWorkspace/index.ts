import { PATH_ROUTE } from "@routes/PATH_ROUTE";
import type { CaptureResult } from "posthog-js";
import { matchPath } from "react-router";

/** 워크스페이스 레이아웃 안의 주소. 초대 · 입장 확인은 레이아웃 밖이라 넣지 않아요 */
const WORKSPACE_LAYOUT_PATHS = [
  PATH_ROUTE.WORKSPACE_HOME,
  PATH_ROUTE.CHAT,
  PATH_ROUTE.CHAT_SESSION,
  PATH_ROUTE.RECORDING,
  PATH_ROUTE.RECORDING_DOCUMENTS,
  PATH_ROUTE.RECORDING_DOCUMENT,
  PATH_ROUTE.DOCUMENTS,
  PATH_ROUTE.DOCUMENT,
];

/** 워크스페이스 레이아웃 안의 경로면 그 워크스페이스 id, 아니면 null이에요 */
const getWorkspaceId = (pathname: string) => {
  const match = WORKSPACE_LAYOUT_PATHS.map((path) =>
    matchPath(path, pathname),
  ).find((pathMatch) => pathMatch !== null);
  // `/workspace/create`처럼 숫자가 아닌 자리는 워크스페이스 홈 주소와도 맞아서 거르게 돼요
  const workspaceId = Number(match?.params.workspaceId);

  return Number.isInteger(workspaceId) ? workspaceId : null;
};

/**
 * @description `posthog.init`의 `before_send`. 워크스페이스 화면에서 잡힌 이벤트(자동 기록 포함)에 그 워크스페이스 id를 `workspace_id`로 붙여요
 * @param event - 보낼 이벤트. PostHog가 잡은 순간의 경로를 `$pathname`에 넣어 둬요
 * @returns workspace_id를 붙인 이벤트. 워크스페이스 레이아웃 밖이면 받은 이벤트 그대로예요
 * @example
 * posthog.init(key, { before_send: attachAnalyticsWorkspace });
 *
 * 화면 코드가 값을 정해 두지 않고 이벤트의 경로로 정해요. 주소가 바뀌면 PostHog는 새 화면을 그리기 전에 `$pageview`를 잡아서,
 * 화면이 그린 뒤에 정한 값은 그 기록에 늦게 닿기 때문이에요.
 * 이벤트에 직접 넣은 workspace_id(녹음한 워크스페이스 등)가 있으면 그 값을 남겨요.
 */
export const attachAnalyticsWorkspace = (event: CaptureResult) => {
  const { $pathname } = event.properties;
  const workspaceId =
    typeof $pathname === "string" ? getWorkspaceId($pathname) : null;

  if (workspaceId === null) return event;

  return {
    ...event,
    properties: { workspace_id: workspaceId, ...event.properties },
  };
};
