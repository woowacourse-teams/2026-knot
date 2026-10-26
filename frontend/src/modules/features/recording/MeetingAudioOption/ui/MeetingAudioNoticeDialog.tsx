import useKeyDown from "@hooks/common/useKeyDown";
import ConfirmDialog from "@primitives/ui/ConfirmDialog";
import Dim from "@primitives/ui/Dim";

import { MEETING_AUDIO_COPY } from "../constants/meetingAudioCopy";

interface MeetingAudioNoticeDialogProps {
  onClose: () => void;
  onConfirm: () => void;
}

/**
 * 공유 창을 열기 전에 녹음 고지와 이어폰 권장을 알리는 확인 모달(STT-R45).
 *
 * 확인 유형이라 바깥을 누르거나 ESC를 눌러도 [취소]와 같이 모달만 닫혀요. 디자인 전이라 Figma 화면은 없어요.
 */
export default function MeetingAudioNoticeDialog({
  onClose,
  onConfirm,
}: MeetingAudioNoticeDialogProps) {
  useKeyDown({ key: "Escape", isEnabled: true, onKeyDown: onClose });

  return (
    <Dim onClick={onClose}>
      <ConfirmDialog
        role="dialog"
        aria-modal="true"
        aria-label={MEETING_AUDIO_COPY.NOTICE_TITLE}
        onClick={(e) => e.stopPropagation()}
        title={MEETING_AUDIO_COPY.NOTICE_TITLE}
        description={MEETING_AUDIO_COPY.NOTICE_DESCRIPTION}
        cancelLabel={MEETING_AUDIO_COPY.NOTICE_CANCEL}
        confirmLabel={MEETING_AUDIO_COPY.NOTICE_CONFIRM}
        onCancel={onClose}
        onConfirm={onConfirm}
      />
    </Dim>
  );
}
