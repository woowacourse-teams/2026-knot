import { describe, expect, it } from "vitest";

import { SearchWriteCardDto } from "@api/dto/search";
import { searchWriteCardResponse } from "@api/mock/responses/search";

import { cancelSearchWriteCardApi } from ".";

describe("cancelSearchWriteCardApi", () => {
  it("[SEARCH-R43] 요청한 카드를 취소해 취소됨 카드를 받는다", async () => {
    const { card } = await cancelSearchWriteCardApi({
      workspaceId: 1,
      conversationId: 10,
      cardId: 31,
    });

    expect(card).toEqual(
      new SearchWriteCardDto({ ...searchWriteCardResponse, status: "CANCELED" }),
    );
  });
});
