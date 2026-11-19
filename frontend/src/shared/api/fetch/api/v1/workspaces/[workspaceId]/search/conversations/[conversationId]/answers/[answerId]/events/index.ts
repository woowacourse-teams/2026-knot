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
  type SearchAnswerEventsCompletedRaw,
  type SearchAnswerEventsDeltaRaw,
  type SearchAnswerEventsEvidenceRaw,
  type SearchAnswerEventsFailedRaw,
  type SearchAnswerEventsNotionEvidenceRaw,
  type SearchAnswerEventsNotionNoticeRaw,
  type SearchAnswerEventsWriteCardRaw,
  type SearchAnswerEventsStoppedRaw,
  type SearchStreamEvent,
} from "@api/dto/search";
import type { SseEvent } from "@api/sse/parseSseEvents";
import { requestSseStream } from "@api/sse/requestSseStream";

export const WORKSPACE_SEARCH_ANSWER_EVENTS_API_PATH = (
  workspaceId: number,
  conversationId: number,
  answerId: number,
) =>
  `/api/v1/workspaces/${workspaceId}/search/conversations/${conversationId}/answers/${answerId}/events`;

/**
 * 재연결 스트림의 이벤트를 첫 질문과 같은 이벤트 DTO로 감싸요. 모르는 이벤트 이름은 버려요.
 *
 * 서버는 답변 메시지 ID를 `answerId`라고 보내서, 첫 질문 스트림의 `answerMessageId`로 바꿔요.
 * 그래야 저장소가 받던 답변과 같은 규칙으로 이어 받아요
 */
const toSearchAnswerEventsStreamEvent = ({
  event,
  data,
}: SseEvent): SearchStreamEvent | null => {
  switch (event) {
    case "snapshot":
      return {
        event,
        data: new SearchStreamSnapshotDto(JSON.parse(data)),
      };
    case "delta": {
      const { answerId, text }: SearchAnswerEventsDeltaRaw = JSON.parse(data);

      return {
        event,
        data: new SearchStreamDeltaDto({ answerMessageId: answerId, text }),
      };
    }
    case "evidence": {
      const { answerId, items }: SearchAnswerEventsEvidenceRaw = JSON.parse(data);

      return {
        event,
        data: new SearchStreamEvidenceDto({ answerMessageId: answerId, items }),
      };
    }
    case "notion_evidence": {
      const { answerId, items }: SearchAnswerEventsNotionEvidenceRaw =
        JSON.parse(data);

      return {
        event,
        data: new SearchStreamNotionEvidenceDto({
          answerMessageId: answerId,
          items,
        }),
      };
    }
    case "notion_notice": {
      const { answerId, reason }: SearchAnswerEventsNotionNoticeRaw =
        JSON.parse(data);

      return {
        event,
        data: new SearchStreamNotionNoticeDto({
          answerMessageId: answerId,
          reason,
        }),
      };
    }
    case "write_card": {
      const { answerId, card }: SearchAnswerEventsWriteCardRaw =
        JSON.parse(data);

      return {
        event,
        data: new SearchStreamWriteCardDto({ answerMessageId: answerId, card }),
      };
    }
    case "completed": {
      const { answerId, status }: SearchAnswerEventsCompletedRaw = JSON.parse(data);

      return {
        event,
        data: new SearchStreamCompletedDto({ answerMessageId: answerId, status }),
      };
    }
    case "failed": {
      const { answerId, status, code }: SearchAnswerEventsFailedRaw = JSON.parse(data);

      return {
        event,
        data: new SearchStreamFailedDto({
          answerMessageId: answerId,
          status,
          code,
        }),
      };
    }
    case "stopped": {
      const { answerId, status }: SearchAnswerEventsStoppedRaw = JSON.parse(data);

      return {
        event,
        data: new SearchStreamStoppedDto({ answerMessageId: answerId, status }),
      };
    }
    default:
      return null;
  }
};

interface GetSearchAnswerEventsApiParams {
  workspaceId: number;
  conversationId: number;
  /** 다시 받을 답변 메시지 ID */
  answerId: number;
  signal?: AbortSignal;
}

/**
 * @description 끝 이벤트 없이 끊겼거나 화면이 받고 있지 않은 답변에 다시 연결해, 저장된 본문부터 끝까지 흘려보내요(SEARCH-R8). 서버는 연결이 끊겨도 답변을 계속 만들어요
 * @param params - 워크스페이스 ID, 탐색 대화 ID, 답변 메시지 ID, 취소용 AbortSignal
 * @returns snapshot·delta·evidence·notion_evidence·notion_notice·write_card·completed·failed·stopped 이벤트를 순서대로 내는 async generator. 이벤트 data는 첫 질문 스트림과 같은 DTO예요. 이미 끝난 답변이면 snapshot, 근거, 끝 이벤트만 내고 닫혀요
 * @throws {SseRequestError} 스트림을 받기 전에 실패 응답을 받은 경우. 답변이 없으면 `ANSWER_NOT_FOUND`, 볼 수 없으면 `SEARCH_ACCESS_DENIED`예요
 * @example
 * for await (const { event, data } of getSearchAnswerEventsApi({ workspaceId: 1, conversationId: 10, answerId: 2 })) {
 *   if (event === "snapshot") answer = data.content;
 *   if (event === "delta") answer += data.text;
 * }
 */
export async function* getSearchAnswerEventsApi({
  workspaceId,
  conversationId,
  answerId,
  signal,
}: GetSearchAnswerEventsApiParams) {
  for await (const sseEvent of requestSseStream({
    method: "GET",
    path: WORKSPACE_SEARCH_ANSWER_EVENTS_API_PATH(
      workspaceId,
      conversationId,
      answerId,
    ),
    signal,
  })) {
    const searchStreamEvent = toSearchAnswerEventsStreamEvent(sseEvent);
    if (searchStreamEvent) yield searchStreamEvent;
  }
}
