import useBeforeUnload from "@hooks/common/useBeforeUnload";
import { useRecordingStore } from "@store/recordingStore";

/**
 * 녹음 중·일시정지 상태나 끝낸 녹음의 조각을 아직 올리는 중에 새로고침하거나 탭을 닫으려 하면 확인 창을 띄우는 도메인 훅.
 *
 * 나가도 녹음은 바로 끝나지 않지만(STT-R28), 마이크가 꺼져 그동안의 소리는 녹음되지 않아요.
 * 저장소 값 하나만 필요해 `useRecordingState` 대신 저장소에서 바로 읽어요.
 */
const useRecordingLeaveWarning = () => {
  const isLeaveBlocked = useRecordingStore(
    (state) => state.status !== "idle" || state.isUploadingChunks,
  );

  useBeforeUnload({ isEnabled: isLeaveBlocked });
};

export default useRecordingLeaveWarning;
