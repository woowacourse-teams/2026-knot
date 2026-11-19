import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";

import { SearchWriteCardDto } from "@api/dto/search";
import {
  searchMessagesResponse,
  searchWriteCardResponse,
} from "@api/mock/responses/search";
import { mockServer } from "@api/mock/server";

import { getSearchMessagesApi } from ".";

const MESSAGES_PATH =
  "*/api/v1/workspaces/:workspaceId/search/conversations/:conversationId/messages";

/** 이번 테스트에서 보낸 요청의 쿼리를 모아요 */
const captureSearchParams = () => {
  const captured: URLSearchParams[] = [];
  mockServer.use(
    http.get(MESSAGES_PATH, ({ request }) => {
      captured.push(new URL(request.url).searchParams);

      return HttpResponse.json(searchMessagesResponse);
    }),
  );

  return captured;
};

describe("getSearchMessagesApi", () => {
  it("size를 주지 않으면 최근 30개를 요청한다 (근거 없음)", async () => {
    const captured = captureSearchParams();

    await getSearchMessagesApi({ workspaceId: 1, conversationId: 100 });

    expect(captured[0].get("size")).toBe("30");
  });

  it("size를 주면 그 수만큼 요청한다 (근거 없음)", async () => {
    const captured = captureSearchParams();

    await getSearchMessagesApi({ workspaceId: 1, conversationId: 100, size: 5 });

    expect(captured[0].get("size")).toBe("5");
  });

  it("[SEARCH-R42] 답변에 붙은 노션 근거, 노션 안내, 쓰기 확인 카드를 함께 받는다", async () => {
    const [question, answer] = searchMessagesResponse.items;
    const notionEvidences = [{ title: "출시 계획", url: "https://www.notion.so/knot/launch" }];
    mockServer.use(
      http.get(MESSAGES_PATH, () =>
        HttpResponse.json({
          ...searchMessagesResponse,
          items: [
            question,
            { ...answer, notionEvidences, notionNotice: "PAGE_NOT_FOUND", writeCard: searchWriteCardResponse },
          ],
        }),
      ),
    );

    const { items } = await getSearchMessagesApi({ workspaceId: 1, conversationId: 100 });

    expect(items[1].notionEvidences).toEqual(notionEvidences);
    expect(items[1].notionNotice).toBe("PAGE_NOT_FOUND");
    expect(items[1].writeCard).toEqual(new SearchWriteCardDto(searchWriteCardResponse));
  });

  it("노션 필드를 보내지 않는 서버의 응답은 노션 근거 없음, 안내 없음, 카드 없음으로 받는다 (노션 연결 배포 전 서버)", async () => {
    const [question] = searchMessagesResponse.items;
    const { notionEvidences, notionNotice, writeCard, ...legacyQuestion } = question;
    mockServer.use(
      http.get(MESSAGES_PATH, () =>
        HttpResponse.json({ ...searchMessagesResponse, items: [legacyQuestion] }),
      ),
    );

    const { items } = await getSearchMessagesApi({ workspaceId: 1, conversationId: 100 });

    expect(items[0].notionEvidences).toEqual(notionEvidences);
    expect(items[0].notionNotice).toBe(notionNotice);
    expect(items[0].writeCard).toBe(writeCard);
  });
});
