import {
  GetDocumentRecordingsResponseDto,
  type GetDocumentRecordingsResponseRaw,
} from "@api/dto/document";
import { httpClient } from "@api/httpClient";

export const DOCUMENT_RECORDINGS_API_PATH = (workspaceId: number) =>
  `/api/v1/workspaces/${workspaceId}/document-recordings`;

interface GetDocumentRecordingsApiParams {
  workspaceId: number;
  /** 앞 응답의 `nextCursor`. 없으면 첫 페이지를 받아요 */
  cursor?: string;
}

/**
 * @description 워크스페이스에서 문서가 1개 이상 나온 녹음을 녹음 시작 최신순으로 한 페이지(녹음 최대 20개) 조회합니다. 녹음마다 그 녹음에서 나온 문서 전부가 함께 와요. 한 페이지의 녹음 수는 서버가 정해 `size`를 보내지 않아요. 실패는 `400` 잘못된 cursor · `401` 미인증 · `403` 워크스페이스 멤버 아님으로 구분해요
 * @param params - 워크스페이스 ID와 앞 응답의 커서
 * @returns 녹음 묶음 한 페이지와 다음 페이지 커서
 * @example
 * const { items, nextCursor } = await getDocumentRecordingsApi({ workspaceId: 1 });
 */
export const getDocumentRecordingsApi = async ({
  workspaceId,
  cursor,
}: GetDocumentRecordingsApiParams) => {
  const response = await httpClient<GetDocumentRecordingsResponseRaw>({
    method: "get",
    url: DOCUMENT_RECORDINGS_API_PATH(workspaceId),
    params: { cursor },
  });

  return new GetDocumentRecordingsResponseDto(response.data);
};
