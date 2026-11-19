import type {
  SearchAnswer,
  SearchAnswerStreamEvent,
} from "@/shared/types/searchAnswer";

/**
 * 도착한 탐색 스트림 이벤트 하나를 답변에 반영한 새 답변을 돌려줘요. 반영할 것이 없으면 받은 답변을 그대로 돌려줘요.
 *
 * - accepted: 접수 대기(`SENDING`)일 때만 대화·메시지 ID를 담고 생성 중(`STREAMING`)으로 바꿔요
 * - snapshot, progress, delta, evidence, notion_evidence, notion_notice, write_card, excluded_documents: 생성 중일 때만 반영해요.
 *   snapshot, delta, evidence, notion_evidence, notion_notice, write_card는 받고 있는 답변 메시지의 것만 반영해요
 * - snapshot: 다시 연결했을 때 오는 저장된 본문이라, 받던 본문을 이 값으로 바꿔요(SEARCH-R8)
 * - completed·failed·stopped: 끝난 방식은 뮤테이션 결과로 판정하므로 여기서는 반영하지 않아요
 */
export const reduceSearchStreamEvent = (
  answer: SearchAnswer,
  { event, data }: SearchAnswerStreamEvent,
) => {
  if (event === "accepted") {
    if (answer.status !== "SENDING") return answer;

    return {
      ...answer,
      status: "STREAMING" as const,
      conversationId: data.conversationId,
      questionMessageId: data.questionMessageId,
      answerMessageId: data.answerMessageId,
    };
  }

  if (answer.status !== "STREAMING") return answer;

  switch (event) {
    case "progress":
      return { ...answer, stage: data.stage };
    case "excluded_documents":
      return { ...answer, excludedDocumentCount: data.count };
    case "snapshot":
      if (data.answerMessageId !== answer.answerMessageId) return answer;

      return { ...answer, text: data.content };
    case "delta":
      if (data.answerMessageId !== answer.answerMessageId) return answer;

      return { ...answer, text: answer.text + data.text };
    case "evidence":
      if (data.answerMessageId !== answer.answerMessageId) return answer;

      return { ...answer, evidences: data.items };
    case "notion_evidence":
      if (data.answerMessageId !== answer.answerMessageId) return answer;

      return { ...answer, notionEvidences: data.items };
    case "notion_notice":
      if (data.answerMessageId !== answer.answerMessageId) return answer;

      return { ...answer, notionNotice: data.reason };
    case "write_card":
      if (data.answerMessageId !== answer.answerMessageId) return answer;

      return { ...answer, writeCard: data.card };
    default:
      return answer;
  }
};
