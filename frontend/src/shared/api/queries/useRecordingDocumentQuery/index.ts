import { getRecordingDocumentApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/document";
import { isHttpError } from "@api/httpClient/error";
import { recordingDocumentKeys } from "@api/queryKey/recordingDocument";
import { useQuery } from "@tanstack/react-query";

/** 처리나 요약이 끝나기 전의 녹음 문서를 다시 물어보는 간격(ms). 정리 화면의 녹음 조회와 같아요 */
const POLL_INTERVAL_MS = 3000;

interface UseRecordingDocumentQueryParams {
  workspaceId: number;
  recordingId: number;
  /** `false`면 조회하지 않아요. 녹음 문서 화면이 아닐 때 꺼 둬요. 기본값은 `true` */
  isEnabled?: boolean;
  /**
   * `true`면 처리나 요약이 끝나기 전까지 3초마다 다시 물어봐요. 기본값은 `false`.
   * 다시 묻는 시계는 부르는 곳마다 따로 돌아서, 같은 화면에서 한 곳(녹음 문서 섹션)만 켜요. 다른 곳은 그 결과를 캐시로 함께 받아요
   */
  isPolling?: boolean;
}

/**
 * 녹음 하나의 녹음 문서(제목 · 처리 상태 · 회의 전체 요약)를 조회하는 쿼리 훅.
 *
 * `isPolling`을 켜면 처리 중(`PROCESSING`)이거나 회의 전체 요약을 만드는 중(`QUEUED` · `RUNNING`)일 때 3초 간격으로 다시 물어봐요.
 * 다시 시도를 접수해 정리 중이나 만드는 중으로 바뀐 뒤 결과가 나오면 화면이 저절로 바뀌어요. 결과가 정해지면 멈춰요.
 * 받은 것이 없거나, 다시 물어도 결과가 같은 오류(4xx)를 받았으면 멈춰요. 네트워크 끊김이나 서버 오류는 같은 간격으로 계속 물어봐요.
 *
 * 두 id가 정수인지는 확인하지 않고 그대로 요청해요. 잘못된 id인지는 서버가 판단해 400 · 404로 답해요.
 */
const useRecordingDocumentQuery = ({
  workspaceId,
  recordingId,
  isEnabled = true,
  isPolling = false,
}: UseRecordingDocumentQueryParams) => {
  return useQuery({
    queryKey: recordingDocumentKeys.detail({ workspaceId, recordingId }),
    queryFn: () => getRecordingDocumentApi({ workspaceId, recordingId }),
    enabled: isEnabled,
    refetchInterval: (query) => {
      if (!isPolling) return false;

      const { data, error } = query.state;

      if (data === undefined) return false;

      if (isHttpError(error) && error.isClientError) return false;

      const summaryStatus = data.meetingSummary?.status;
      const isWaiting =
        data.processingStatus === "PROCESSING" ||
        summaryStatus === "QUEUED" ||
        summaryStatus === "RUNNING";

      return isWaiting ? POLL_INTERVAL_MS : false;
    },
  });
};

export default useRecordingDocumentQuery;
