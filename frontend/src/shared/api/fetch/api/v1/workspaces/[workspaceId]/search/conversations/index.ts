import {
  GetSearchConversationsResponseDto,
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
  type GetSearchConversationsResponseRaw,
  type PostSearchConversationRequestDto,
  type SearchStreamEvent,
} from "@api/dto/search";
import { httpClient } from "@api/httpClient";
import type { SseEvent } from "@api/sse/parseSseEvents";
import { requestSseStream } from "@api/sse/requestSseStream";

export const WORKSPACE_SEARCH_CONVERSATIONS_API_PATH = (workspaceId: number) =>
  `/api/v1/workspaces/${workspaceId}/search/conversations`;

/** 한 번에 받을 대화 수. 서버 기본값과 같아요 */
const DEFAULT_PAGE_SIZE = 20;

interface GetSearchConversationsApiParams {
  workspaceId: number;
  /** 한 번에 받을 대화 수. 없으면 20개 */
  size?: number;
}

/**
 * @description 내 탐색 대화 목록의 첫 페이지를 조회합니다. 다음 페이지는 아직 받지 않아요
 * @param params - 워크스페이스 ID·한 번에 받을 대화 수
 * @returns updatedAt 내림차순 대화 목록과 다음 페이지 커서
 * @example
 * const { items } = await getSearchConversationsApi({ workspaceId: 1 });
 */
export const getSearchConversationsApi = async ({
  workspaceId,
  size = DEFAULT_PAGE_SIZE,
}: GetSearchConversationsApiParams) => {
  const response = await httpClient<GetSearchConversationsResponseRaw>({
    method: "get",
    url: WORKSPACE_SEARCH_CONVERSATIONS_API_PATH(workspaceId),
    params: { size },
  });

  return new GetSearchConversationsResponseDto(response.data);
};

/** 명세에 있는 이벤트만 DTO로 감싸요. 모르는 이벤트 이름은 버려요 */
export const toSearchStreamEvent = ({
  event,
  data,
}: SseEvent): SearchStreamEvent | null => {
  // case마다 event가 리터럴로 좁혀져 data DTO와 짝이 맞는지 타입이 확인해 줘요
  switch (event) {
    case "accepted":
      return { event, data: new SearchStreamAcceptedDto(JSON.parse(data)) };
    case "progress":
      return { event, data: new SearchStreamProgressDto(JSON.parse(data)) };
    case "delta":
      return { event, data: new SearchStreamDeltaDto(JSON.parse(data)) };
    case "evidence":
      return { event, data: new SearchStreamEvidenceDto(JSON.parse(data)) };
    case "notion_evidence":
      return {
        event,
        data: new SearchStreamNotionEvidenceDto(JSON.parse(data)),
      };
    case "notion_notice":
      return { event, data: new SearchStreamNotionNoticeDto(JSON.parse(data)) };
    case "write_card":
      return { event, data: new SearchStreamWriteCardDto(JSON.parse(data)) };
    case "excluded_documents":
      return {
        event,
        data: new SearchStreamExcludedDocumentsDto(JSON.parse(data)),
      };
    case "completed":
      return { event, data: new SearchStreamCompletedDto(JSON.parse(data)) };
    case "failed":
      return { event, data: new SearchStreamFailedDto(JSON.parse(data)) };
    case "stopped":
      return { event, data: new SearchStreamStoppedDto(JSON.parse(data)) };
    default:
      return null;
  }
};

interface CreateSearchConversationApiParams {
  workspaceId: number;
  body: PostSearchConversationRequestDto;
  signal?: AbortSignal;
}

/**
 * @description 첫 질문으로 탐색 대화를 시작하고 SSE로 도착하는 답변 이벤트를 순서대로 흘려보내요
 * @param params - 워크스페이스 ID, 첫 질문 전송 요청 본문, 취소용 AbortSignal
 * @returns accepted·progress·delta·evidence·notion_evidence·notion_notice·write_card·excluded_documents·completed·failed·stopped 이벤트를 순서대로 내는 async generator. 받는 쪽이 중간에 멈추면 스트림 읽기를 정리해요
 * @throws {SseRequestError} accepted 이벤트 전에 실패 응답을 받은 경우. `error.code === "REQUEST_ID_CONFLICT"`처럼 서버 오류 코드로 분기해요
 * @example
 * for await (const { event, data } of createSearchConversationApi({ workspaceId: 1, body })) {
 *   if (event === "delta") answer += data.text;
 * }
 */
export async function* createSearchConversationApi({
  workspaceId,
  body,
  signal,
}: CreateSearchConversationApiParams) {
  for await (const sseEvent of requestSseStream({
    method: "POST",
    path: WORKSPACE_SEARCH_CONVERSATIONS_API_PATH(workspaceId),
    body,
    signal,
  })) {
    const searchStreamEvent = toSearchStreamEvent(sseEvent);
    if (searchStreamEvent) yield searchStreamEvent;
  }
}

/**
 * accepted 뒤에 `failed` 이벤트로 도착한 답변 실패.
 *
 * 요청 함수는 이 이벤트를 다른 이벤트와 같은 줄에 흘려보내지만, 화면에서는 답변이 끊긴 실패이므로
 * 뮤테이션의 실패 상태에 싣도록 throw로 바꿔요. accepted 전 실패(`SseRequestError`)와 달리
 * 질문은 서버에 저장돼 있어 입력창 복원이 아니라 답변 자리의 다시 시도로 이어져요.
 */
export class SearchAnswerFailedError extends Error {
  /** 실패한 답변 메시지 ID. 화면에서 그 답변 자리를 찾을 때 써요 */
  answerMessageId: number;
  /** 실패 원인 오류 코드. 예: LLM_STREAM_FAILED */
  code: string;

  constructor({ answerMessageId, code }: SearchStreamFailedDto) {
    super(`탐색 답변 생성에 실패했어요 (${code})`);

    this.name = "SearchAnswerFailedError";
    this.answerMessageId = answerMessageId;
    this.code = code;
  }
}

/**
 * 끝 이벤트(completed·failed·stopped) 없이 닫힌 답변 스트림. 취소한 경우는 해당하지 않아요.
 *
 * 연결이 끊겨도 서버는 답변을 계속 만들어요(SEARCH-R8). 서버에 FAILED로 저장된 답변(`SearchAnswerFailedError`)과
 * 다르므로 따로 두고, 받는 쪽은 실패로 그리지 않고 재연결 API로 이어 받아요.
 */
export class SearchAnswerDisconnectedError extends Error {
  constructor() {
    super("탐색 답변 스트림이 끝 이벤트 없이 닫혔어요");

    this.name = "SearchAnswerDisconnectedError";
  }
}

interface RelaySearchAnswerUntilEndParams {
  /** 첫 질문·후속 질문·재시도 요청 함수가 내는 이벤트 스트림 */
  stream: AsyncIterable<SearchStreamEvent>;
  /** 이벤트가 도착할 때마다 불려요. 화면 상태에 쌓는 건 부르는 쪽이 해요 */
  onEvent: (event: SearchStreamEvent) => void;
  /** 요청 함수에 넘긴 것과 같은 취소 신호. 끝 이벤트 없이 끝났을 때 취소인지 가려요 */
  signal?: AbortSignal;
}

/**
 * @description 탐색 답변 스트림을 끝까지 받으며 이벤트를 넘기고, 답변이 어떻게 끝났는지 판정해요. 첫 질문·후속 질문·재시도 뮤테이션이 함께 써요
 * @param params - 요청 함수의 이벤트 스트림, 이벤트를 받을 콜백, 요청 함수에 넘긴 것과 같은 취소 신호
 * @returns completed·stopped 이벤트의 data. 두 끝은 `status`로 구분해요. 취소했으면 null
 * @throws {SearchAnswerFailedError} accepted 뒤 `failed` 이벤트를 받은 경우
 * @throws {SseRequestError} accepted 전에 실패 응답을 받은 경우. 요청 함수가 던진 그대로 올려요
 * @throws {SearchAnswerDisconnectedError} 취소하지 않았는데 끝 이벤트 없이 스트림이 닫힌 경우
 * @example
 * const completed = await relaySearchAnswerUntilEnd({
 *   stream: createSearchConversationApi({ workspaceId, body, signal }),
 *   onEvent,
 *   signal,
 * });
 */
export const relaySearchAnswerUntilEnd = async ({
  stream,
  onEvent,
  signal,
}: RelaySearchAnswerUntilEndParams) => {
  try {
    // 서버가 끝 이벤트로 결과를 알려 주면 그대로 따라요
    for await (const searchStreamEvent of stream) {
      onEvent(searchStreamEvent);

      if (searchStreamEvent.event === "completed")
        return searchStreamEvent.data;
      if (searchStreamEvent.event === "stopped") return searchStreamEvent.data;
      if (searchStreamEvent.event === "failed") {
        throw new SearchAnswerFailedError(searchStreamEvent.data);
      }
    }
  } catch (error) {
    // 응답 전에 abort됨: fetch가 던진 signal.reason이면 사용자가 떠난 것이라 실패로 보지 않아요

    if (signal?.aborted && error === signal.reason) return null;
    // 질문 거절·답변 실패·onEvent 오류는 화면이 알아야 하니 그대로 올려요
    throw error;
  }

  // 응답 도중 abort됨: readSseStream이 오류 없이 끝나서 여기로 와요
  if (signal?.aborted) return null;

  // 끝 이벤트 없이 끊김: 서버는 답변을 계속 만들고 있을 수 있어요
  throw new SearchAnswerDisconnectedError();
};
