import {
  PostRecordingAudioChunkCompleteRequestDto,
  type PostRecordingAudioChunkCompleteRequestInput,
} from "@api/dto/recording";
import { completeRecordingAudioChunkApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/audioChunks/[sequence]/complete";
import { useMutation } from "@tanstack/react-query";

interface CompleteRecordingAudioChunkMutationVariables extends PostRecordingAudioChunkCompleteRequestInput {
  workspaceId: number;
  recordingId: number;
  sequence: number;
}

/**
 * PUT을 마친 PCM 조각의 저장을 서버에 확인받는 뮤테이션 훅.
 *
 * 서버가 저장소의 크기·checksum을 검증하고 `VERIFIED`로 기록해요. 이미 확인된 조각을 다시 보내도
 * 같은 결과를 돌려줘요. 오류는 `HttpError`의 `code`로 넘겨요. 무효화할 쿼리는 없어요.
 *
 * 조각 전송 큐가 일시 실패를 다시 보내므로, 실패는 큐가 골라 보고해요(`meta.errorReport`).
 */
const useCompleteRecordingAudioChunkMutation = () => {
  return useMutation({
    // 오프라인이어도 멈춰 두지 않고 실패로 끝내, 다시 보내기와 안내를 부르는 쪽이 정해요
    networkMode: "always",
    meta: { errorReport: "caller" },
    mutationFn: ({
      workspaceId,
      recordingId,
      sequence,
      ...input
    }: CompleteRecordingAudioChunkMutationVariables) =>
      completeRecordingAudioChunkApi({
        workspaceId,
        recordingId,
        sequence,
        body: new PostRecordingAudioChunkCompleteRequestDto(input),
      }),
  });
};

export default useCompleteRecordingAudioChunkMutation;
