import { TOAST_MESSAGES } from "@constants/toastMessages";
import useRecordingState from "@hooks/domain/recording/useRecordingState";
import { usePinnedToast } from "@provider/context/toastContext";

interface GetConnectionToastParams {
  isConnectionLost: boolean;
  isEnding: boolean;
  isChunkArchiveFailed: boolean;
}

const getConnectionToast = ({
  isConnectionLost,
  isEnding,
  isChunkArchiveFailed,
}: GetConnectionToastParams) => {
  if (!isConnectionLost) return null;
  if (isEnding) return TOAST_MESSAGES.RECORDING_ENDING_DISCONNECTED;

  return isChunkArchiveFailed
    ? TOAST_MESSAGES.RECORDING_ARCHIVE_FAILED
    : TOAST_MESSAGES.RECORDING_DISCONNECTED;
};

/**
 * 이 탭의 연결이 끊긴 동안 어느 화면에서든 X로 닫는 토스트로 알리는 도메인 훅(STT-R53).
 *
 * 끊겨도 녹음은 계속돼, 소리를 잃지 않는다는 것을 먼저 알려요.
 * 브라우저가 조각 보관을 거절했으면 탭을 닫을 때 소리를 잃을 수 있다고, 끊긴 채 끝내는 중이면 기다린다고 문구를 바꿔요.
 * 연결이 돌아오거나 녹음이 끝나면 닫아요. 녹음은 화면을 옮겨도 이어지므로 모든 화면을 감싸는 곳에서 불러요.
 *
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=6083-27373 Recorder/ConnectionToast
 */
const useRecordingConnectionToast = () => {
  const { isConnectionLost, isEnding, isChunkArchiveFailed } =
    useRecordingState();

  usePinnedToast({
    pinKey: "recordingConnection",
    toast: getConnectionToast({
      isConnectionLost,
      isEnding,
      isChunkArchiveFailed,
    }),
  });
};

export default useRecordingConnectionToast;
