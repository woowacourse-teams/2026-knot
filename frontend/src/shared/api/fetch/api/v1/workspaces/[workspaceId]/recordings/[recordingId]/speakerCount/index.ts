import {
  PatchSpeakerCountResponseDto,
  type PatchSpeakerCountResponseRaw,
  type PatchSpeakerCountRequestDto,
} from "@api/dto/recording";
import { httpClient } from "@api/httpClient";
import { RECORDING_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]";
export const RECORDING_SPEAKER_COUNT_API_PATH = (
  workspaceId: number,
  recordingId: number,
) => `${RECORDING_API_PATH(workspaceId, recordingId)}/speaker-count`;
/**
 * @description 녹음의 참가자 수를 저장합니다
 * @param params - 녹음 식별자와 요청 본문
 * @returns 서버에 저장된 값
 * @example await changeSpeakerCountApi(params)
 */
export const changeSpeakerCountApi = async ({
  workspaceId,
  recordingId,
  body,
}: { workspaceId: number; recordingId: number } & {
  body: PatchSpeakerCountRequestDto;
}) => {
  const response = await httpClient<PatchSpeakerCountResponseRaw>({
    method: "patch",
    url: RECORDING_SPEAKER_COUNT_API_PATH(workspaceId, recordingId),
    data: body,
  });
  return new PatchSpeakerCountResponseDto(response.data);
};
