import {
  PostSearchNotionConnectionAuthorizationResponseDto,
  type PostSearchNotionConnectionAuthorizationRequestDto,
  type PostSearchNotionConnectionAuthorizationResponseRaw,
} from "@api/dto/search";
import { httpClient } from "@api/httpClient";

export const WORKSPACE_SEARCH_NOTION_CONNECTION_AUTHORIZATION_API_PATH = (
  workspaceId: number,
) =>
  `/api/v1/workspaces/${workspaceId}/search/notion-connection/authorization`;

interface StartSearchNotionConnectionApiParams {
  workspaceId: number;
  body: PostSearchNotionConnectionAuthorizationRequestDto;
}

/**
 * @description 보이는 범위 안내에 동의한 멤버의 노션 연결이나 만료된 연결의 다시 연결을 시작해요. 연결됨이면 409 NOTION_CONNECTION_ALREADY_CONNECTED예요
 * @param params - 워크스페이스 ID, 동의 요청 본문
 * @returns 화면이 이동할 노션 동의 화면 주소
 * @example
 * const { authorizationUrl } = await startSearchNotionConnectionApi({ workspaceId: 1, body });
 */
export const startSearchNotionConnectionApi = async ({
  workspaceId,
  body,
}: StartSearchNotionConnectionApiParams) => {
  const response =
    await httpClient<PostSearchNotionConnectionAuthorizationResponseRaw>({
      method: "post",
      url: WORKSPACE_SEARCH_NOTION_CONNECTION_AUTHORIZATION_API_PATH(
        workspaceId,
      ),
      data: body,
    });

  return new PostSearchNotionConnectionAuthorizationResponseDto(response.data);
};
