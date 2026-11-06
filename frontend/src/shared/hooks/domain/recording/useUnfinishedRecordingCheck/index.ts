import { getRecordingApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]";
import { recordingKeys } from "@api/queryKey/recording";
import { TOAST_MESSAGES } from "@constants/toastMessages";
import { useToast } from "@provider/context/toastContext";
import { useRecordingStore } from "@store/recordingStore";
import {
  deleteRecordingChunks,
  readRecordingChunks,
} from "@store/recordingStore/recordingChunkArchive";
import { useQueryClient } from "@tanstack/react-query";
import { isUnreachableRecordingError } from "@utils/isUnreachableRecordingError";
import { logRequestError } from "@utils/logRequestError";
import {
  clearRecordingStartProof,
  getRecordingResumeProof,
} from "@utils/recordingControlProof";
import {
  forgetUnfinishedRecording,
  getUnfinishedRecording,
} from "@utils/unfinishedRecording";
import { useEffect } from "react";

/**
 * 워크스페이스에 들어올 때 이 브라우저에서 시작한 녹음의 마무리를 확인하는 도메인 훅.
 *
 * 녹음 탭을 닫거나 새로고침한 사이 서버가 녹음을 끝냈으면 이어 갈 수 없어, 남은 조각과 이어 녹음 증명을 지워요.
 * 연결 끊김으로 끝났으면 주의 토스트로 알리고, 올리지 못한 조각이 남아 있었으면 그것도 알려요(STT-R26). 아직 녹음 중이면 그대로 둬요.
 * 조회가 일시 실패하면 다음에 들어올 때 다시 확인해요.
 */
const useUnfinishedRecordingCheck = () => {
  const { show } = useToast();
  const queryClient = useQueryClient();

  useEffect(() => {
    const unfinished = getUnfinishedRecording();
    if (!unfinished) return;

    const { workspaceId, recordingId } = unfinished;
    // 이 탭이 맡고 있는 녹음은 생존 신호가 살펴요
    if (useRecordingStore.getState().session?.recordingId === recordingId) {
      return;
    }

    const forget = () => {
      forgetUnfinishedRecording(recordingId);
      void deleteRecordingChunks(recordingId).catch(() => undefined);
      if (getRecordingResumeProof()?.recordingId === recordingId) {
        clearRecordingStartProof();
      }
      void queryClient.invalidateQueries({
        queryKey: recordingKeys.current(workspaceId),
      });
    };

    getRecordingApi({ workspaceId, recordingId })
      .then(async ({ sessionStatus, endReason }) => {
        if (sessionStatus === "RECORDING" || sessionStatus === "PAUSED") return;

        const unsentChunks = await readRecordingChunks(recordingId).catch(
          () => [],
        );
        forget();
        if (endReason === "CONNECTION_EXPIRED") {
          show(TOAST_MESSAGES.RECORDING_CONNECTION_LOST);
          if (unsentChunks.length > 0) {
            show(TOAST_MESSAGES.RECORDING_CHUNKS_NOT_UPLOADED);
          }
        }
      })
      .catch((error: unknown) => {
        if (isUnreachableRecordingError(error)) {
          forget();

          return;
        }
        logRequestError({
          feature: "recording",
          action: "끝나지 않은 녹음 확인",
          error,
        });
      });
  }, [queryClient, show]);
};

export default useUnfinishedRecordingCheck;
