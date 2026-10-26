import {
  GetRecordingTranscriptResponseDto,
  type GetRecordingTranscriptResponseRaw,
} from "@api/dto/recordingDocument";
import { httpClient } from "@api/httpClient";

export const RECORDING_TRANSCRIPT_API_PATH = (
  workspaceId: number,
  recordingId: number,
) => `/api/v1/workspaces/${workspaceId}/recordings/${recordingId}/transcript`;

interface GetRecordingTranscriptApiParams {
  workspaceId: number;
  recordingId: number;
}

/**
 * @description 녹음 하나의 전사 전체를 조회합니다. 전사 전 · 전사 실패 · 주제 분류 전이나 실패 · 정리할 내용 없음이면 `404` `RECORDING_TRANSCRIPT_NOT_FOUND`로 답해요
 * @param params - 워크스페이스 ID·녹음 세션 ID
 * @returns 전사 원문 ID·시작 시각 순서의 발화 구간
 * @example
 * const { segments } = await getRecordingTranscriptApi({ workspaceId: 1, recordingId: 42 });
 */
export const getRecordingTranscriptApi = async ({
  workspaceId,
  recordingId,
}: GetRecordingTranscriptApiParams) => {
  const response = await httpClient<GetRecordingTranscriptResponseRaw>({
    method: "get",
    url: RECORDING_TRANSCRIPT_API_PATH(workspaceId, recordingId),
  });

  return new GetRecordingTranscriptResponseDto(response.data);
};
