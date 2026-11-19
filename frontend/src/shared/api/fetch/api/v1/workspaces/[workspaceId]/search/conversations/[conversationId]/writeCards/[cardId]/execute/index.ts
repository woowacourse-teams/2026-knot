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
  });

  return new PostSearchWriteCardExecuteResponseDto(response.data);
};
