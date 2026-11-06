import {
  PostRecordingEndRequestDto,
  type PostRecordingEndRequestInput,
} from "@api/dto/recording";
import { endRecordingApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/end";
import { useMutation } from "@tanstack/react-query";

interface EndRecordingMutationVariables extends PostRecordingEndRequestInput {
  workspaceId: number;
  recordingId: number;
}

/**
 * 최초 녹음 탭에서 녹음 세션을 종료하는 뮤테이션 훅.
 *
 * 종료 성공은 업로드 완료를 뜻하지 않아,
 * 최종 오디오는 이어서 업로드 URL을 받아 올려야 해요. 무효화할 쿼리는 없어요.
 *
 * PCM 녹음은 일시 실패를 간격을 두고 다시 보내므로, 실패는 부르는 쪽이 골라 보고해요(`meta.errorReport`).
 */
const useEndRecordingMutation = () => {
  return useMutation({
    // 오프라인이어도 멈춰 두지 않고 실패로 끝내, 다시 보내기와 안내를 부르는 쪽이 정해요
    networkMode: "always",
    meta: { errorReport: "caller" },
    mutationFn: ({
      workspaceId,
      recordingId,
      ...input
    }: EndRecordingMutationVariables) =>
      endRecordingApi({
        workspaceId,
        recordingId,
        body: new PostRecordingEndRequestDto(input),
      }),
  });
};

export default useEndRecordingMutation;
