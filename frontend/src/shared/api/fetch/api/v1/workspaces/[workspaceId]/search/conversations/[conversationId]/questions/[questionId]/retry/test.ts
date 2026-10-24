import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";

import {
  SearchStreamAcceptedDto,
  SearchStreamCompletedDto,
  SearchStreamDeltaDto,
  SearchStreamEvidenceDto,
  SearchStreamProgressDto,
} from "@api/dto/search";
import { csrfTokenResponse } from "@api/mock/responses/auth";
import { searchAnswerRetryStreamResponse } from "@api/mock/responses/search";
import { mockServer } from "@api/mock/server";
import { SseRequestError } from "@api/sse/requestSseStream";

import { retrySearchAnswerApi } from ".";

const WORKSPACE_ID = 1;
// 첫 질문 mock의 대화 ID와 다른 값이어야 경로 값을 그대로 쓰는지 드러나요
const CONVERSATION_ID = 200;
const QUESTION_ID = 3;
const SEARCH_ANSWER_RETRY_PATH =
  "*/api/v1/workspaces/:workspaceId/search/conversations/:conversationId/questions/:questionId/retry";

const RETRY = { requestId: "5d1c2b3a-4e5f-4a6b-8c7d-9e0f1a2b3c4d" };

/** 스트림이 끝날 때까지 받아 이벤트를 순서대로 모아요 */
const readAllRetryEvents = async () => {
  const events = [];

  for await (const event of retrySearchAnswerApi({
    workspaceId: WORKSPACE_ID,
    conversationId: CONVERSATION_ID,
    questionId: QUESTION_ID,
    body: RETRY,
  })) {
    events.push(event);
  }

  return events;
};

/** 스트림을 끝까지 받다가 throw된 오류를 돌려줘요 */
const catchRetryError = () =>
  readAllRetryEvents().then(
    () => undefined,
    (error: unknown) => error,
  );

/** 이번 테스트에서만 accepted 전 HTTP 오류로 응답하게 바꿔요 */
const respondWithError = (status: number, code: string) =>
  mockServer.use(
    http.post(SEARCH_ANSWER_RETRY_PATH, () =>
      HttpResponse.json({ code, message: "요청을 처리하지 못했어요" }, { status }),
    ),
  );

describe("retrySearchAnswerApi", () => {
  it("[SEARCH-R5] 경로의 질문으로 답변을 다시 만들고 accepted → progress → delta → evidence → completed 순서로 낸다", async () => {
    const { stages, deltas, evidences } = searchAnswerRetryStreamResponse;
    // 서버는 실패했던 답변 메시지를 비우고 다시 채우므로, 질문 바로 다음 메시지가 그 답변이에요
    const answerMessageId = QUESTION_ID + 1;

    const events = await readAllRetryEvents();

    expect(events).toEqual([
      {
        event: "accepted",
        data: new SearchStreamAcceptedDto({
          conversationId: CONVERSATION_ID,
          questionMessageId: QUESTION_ID,
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

  it("[SEARCH-R5] 재시도 accepted의 questionId·answerMessageId를 경로의 대화 ID와 합쳐 첫 질문과 같은 accepted 모양으로 낸다", async () => {
    // 서버 계약(backend/docs/api/search-answer-retry.md)대로 대화 ID 없이 status가 함께 와요
    const accepted = {
      questionId: QUESTION_ID,
      answerMessageId: 999,
      status: "STREAMING",
    };
    mockServer.use(
      http.post(
        SEARCH_ANSWER_RETRY_PATH,
        () =>
          new HttpResponse(
            `event: accepted\ndata: ${JSON.stringify(accepted)}\n\n` +
              `event: completed\ndata: ${JSON.stringify({ answerMessageId: 999, status: "COMPLETED" })}\n\n`,
            { headers: { "Content-Type": "text/event-stream" } },
          ),
      ),
    );

    const [acceptedEvent] = await readAllRetryEvents();

    expect(acceptedEvent).toEqual({
      event: "accepted",
      data: new SearchStreamAcceptedDto({
        conversationId: CONVERSATION_ID,
        questionMessageId: QUESTION_ID,
        answerMessageId: 999,
      }),
    });
  });

  it("요청 ID만 담은 본문을 SSE와 CSRF 헤더와 함께 POST로 보낸다", async () => {
    const requests: Request[] = [];
    // 요청만 남기고 아무것도 돌려주지 않아 기본 mock 핸들러가 이어서 응답해요
    mockServer.use(
      http.post(SEARCH_ANSWER_RETRY_PATH, ({ request }) => {
        requests.push(request.clone());
      }),
    );

    await readAllRetryEvents();

    const [request] = requests;
    expect(new URL(request.url).pathname).toBe(
      `/api/v1/workspaces/${WORKSPACE_ID}/search/conversations/${CONVERSATION_ID}/questions/${QUESTION_ID}/retry`,
    );
    expect(request.method).toBe("POST");
    expect(request.headers.get("Accept")).toBe("text/event-stream");
    expect(request.headers.get("X-XSRF-TOKEN")).toBe(csrfTokenResponse.token);
    expect(await request.json()).toEqual(RETRY);
  });

  it("[SEARCH-R5] 답변이 실패 상태가 아니면 accepted 전 409를 상태 코드와 서버 오류 코드에 담아 throw한다", async () => {
    respondWithError(409, "ANSWER_NOT_FAILED");

    const error = await catchRetryError();

    expect(error).toBeInstanceOf(SseRequestError);
    expect(error).toMatchObject({ status: 409, code: "ANSWER_NOT_FAILED" });
  });

  it("[SEARCH-R6] 이미 답변을 만드는 중이면 accepted 전 409를 상태 코드와 서버 오류 코드에 담아 throw한다", async () => {
    respondWithError(409, "ANSWER_ALREADY_IN_PROGRESS");

    const error = await catchRetryError();

    expect(error).toBeInstanceOf(SseRequestError);
    expect(error).toMatchObject({
      status: 409,
      code: "ANSWER_ALREADY_IN_PROGRESS",
    });
  });
});
