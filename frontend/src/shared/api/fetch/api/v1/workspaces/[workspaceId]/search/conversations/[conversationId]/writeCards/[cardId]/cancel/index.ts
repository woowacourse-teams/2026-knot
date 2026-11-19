import {
  PostSearchWriteCardCancelResponseDto,
  type SearchWriteCardRaw,
} from "@api/dto/search";
import { httpClient } from "@api/httpClient";

export const WORKSPACE_SEARCH_WRITE_CARD_CANCEL_API_PATH = (
  workspaceId: number,
  conversationId: number,
  cardId: number,
) =>
  `/api/v1/workspaces/${workspaceId}/search/conversations/${conversationId}/write-cards/${cardId}/cancel`;

interface CancelSearchWriteCardApiParams {
  workspaceId: number;
  conversationId: number;
  cardId: number;
}

/**
 * @description 대기인 쓰기 확인 카드를 취소해요. 이미 취소된 카드는 지금 상태를 그대로 받고, 대기가 아닌 카드는 409 WRITE_CARD_NOT_PENDING이에요
 * @param params - 워크스페이스 ID, 탐색 대화 ID, 쓰기 확인 카드 ID
 * @returns 취소한 뒤의 카드(status CANCELED)
 * @example
 * const { card } = await cancelSearchWriteCardApi({ workspaceId: 1, conversationId: 10, cardId: 31 });
 */
export const cancelSearchWriteCardApi = async ({
  workspaceId,
  conversationId,
  cardId,
}: CancelSearchWriteCardApiParams) => {
  const response = await httpClient<SearchWriteCardRaw>({
    method: "post",
    url: WORKSPACE_SEARCH_WRITE_CARD_CANCEL_API_PATH(
      workspaceId,
      conversationId,
      cardId,
    ),
  });

  return new PostSearchWriteCardCancelResponseDto(response.data);
};
