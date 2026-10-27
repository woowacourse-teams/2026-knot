/**
 * 녹음 화면의 화상 회의 줄과 연결 안내의 화면 문구(STT-R44·R45·R47·R48·R49).
 *
 * Figma 「녹음 · 온라인 회의 소리도 함께 녹음」 시안(5403-3372)의 문구예요. 테스트도 문구를 직접 적지 않고 이 값으로 찾아요.
 * 연결된 뒤 띄우는 이어폰 토스트는 `TOAST_MESSAGES.MEETING_AUDIO_CONNECTED`에 있어요.
 */
export const MEETING_AUDIO_COPY = {
  MEETING_TITLE: "화상 회의 상대방 목소리",
  OPTION: "함께 녹음하기",
  RECONNECT: "다시 연결하기",
  STATUS: {
    RECORDING: "녹음 중",
    PAUSED: "일시정지",
    NO_AUDIO: "소리 없음",
    STOPPED: "멈춤",
    UNSUPPORTED: "지원 안 함",
  },
  MEETING_DESCRIPTION: {
    off: "Zoom, Google Meet 같은 화상 회의 중이라면 상대방 목소리도 함께 녹음할 수 있어요",
    connecting: "브라우저 공유 창에서 회의 화면을 골라 주세요",
    on: "이어폰으로 들어도 그대로 녹음돼요. 그만 녹음하려면 브라우저의 「공유 중지」를 누르세요",
    paused: "녹음을 이어 가면 지금 대화와 함께 다시 녹음돼요",
    noAudio:
      "소리가 들어오지 않아요. 다시 연결할 때 「오디오도 공유」를 켜 주세요",
    stopped: "화면 공유가 끝났어요. 지금 대화는 계속 녹음하고 있어요",
    unsupported:
      "이 브라우저에서는 녹음할 수 없어요. 다음 녹음은 Chrome이나 Edge에서 시작해 보세요",
    mobile: "화상 회의 소리는 컴퓨터의 Chrome, Edge에서만 녹음할 수 있어요",
  },
  DIALOG: {
    TITLE: "화상 회의 상대방 목소리도 녹음할까요?",
    DESCRIPTION:
      "지금 나누는 대화는 마이크로 녹음되고 있어요.\n화상 회의 화면을 공유하면 상대방 목소리도 함께 녹음돼요.",
    LOCATION_QUESTION: "화상 회의를 어디서 열었나요?",
    STEPS_TITLE: "다음에 열리는 공유 창에서 이렇게 해 주세요",
    EXAMPLE_TITLE: "공유 창은 이렇게 생겼어요",
    EXAMPLE_TABS: ["Chrome 탭", "창", "전체 화면"],
    EXAMPLE_AUDIO_HINT: "꼭 켜 주세요",
    NOTICE: "녹음하기 전에 참여자에게 먼저 알려 주세요",
    CANCEL: "취소",
    CONFIRM: "공유할 화면 고르기",
  },
  /** 회의를 연 곳마다 공유 창에서 고를 것이 달라요. 예시의 탭은 `DIALOG.EXAMPLE_TABS` 중 하나예요 */
  LOCATIONS: {
    browserTab: {
      LABEL: "브라우저에서",
      DESCRIPTION: "Google Meet, 웹 Zoom",
      STEPS: [
        "위쪽에서 「Chrome 탭」을 눌러요",
        "회의 중인 탭을 골라요",
        "아래쪽 「탭 오디오도 공유」를 켜고 「공유」를 눌러요",
      ],
      EXAMPLE_TAB: "Chrome 탭",
      EXAMPLE_ITEM: "Google Meet · 주간 회의",
      EXAMPLE_AUDIO: "탭 오디오도 공유",
    },
    meetingApp: {
      LABEL: "설치한 앱에서",
      DESCRIPTION: "Zoom, Teams, Discord 앱",
      STEPS: [
        "위쪽에서 「전체 화면」을 눌러요",
        "아래쪽 「시스템 오디오도 공유」를 켜고 「공유」를 눌러요",
      ],
      EXAMPLE_TAB: "전체 화면",
      EXAMPLE_ITEM: "전체 화면",
      EXAMPLE_AUDIO: "시스템 오디오도 공유",
    },
  },
} as const;
