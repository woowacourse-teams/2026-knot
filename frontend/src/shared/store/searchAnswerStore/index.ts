import { SearchAnswerFailedError } from "@api/fetch/api/v1/workspaces/[workspaceId]/search/conversations";
import { create } from "zustand";

import type {
  SearchAnswer,
  SearchAnswerStatus,
  SearchAnswerStreamEvent,
} from "@/shared/types/searchAnswer";

import { reduceSearchStreamEvent } from "./reduceSearchStreamEvent";

interface SearchAnswerState extends SearchAnswer {
  /**
   * 지금 받고 있는 요청의 컨트롤러. 질문을 보내고 끝나기 전까지만 값이 있어요.
   * 스트림을 끊는 데도 쓰고, 동작을 부른 요청이 지금 요청인지 가리는 표식으로도 써요.
   */
  controller: AbortController | null;
}

/** 다시 요청할 실패한 답변의 메시지 ID */
export interface SearchAnswerRetryTarget {
  questionMessageId: number;
  answerMessageId: number;
}

interface StartSearchAnswerParams {
  question: string;

  /** 후속 질문·재시도면 이어 가는 대화 ID, 첫 질문이면 `null` */
  conversationId: number | null;

  /**
   * 실패한 답변을 다시 요청할 때 그 질문·답변 메시지 ID. 새 질문이면 없어요.
   * 접수 전부터 들고 있어야 화면이 저장된 실패 턴을 이 답변으로 바꿔 그려요
   */
  retryOf?: SearchAnswerRetryTarget;
}

/** 화면이 받고 있지 않은, 서버에서 만들고 있는 답변 */
export interface SearchAnswerResumeTarget {
  question: string;

  conversationId: number;

  questionMessageId: number;

  answerMessageId: number;

  /** 메시지 조회로 받은, 지금까지 저장된 본문 */
  text: string;
}

interface SearchAnswerActions {
  /**
   * 질문을 보내거나 실패한 답변을 다시 요청하며 이전 답변을 비우고 접수 대기로 바꾼 뒤, 이번 요청의 컨트롤러를 돌려줘요.
   * 이미 접수 대기·생성 중이면 아무것도 바꾸지 않고 `null`을 돌려줘요(SEARCH-R6).
   */
  start: (params: StartSearchAnswerParams) => AbortController | null;

  /**
   * 화면이 받고 있지 않은, 서버에서 만들고 있는 답변을 이어 받기 시작해요(SEARCH-R8).
   * 이미 접수된 답변이라 접수 대기를 거치지 않고 저장된 본문을 든 생성 중으로 바꾼 뒤, 이번 연결의 컨트롤러를 돌려줘요.
   * 이미 접수 대기·생성 중이면 아무것도 바꾸지 않고 `null`을 돌려줘요.
   */
  resume: (target: SearchAnswerResumeTarget) => AbortController | null;

  /** 도착한 이벤트 하나를 답변에 반영해요. 지금 요청의 이벤트가 아니면 버려요 */
  applyEvent: (
    controller: AbortController,
    event: SearchAnswerStreamEvent,
  ) => void;

  /** 완료·중지로 끝난 방식을 남기고 컨트롤러를 놓아요. 받은 답변은 그대로 둬요 */
  settle: (
    controller: AbortController,
    status: Extract<SearchAnswerStatus, "COMPLETED" | "STOPPED">,
  ) => void;

  /**
   * 요청 실패를 반영하고, accepted 전(접수 대기)에 실패했는지 돌려줘요.
   * accepted 전이면 서버에 질문이 저장되지 않았으므로 질문을 지워요(SEARCH-R4).
   * 입력창 복원과 안내는 질문을 보낸 쪽이 이 결과로 해요.
   * accepted 뒤면 검증되지 않은 답변과 출처를 지우고 안내를 표시할 상태로 바꿔요(SEARCH-R5). 지금 요청이 아니면 아무것도 바꾸지 않고 `false`예요.
   */
  fail: (controller: AbortController, error: unknown) => boolean;

  /**
   * 다시 연결해도 끝을 받지 못해 이어 받기를 그만두고 처음 상태로 돌아가요. 지금 요청이 아니면 아무것도 바꾸지 않아요.
   * 끝난 방식을 모르니 실패로 그리지 않고, 화면은 다시 조회한 저장된 메시지로 그 답변을 그려요.
   */
  release: (controller: AbortController) => void;

  /** 받고 있는 스트림을 끊고 처음 상태로 돌아가요. 서버는 답변을 계속 만들어요(SEARCH-R8) */
  abort: () => void;
}

const IDLE_STATE: SearchAnswerState = {
  status: "IDLE",
  question: "",
  conversationId: null,
  questionMessageId: null,
  answerMessageId: null,
  text: "",
  evidences: [],
  stage: null,
  excludedDocumentCount: 0,
  failedCode: null,
  controller: null,
};

/**
 * 지금 도착 중인 탐색 답변 하나를 담는 전역 저장소.
 *
 * 질문은 워크스페이스 독에서 보내고 답변은 대화 패널에 그려지는데, 둘이 서로 다른 구획이라 한곳에 두고 함께 읽어요.
 *
 * 상태 규칙은 모두 여기에 둬요. 이벤트를 답변에 반영하는 규칙은 같은 폴더의 `reduceSearchStreamEvent`가 맡아요.
 * 요청 하나가 끝나기 전에 끊거나 새 질문을 보내면 늦게 도착한 이벤트·결과가 새 답변을 덮어쓸 수 있어서,
 * 요청이 부르는 동작(`applyEvent`·`settle`·`fail`·`release`)은 받은 컨트롤러가 지금 컨트롤러와 같을 때만 반영해요.
 * 질문 전송 요청과 주소 이동은 `@hooks/domain/search/useSearchAnswerControl`이 맡아요.
 * 화면은 도메인 훅으로 읽고, 테스트는 답변이 테스트끼리 새지 않도록 `abort`로 초기화해요.
 */
export const useSearchAnswerStore = create<
  SearchAnswerState & SearchAnswerActions
>()((set, get) => {
  const isCurrent = (controller: AbortController) =>
    get().controller === controller;

  return {
    ...IDLE_STATE,

    start: ({ question, conversationId, retryOf }) => {
      const { status } = get();
      if (status === "SENDING" || status === "STREAMING") return null;

      const controller = new AbortController();
      set({
        ...IDLE_STATE,
        status: "SENDING",
        question,
        conversationId,
        questionMessageId: retryOf?.questionMessageId ?? null,
        answerMessageId: retryOf?.answerMessageId ?? null,
        controller,
      });

      return controller;
    },

    resume: (target) => {
      const { status } = get();
      if (status === "SENDING" || status === "STREAMING") return null;

      const controller = new AbortController();
      set({ ...IDLE_STATE, ...target, status: "STREAMING", controller });

      return controller;
    },

    applyEvent: (controller, event) => {
      if (!isCurrent(controller)) return;

      set(reduceSearchStreamEvent(get(), event));
    },

    settle: (controller, status) => {
      if (!isCurrent(controller)) return;

      set({ status, controller: null });
    },

    fail: (controller, error) => {
      if (!isCurrent(controller)) return false;

      if (get().status === "SENDING") {
        set(IDLE_STATE);
        return true;
      }

      set({
        status: "FAILED",
        text: "",
        evidences: [],
        failedCode:
          error instanceof SearchAnswerFailedError ? error.code : null,
        controller: null,
      });

      return false;
    },

    release: (controller) => {
      if (!isCurrent(controller)) return;

      set(IDLE_STATE);
    },

    abort: () => {
      get().controller?.abort();
      set(IDLE_STATE);
    },
  };
});
