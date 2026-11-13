import {
  GetTeammateRecordingsResponseDto,
  type GetTeammateRecordingsResponseRaw,
} from "@api/dto/recording";
import { httpClient } from "@api/httpClient";

export const TEAMMATE_RECORDINGS_API_PATH = (workspaceId: number) =>
  `/api/v1/workspaces/${workspaceId}/recordings/teammates-in-progress`;

/**
 * @description 워크스페이스에서 나를 뺀 팀원의 녹음 중 · 일시정지 녹음을 모두 조회합니다(STT-R55)
 * @param workspaceId - 워크스페이스 ID
 * @returns 팀원 진행 중 녹음 목록. 없으면 `items`가 빈 배열
 * @example
 * const { items } = await getTeammateRecordingsApi(1);
 */
export const getTeammateRecordingsApi = async (workspaceId: number) => {
  const response = await httpClient<GetTeammateRecordingsResponseRaw>({
    method: "get",
    url: TEAMMATE_RECORDINGS_API_PATH(workspaceId),
  });

  return new GetTeammateRecordingsResponseDto(response.data);
};
