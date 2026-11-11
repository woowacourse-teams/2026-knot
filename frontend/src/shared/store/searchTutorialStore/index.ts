import { create } from "zustand";

/** 탐색 튜토리얼의 단계. 1 하이라이트, 2 예시 질문 입력, 3 예시 답변 설명, 4 직접 물어보기 */
export type SearchTutorialStep = 1 | 2 | 3 | 4;

/**
 * 1단계 안내 카드의 상황.
 * - `default`: 처음 뜰 때
 * - `document`: 녹음 문서 화면에서 뜰 때. 제목이 「이 회의 내용, 탐색에서 물어볼 수 있어요」예요
 * - `again`: 「다음에 볼게요」를 누른 뒤 두 번째로 뜰 때. 미루기 대신 닫기만 둬요
 */
export type SearchTutorialVariant = "default" | "document" | "again";

/** 튜토리얼이 시작된 곳. 사용 기록에 남겨요 */
export type SearchTutorialSource =
  | "home"
  | "search"
  | "documents"
  | "document"
  | "recording_document"
  | "replay";

interface StartParams {
  source: SearchTutorialSource;
  variant: SearchTutorialVariant;
}

interface SearchTutorialState {
  /** 지금 단계. 튜토리얼이 떠 있지 않으면 `null`이에요 */
  step: SearchTutorialStep | null;
  variant: SearchTutorialVariant;
  source: SearchTutorialSource | null;

  /** 2단계에서 독 입력창에 한 글자씩 넣고 있는 예시 질문. 넣고 있지 않으면 `null`이에요 */
  draft: string | null;

  /** 3단계의 예시 답변을 다 보여 줬는지. 다 보여 준 뒤에 안내 카드를 띄워요 */
  isAnswerShown: boolean;

  start: (params: StartParams) => void;
  goTo: (step: SearchTutorialStep) => void;
  setDraft: (draft: string | null) => void;
  markAnswerShown: () => void;
  /** 튜토리얼을 닫아요. 저장소 기록과 사용 기록은 부른 쪽이 남겨요 */
  end: () => void;
}

const INITIAL_STATE = {
  step: null,
  variant: "default",
  source: null,
  draft: null,
  isAnswerShown: false,
} as const;

/**
 * 탐색 튜토리얼의 진행 상태를 담는 전역 저장소.
 *
 * 튜토리얼은 GNB, 독, 탐색 대화 칸을 함께 바꿔서, 여러 위젯이 같은 단계를 봐야 해요.
 * 그래서 단계와 예시 질문 입력 상태를 여기에 두고, 각 위젯은 필요한 값만 읽어요.
 */
export const useSearchTutorialStore = create<SearchTutorialState>((set) => ({
  ...INITIAL_STATE,

  start: ({ source, variant }) =>
    set({ ...INITIAL_STATE, step: 1, source, variant }),

  goTo: (step) => set({ step, draft: null, isAnswerShown: false }),

  setDraft: (draft) => set({ draft }),

  markAnswerShown: () => set({ isAnswerShown: true }),

  end: () => set(INITIAL_STATE),
}));

/** 튜토리얼이 떠 있는지. 독 첫 사용 안내처럼 같은 자리를 쓰는 안내는 이 동안 띄우지 않아요 */
export const useIsSearchTutorialActive = () =>
  useSearchTutorialStore((state) => state.step !== null);
