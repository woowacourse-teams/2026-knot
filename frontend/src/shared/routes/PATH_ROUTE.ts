import { generatePath, PathParam } from "react-router";

export const PATH_ROUTE = {
  HOME: "/",

  LOGIN: "/login",

  ONBOARDING: "/onboarding",
  ONBOARDING_COMPLETE: "/onboarding/complete",

  WORKSPACE: "/workspace",
  WORKSPACE_CREATE: "/workspace/create",
  WORKSPACE_CODE: "/workspace/code",
  WORKSPACE_HOME: "/workspace/:workspaceId",
  WORKSPACE_INVITE: "/workspace/:workspaceId/invite",
  WORKSPACE_JOIN: "/workspace/:workspaceId/join",

  CHAT: "/workspace/:workspaceId/chat",
  CHAT_SESSION: "/workspace/:workspaceId/chat/:sessionId",

  RECORDING: "/workspace/:workspaceId/recording",
  // 녹음을 끝낸 뒤 문서가 만들어질 때까지 보여 주는 정리 화면
  RECORDING_DOCUMENTS: "/workspace/:workspaceId/recordings/:recordingId",

  DOCUMENTS: "/workspace/:workspaceId/documents",
  DOCUMENT: "/workspace/:workspaceId/documents/:documentId",

  INVITE: "/invite/:token",

  JOIN_ERROR: "/join-error",
} as const;

type RouteKey = keyof typeof PATH_ROUTE;

type RouteParams<K extends RouteKey> = {
  [P in PathParam<(typeof PATH_ROUTE)[K]>]: string;
};

type GetRouterPathParams<K extends RouteKey> = [
  PathParam<(typeof PATH_ROUTE)[K]>,
] extends [never]
  ? { routeKey: K; params?: never }
  : { routeKey: K; params: RouteParams<K> };

/** 지금 보고 있는 워크스페이스 ID만 채우면 갈 수 있는 화면 */
export type WorkspaceRouteKey = {
  [K in RouteKey]: [PathParam<(typeof PATH_ROUTE)[K]>] extends ["workspaceId"]
    ? [PathParam<(typeof PATH_ROUTE)[K]>] extends [never]
      ? never
      : K
    : never;
}[RouteKey];

/**
 * 앱 밖(Notion 새 소식 등)에서 받은 화면 이름이 워크스페이스 ID만 채우면 갈 수 있는 화면인지 확인합니다.
 * `scripts/release-note.mjs`의 `parseLinkScreens`와 같은 기준입니다.
 */
export const isWorkspaceRouteKey = (
  value: string,
): value is WorkspaceRouteKey =>
  Object.entries(PATH_ROUTE).some(
    ([key, path]) =>
      key === value && path.match(/:\w+|\*/g)?.join() === ":workspaceId",
  );

/**
 * path가 필요한 곳에서 사용할 수 있는 유틸입니다.
 * @example
 * getRouterPath({ routeKey: "WORKSPACE_HOME", params: { workspaceId: "123" } })
 * // returns "/workspace/123"
 */
export const getRouterPath = <K extends RouteKey>({
  routeKey,
  params,
}: GetRouterPathParams<K>) => {
  const path: string = PATH_ROUTE[routeKey];

  return generatePath(path, params);
};
