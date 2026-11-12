import { searchAnswerStreamResponse, searchGeneralAnswerStreamResponse, searchMessagesResponse } from "@api/mock/responses/search";
import { toSearchAnswerStreamResponse } from "@api/mock/handlers/api/v1/workspaces/[workspaceId]/search/conversations";
import { http, HttpResponse } from "msw";

const STORAGE_KEY = "knot.mock.searchChat";
const readScenario = () => {
  try {
    return localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
};
interface FrameParams { event: string; data: object; }
const frame = ({ event, data }: FrameParams) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
const stream = (body: string) => new HttpResponse(body, {
  headers: { "Content-Type": "text/event-stream" },
});
const PARTIAL = "검증되지 않은 임시 답변";

/** 브라우저 QA에서만 정상 대화, 이전 실패, 접수 오류와 연결 끊김을 재현해요. */
export const searchChatHandlers = [
  http.post("*/api/v1/workspaces/:workspaceId/search/conversations", () => {
    const scenario = readScenario();
    if (scenario === "preaccept") return new HttpResponse(null, { status: 503 });
    if (scenario === "general") return toSearchAnswerStreamResponse(searchGeneralAnswerStreamResponse);
    if (scenario !== "failed" && scenario !== "disconnected" && scenario !== "resume500") return;
    const { conversationId, questionMessageId, answerMessageId } = searchAnswerStreamResponse;
    return stream(
      frame({ event: "accepted", data: { conversationId, questionMessageId, answerMessageId } }) +
      frame({ event: "delta", data: { answerMessageId, text: PARTIAL } }) +
      (scenario === "failed" ? frame({ event: "failed", data: { answerMessageId, status: "FAILED", code: "ANSWER_GENERATION_FAILED" } }) : ""),
    );
  }),
  http.get("*/api/v1/workspaces/:workspaceId/search/conversations/:conversationId/messages", ({ params }) => {
    const scenario = readScenario();
    if (scenario === "general") return HttpResponse.json({
      ...searchMessagesResponse, conversationId: Number(params.conversationId), items: [],
    });
    if (!["legacy", "failed", "disconnected", "resume500", "recovered"].includes(scenario ?? "")) return;
    const [question, answer] = searchMessagesResponse.items;
    const { questionMessageId, answerMessageId } = searchAnswerStreamResponse;
    return HttpResponse.json({
      ...searchMessagesResponse,
      conversationId: Number(params.conversationId),
      items: [
        { ...question, id: questionMessageId },
        { ...answer, id: answerMessageId, status: ["disconnected", "resume500"].includes(scenario ?? "") ? "STREAMING" : "FAILED", content: PARTIAL, evidences: [] },
      ],
    });
  }),
  http.post("*/api/v1/workspaces/:workspaceId/search/conversations/:conversationId/questions", () => {
    if (["disconnected", "resume500"].includes(readScenario() ?? "")) localStorage.setItem(STORAGE_KEY, "recovered");
    // The default handler returns the follow-up answer; subsequent reads no longer report an active old execution.
  }),
  http.get("*/api/v1/workspaces/:workspaceId/search/conversations/:conversationId/answers/:answerId/events", ({ params }) => {
    if (readScenario() === "resume500") return new HttpResponse(null, { status: 503 });
    if (readScenario() !== "disconnected") return;
    return stream(frame({ event: "snapshot", data: { answerId: Number(params.answerId), status: "STREAMING", content: PARTIAL } }));
  }),
];
