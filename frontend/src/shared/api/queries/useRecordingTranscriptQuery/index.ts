import { getRecordingTranscriptApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/transcript";
import { recordingDocumentKeys } from "@api/queryKey/recordingDocument";
import { useQuery } from "@tanstack/react-query";

interface UseRecordingTranscriptQueryParams {
  workspaceId: number;
  recordingId: number;
  /** `false`면 조회하지 않아요. 전사가 없는 처리 상태(정리 중 · 전사 실패 · 정리할 내용 없음)에서 꺼 둬요. 기본값은 `true` */
  isEnabled?: boolean;
}

/**
 * 녹음 하나의 전사 전체를 조회하는 쿼리 훅.
 *
 * 전사 전 · 전사 실패 · 주제 분류 전이나 실패 · 정리할 내용 없음이면 서버가 404 `RECORDING_TRANSCRIPT_NOT_FOUND`로 답해요.
 * 녹음 문서 화면은 이 404로 주제 분류 실패를 알아내요.
 *
 * 두 id가 정수인지는 확인하지 않고 그대로 요청해요. 잘못된 id인지는 서버가 판단해 400 · 404로 답해요.
 */
const useRecordingTranscriptQuery = ({
  workspaceId,
  recordingId,
  isEnabled = true,
}: UseRecordingTranscriptQueryParams) => {
  return useQuery({
    queryKey: recordingDocumentKeys.transcript({ workspaceId, recordingId }),
    queryFn: () => getRecordingTranscriptApi({ workspaceId, recordingId }),
    enabled: isEnabled,
  });
};

export default useRecordingTranscriptQuery;
