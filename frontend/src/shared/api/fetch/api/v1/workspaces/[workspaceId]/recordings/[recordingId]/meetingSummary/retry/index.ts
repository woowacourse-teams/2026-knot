import {
  PostMeetingSummaryRetryResponseDto,
  type PostMeetingSummaryRetryResponseRaw,
} from "@api/dto/recordingDocument";
import { httpClient } from "@api/httpClient";

export const MEETING_SUMMARY_RETRY_API_PATH = (
  workspaceId: number,
  recordingId: number,
) =>
  `/api/v1/workspaces/${workspaceId}/recordings/${recordingId}/meeting-summary/retry`;

interface RetryMeetingSummaryApiParams {
  workspaceId: number;
  recordingId: number;
}

/**
 * @description 실패한 회의 전체 요약을 다시 접수합니다. 접수(202)는 요약 성공이 아니라 결과는 녹음 문서 조회로 확인해요. 다시 시도할 수 없으면 `409` `MEETING_SUMMARY_RETRY_NOT_ALLOWED`로 답해요
 * @param params - 워크스페이스 ID·녹음 세션 ID
 * @returns 녹음 세션 ID와 접수 당시 요약 상태
 * @example
 * await retryMeetingSummaryApi({ workspaceId: 1, recordingId: 42 });
 */
export const retryMeetingSummaryApi = async ({
  workspaceId,
  recordingId,
}: RetryMeetingSummaryApiParams) => {
  const response = await httpClient<PostMeetingSummaryRetryResponseRaw>({
    method: "post",
    url: MEETING_SUMMARY_RETRY_API_PATH(workspaceId, recordingId),
  });

  return new PostMeetingSummaryRetryResponseDto(response.data);
};
