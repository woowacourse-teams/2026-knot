import {
  PatchSpeakerNameResponseDto,
  type PatchSpeakerNameResponseRaw,
  type PatchSpeakerNameRequestDto,
} from "@api/dto/recording";
import { httpClient } from "@api/httpClient";
import { RECORDING_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]";
export const RECORDING_SPEAKER_API_PATH = (
  workspaceId: number,
  recordingId: number,
  speakerNumber: number,
) =>
  `${RECORDING_API_PATH(workspaceId, recordingId)}/speakers/${speakerNumber}`;
/**
 * @description 녹음의 화자 표시명를 저장합니다
 * @param params - 녹음 식별자와 요청 본문
 * @returns 서버에 저장된 값
 * @example await changeSpeakerNameApi(params)
 */
export const changeSpeakerNameApi = async ({
  workspaceId,
  recordingId,
  body,
  speakerNumber,
}: { workspaceId: number; recordingId: number } & { speakerNumber: number } & {
  body: PatchSpeakerNameRequestDto;
}) => {
  const response = await httpClient<PatchSpeakerNameResponseRaw>({
    method: "patch",
    url: RECORDING_SPEAKER_API_PATH(workspaceId, recordingId, speakerNumber),
    data: body,
  });
  return new PatchSpeakerNameResponseDto(response.data);
};
