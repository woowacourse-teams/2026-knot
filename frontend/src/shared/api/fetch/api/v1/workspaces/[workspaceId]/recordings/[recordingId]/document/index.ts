import {
  GetRecordingDocumentResponseDto,
  type GetRecordingDocumentResponseRaw,
} from "@api/dto/recordingDocument";
import { httpClient } from "@api/httpClient";

export const RECORDING_DOCUMENT_API_PATH = (
  workspaceId: number,
  recordingId: number,
) => `/api/v1/workspaces/${workspaceId}/recordings/${recordingId}/document`;

interface GetRecordingDocumentApiParams {
  workspaceId: number;
  recordingId: number;
}

/**
 * @description 녹음 하나의 녹음 문서(제목 · 처리 상태 · 회의 전체 요약)를 조회합니다. 실패는 `401` 미인증 · `403` 워크스페이스 멤버 아님 · `404` 없는 녹음이나 워크스페이스로 구분해요
 * @param params - 워크스페이스 ID·녹음 세션 ID
 * @returns 녹음 제목·시작 시각·녹음 길이·처리 상태·다시 시도 가능 여부·회의 전체 요약
 * @example
 * const { title, processingStatus } = await getRecordingDocumentApi({ workspaceId: 1, recordingId: 42 });
 */
export const getRecordingDocumentApi = async ({
  workspaceId,
  recordingId,
}: GetRecordingDocumentApiParams) => {
  const response = await httpClient<GetRecordingDocumentResponseRaw>({
    method: "get",
    url: RECORDING_DOCUMENT_API_PATH(workspaceId, recordingId),
  });

  return new GetRecordingDocumentResponseDto(response.data);
};
