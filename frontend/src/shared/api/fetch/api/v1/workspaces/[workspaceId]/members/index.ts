import {
  GetWorkspaceMembersResponseDto,
  type GetWorkspaceMembersResponseRaw,
} from "@api/dto/workspace";
import { httpClient } from "@api/httpClient";
export const WORKSPACE_MEMBERS_API_PATH = (workspaceId: number) =>
  `/api/v1/workspaces/${workspaceId}/members`;
/**
 * @description 현재 워크스페이스 멤버를 가입 순서로 조회합니다
 * @param params - 워크스페이스와 다음 페이지 커서
 * @returns 본인을 포함한 멤버와 다음 커서
 * @example await getWorkspaceMembersApi({workspaceId: 1})
 */
export const getWorkspaceMembersApi = async ({
  workspaceId,
  cursor,
}: {
  workspaceId: number;
  cursor?: string | null;
}) => {
  const response = await httpClient<GetWorkspaceMembersResponseRaw>({
    method: "get",
    url: WORKSPACE_MEMBERS_API_PATH(workspaceId),
    params: { cursor: cursor ?? undefined, limit: 50 },
  });
  return new GetWorkspaceMembersResponseDto(response.data);
};
