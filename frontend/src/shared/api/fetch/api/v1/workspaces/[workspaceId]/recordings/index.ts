import {
  GetRecordingsResponseDto,
  PostRecordingResponseDto,
  type GetRecordingsResponseRaw,
  type PostRecordingRequestDto,
  type PostRecordingResponseRaw,
} from "@api/dto/recording";
import { httpClient } from "@api/httpClient";

export const RECORDINGS_API_PATH = (workspaceId: number) =>
  `/api/v1/workspaces/${workspaceId}/recordings`;

interface GetRecordingsApiParams {
  workspaceId: number;
  /** 앞 응답의 `nextCursor`. 없으면 첫 페이지를 받아요 */
  cursor?: string;
}

/**
 * @description 워크스페이스의 녹음 기록을 녹음 시작 최신순으로 한 페이지(기본 20개) 조회합니다. 누가 녹음했는지와 관계없이 음성 저장이 끝난 녹음이 처리 상태와 함께 와요. 실패는 `400` 잘못된 cursor · `401` 미인증 · `403` 워크스페이스 멤버 아님 · `404` 워크스페이스 없음으로 구분해요
 * @param params - 워크스페이스 ID와 앞 응답의 커서
 * @returns 녹음 한 페이지와 다음 페이지 커서
 * @example
 * const { items, nextCursor } = await getRecordingsApi({ workspaceId: 1 });
 */
export const getRecordingsApi = async ({
  workspaceId,
  cursor,
}: GetRecordingsApiParams) => {
  const response = await httpClient<GetRecordingsResponseRaw>({
    method: "get",
    url: RECORDINGS_API_PATH(workspaceId),
    params: { cursor },
  });

  return new GetRecordingsResponseDto(response.data);
};

interface StartRecordingApiParams {
  workspaceId: number;
  body: PostRecordingRequestDto;
}

/**
 * @description 워크스페이스에서 내 녹음 세션을 시작합니다
 * @param params - 워크스페이스 ID·시작 요청 ID·최초 탭 ID·제어 증명
 * @returns 녹음 세션 ID·상태·서버가 확정한 시작 시각·새로 만든 녹음인지 여부
 * @example
 * const { recordingId } = await startRecordingApi({ workspaceId: 1, body });
 */
export const startRecordingApi = async ({
  workspaceId,
  body,
}: StartRecordingApiParams) => {
  const response = await httpClient<PostRecordingResponseRaw>({
    method: "post",
    url: RECORDINGS_API_PATH(workspaceId),
    data: body,
  });

  return new PostRecordingResponseDto(response.data, response.status);
};
