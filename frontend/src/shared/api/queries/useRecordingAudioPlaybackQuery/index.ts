import { getRecordingAudioPlaybackApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/audioPlayback";
import { recordingDocumentKeys } from "@api/queryKey/recordingDocument";
import { useQuery } from "@tanstack/react-query";

interface UseRecordingAudioPlaybackQueryParams {
  workspaceId: number;
  recordingId: number;
}

/**
 * 녹음 음성을 재생할 서명 URL과 음성 상태를 조회하는 쿼리 훅.
 *
 * URL에는 만료 시각이 있어, 창을 다시 보거나 다시 연결될 때 저절로 다시 받지 않게 해요.
 * 재생 중에 URL이 바뀌면 재생이 끊겨서예요. 만료된 URL은 재생할 때 `refetch`로 새로 받아요.
 *
 * 두 id가 정수인지는 확인하지 않고 그대로 요청해요. 잘못된 id인지는 서버가 판단해 400 · 404로 답해요.
 */
const useRecordingAudioPlaybackQuery = ({
  workspaceId,
  recordingId,
}: UseRecordingAudioPlaybackQueryParams) => {
  return useQuery({
    queryKey: recordingDocumentKeys.audioPlayback({ workspaceId, recordingId }),
    queryFn: () => getRecordingAudioPlaybackApi({ workspaceId, recordingId }),
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });
};

export default useRecordingAudioPlaybackQuery;
