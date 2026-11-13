import { endRecordingApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/end";
import { recordingKeys } from "@api/queryKey/recording";
import { useMutation, useQueryClient } from "@tanstack/react-query";

interface EndOtherTabRecordingMutationVariables {
  workspaceId: number;
  recordingId: number;
}

/**
 * 다른 탭·기기에서 내 녹음을 끝내는 뮤테이션 훅(STT-R28).
 *
 * 녹음 탭의 증명 없이 본문 없이 보내요. 서버가 받은 소리까지로 문서를 만들어요.
 * 맨 위 줄과 목록이 바뀌므로 성공하면 녹음 쿼리를 다시 받아요.
 * 이미 끝난 녹음의 거절은 끝낸 것과 같아 부르는 쪽이 골라 보고해요(`meta.errorReport`).
 */
const useEndOtherTabRecordingMutation = () => {
  const queryClient = useQueryClient();

  return useMutation({
    meta: { errorReport: "caller" },
    mutationFn: ({
      workspaceId,
      recordingId,
    }: EndOtherTabRecordingMutationVariables) =>
      endRecordingApi({ workspaceId, recordingId }),
    onSettled: () =>
      queryClient.invalidateQueries({ queryKey: recordingKeys.all }),
  });
};

export default useEndOtherTabRecordingMutation;
