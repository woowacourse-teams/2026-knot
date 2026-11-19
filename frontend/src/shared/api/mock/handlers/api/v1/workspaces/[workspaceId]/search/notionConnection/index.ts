import { http, HttpResponse } from "msw";

import { searchNotionConnectionResponse } from "@api/mock/responses/search";

// 경로 파라미터가 있어 fetch 상수 대신 패턴을 직접 적어요
export const workspaceSearchNotionConnectionHandlers = [
  http.get("*/api/v1/workspaces/:workspaceId/search/notion-connection", () =>
    HttpResponse.json(searchNotionConnectionResponse),
  ),
  http.delete(
    "*/api/v1/workspaces/:workspaceId/search/notion-connection",
    () => new HttpResponse(null, { status: 204 }),
  ),
];
