import { getAllDocumentsApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/documents";
import { documentKeys } from "@api/queryKey/document";
import { useEffect, useRef } from "react";
import { useQuery } from "@tanstack/react-query";

interface UseDocumentsByRecordingQueryParams {
  workspaceId: number;
  /** 문서가 나온 녹음의 ID */
  recordingSessionId: number;
  /** `false`면 조회하지 않아요. 녹음 정리가 끝나기 전처럼 아직 문서가 없을 때 꺼 둬요. 기본값은 `true` */
  isEnabled?: boolean;
  /** 주제별 문서를 생성 중이면 목록을 갱신해요. */
  isPolling?: boolean;
}

/**
 * 한 녹음에서 나온 문서를 조회하는 쿼리 훅. 워크스페이스의 문서 전체는 `useDocumentsQuery`로 조회해요.
 *
 * 문서 목록 API에 녹음 ID 조건을 붙여 요청해요.
 * 한 페이지가 아니라 다음 페이지가 없을 때까지 이어 받아요. 같은 녹음의 문서 사이를 옮겨 다니거나 첫 문서를 고르려면 전체가 있어야 해서예요.
 * 이어 받는 도중 한 요청이라도 실패하면 조회 전체가 실패예요.
 *
 * id가 정수인지는 확인하지 않고 그대로 요청해요. 잘못된 id인지는 서버가 판단해 400으로 답해요.
 */
const useDocumentsByRecordingQuery = ({
  workspaceId,
  recordingSessionId,
  isEnabled = true,
  isPolling = false,
}: UseDocumentsByRecordingQueryParams) => {
  const query = useQuery({
    queryKey: documentKeys.list({ workspaceId, recordingSessionId }),
    queryFn: () => getAllDocumentsApi({ workspaceId, recordingSessionId }),
    enabled: isEnabled,
    refetchInterval: isPolling ? 3000 : false,
  });
  const previous = useRef({ workspaceId, recordingSessionId, isPolling });
  const { refetch } = query;
  useEffect(() => {
    const last = previous.current;
    previous.current = { workspaceId, recordingSessionId, isPolling };
    // 마지막 목록 조회 뒤 생성이 끝나면 타이머가 취소되므로 최종 결과를 한 번 더 받아요.
    if (
      isEnabled &&
      last.workspaceId === workspaceId &&
      last.recordingSessionId === recordingSessionId &&
      last.isPolling &&
      !isPolling
    )
      void refetch();
  }, [workspaceId, recordingSessionId, isEnabled, isPolling, refetch]);
  return query;
};

export default useDocumentsByRecordingQuery;
