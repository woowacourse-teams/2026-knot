import {
  PostRecordingReconnectRequestDto,
  type PostRecordingReconnectRequestInput,
} from "@api/dto/recording";
import { reconnectRecordingApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/reconnect";
import { useMutation } from "@tanstack/react-query";

interface ReconnectRecordingMutationVariables extends PostRecordingReconnectRequestInput {
  workspaceId: number;
  recordingId: number;
}

/**
 * 같은 탭을 새로고침한 뒤 녹음을 이어 받는 뮤테이션 훅.
 *
 * 다른 탭이 먼저 이어 받은 거절(403)은 안내로 끝나는 정상 흐름이라, 실패는 부르는 쪽이 골라 보고해요(`meta.errorReport`).
 */
const useReconnectRecordingMutation = () => {
  return useMutation({
    // 오프라인이어도 멈춰 두지 않고 실패로 끝내, 다시 보내기와 안내를 부르는 쪽이 정해요
    networkMode: "always",
    meta: { errorReport: "caller" },
    mutationFn: ({
      workspaceId,
      recordingId,
      ...input
    }: ReconnectRecordingMutationVariables) =>
      reconnectRecordingApi({
        workspaceId,
        recordingId,
        body: new PostRecordingReconnectRequestDto(input),
      }),
  });
};

export default useReconnectRecordingMutation;
