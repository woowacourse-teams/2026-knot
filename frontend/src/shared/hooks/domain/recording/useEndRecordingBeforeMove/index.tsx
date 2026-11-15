import useKeyDown from "@hooks/common/useKeyDown";
import ConfirmDialog from "@primitives/ui/ConfirmDialog";
import Dim from "@primitives/ui/Dim";
import { useDialog } from "@provider/context/dialogContext";
import { useRecordingStore } from "@store/recordingStore";

import useRecordingControl from "../useRecordingControl";

const TITLE = "녹음을 끝내고 이동할까요?";

interface EndRecordingBeforeMoveDialogProps {
  onCancel: () => void;
  onConfirm: () => void;
}

/**
 * 녹음 중에 다른 워크스페이스로 옮겨 갈 때 녹음을 끝낼지 먼저 묻는 도메인 훅.
 *
 * 녹음은 워크스페이스를 가리지 않는 전역 저장소에 있어, 그대로 옮겨 가면 다른 워크스페이스에서 녹음이 이어져요.
 * 그래서 녹음 중(일시정지 포함)이면 [취소]/[끝내고 이동] 모달을 띄우고, 녹음이 없으면 묻지 않고 바로 이동해요.
 * [끝내고 이동]은 녹음 끝내기와 같이 녹음을 끝내 올린 뒤 이동하고, [취소]는 녹음과 화면을 그대로 둬요.
 * 종료 요청이 실패하면 녹음을 이어 가고 이동하지 않아요. 실패 안내(오류 토스트)는 `useRecordingControl`이 띄워요.
 *
 * 녹음 상태 하나만 필요해 `useRecordingState` 대신 저장소에서 바로 읽어요.
 */
const useEndRecordingBeforeMove = () => {
  const { open } = useDialog();
  const isRecordingActive = useRecordingStore(
    (state) => state.status !== "idle",
  );
  const { endRecordingWithoutConfirm } = useRecordingControl();

  /** 녹음이 없으면 `move`를 바로 부르고, 있으면 모달로 물은 뒤 끝내기로 하면 녹음을 끝내고 불러요 */
  const moveAfterEndingRecording = (move: () => void) => {
    if (!isRecordingActive) {
      move();
      return;
    }
    // 이미 끝내는 중이면 끝난 뒤의 이동과 겹치므로 확인 창을 띄우지 않아요
    if (useRecordingStore.getState().isEnding) return;

    open(({ close }) => (
      <EndRecordingBeforeMoveDialog
        onCancel={close}
        onConfirm={async () => {
          close();
          const result = await endRecordingWithoutConfirm("workspace_move");
          if (result !== "notEnded") move();
        }}
      />
    ));
  };

  return { moveAfterEndingRecording };
};

/**
 * 녹음 중 워크스페이스를 옮길 때의 확인 모달.
 *
 * 확인 유형이라 바깥을 누르거나 ESC를 눌러도 [취소]와 같이 모달만 닫혀요.
 *
 * @see {@link https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2017-35354 워크스페이스/녹음 중 전환 확인}
 */
function EndRecordingBeforeMoveDialog({
  onCancel,
  onConfirm,
}: EndRecordingBeforeMoveDialogProps) {
  useKeyDown({ key: "Escape", isEnabled: true, onKeyDown: onCancel });

  return (
    <Dim onClick={onCancel}>
      <ConfirmDialog
        role="dialog"
        aria-modal="true"
        aria-label={TITLE}
        onClick={(e) => e.stopPropagation()}
        title={TITLE}
        description={
          "녹음은 워크스페이스마다 따로 있어요.\n지금 녹음은 문서로 정리돼요."
        }
        cancelLabel="취소"
        confirmLabel="끝내고 이동"
        onCancel={onCancel}
        onConfirm={onConfirm}
      />
    </Dim>
  );
}

export default useEndRecordingBeforeMove;
