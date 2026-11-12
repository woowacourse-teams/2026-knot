import { getTeammateRecordingsApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/teammatesInProgress";
import { isHttpError } from "@api/httpClient/error";
import { recordingKeys } from "@api/queryKey/recording";
import { useQuery } from "@tanstack/react-query";

/** 팀원 진행 중 녹음을 다시 물어보는 간격(ms). 내 녹음의 다른 탭 확인과 같은 30초예요 */
const POLL_INTERVAL_MS = 30 * 1000;

interface UseTeammateRecordingsQueryParams {
  workspaceId: number;
}

/**
 * 워크스페이스에서 나를 뺀 팀원의 녹음 중 · 일시정지 녹음을 조회하는 쿼리 훅(STT-R55).
 *
 * 홈 녹음 기록 카드의 팀원 줄이 써요. 팀원이 언제 녹음을 시작할지 모르니 홈을 연 동안 30초마다, 창으로 돌아올 때도 다시 물어봐요.
 * 로그인 만료 · 권한 없음(4xx)을 받으면 다시 물어도 같아서 멈춰요.
 */
const useTeammateRecordingsQuery = ({
  workspaceId,
}: UseTeammateRecordingsQueryParams) => {
  return useQuery({
    queryKey: recordingKeys.teammates(workspaceId),
    queryFn: () => getTeammateRecordingsApi(workspaceId),
    refetchInterval: ({ state: { error } }) =>
      isHttpError(error) && error.isClientError ? false : POLL_INTERVAL_MS,
    refetchOnWindowFocus: true,
  });
};

export default useTeammateRecordingsQuery;
