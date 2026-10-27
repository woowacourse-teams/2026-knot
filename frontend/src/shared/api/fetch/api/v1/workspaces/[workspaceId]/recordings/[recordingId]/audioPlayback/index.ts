import {
  GetRecordingAudioPlaybackResponseDto,
  type GetRecordingAudioPlaybackResponseRaw,
} from "@api/dto/recordingDocument";
import { httpClient } from "@api/httpClient";

export const RECORDING_AUDIO_PLAYBACK_API_PATH = (
  workspaceId: number,
  recordingId: number,
) =>
  `/api/v1/workspaces/${workspaceId}/recordings/${recordingId}/audio-playback`;

interface GetRecordingAudioPlaybackApiParams {
  workspaceId: number;
  recordingId: number;
}

/**
 * @description 녹음 음성을 재생할 서명 URL과 음성 상태를 조회합니다. URL이 만료되면 같은 요청으로 새 URL을 받아요. 저장소를 확인하지 못하면 `500` `AUDIO_STORAGE_UNAVAILABLE`로 답해요
 * @param params - 워크스페이스 ID·녹음 세션 ID
 * @returns 음성 상태·재생 URL·만료 시각·원본 형식·녹음 길이(ms)
 * @example
 * const { audioStatus, playbackUrl } = await getRecordingAudioPlaybackApi({ workspaceId: 1, recordingId: 42 });
 */
export const getRecordingAudioPlaybackApi = async ({
  workspaceId,
  recordingId,
}: GetRecordingAudioPlaybackApiParams) => {
  const response = await httpClient<GetRecordingAudioPlaybackResponseRaw>({
    method: "get",
    url: RECORDING_AUDIO_PLAYBACK_API_PATH(workspaceId, recordingId),
  });

  return new GetRecordingAudioPlaybackResponseDto(response.data);
};
