import { http, HttpResponse } from "msw";
import { workspaceMembersResponse } from "@api/mock/responses/recordingSpeaker";
export const workspaceMembersHandlers = [
  http.get("*/api/v1/workspaces/:workspaceId/members", () =>
    HttpResponse.json(workspaceMembersResponse),
  ),
];
