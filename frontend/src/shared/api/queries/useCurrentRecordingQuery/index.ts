import { getCurrentRecordingApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/current";
import { isHttpError } from "@api/httpClient/error";
import { recordingKeys } from "@api/queryKey/recording";
import { useQuery } from "@tanstack/react-query";

/** 문서 정리 중인 녹음을 다시 물어보는 간격(ms). 정리 화면의 녹음 조회와 같아요 */
const POLL_INTERVAL_MS = 3000;

interface UseCurrentRecordingQueryParams {
  workspaceId: number;
}

/**
 * 워크스페이스에서 내 화면에 표시할 현재 녹음 한 건을 조회하는 쿼리 훅.
 *
 * 홈의 「진행 중인 녹음」 카드가 녹음 상태와 누적 녹음 시간을 보여 주는 데 써요.
 * 표시할 녹음이 없으면(204) `data`가 `null`이에요.
 * 문서 정리 중(`PROCESSING`)인 동안에는 3초 간격으로 다시 물어봐요. 정리가 끝나면 서버가 204로 답해 멈춰요.
 * 다시 물어봐도 결과가 같은 오류(4xx: 로그인 만료 · 권한 없음)를 받으면 멈춰요. 실패해도 받은 정리 중 응답이 남아 있어 멈추지 않으면 끝없이 물어봐요.
 * 네트워크 끊김이나 서버 오류는 잠깐의 문제일 수 있어 같은 간격으로 계속 물어봐요.
 * 녹음 중 · 일시정지는 시간을 화면에서 늘리므로 다시 물어보지 않아요.
 * 전사 실패 · 문서 생성 실패는 사용자가 다시 시도하기 전에는 바뀌지 않아 다시 물어보지 않아요.
 * 다시 시도가 접수되면 재시도 뮤테이션이 녹음 쿼리를 무효화해 다시 받아요.
 *
 * `workspaceId`가 정수인지는 검사하지 않아요. `/workspace/abc` 같은 잘못된 주소여도 요청하고, 판단은 서버 응답에 맡겨요.
 */
const useCurrentRecordingQuery = ({
  workspaceId,
}: UseCurrentRecordingQueryParams) => {
  return useQuery({
    queryKey: recordingKeys.current(workspaceId),
    queryFn: () => getCurrentRecordingApi(workspaceId),
    refetchInterval: ({ state: { data, error } }) => {
      if (isHttpError(error) && error.isClientError) return false;

      return data?.status === "PROCESSING" ? POLL_INTERVAL_MS : false;
    },
  });
};

export default useCurrentRecordingQuery;
