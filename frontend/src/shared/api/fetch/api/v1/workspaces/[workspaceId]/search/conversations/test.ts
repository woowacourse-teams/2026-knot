import { delay, http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";

import {
  SearchStreamAcceptedDto,
  SearchStreamCompletedDto,
  SearchStreamDeltaDto,
  SearchStreamEvidenceDto,
  SearchStreamExcludedDocumentsDto,
  SearchStreamFailedDto,
  SearchStreamNotionEvidenceDto,
  SearchStreamNotionNoticeDto,
  SearchStreamProgressDto,
  SearchStreamStoppedDto,
  SearchStreamWriteCardDto,
} from "@api/dto/search";
import { createSearchQuestionApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/search/conversations/[conversationId]/questions";
import {
  searchAnswerStreamResponse,
  searchConversationsResponse,
  searchQuestionAnswerStreamResponse,
  searchWriteCardResponse,
} from "@api/mock/responses/search";
import { mockServer } from "@api/mock/server";
import { SseRequestError } from "@api/sse/requestSseStream";

import {
  createSearchConversationApi,
  getSearchConversationsApi,
  relaySearchAnswerUntilEnd,
  SearchAnswerDisconnectedError,
  SearchAnswerFailedError,
} from ".";

const WORKSPACE_ID = 1;
const SEARCH_CONVERSATIONS_PATH =
  "*/api/v1/workspaces/:workspaceId/search/conversations";

const QUESTION = {
  content: "DB 기술 선정 관련 문서 있어?",
  requestId: "4f1c2a52-7d1e-4a43-9f61-0c5b9b1d2e10",
};

const ACCEPTED = {
  conversationId: 100,
  questionMessageId: 1001,
  answerMessageId: 1002,
};
const ANSWER_MESSAGE_ID = ACCEPTED.answerMessageId;

const encoder = new TextEncoder();

/** 스트림이 끝날 때까지 받아 이벤트를 순서대로 모아요 */
const readAllSearchEvents = async () => {
  const events = [];

  for await (const event of createSearchConversationApi({
    workspaceId: WORKSPACE_ID,
    body: QUESTION,
  })) {
    events.push(event);
  }

  return events;
};

/** 스트림을 끝까지 받다가 throw된 오류를 돌려줘요 */
const catchSearchError = () =>
  readAllSearchEvents().then(
    () => undefined,
    (error: unknown) => error,
  );

/** 서버가 보내는 SSE 프레임 한 개예요 */
const toFrame = (event: string, data: object) =>
  `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;

const toBytes = (chunk: string | Uint8Array) =>
  typeof chunk === "string" ? encoder.encode(chunk) : chunk;

/** 이번 테스트에서만 준비된 조각을 흘려보내고 닫는 SSE 응답으로 바꿔요 */
const respondWithSseChunks = (chunks: (string | Uint8Array)[]) =>
  mockServer.use(
    http.post(
      SEARCH_CONVERSATIONS_PATH,
      () =>
        new HttpResponse(
          new ReadableStream({
            start(controller) {
              chunks.forEach((chunk) => controller.enqueue(toBytes(chunk)));
              controller.close();
            },
          }),
          { headers: { "Content-Type": "text/event-stream" } },
        ),
    ),
  );

/** 이번 테스트에서만 accepted 전 HTTP 오류로 응답하게 바꿔요 */
const respondWithError = (status: number, code: string) =>
  mockServer.use(
    http.post(SEARCH_CONVERSATIONS_PATH, () =>
      HttpResponse.json({ code, message: "요청을 처리하지 못했어요" }, { status }),
    ),
  );

describe("createSearchConversationApi", () => {
  // SEARCH-R1 문서 근거 답변
  it("[SEARCH-R1] mock 스트림을 accepted → progress → delta → evidence → completed 순서로 낸다", async () => {
    const {
      conversationId,
      questionMessageId,
      answerMessageId,
      stages,
      deltas,
      evidences,
    } = searchAnswerStreamResponse;

    const events = await readAllSearchEvents();

    expect(events).toEqual([
      {
        event: "accepted",
        data: new SearchStreamAcceptedDto({
          conversationId,
          questionMessageId,
          answerMessageId,
        }),
      },
      ...stages.map((stage) => ({
        event: "progress",
        data: new SearchStreamProgressDto({ stage }),
      })),
      ...deltas.map((text) => ({
        event: "delta",
        data: new SearchStreamDeltaDto({ answerMessageId, text }),
      })),
      {
        event: "evidence",
        data: new SearchStreamEvidenceDto({ answerMessageId, items: evidences }),
      },
      {
        event: "completed",
        data: new SearchStreamCompletedDto({
          answerMessageId,
          status: "COMPLETED",
        }),
      },
    ]);
  });

  // requestId 충돌 409 (API 명세 · 첫 질문)
  it("accepted 전 409는 상태 코드와 서버 오류 코드를 담아 throw한다", async () => {
    respondWithError(409, "REQUEST_ID_CONFLICT");

    const error = await catchSearchError();

    expect(error).toBeInstanceOf(SseRequestError);
    expect(error).toMatchObject({ status: 409, code: "REQUEST_ID_CONFLICT" });
  });

  // SEARCH-R4 accepted 전 실패
  it("[SEARCH-R4] accepted 전 403은 상태 코드와 서버 오류 코드를 담아 throw한다", async () => {
    respondWithError(403, "WORKSPACE_ACCESS_DENIED");

    const error = await catchSearchError();

    expect(error).toBeInstanceOf(SseRequestError);
    expect(error).toMatchObject({ status: 403, code: "WORKSPACE_ACCESS_DENIED" });
  });

  // SEARCH-R5 accepted 뒤의 실패는 HTTP 오류가 아니라 이벤트로 와요
  it("[SEARCH-R5] accepted 뒤 failed 이벤트는 throw하지 않고 그대로 낸다", async () => {
    const delta = { answerMessageId: ANSWER_MESSAGE_ID, text: "DB는 " };
    const failed = {
      answerMessageId: ANSWER_MESSAGE_ID,
      status: "FAILED",
      code: "LLM_STREAM_FAILED",
    } as const;
    respondWithSseChunks([
      toFrame("accepted", ACCEPTED),
      toFrame("delta", delta),
      toFrame("failed", failed),
    ]);

    const events = await readAllSearchEvents();

    expect(events).toEqual([
      { event: "accepted", data: new SearchStreamAcceptedDto(ACCEPTED) },
      { event: "delta", data: new SearchStreamDeltaDto(delta) },
      { event: "failed", data: new SearchStreamFailedDto(failed) },
    ]);
  });

  it("모르는 이벤트 이름은 버린다", async () => {
    respondWithSseChunks([
      toFrame("accepted", ACCEPTED),
      toFrame("heartbeat", {}),
      "data: {}\n\n",
    ]);

    const events = await readAllSearchEvents();

    expect(events).toEqual([
      { event: "accepted", data: new SearchStreamAcceptedDto(ACCEPTED) },
    ]);
  });

  it("한글이 청크 경계 바이트에서 잘려도 깨지지 않는다", async () => {
    const delta = { answerMessageId: ANSWER_MESSAGE_ID, text: "테스트 " };
    const frame = toFrame("delta", delta);
    const frameBytes = encoder.encode(frame);
    // "테"의 3바이트 한가운데를 지나도록 잘라요
    const splitIndex =
      encoder.encode(frame.slice(0, frame.indexOf("테"))).length + 1;
    respondWithSseChunks([
      frameBytes.slice(0, splitIndex),
      frameBytes.slice(splitIndex),
    ]);

    const events = await readAllSearchEvents();

    expect(events).toEqual([
      { event: "delta", data: new SearchStreamDeltaDto(delta) },
    ]);
  });

  // SEARCH-R2 근거 최대 3개
  it("[SEARCH-R2] progress·excluded_documents·evidence·stopped를 각각 받는다", async () => {
    const evidence = {
      answerMessageId: ANSWER_MESSAGE_ID,
      items: [1, 2, 3].map((rank) => ({
        documentId: 10 + rank,
        title: `회의록 ${rank}`,
        topic: "DB 기술 선정",
        rank,
      })),
    };
    const stopped = {
      answerMessageId: ANSWER_MESSAGE_ID,
      status: "STOPPED",
    } as const;
    respondWithSseChunks([
      toFrame("progress", { stage: "SEARCHING" }),
      toFrame("excluded_documents", { count: 2 }),
      toFrame("evidence", evidence),
      toFrame("stopped", stopped),
    ]);

    const events = await readAllSearchEvents();

    expect(events).toEqual([
      {
        event: "progress",
        data: new SearchStreamProgressDto({ stage: "SEARCHING" }),
      },
      {
        event: "excluded_documents",
        data: new SearchStreamExcludedDocumentsDto({ count: 2 }),
      },
      { event: "evidence", data: new SearchStreamEvidenceDto(evidence) },
      { event: "stopped", data: new SearchStreamStoppedDto(stopped) },
    ]);
  });

  it("[SEARCH-R37,SEARCH-R39,SEARCH-R40] notion_evidence, notion_notice, write_card를 각각 받는다", async () => {
    const notionEvidence = {
      answerMessageId: ANSWER_MESSAGE_ID,
      items: [{ title: "출시 계획", url: "https://www.notion.so/knot/launch" }],
    };
    const notionNotice = {
      answerMessageId: ANSWER_MESSAGE_ID,
      reason: "CONNECTION_EXPIRED",
    } as const;
    const writeCard = {
      answerMessageId: ANSWER_MESSAGE_ID,
      card: searchWriteCardResponse,
    };
    respondWithSseChunks([
      toFrame("notion_evidence", notionEvidence),
      toFrame("notion_notice", notionNotice),
      toFrame("write_card", writeCard),
    ]);

    const events = await readAllSearchEvents();

    expect(events).toEqual([
      {
        event: "notion_evidence",
        data: new SearchStreamNotionEvidenceDto(notionEvidence),
      },
      {
        event: "notion_notice",
        data: new SearchStreamNotionNoticeDto(notionNotice),
      },
      { event: "write_card", data: new SearchStreamWriteCardDto(writeCard) },
    ]);
  });

  // SEARCH-R8 연결 끊김
  it("[SEARCH-R8] signal을 abort하면 그 뒤로 이벤트 없이 정상 종료한다", async () => {
    const controller = new AbortController();
    const events = [];

    for await (const event of createSearchConversationApi({
      workspaceId: WORKSPACE_ID,
      body: QUESTION,
      signal: controller.signal,
    })) {
      events.push(event);
      controller.abort();
    }

    expect(events).toEqual([
      { event: "accepted", data: expect.any(SearchStreamAcceptedDto) },
    ]);
  });

  // SEARCH-R8 연결 끊김
  it("[SEARCH-R8] 받는 쪽이 completed에서 return하면 이후 프레임을 읽지 않고 스트림을 정리한다", async () => {
    // msw가 응답 본문을 옮겨 담아 원본 스트림의 cancel까지는 전달되지 않으므로 reader의 cancel을 지켜봐요
    const cancel = vi.spyOn(ReadableStreamDefaultReader.prototype, "cancel");
    const completed = {
      answerMessageId: ANSWER_MESSAGE_ID,
      status: "COMPLETED",
    } as const;
    mockServer.use(
      http.post(
        SEARCH_CONVERSATIONS_PATH,
        () =>
          new HttpResponse(
            // 닫지 않은 스트림이라 정리하지 않으면 계속 다음 프레임을 기다려요
            new ReadableStream({
              start(controller) {
                controller.enqueue(
                  encoder.encode(
                    toFrame("completed", completed) +
                      toFrame("delta", { answerMessageId: 0, text: "뒤" }),
                  ),
                );
              },
            }),
            { headers: { "Content-Type": "text/event-stream" } },
          ),
      ),
    );

    const receiveUntilCompleted = async () => {
      for await (const { event, data } of createSearchConversationApi({
        workspaceId: WORKSPACE_ID,
        body: QUESTION,
      })) {
        if (event === "completed") return data.answerMessageId;
      }
    };

    await expect(receiveUntilCompleted()).resolves.toBe(ANSWER_MESSAGE_ID);
    await vi.waitFor(() => expect(cancel).toHaveBeenCalled());
  });
});

// 첫 질문 mock의 대화 ID와 다른 값이어야 후속 질문 경로의 값이 드러나요
const CONVERSATION_ID = 200;

interface CompletedAnswerEventsParams {
  conversationId: number;
  questionMessageId: number;
  answerMessageId: number;
  stages: ("SEARCHING" | "GENERATING")[];
  deltas: string[];
  evidences: (typeof searchAnswerStreamResponse)["evidences"];
}

/** mock 응답이 정상 완료될 때 도착하는 이벤트를 앱 모양으로 바꿔요 */
const toCompletedAnswerEvents = ({
  conversationId,
  questionMessageId,
  answerMessageId,
  stages,
  deltas,
  evidences,
}: CompletedAnswerEventsParams) => [
  {
    event: "accepted",
    data: new SearchStreamAcceptedDto({
      conversationId,
      questionMessageId,
      answerMessageId,
    }),
  },
  ...stages.map((stage) => ({
    event: "progress",
    data: new SearchStreamProgressDto({ stage }),
  })),
  ...deltas.map((text) => ({
    event: "delta",
    data: new SearchStreamDeltaDto({ answerMessageId, text }),
  })),
  {
    event: "evidence",
    data: new SearchStreamEvidenceDto({ answerMessageId, items: evidences }),
  },
  {
    event: "completed",
    data: new SearchStreamCompletedDto({ answerMessageId, status: "COMPLETED" }),
  },
];

/** onEvent가 받은 이벤트를 받은 순서대로 꺼내요 */
const toReceivedEvents = (onEvent: ReturnType<typeof vi.fn>) =>
  onEvent.mock.calls.map(([event]) => event);

describe("relaySearchAnswerUntilEnd", () => {
  // SEARCH-R1 문서 근거 답변 · API 명세 첫 질문
  it("[SEARCH-R1] 첫 질문 스트림의 이벤트를 도착 순서대로 onEvent로 넘기고 completed의 data를 반환한다", async () => {
    const onEvent = vi.fn();

    const result = await relaySearchAnswerUntilEnd({
      stream: createSearchConversationApi({
        workspaceId: WORKSPACE_ID,
        body: QUESTION,
      }),
      onEvent,
    });

    expect(toReceivedEvents(onEvent)).toEqual(
      toCompletedAnswerEvents(searchAnswerStreamResponse),
    );
    expect(result).toEqual(
      new SearchStreamCompletedDto({
        answerMessageId: searchAnswerStreamResponse.answerMessageId,
        status: "COMPLETED",
      }),
    );
  });

  // SEARCH-R1 문서 근거 답변 · API 명세 후속 질문
  it("[SEARCH-R1] 후속 질문 스트림도 이벤트를 도착 순서대로 onEvent로 넘기고 completed의 data를 반환한다", async () => {
    const onEvent = vi.fn();

    const result = await relaySearchAnswerUntilEnd({
      stream: createSearchQuestionApi({
        workspaceId: WORKSPACE_ID,
        conversationId: CONVERSATION_ID,
        body: QUESTION,
      }),
      onEvent,
    });

    expect(toReceivedEvents(onEvent)).toEqual(
      toCompletedAnswerEvents({
        ...searchQuestionAnswerStreamResponse,
        conversationId: CONVERSATION_ID,
      }),
    );
    expect(result).toEqual(
      new SearchStreamCompletedDto({
        answerMessageId: searchQuestionAnswerStreamResponse.answerMessageId,
        status: "COMPLETED",
      }),
    );
  });

  // SEARCH-R5 답변 실패 · API 명세 failed 이벤트
  it("[SEARCH-R5] accepted 뒤 failed 이벤트는 onEvent로 넘긴 뒤 answerMessageId와 code를 담은 SearchAnswerFailedError로 throw한다", async () => {
    const failed = {
      answerMessageId: ANSWER_MESSAGE_ID,
      status: "FAILED",
      code: "LLM_STREAM_FAILED",
    } as const;
    respondWithSseChunks([
      toFrame("accepted", ACCEPTED),
      toFrame("failed", failed),
    ]);
    const onEvent = vi.fn();

    const error = await relaySearchAnswerUntilEnd({
      stream: createSearchConversationApi({
        workspaceId: WORKSPACE_ID,
        body: QUESTION,
      }),
      onEvent,
    }).catch((caught: unknown) => caught);

    expect(toReceivedEvents(onEvent)).toEqual([
      { event: "accepted", data: new SearchStreamAcceptedDto(ACCEPTED) },
      { event: "failed", data: new SearchStreamFailedDto(failed) },
    ]);
    expect(error).toBeInstanceOf(SearchAnswerFailedError);
    expect(error).toMatchObject({
      answerMessageId: ANSWER_MESSAGE_ID,
      code: "LLM_STREAM_FAILED",
    });
  });

  // SEARCH-R4 질문 전송 실패 · API 명세 첫 질문 403
  it("[SEARCH-R4] accepted 전 실패 응답은 onEvent를 부르지 않고 SseRequestError를 그대로 throw한다", async () => {
    respondWithError(403, "WORKSPACE_ACCESS_DENIED");
    const onEvent = vi.fn();

    const error = await relaySearchAnswerUntilEnd({
      stream: createSearchConversationApi({
        workspaceId: WORKSPACE_ID,
        body: QUESTION,
      }),
      onEvent,
    }).catch((caught: unknown) => caught);

    expect(onEvent).not.toHaveBeenCalled();
    expect(error).toBeInstanceOf(SseRequestError);
    expect(error).toMatchObject({ status: 403, code: "WORKSPACE_ACCESS_DENIED" });
  });

  // SEARCH-R7 답변 중지 · API 명세 stopped 이벤트
  it("[SEARCH-R7] stopped 이벤트는 onEvent로 넘긴 뒤 stopped의 data를 반환한다", async () => {
    const delta = { answerMessageId: ANSWER_MESSAGE_ID, text: "DB는 " };
    const stopped = { answerMessageId: ANSWER_MESSAGE_ID, status: "STOPPED" } as const;
    respondWithSseChunks([
      toFrame("accepted", ACCEPTED),
      toFrame("delta", delta),
      toFrame("stopped", stopped),
    ]);
    const onEvent = vi.fn();

    const result = await relaySearchAnswerUntilEnd({
      stream: createSearchConversationApi({
        workspaceId: WORKSPACE_ID,
        body: QUESTION,
      }),
      onEvent,
    });

    expect(toReceivedEvents(onEvent)).toEqual([
      { event: "accepted", data: new SearchStreamAcceptedDto(ACCEPTED) },
      { event: "delta", data: new SearchStreamDeltaDto(delta) },
      { event: "stopped", data: new SearchStreamStoppedDto(stopped) },
    ]);
    expect(result).toEqual(new SearchStreamStoppedDto(stopped));
  });

  // SEARCH-R8 이어 보기 · 화면을 떠나면 스트림만 끊고 실패로 보지 않음
  it("[SEARCH-R8] 요청을 취소하면 실패 없이 null을 반환한다", async () => {
    const controller = new AbortController();
    // accepted를 받자마자 화면을 떠난 상황이에요
    const onEvent = vi.fn(() => controller.abort());

    const result = await relaySearchAnswerUntilEnd({
      stream: createSearchConversationApi({
        workspaceId: WORKSPACE_ID,
        body: QUESTION,
        signal: controller.signal,
      }),
      onEvent,
      signal: controller.signal,
    });

    expect(toReceivedEvents(onEvent)).toEqual([
      { event: "accepted", data: expect.any(SearchStreamAcceptedDto) },
    ]);
    expect(result).toBeNull();
  });

  // SEARCH-R8 이어 보기(재연결 전까지 실패로 처리) · #580 TODO
  it("[SEARCH-R8] 끝 이벤트 없이 닫힌 스트림은 취소가 아니면 SearchAnswerDisconnectedError로 throw한다", async () => {
    const delta = { answerMessageId: ANSWER_MESSAGE_ID, text: "DB는 " };
    respondWithSseChunks([toFrame("accepted", ACCEPTED), toFrame("delta", delta)]);
    const onEvent = vi.fn();

    const error = await relaySearchAnswerUntilEnd({
      stream: createSearchConversationApi({
        workspaceId: WORKSPACE_ID,
        body: QUESTION,
      }),
      onEvent,
    }).catch((caught: unknown) => caught);

    expect(toReceivedEvents(onEvent)).toEqual([
      { event: "accepted", data: new SearchStreamAcceptedDto(ACCEPTED) },
      { event: "delta", data: new SearchStreamDeltaDto(delta) },
    ]);
    expect(error).toBeInstanceOf(SearchAnswerDisconnectedError);
  });

  // SEARCH-R8 이어 보기 · 질문을 보내자마자 화면을 떠나도 실패로 보지 않음
  it("[SEARCH-R8] 응답을 기다리는 중에 abort하면 실패 없이 null을 반환한다", async () => {
    let resolveRequestArrived = () => {};
    const requestArrived = new Promise<void>((resolve) => {
      resolveRequestArrived = resolve;
    });
    mockServer.use(
      http.post(SEARCH_CONVERSATIONS_PATH, async () => {
        resolveRequestArrived();
        await delay("infinite");
        return new HttpResponse(null);
      }),
    );
    const controller = new AbortController();
    const onEvent = vi.fn();

    const result = relaySearchAnswerUntilEnd({
      stream: createSearchConversationApi({
        workspaceId: WORKSPACE_ID,
        body: QUESTION,
        signal: controller.signal,
      }),
      onEvent,
      signal: controller.signal,
    });
    await requestArrived;
    controller.abort();

    await expect(result).resolves.toBeNull();
    expect(onEvent).not.toHaveBeenCalled();
  });

  // SEARCH-R8 이어 보기 · 보내기 전에 이미 화면을 떠난 경우
  it("[SEARCH-R8] 이미 취소된 signal로 보내면 실패 없이 null을 반환한다", async () => {
    const controller = new AbortController();
    controller.abort();
    const onEvent = vi.fn();

    const result = await relaySearchAnswerUntilEnd({
      stream: createSearchConversationApi({
        workspaceId: WORKSPACE_ID,
        body: QUESTION,
        signal: controller.signal,
      }),
      onEvent,
      signal: controller.signal,
    });

    expect(result).toBeNull();
    expect(onEvent).not.toHaveBeenCalled();
  });

  // 취소 판정이 다른 오류까지 삼키지 않도록 지키는 테스트
  it("onEvent가 던진 오류는 취소로 바꾸지 않고 그대로 전달한다", async () => {
    const callbackError = new Error("화면 상태를 바꾸지 못했어요");
    const onEvent = vi.fn(() => {
      throw callbackError;
    });

    const error = await relaySearchAnswerUntilEnd({
      stream: createSearchConversationApi({
        workspaceId: WORKSPACE_ID,
        body: QUESTION,
      }),
      onEvent,
    }).catch((caught: unknown) => caught);

    expect(error).toBe(callbackError);
  });
});

/** 이번 테스트에서 보낸 목록 조회 요청의 쿼리를 모아요 */
const captureListSearchParams = () => {
  const captured: URLSearchParams[] = [];
  mockServer.use(
    http.get(SEARCH_CONVERSATIONS_PATH, ({ request }) => {
      captured.push(new URL(request.url).searchParams);

      return HttpResponse.json(searchConversationsResponse);
    }),
  );

  return captured;
};

describe("getSearchConversationsApi", () => {
  it("size를 주지 않으면 첫 페이지 20개를 요청한다 (근거 없음)", async () => {
    const captured = captureListSearchParams();

    await getSearchConversationsApi({ workspaceId: WORKSPACE_ID });

    expect(captured[0].get("size")).toBe("20");
  });

  it("커서 없이 첫 페이지만 요청한다 (근거 없음)", async () => {
    const captured = captureListSearchParams();

    await getSearchConversationsApi({ workspaceId: WORKSPACE_ID });

    expect(captured[0].has("cursor")).toBe(false);
  });
});
