import useEndOtherTabRecordingMutation from "@api/mutations/useEndOtherTabRecordingMutation";
import { TOAST_MESSAGES } from "@constants/toastMessages";
import useEndRecordingDialog from "@hooks/domain/recording/useEndRecordingDialog";
import { useToast } from "@provider/context/toastContext";
import { deleteRecordingChunks } from "@store/recordingStore/recordingChunkArchive";
import { isClosedRecordingError } from "@utils/isClosedRecordingError";
import { logRequestError } from "@utils/logRequestError";
import { postRecordingEnded } from "@utils/recordingChannel";
import { trackRecordingEndOtherTabResult } from "@utils/trackRecordingEndOtherTabResult";
import { forgetUnfinishedRecording } from "@utils/unfinishedRecording";
import { useCallback } from "react";

interface EndOtherTabRecordingParams {
  workspaceId: number;
  recordingId: number;
}

/**
 * 녹음 탭이 아닌 탭·기기에서 내 녹음을 끝내는 도메인 훅(STT-R28).
 *
 * 녹음 탭에서 끝낼 때와 같은 확인 창을 거쳐 본문 없이 종료를 보내요. 서버가 받은 소리까지로 문서를 만들어요.
 * 같은 브라우저의 녹음 탭에는 바로 알리고, 다른 기기의 녹음 탭은 다음 신호 응답에서 멈춰요.
 * 탭을 닫아 둔 녹음이면 이 브라우저에 남은 조각도 지워요.
 * 이미 끝난 녹음이면 끝낸 것과 같아 알리지 않고, 그 밖의 실패는 오류 토스트로 알려요.
 * 끝낸 결과(끝냄 · 이미 끝나 있었음 · 실패)는 사용 기록(`recording_end_other_tab_result`)으로도 보내요.
 */
const useEndOtherTabRecording = () => {
  const { openEndRecordingDialog } = useEndRecordingDialog();
  const { mutateAsync: endRecording } = useEndOtherTabRecordingMutation();
  const { show } = useToast();

  const endOtherTabRecording = useCallback(
    (recording: EndOtherTabRecordingParams) =>
      openEndRecordingDialog({
        onEnd: async () => {
          try {
            await endRecording(recording);
            trackRecordingEndOtherTabResult({ ...recording, error: null });
          } catch (error) {
            trackRecordingEndOtherTabResult({ ...recording, error });
            if (!isClosedRecordingError(error)) {
              logRequestError({
                feature: "recording",
                action: "다른 탭에서 녹음 종료",
                error,
              });
              show(TOAST_MESSAGES.RECORDING_END_FAILED);

              return;
            }
          }

          postRecordingEnded(recording);
          forgetUnfinishedRecording(recording.recordingId);
          void deleteRecordingChunks(recording.recordingId).catch(
            () => undefined,
          );
        },
      }),
    [endRecording, openEndRecordingDialog, show],
  );

  return { endOtherTabRecording };
};

export default useEndOtherTabRecording;
