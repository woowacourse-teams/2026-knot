import {
  GetSearchNotionConnectionResponseDto,
  type GetSearchNotionConnectionResponseRaw,
} from "@api/dto/search";
import { httpClient } from "@api/httpClient";

export const WORKSPACE_SEARCH_NOTION_CONNECTION_API_PATH = (
  workspaceId: number,
) => `/api/v1/workspaces/${workspaceId}/search/notion-connection`;

/**
 * @description 워크스페이스의 노션 연결을 조회합니다. 연결이 없어도 200이고 status가 NOT_CONNECTED예요
 * @param workspaceId - 워크스페이스 ID
 * @returns 연결 상태, 연결한 노션 워크스페이스의 이름과 아이콘, 연결한 사람, 지금 멤버가 끊을 수 있는지
 * @example
 * const { status, canDisconnect } = await getSearchNotionConnectionApi(1);
 */
export const getSearchNotionConnectionApi = async (workspaceId: number) => {
  const response = await httpClient<GetSearchNotionConnectionResponseRaw>({
    method: "get",
    url: WORKSPACE_SEARCH_NOTION_CONNECTION_API_PATH(workspaceId),
  });

  return new GetSearchNotionConnectionResponseDto(response.data);
};

/**
 * @description 워크스페이스의 노션 연결을 끊습니다. 연결한 사람과 OWNER만 끊을 수 있고 만료된 연결도 끊을 수 있어요. 성공 시 응답 본문은 없어요(204)
 * @param workspaceId - 워크스페이스 ID
 * @example
 * await disconnectSearchNotionConnectionApi(1);
 */
export const disconnectSearchNotionConnectionApi = async (
  workspaceId: number,
) => {
  await httpClient({
    method: "delete",
    url: WORKSPACE_SEARCH_NOTION_CONNECTION_API_PATH(workspaceId),
  });
};
