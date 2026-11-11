import type {
  SearchTutorialStep,
  SearchTutorialVariant,
} from "@store/searchTutorialStore";

/** 멤버별 튜토리얼 기록을 남기는 `localStorage` 키 앞부분. 뒤에 멤버 ID를 붙여요 */
export const SEARCH_TUTORIAL_RECORD_KEY = "knot.searchTutorial";

/** 화면이 바뀐 뒤 튜토리얼을 띄우기까지 기다리는 시간(ms). 탭 전환 모션이 끝난 뒤에 떠야 깜빡이지 않아요 */
export const SEARCH_TUTORIAL_SETTLE_MS = 600;

/** 녹음 문서 화면에서 문서를 살짝 확인할 시간(ms). 이만큼 지나거나 한 번 스크롤하면 띄워요 */
export const SEARCH_TUTORIAL_DOCUMENT_DWELL_MS = 5000;

/** 2단계에서 예시 질문을 한 글자 넣는 간격(ms) */
export const SEARCH_TUTORIAL_TYPING_INTERVAL_MS = 55;

/** 예시 질문을 다 넣은 뒤 보내기까지 기다리는 시간(ms) */
export const SEARCH_TUTORIAL_SEND_DELAY_MS = 500;

/** 밝힌 영역과 대상 사이의 여백(px) */
export const SPOTLIGHT_PADDING = 6;

/** 안내 카드 폭(px). Figma Coach/Card와 같아요 */
export const COACH_CARD_WIDTH = 340;

/** 단계마다 밝히는 대상. 각 대상 요소에 `data-search-tutorial` 속성으로 이름이 붙어 있어요 */
export const SEARCH_TUTORIAL_TARGET = {
  searchTab: "search-tab",
  dockChat: "dock-chat",
  dockInput: "dock-input",
  conversation: "conversation",
  questionKinds: "question-kinds",
} as const;

export type SearchTutorialTarget =
  (typeof SEARCH_TUTORIAL_TARGET)[keyof typeof SEARCH_TUTORIAL_TARGET];

interface CoachCopy {
  title: string;
  description: string;
}

/** 단계별 안내 카드 문구. Figma Coach/Card와 같아요 */
export const COACH_COPY: Record<SearchTutorialStep, CoachCopy> = {
  1: {
    title: "회의 내용은 탐색에서 물어보세요",
    description: "예시 회의로 탐색을 어떻게 쓰는지 보여 드릴게요.",
  },
  2: {
    title: "회의 이름을 넣어 물어보면 돼요",
    description: "정리된 문서에서 찾아 답해요. 예시 질문을 대신 보내 볼게요.",
  },
  3: {
    title: "결론과 근거 문서를 함께 보여 줘요",
    description:
      "실제 답변에서는 카드를 눌러 원래 문서를 확인할 수 있어요. 문서에 없는 내용은 없다고 답해요.",
  },
  4: {
    title: "이제 직접 물어보세요",
    description:
      "지난 결정, 아직 못 정한 것, 다음 회의 준비까지 물어볼 수 있어요. 아래 질문을 눌러도 돼요.",
  },
};

/** 녹음 문서 화면에서 뜬 1단계는 그 회의를 가리키는 제목을 써요 */
export const DOCUMENT_COACH_TITLE = "이 회의 내용, 탐색에서 물어볼 수 있어요";

/** 1단계의 미루기 버튼 문구. 두 번째로 뜰 때는 미루지 않고 닫기만 해요 */
export const FIRST_STEP_DISMISS_LABEL: Record<SearchTutorialVariant, string> = {
  default: "다음에 볼게요",
  document: "다음에 볼게요",
  again: "닫기",
};

export const SEARCH_TUTORIAL_STEP_COUNT = 4;
