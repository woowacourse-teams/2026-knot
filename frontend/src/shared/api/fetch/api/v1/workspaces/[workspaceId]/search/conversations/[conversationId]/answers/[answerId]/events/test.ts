import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";

import {
  SearchStreamCompletedDto,
  SearchStreamDeltaDto,
  SearchStreamEvidenceDto,
  SearchStreamFailedDto,
  SearchStreamNotionEvidenceDto,
  SearchStreamNotionNoticeDto,
  SearchStreamSnapshotDto,
  SearchStreamStoppedDto,
  SearchStreamWriteCardDto,
} from "@api/dto/search";
import {
  searchAnswerEventsStreamResponse,
  searchWriteCardResponse,
} from "@api/mock/responses/search";
import { mockServer } from "@api/mock/server";
import { SseRequestError } from "@api/sse/requestSseStream";

import { getSearchAnswerEventsApi } from ".";

const WORKSPACE_ID = 1;
const CONVERSATION_ID = 200;
const ANSWER_ID = 22;
const SEARCH_ANSWER_EVENTS_PATH =
  "*/api/v1/workspaces/:workspaceId/search/conversations/:conversationId/answers/:answerId/events";

/** 스트림이 끝날 때까지 받아 이벤트를 순서대로 모아요 */
const readAllEvents = async () => {
  const events = [];

  for await (const event of getSearchAnswerEventsApi({
    workspaceId: WORKSPACE_ID,
    conversationId: CONVERSATION_ID,
    answerId: ANSWER_ID,
  })) {
    events.push(event);
  }

  return events;
};

/** 이번 테스트에서만 넘긴 프레임을 한 번에 보내고 닫아요 */
const respondWithFrames = (frames: { event: string; data: object }[]) =>
  mockServer.use(
    http.get(
      SEARCH_ANSWER_EVENTS_PATH,
      () =>
        new HttpResponse(
          frames
            .map(({ event, data }) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)
            .join(""),
          { headers: { "Content-Type": "text/event-stream" } },
        ),
    ),
  );

describe("getSearchAnswerEventsApi", () => {
  it("[SEARCH-R8] 저장된 본문(snapshot) → 나머지 조각 → 근거 → 완료 순서로, 서버의 answerId를 answerMessageId로 바꿔 낸다", async () => {
    const { content, deltas, evidences } = searchAnswerEventsStreamResponse;

    const events = await readAllEvents();

    expect(events).toEqual([
      {
        event: "snapshot",
        data: new SearchStreamSnapshotDto({
          answerId: ANSWER_ID,
          content,
          status: "STREAMING",
        }),
      },
      ...deltas.map((text) => ({
        event: "delta",
        data: new SearchStreamDeltaDto({ answerMessageId: ANSWER_ID, text }),
      })),
      {
        event: "evidence",
        data: new SearchStreamEvidenceDto({
          answerMessageId: ANSWER_ID,
          items: evidences,
        }),
      },
      {
        event: "completed",
        data: new SearchStreamCompletedDto({
          answerMessageId: ANSWER_ID,
          status: "COMPLETED",
        }),
      },
    ]);
  });

  it("[SEARCH-R8] 실패·중지로 끝난 답변도 answerMessageId로 바꿔 낸다", async () => {
    respondWithFrames([
      { event: "failed", data: { answerId: ANSWER_ID, status: "FAILED", code: "LLM_STREAM_FAILED" } },
      { event: "stopped", data: { answerId: ANSWER_ID, status: "STOPPED" } },
    ]);

    const events = await readAllEvents();

    expect(events).toEqual([
      {
        event: "failed",
        data: new SearchStreamFailedDto({
          answerMessageId: ANSWER_ID,
          status: "FAILED",
          code: "LLM_STREAM_FAILED",
        }),
      },
      {
        event: "stopped",
        data: new SearchStreamStoppedDto({
          answerMessageId: ANSWER_ID,
          status: "STOPPED",
        }),
      },
    ]);
  });

  it("[SEARCH-R37,SEARCH-R39,SEARCH-R42] 노션 근거, 노션 안내, 쓰기 확인 카드도 answerMessageId로 바꿔 낸다", async () => {
    const items = [{ title: "출시 계획", url: "https://www.notion.so/knot/launch" }];
    respondWithFrames([
      { event: "notion_evidence", data: { answerId: ANSWER_ID, items } },
      { event: "notion_notice", data: { answerId: ANSWER_ID, reason: "READ_FAILED" } },
      { event: "write_card", data: { answerId: ANSWER_ID, card: searchWriteCardResponse } },
    ]);

    const events = await readAllEvents();

    expect(events).toEqual([
      {
        event: "notion_evidence",
        data: new SearchStreamNotionEvidenceDto({ answerMessageId: ANSWER_ID, items }),
      },
      {
        event: "notion_notice",
        data: new SearchStreamNotionNoticeDto({ answerMessageId: ANSWER_ID, reason: "READ_FAILED" }),
      },
      {
        event: "write_card",
        data: new SearchStreamWriteCardDto({ answerMessageId: ANSWER_ID, card: searchWriteCardResponse }),
      },
    ]);
  });

  it("명세에 없는 이벤트는 버린다", async () => {
    respondWithFrames([
      { event: "accepted", data: { answerId: ANSWER_ID } },
      { event: "completed", data: { answerId: ANSWER_ID, status: "COMPLETED" } },
    ]);

    const events = await readAllEvents();

    expect(events.map(({ event }) => event)).toEqual(["completed"]);
  });

  it("CSRF 토큰 없이 GET으로 요청한다", async () => {
    const requests: Request[] = [];
    mockServer.events.on("request:start", ({ request }) => {
      requests.push(request.clone());
    });

    await readAllEvents();
    mockServer.events.removeAllListeners();

    const [request] = requests;
    expect(request.method).toBe("GET");
    expect(new URL(request.url).pathname).toBe(
      `/api/v1/workspaces/${WORKSPACE_ID}/search/conversations/${CONVERSATION_ID}/answers/${ANSWER_ID}/events`,
    );
    expect(request.headers.get("Accept")).toBe("text/event-stream");
    expect(request.headers.get("X-XSRF-TOKEN")).toBeNull();
  });

  it("스트림을 받기 전에 실패하면 서버 오류 코드를 담은 SseRequestError를 던진다", async () => {
    mockServer.use(
      http.get(SEARCH_ANSWER_EVENTS_PATH, () =>
        HttpResponse.json(
          { code: "ANSWER_NOT_FOUND", message: "답변을 찾을 수 없어요" },
          { status: 404 },
        ),
      ),
    );

    const error = await readAllEvents().then(
      () => undefined,
      (caught: unknown) => caught,
    );

    expect(error).toBeInstanceOf(SseRequestError);
    expect(error).toMatchObject({ status: 404, code: "ANSWER_NOT_FOUND" });
  });
});
