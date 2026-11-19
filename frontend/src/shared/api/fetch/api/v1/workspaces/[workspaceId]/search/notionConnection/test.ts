import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";

import { GetSearchNotionConnectionResponseDto } from "@api/dto/search";
import { searchNotionConnectionResponse } from "@api/mock/responses/search";
import { mockServer } from "@api/mock/server";

import {
  disconnectSearchNotionConnectionApi,
  getSearchNotionConnectionApi,
} from ".";

const NOTION_CONNECTION_PATH =
  "*/api/v1/workspaces/:workspaceId/search/notion-connection";

describe("getSearchNotionConnectionApi", () => {
  it("[SEARCH-R30] 연결된 노션의 이름과 아이콘, 연결한 사람, 끊을 수 있는지를 받는다", async () => {
    const connection = await getSearchNotionConnectionApi(1);

    expect(connection).toEqual(
      new GetSearchNotionConnectionResponseDto(searchNotionConnectionResponse),
    );
  });

  it("[SEARCH-R29] 연결이 없으면 NOT_CONNECTED와 빈 값을 받는다", async () => {
    mockServer.use(
      http.get(NOTION_CONNECTION_PATH, () =>
        HttpResponse.json({
          status: "NOT_CONNECTED",
          notionWorkspaceName: null,
          notionWorkspaceIcon: null,
          connectedBy: null,
          canDisconnect: false,
        }),
      ),
    );

    const connection = await getSearchNotionConnectionApi(1);

    expect(connection.status).toBe("NOT_CONNECTED");
    expect(connection.connectedBy).toBeNull();
  });
});

describe("disconnectSearchNotionConnectionApi", () => {
  it("[SEARCH-R33] 지금 워크스페이스의 노션 연결에 DELETE를 보낸다", async () => {
    const requested: string[] = [];
    mockServer.use(
      http.delete(NOTION_CONNECTION_PATH, ({ params }) => {
        requested.push(String(params.workspaceId));

        return new HttpResponse(null, { status: 204 });
      }),
    );

    await disconnectSearchNotionConnectionApi(7);

    expect(requested).toEqual(["7"]);
  });
});
