import {
  PostRecordingReconnectResponseDto,
  type PostRecordingHeartbeatResponseRaw,
  type PostRecordingReconnectRequestDto,
} from "@api/dto/recording";
import { httpClient } from "@api/httpClient";

export const RECORDING_RECONNECT_API_PATH = (
  workspaceId: number,
  recordingId: number,
) => `/api/v1/workspaces/${workspaceId}/recordings/${recordingId}/reconnect`;

interface ReconnectRecordingApiParams {
  workspaceId: number;
  recordingId: number;
  body: PostRecordingReconnectRequestDto;
}

/**
 * @description 같은 탭을 새로고침한 뒤 녹음을 이어 받고 녹음 탭 증명을 새 값으로 바꿉니다
 * @param params - 워크스페이스 ID, 녹음 세션 ID, 이전 증명과 새 증명
 * @returns 서버 기준 녹음 세션 상태. 이미 끝난 녹음이면 증명을 바꾸지 않고 끝난 결과를 줘요
 * @example
 * const { status } = await reconnectRecordingApi({ workspaceId: 1, recordingId: 10, body });
 */
export const reconnectRecordingApi = async ({
  workspaceId,
  recordingId,
  body,
}: ReconnectRecordingApiParams) => {
  const response = await httpClient<PostRecordingHeartbeatResponseRaw>({
    method: "post",
    url: RECORDING_RECONNECT_API_PATH(workspaceId, recordingId),
    data: body,
  });

  return new PostRecordingReconnectResponseDto(response.data);
};
