import { getRecordingApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]";
import { isHttpError } from "@api/httpClient/error";
import { recordingKeys } from "@api/queryKey/recording";
import { useQuery } from "@tanstack/react-query";
import { getRecordingDocumentsStatus } from "@utils/getRecordingDocumentsStatus";

/** 문서 정리가 끝나기 전의 녹음 상태를 다시 물어보는 간격(ms) */
const POLL_INTERVAL_MS = 3000;

interface UseRecordingQueryParams {
  workspaceId: number;
  recordingId: number;
  /** `false`면 저절로 조회하지 않고 `refetch`로 부를 때만 조회해요. 기본값은 `true` */
  isEnabled?: boolean;
}

/**
 * 녹음 하나의 상태를 조회하는 쿼리 훅.
 *
 * 녹음을 끝낸 뒤 문서 정리의 결과가 정해지지 않은 동안에는 3초 간격으로 다시 물어봐요.
 * 결과가 정해졌거나(문서를 모두 만듦 · 만들 내용 없음 · 실패) 아직 녹음 중이면 멈춰요.
 * 어디까지 됐는지는 정리 화면과 같은 기준(`getRecordingDocumentsStatus`)으로 봐요.
 *
 * 녹음 상태를 한 번도 받지 못했으면 저절로 다시 물어보지 않아요.
 * 받은 것이 없을 때 다시 물어보면 실패 기록이 지워져, 화면이 실패와 불러오는 중을 오가기 때문이에요.
 *
 * 정리 중인 상태를 받은 뒤에 조회가 실패하면 오류 종류로 나눠요.
 * 로그인 만료 · 권한 없음 · 없는 녹음처럼 다시 물어도 결과가 같은 오류(4xx)는 멈추고,
 * 네트워크 끊김이나 서버 오류는 잠깐의 문제일 수 있어 같은 간격으로 계속 물어봐요.
 *
 * 두 id가 정수인지는 확인하지 않고 그대로 요청해요. 잘못된 id인지는 서버가 판단해 400 · 404로 답해요.
 */
const useRecordingQuery = ({
  workspaceId,
  recordingId,
  isEnabled = true,
}: UseRecordingQueryParams) => {
  return useQuery({
    queryKey: recordingKeys.detail({ workspaceId, recordingId }),
    queryFn: () => getRecordingApi({ workspaceId, recordingId }),
    enabled: isEnabled,
    refetchInterval: (query) => {
      const { data, error } = query.state;

      // 다시 조회는 화면의 「다시 시도」와, 다시 연결되거나 창을 다시 볼 때의 조회가 맡아요
      if (data === undefined) return false;

      if (isHttpError(error) && error.isClientError) return false;

      const isOrganizing = getRecordingDocumentsStatus(data) === "organizing";

      return isOrganizing ? POLL_INTERVAL_MS : false;
    },
  });
};

export default useRecordingQuery;
