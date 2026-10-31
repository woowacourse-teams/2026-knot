import { generatePath, PathParam } from "react-router";

export const PATH_ROUTE = {
  HOME: "/",

  LOGIN: "/login",
  // 로그인 처리에 실패해 돌아왔을 때 보여 주는 로그인 실패 화면(ERR-15, AUTH-R7)
  LOGIN_FAILED: "/login/failed",

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
  // 녹음 문서(녹음 제목 · 녹음 음성 · 회의 전체 요약 · 이 회의에서 나온 문서 · 전사, STT-R36). 문서 탭 「녹음별」 탭의 녹음 행이 이 주소로 보내요
  RECORDING_DOCUMENT:
    "/workspace/:workspaceId/recordings/:recordingId/document",

  // 문서 탭. 탭마다 주소가 있고, 탭 없는 주소는 「녹음별」 탭으로 보내요(DOC-R9 · R20)
  DOCUMENTS: "/workspace/:workspaceId/documents",
  DOCUMENTS_BY_RECORDING: "/workspace/:workspaceId/documents/recordings",
  DOCUMENTS_BY_TOPIC: "/workspace/:workspaceId/documents/topics",
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
