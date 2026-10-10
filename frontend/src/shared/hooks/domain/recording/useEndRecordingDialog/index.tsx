import useKeyDown from "@hooks/common/useKeyDown";
import ConfirmDialog from "@primitives/ui/ConfirmDialog";
import Dim from "@primitives/ui/Dim";
import { useDialog } from "@provider/context/dialogContext";
import { useCallback } from "react";

const TITLE = "녹음을 끝낼까요?";

interface EndRecordingDialogProps {
  onClose: () => void;
  onEnd: () => void;
}

/**
 * 녹음을 끝내기 전에 [계속 녹음]/[녹음 끝내기]를 묻는 모달을 띄우는 도메인 훅.
 *
 * 녹음 화면의 녹음 바와 독의 녹음 칩에서 녹음을 끝낼 때 같은 모달을 써요.
 * [계속 녹음]은 모달만 닫고 녹음은 그대로 이어 가요.
 */
const useEndRecordingDialog = () => {
  const { open } = useDialog();

  const openEndRecordingDialog = useCallback(
    ({ onEnd }: OpenEndRecordingDialogParams) =>
      open(({ close }) => (
        <EndRecordingDialog
          onClose={close}
          onEnd={() => {
            close();
            onEnd();
          }}
        />
      )),
    [open],
  );

  return { openEndRecordingDialog };
};

/**
 * 녹음을 끝낼지 묻는 확인 모달.
 *
 * 확인 유형이라 바깥을 누르거나 ESC를 눌러도 [계속 녹음]과 같이 모달만 닫혀요.
 *
 * @see {@link https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2017-35790 녹음 종료/종료 확인}
 */
function EndRecordingDialog({ onClose, onEnd }: EndRecordingDialogProps) {
  useKeyDown({ key: "Escape", isEnabled: true, onKeyDown: onClose });

  return (
    <Dim onClick={onClose}>
      <ConfirmDialog
        role="dialog"
        aria-modal="true"
        aria-label={TITLE}
        onClick={(e) => e.stopPropagation()}
        title={TITLE}
        description="지금까지 녹음한 내용으로 바로 문서를 만들어요."
        cancelLabel="계속 녹음"
        confirmLabel="녹음 끝내기"
        onCancel={onClose}
        onConfirm={onEnd}
      />
    </Dim>
  );
}

interface OpenEndRecordingDialogParams {
  /** [녹음 끝내기]를 누르면 모달을 닫은 뒤 부를 함수. 녹음을 끝내요 */
  onEnd: () => void;
}

export default useEndRecordingDialog;
