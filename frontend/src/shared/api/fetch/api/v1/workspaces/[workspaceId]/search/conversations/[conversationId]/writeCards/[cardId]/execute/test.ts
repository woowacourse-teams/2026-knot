import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";

import { SearchWriteCardDto } from "@api/dto/search";
import {
  searchWriteCardCreatedPageUrl,
  searchWriteCardResponse,
} from "@api/mock/responses/search";
import { mockServer } from "@api/mock/server";

import { executeSearchWriteCardApi } from ".";

const EXECUTE_PATH =
  "*/api/v1/workspaces/:workspaceId/search/conversations/:conversationId/write-cards/:cardId/execute";

describe("executeSearchWriteCardApi", () => {
  it("[SEARCH-R50] 요청한 카드를 실행해 만든 페이지 주소가 담긴 실행됨 카드를 받는다", async () => {
    const { card } = await executeSearchWriteCardApi({
      workspaceId: 1,
      conversationId: 10,
      cardId: 31,
    });

    expect(card).toEqual(
      new SearchWriteCardDto({
        ...searchWriteCardResponse,
        status: "DONE",
        createdPageUrl: searchWriteCardCreatedPageUrl,
      }),
    );
  });

  it("[SEARCH-R52] 다시 누를 수 있는 오류는 HTTP 오류가 아니라 대기 카드와 사유로 받는다", async () => {
    mockServer.use(
      http.post(EXECUTE_PATH, () =>
        HttpResponse.json({
          ...searchWriteCardResponse,
          retryReason: "NOTION_CONNECTION_REQUIRED",
        }),
      ),
    );

    const { card } = await executeSearchWriteCardApi({
      workspaceId: 1,
      conversationId: 10,
      cardId: 31,
    });

    expect(card.status).toBe("PENDING");
    expect(card.retryReason).toBe("NOTION_CONNECTION_REQUIRED");
  });
});
