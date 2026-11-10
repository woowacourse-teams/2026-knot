import { uploadRecordingAudioChunkApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/audioChunks";
import { useMutation } from "@tanstack/react-query";

interface UploadRecordingAudioChunkMutationVariables {
  uploadUrl: string;
  requiredHeaders: Record<string, string>;
  pcm: ArrayBuffer;
}

/**
 * 예약 응답의 서명 URL로 PCM 조각을 객체 저장소에 직접 올리는 뮤테이션 훅.
 *
 * 우리 API가 아니라 요청 DTO가 없고, 실패는 axios 오류 그대로 넘겨요(이미 객체가 있으면 412).
 * PUT 성공은 저장 완료가 아니라서 이어서 저장 확인 뮤테이션을 보내야 해요.
 *
 * 조각 전송 큐가 실패하면 새 URL로 다시 올리므로, 실패는 큐가 골라 보고해요(`meta.errorReport`).
 */
const useUploadRecordingAudioChunkMutation = () => {
  return useMutation({
    // 오프라인이어도 멈춰 두지 않고 실패로 끝내, 다시 보내기와 안내를 부르는 쪽이 정해요
    networkMode: "always",
    meta: { errorReport: "caller" },
    mutationFn: (variables: UploadRecordingAudioChunkMutationVariables) =>
      uploadRecordingAudioChunkApi(variables),
  });
};

export default useUploadRecordingAudioChunkMutation;
