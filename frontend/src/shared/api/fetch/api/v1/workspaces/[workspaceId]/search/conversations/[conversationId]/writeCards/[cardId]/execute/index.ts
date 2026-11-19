import {
  PostSearchWriteCardExecuteResponseDto,
  type SearchWriteCardRaw,
} from "@api/dto/search";
import { httpClient } from "@api/httpClient";

export const WORKSPACE_SEARCH_WRITE_CARD_EXECUTE_API_PATH = (
  workspaceId: number,
  conversationId: number,
  cardId: number,
) =>
  `/api/v1/workspaces/${workspaceId}/search/conversations/${conversationId}/write-cards/${cardId}/execute`;

/** 실행하는 요청은 서버가 노션 응답과 대기를 기다리며 최악 약 56초 붙잡아요(BE #1147). 기본 10초면 끝나기 전에 끊겨요 */
const EXECUTE_TIMEOUT_DURATION = 70_000;

interface ExecuteSearchWriteCardApiParams {
  workspaceId: number;
  conversationId: number;
  cardId: number;
}

/**
 * @description 쓰기 확인 카드를 실행해 노션 페이지를 만들어요. 다시 누를 수 있는 오류와 실패도 HTTP 오류가 아니라 응답 카드의 상태로 와요
 * @param params - 워크스페이스 ID, 탐색 대화 ID, 쓰기 확인 카드 ID
 * @returns 실행이 끝난 뒤의 카드. 성공이면 DONE, 다시 누를 수 있으면 PENDING과 retryReason, 실패면 FAILED와 failureReason
 * @example
 * const { card } = await executeSearchWriteCardApi({ workspaceId: 1, conversationId: 10, cardId: 31 });
 */
export const executeSearchWriteCardApi = async ({
  workspaceId,
  conversationId,
  cardId,
}: ExecuteSearchWriteCardApiParams) => {
  const response = await httpClient<SearchWriteCardRaw>({
    method: "post",
    url: WORKSPACE_SEARCH_WRITE_CARD_EXECUTE_API_PATH(
      workspaceId,
      conversationId,
      cardId,
    ),
    timeout: EXECUTE_TIMEOUT_DURATION,
  });

  return new PostSearchWriteCardExecuteResponseDto(response.data);
};
