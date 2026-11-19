import { http, HttpResponse } from "msw";

import { searchNotionConnectionAuthorizationResponse } from "@api/mock/responses/search";

// 경로 파라미터가 있어 fetch 상수 대신 패턴을 직접 적어요
export const workspaceSearchNotionConnectionAuthorizationHandlers = [
  http.post(
    "*/api/v1/workspaces/:workspaceId/search/notion-connection/authorization",
    () => HttpResponse.json(searchNotionConnectionAuthorizationResponse),
  ),
];
