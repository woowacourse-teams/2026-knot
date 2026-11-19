import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";

import { searchNotionConnectionAuthorizationResponse } from "@api/mock/responses/search";
import { mockServer } from "@api/mock/server";

import { startSearchNotionConnectionApi } from ".";

const AUTHORIZATION_PATH =
  "*/api/v1/workspaces/:workspaceId/search/notion-connection/authorization";

describe("startSearchNotionConnectionApi", () => {
  it("[SEARCH-R30] 동의 값을 보내고 노션 동의 화면 주소를 받는다", async () => {
    const bodies: unknown[] = [];
    mockServer.use(
      http.post(AUTHORIZATION_PATH, async ({ request }) => {
        bodies.push(await request.json());

        return HttpResponse.json(searchNotionConnectionAuthorizationResponse);
      }),
    );

    const { authorizationUrl } = await startSearchNotionConnectionApi({
      workspaceId: 1,
      body: { agreed: true },
    });

    expect(bodies).toEqual([{ agreed: true }]);
    expect(authorizationUrl).toBe(
      searchNotionConnectionAuthorizationResponse.authorizationUrl,
    );
  });

  it("[SEARCH-R30] 이미 연결돼 있으면 409와 서버 오류 코드로 실패한다", async () => {
    mockServer.use(
      http.post(AUTHORIZATION_PATH, () =>
        HttpResponse.json(
          {
            code: "NOTION_CONNECTION_ALREADY_CONNECTED",
            message: "이미 연결됨",
          },
          { status: 409 },
        ),
      ),
    );

    await expect(
      startSearchNotionConnectionApi({
        workspaceId: 1,
        body: { agreed: true },
      }),
    ).rejects.toMatchObject({
      type: "conflict",
      code: "NOTION_CONNECTION_ALREADY_CONNECTED",
    });
  });
});
