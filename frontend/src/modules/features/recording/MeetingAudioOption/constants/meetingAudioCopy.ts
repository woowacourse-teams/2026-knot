/**
 * 회의 소리 함께 녹음의 화면 문구(STT-R44·R45·R47·R48·R49).
 *
 * 디자인이 정해지기 전의 임시 문구라, 테스트는 문구를 직접 적지 않고 이 값으로 찾아요.
 */
export const MEETING_AUDIO_COPY = {
  OPTION: "회의 소리도 함께 녹음",
  UNSUPPORTED:
    "회의 소리 함께 녹음은 컴퓨터의 Chrome, Edge 같은 Chrome 계열 브라우저에서 쓸 수 있어요",
  NOTICE_TITLE: "회의 소리도 함께 녹음할까요?",
  NOTICE_DESCRIPTION:
    "상대방 목소리도 함께 녹음돼요. 회의 참여자에게 녹음한다고 알려 주세요.\n스피커로 들으면 회의 소리가 마이크로 다시 들어가 겹치니 이어폰을 써 주세요.",
  NOTICE_CANCEL: "취소",
  NOTICE_CONFIRM: "공유할 화면 고르기",
  NO_AUDIO: "회의 소리를 받지 못해 마이크 소리만 녹음하고 있어요",
  STOPPED: "회의 소리 녹음이 멈췄어요. 마이크 소리는 계속 녹음하고 있어요",
} as const;
