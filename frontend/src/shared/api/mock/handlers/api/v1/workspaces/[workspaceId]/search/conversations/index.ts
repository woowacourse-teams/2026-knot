import { delay, http, HttpResponse } from "msw";

import {
  searchAnswerStreamResponse,
  searchConversationsResponse,
} from "@api/mock/responses/search";
import type {
  SearchAnswerRetryAccepted,
  SearchAnswerRetryStream,
  SearchAnswerStream,
} from "@api/mock/types/search";

/**
 * 프레임 사이의 간격(ms).
 *
 * 한 번에 몰아 보내면 스트리밍이 아니라 한 덩어리 응답이 돼요. 사람이 글자가 늘어나는 걸
 * 알아볼 수 있는 속도로 띄워요.
 */
const FRAME_INTERVAL = 80;

const encoder = new TextEncoder();

/** SSE 프레임 한 개. 빈 줄이 이벤트의 끝을 알려요 */
const toFrame = (event: string, data: object) =>
  `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;

/** accepted 뒤에 오는 프레임. 첫 질문·후속 질문·재시도가 같은 순서로 보내요. 노션 이벤트는 값이 있을 때만 보내요 */
const toAnswerBodyFrames = ({
  answerMessageId,
  stages,
  deltas,
  evidences,
  notionEvidences,
  notionNotice,
  writeCard,
}: Omit<SearchAnswerStream, "conversationId" | "questionMessageId">) => [
  ...stages.map((stage) => toFrame("progress", { stage })),
  ...deltas.map((text) => toFrame("delta", { answerMessageId, text })),
  toFrame("evidence", { answerMessageId, items: evidences }),
  ...(notionEvidences
    ? [toFrame("notion_evidence", { answerMessageId, items: notionEvidences })]
    : []),
  ...(notionNotice
    ? [toFrame("notion_notice", { answerMessageId, reason: notionNotice })]
    : []),
  ...(writeCard
    ? [toFrame("write_card", { answerMessageId, card: writeCard })]
    : []),
  toFrame("completed", { answerMessageId, status: "COMPLETED" }),
];

/** mock 응답을 서버가 보내는 순서대로 프레임으로 바꿔요 */
const toAnswerFrames = ({
  conversationId,
  questionMessageId,
  answerMessageId,
  ...body
}: SearchAnswerStream) => [
  toFrame("accepted", { conversationId, questionMessageId, answerMessageId }),
  ...toAnswerBodyFrames({ answerMessageId, ...body }),
];

/** 재시도 응답을 프레임으로 바꿔요. accepted만 서버 계약대로 첫 질문과 다른 모양이에요 */
const toAnswerRetryFrames = ({
  accepted,
  ...body
}: SearchAnswerRetryStream & { accepted: SearchAnswerRetryAccepted }) => [
  toFrame("accepted", accepted),
  ...toAnswerBodyFrames({ answerMessageId: accepted.answerMessageId, ...body }),
];

/** 프레임을 FRAME_INTERVAL 간격으로 하나씩 흘려보내고 닫는 스트림이에요 */
const toDelayedStream = (frames: string[]) =>
  new ReadableStream({
    async start(controller) {
      for (const frame of frames) {
        await delay(FRAME_INTERVAL);
        controller.enqueue(encoder.encode(frame));
      }

      controller.close();
    },
  });

const toSseResponse = (frames: string[]) =>
  new HttpResponse(toDelayedStream(frames), {
    headers: { "Content-Type": "text/event-stream" },
  });

/** 첫 질문·후속 질문 핸들러가 같은 프레임 순서와 간격으로 답하도록 함께 써요 */
export const toSearchAnswerStreamResponse = (stream: SearchAnswerStream) =>
  toSseResponse(toAnswerFrames(stream));

interface ToSearchAnswerRetryStreamResponseParams {
  stream: SearchAnswerRetryStream;
  /** 경로의 질문 ID로 채운 accepted. 서버처럼 대화 ID 없이 보내요 */
  accepted: SearchAnswerRetryAccepted;
}

/** 재시도 핸들러가 accepted만 재시도 계약 모양으로 바꿔 같은 간격으로 답해요 */
export const toSearchAnswerRetryStreamResponse = ({
  stream,
  accepted,
}: ToSearchAnswerRetryStreamResponseParams) =>
  toSseResponse(toAnswerRetryFrames({ ...stream, accepted }));

export const workspaceSearchConversationsHandlers = [
  http.get("*/api/v1/workspaces/:workspaceId/search/conversations", () =>
    HttpResponse.json(searchConversationsResponse),
  ),
  http.post("*/api/v1/workspaces/:workspaceId/search/conversations", () =>
    toSearchAnswerStreamResponse(searchAnswerStreamResponse),
  ),
];
