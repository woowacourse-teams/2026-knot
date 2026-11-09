/** 마지막으로 본 탐색 대화를 브라우저에 남겨 두는 키의 앞부분. 워크스페이스마다 따로 남기도록 뒤에 워크스페이스 id를 붙여요 */
const LAST_CONVERSATION_KEY_PREFIX = "knot.lastConversation";

interface SaveLastConversationIdParams {
  workspaceId: string;
  conversationId: number;
}

const getLastConversationKey = (workspaceId: string) =>
  `${LAST_CONVERSATION_KEY_PREFIX}.${workspaceId}`;

/**
 * 이 탭에서 워크스페이스의 탐색 화면을 마지막으로 봤을 때의 대화 id를 읽어요.
 *
 * 빈 새 대화를 봤거나 본 적이 없으면 `undefined`예요. 읽을 수 없을 때도 본 적이 없는 것으로 봐요.
 */
export const readLastConversationId = (workspaceId: string) => {
  try {
    const stored = sessionStorage.getItem(getLastConversationKey(workspaceId));

    const conversationId = Number(stored);

    // 남긴 값이 없거나 망가졌으면 본 적이 없는 것으로 봐요
    return stored !== null && Number.isInteger(conversationId)
      ? conversationId
      : undefined;
  } catch {
    return undefined;
  }
};

/**
 * 지금 보는 대화를 남겨요. 같은 탭에서는 새로고침해도 남고, 탭을 닫으면 사라지도록 `sessionStorage`에 둬요
 */
export const saveLastConversationId = ({
  workspaceId,
  conversationId,
}: SaveLastConversationIdParams) => {
  try {
    sessionStorage.setItem(
      getLastConversationKey(workspaceId),
      String(conversationId),
    );
  } catch {
    // 남길 수 없으면 다음번 탐색은 새 대화로 열려요
  }
};

/** 빈 새 대화를 열었거나 남긴 대화를 더는 열 수 없으면 비워요 */
export const clearLastConversationId = (workspaceId: string) => {
  try {
    sessionStorage.removeItem(getLastConversationKey(workspaceId));
  } catch {
    // 비울 수 없어도 화면 이동은 그대로 해요
  }
};

/** 로그아웃하면 불러 이 탭에 남은 모든 워크스페이스의 대화를 지워요(AUTH-R20) */
export const clearAllLastConversationIds = () => {
  try {
    Object.keys(sessionStorage)
      .filter((key) => key.startsWith(LAST_CONVERSATION_KEY_PREFIX))
      .forEach((key) => sessionStorage.removeItem(key));
  } catch {
    // 지우지 못해도 로그아웃은 이어 가요
  }
};
