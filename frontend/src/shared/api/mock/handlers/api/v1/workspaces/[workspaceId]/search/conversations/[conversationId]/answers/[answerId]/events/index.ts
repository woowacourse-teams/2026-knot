import { delay, http, HttpResponse } from "msw";

import { searchAnswerEventsStreamResponse } from "@api/mock/responses/search";
import type { SearchAnswerEventsStream } from "@api/mock/types/search";

/** 프레임 사이의 간격(ms). 첫 질문 mock과 같은 속도로 글자가 늘어나요 */
const FRAME_INTERVAL = 80;

const encoder = new TextEncoder();

/** SSE 프레임 한 개. 빈 줄이 이벤트의 끝을 알려요 */
const toFrame = (event: string, data: object) =>
  `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;

interface ToAnswerEventsFramesParams extends SearchAnswerEventsStream {
  answerId: number;
}

/**
 * 서버처럼 저장된 본문(snapshot) → 나머지 조각 → 근거 → 완료 순서로, 답변 ID를 `answerId`라고 보내요.
 * 노션 이벤트는 첫 질문 mock처럼 값이 있을 때만 근거 뒤에 보내요
 */
const toAnswerEventsFrames = ({
  answerId,
  content,
  deltas,
  evidences,
  notionEvidences,
  notionNotice,
  writeCard,
}: ToAnswerEventsFramesParams) => [
  toFrame("snapshot", { answerId, content, status: "STREAMING" }),
  ...deltas.map((text) => toFrame("delta", { answerId, text })),
  toFrame("evidence", { answerId, items: evidences }),
  ...(notionEvidences
    ? [toFrame("notion_evidence", { answerId, items: notionEvidences })]
    : []),
  ...(notionNotice
    ? [toFrame("notion_notice", { answerId, reason: notionNotice })]
    : []),
  ...(writeCard ? [toFrame("write_card", { answerId, card: writeCard })] : []),
  toFrame("completed", { answerId, status: "COMPLETED" }),
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

export const workspaceSearchAnswerEventsHandlers = [
  http.get(
    "*/api/v1/workspaces/:workspaceId/search/conversations/:conversationId/answers/:answerId/events",
    ({ params }) =>
      new HttpResponse(
        toDelayedStream(
          toAnswerEventsFrames({
            ...searchAnswerEventsStreamResponse,
            answerId: Number(params.answerId),
          }),
        ),
        { headers: { "Content-Type": "text/event-stream" } },
      ),
  ),
];
