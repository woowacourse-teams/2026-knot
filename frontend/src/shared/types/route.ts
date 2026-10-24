import type { PATH_ROUTE } from "@routes/PATH_ROUTE";
import type { PathParam } from "react-router";

type RouteKey = keyof typeof PATH_ROUTE;

/** 지금 보고 있는 워크스페이스 ID만 채우면 갈 수 있는 화면 */
export type WorkspaceRouteKey = {
  [K in RouteKey]: [PathParam<(typeof PATH_ROUTE)[K]>] extends ["workspaceId"]
    ? [PathParam<(typeof PATH_ROUTE)[K]>] extends [never]
      ? never
      : K
    : never;
}[RouteKey];
