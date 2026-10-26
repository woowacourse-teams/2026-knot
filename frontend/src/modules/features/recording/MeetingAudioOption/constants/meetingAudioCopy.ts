/**
 * 「녹음하는 소리」 패널과 온라인 회의 연결 안내의 화면 문구(STT-R44·R45·R47·R48·R49).
 *
 * Figma 「녹음 · 온라인 회의 소리도 함께 녹음」 시안(5403-3372)의 문구예요. 테스트도 문구를 직접 적지 않고 이 값으로 찾아요.
 */
export const MEETING_AUDIO_COPY = {
  PANEL_TITLE: "녹음하는 소리",
  MICROPHONE_TITLE: "마이크",
  MICROPHONE_DESCRIPTION: "이 자리에서 나누는 대화",
  MEETING_TITLE: "온라인 회의",
  OPTION: "함께 녹음하기",
  RECONNECT: "다시 연결하기",
  STATUS: {
    RECORDING: "녹음 중",
    PAUSED: "일시정지",
    NO_AUDIO: "소리 없음",
    STOPPED: "멈춤",
    UNSUPPORTED: "이 브라우저는 지원 안 함",
    MOBILE: "모바일은 지원 안 함",
  },
  MEETING_DESCRIPTION: {
    off: "Zoom, Google Meet 같은\n화상 회의 속 상대방 목소리",
    connecting: "브라우저 공유 창에서\n회의 화면을 골라 주세요",
    on: "이어폰으로 들어도 그대로 녹음돼요.\n브라우저에서 「공유 중지」를 누르면 멈춰요",
    paused: "녹음을 이어 가면 마이크 소리와 함께 다시 담아요",
    noAudio: "소리가 들어오지 않아요.\n공유 창에서 오디오 공유를 켜 주세요",
    stopped: "화면 공유가 끝나\n마이크 소리만 녹음하고 있어요",
    unsupported:
      "Chrome, Edge에서만 녹음할 수 있어요.\n다음 녹음은 컴퓨터의 Chrome이나 Edge에서 시작해 보세요",
    mobile:
      "온라인 회의 소리는 컴퓨터의 Chrome, Edge에서만 녹음할 수 있어요. 마이크 녹음은 그대로 돼요",
  },
  DIALOG: {
    TITLE: "온라인 회의 소리도 녹음할까요?",
    DESCRIPTION:
      "같은 공간의 대화는 마이크로 이미 녹음하고 있어요.\n화상 회의 속 상대방 목소리는 컴퓨터에서 나는 소리라\n회의 화면을 공유해서 연결해요.",
    LOCATION_QUESTION: "회의를 어디서 하고 있나요?",
    STEPS_TITLE: "다음에 열리는 공유 창에서",
    EXAMPLE_TITLE: "공유 창 예시",
    EXAMPLE_TABS: ["Chrome 탭", "창", "전체 화면"],
    EXAMPLE_AUDIO_HINT: "꼭 켜 주세요",
    NOTICES: [
      "참여자에게 녹음한다고 먼저 알려 주세요. 상대방 목소리도 함께 녹음돼요.",
      "이어폰을 끼고 들어도 상대방 목소리는 그대로 녹음돼요.\n스피커로 들으면 소리가 겹칠 수 있어 이어폰을 권해요.",
    ],
    CANCEL: "취소",
    CONFIRM: "공유 창 열기",
  },
  /** 회의 위치마다 공유 창에서 고를 것이 달라요. 예시의 탭은 `DIALOG.EXAMPLE_TABS` 중 하나예요 */
  LOCATIONS: {
    browserTab: {
      LABEL: "브라우저 탭",
      DESCRIPTION: "Google Meet, 웹 Zoom",
      STEPS: [
        "「Chrome 탭」을 골라요",
        "회의 중인 탭을 골라요",
        "「탭 오디오도 공유」를 켜고 「공유」를 눌러요",
      ],
      EXAMPLE_TAB: "Chrome 탭",
      EXAMPLE_ITEM: "Google Meet · 주간 회의",
      EXAMPLE_AUDIO: "탭 오디오도 공유",
    },
    meetingApp: {
      LABEL: "회의 앱",
      DESCRIPTION: "Zoom, Discord, Teams 앱",
      STEPS: [
        "「전체 화면」을 골라요",
        "「시스템 오디오도 공유」를 켜고 「공유」를 눌러요",
      ],
      EXAMPLE_TAB: "전체 화면",
      EXAMPLE_ITEM: "전체 화면",
      EXAMPLE_AUDIO: "시스템 오디오도 공유",
    },
  },
} as const;
