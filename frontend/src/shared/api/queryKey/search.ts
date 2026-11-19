interface SearchMessagesKeyParams {
  workspaceId: number;
  /** 대화 ID. 없으면 아직 질문을 보내지 않은 새 대화라 조회하지 않아요 */
  conversationId?: number;
}

/**
 * 탐색 쿼리 키
 *
 * - `conversations` 워크스페이스의 내 탐색 대화 목록
 * - `messages` 한 탐색 대화의 메시지 목록
 * - `notionConnection` 워크스페이스의 노션 연결
 */
export const searchKeys = {
  all: ["search"] as const,

  conversations: (workspaceId: number | null) =>
    [...searchKeys.all, "conversations", workspaceId] as const,

  messages: ({ workspaceId, conversationId }: SearchMessagesKeyParams) =>
    [...searchKeys.all, "messages", workspaceId, conversationId] as const,

  notionConnection: (workspaceId: number) =>
    [...searchKeys.all, "notionConnection", workspaceId] as const,
};
