import type useCreateSearchConversationMutation from "@api/mutations/useCreateSearchConversationMutation";
import type useSearchMessagesQuery from "@api/queries/useSearchMessagesQuery";

/** 저장된 탐색 메시지 하나. 메시지 조회 쿼리의 응답 항목에서 추론해요 */
export type SearchMessage = NonNullable<
  ReturnType<typeof useSearchMessagesQuery>["data"]
>["items"][number];

/** 첫 질문·후속 질문 스트림이 함께 내는 이벤트 한 건. 뮤테이션의 `onEvent` 인자에서 추론해요 */
export type SearchAnswerStreamEvent = Parameters<
  Parameters<
    ReturnType<typeof useCreateSearchConversationMutation>["mutateAsync"]
  >[0]["onEvent"]
>[0];

/** 진행 단계. SEARCHING은 문서 검색 중, GENERATING은 답변 생성 중 */
export type SearchAnswerStage = Extract<
  SearchAnswerStreamEvent,
  { event: "progress" }
>["data"]["stage"];

/** 스트림으로 도착한 답변의 근거 문서 하나 */
export type SearchAnswerEvidence = Extract<
  SearchAnswerStreamEvent,
  { event: "evidence" }
>["data"]["items"][number];

/**
 * 도착 중인 답변의 상태.
 *
 * - `IDLE`: 보낸 질문이 없어요
 * - `SENDING`: 질문을 보냈고 서버의 접수 확인(accepted)을 기다리는 중이에요
 * - `STREAMING`·`COMPLETED`·`FAILED`·`STOPPED`: 저장된 답변 메시지의 상태(`SearchMessage["status"]`)와 같은 값이에요
 */
export type SearchAnswerStatus =
  | "IDLE"
  | "SENDING"
  | Extract<
      SearchMessage["status"],
      "STREAMING" | "COMPLETED" | "FAILED" | "STOPPED"
    >;

/** 질문 하나를 보내 답변이 끝날 때까지 쌓이는 값 */
export interface SearchAnswer {
  status: SearchAnswerStatus;

  /** 보낸 질문 내용. 보낸 질문이 없으면 빈 문자열이에요 */
  question: string;

  /** 탐색 대화 ID. 첫 질문은 accepted를 받기 전까지 `null`이에요 */
  conversationId: number | null;

  /** 저장된 질문 메시지 ID. accepted를 받기 전까지 `null`이에요 */
  questionMessageId: number | null;

  /** 채워지는 답변 메시지 ID. accepted를 받기 전까지 `null`이에요 */
  answerMessageId: number | null;

  /** 도착한 답변 조각을 이어 붙인 텍스트 */
  text: string;

  /** 답변의 근거 문서. 최대 3개 */
  evidences: SearchAnswerEvidence[];

  /** 지금 진행 단계. progress를 받기 전까지 `null`이에요 */
  stage: SearchAnswerStage | null;

  /** 근거에서 빠진 문서 수 */
  excludedDocumentCount: number;

  /** 답변 실패 오류 코드. `failed` 이벤트로 실패했을 때만 값이 있어요 */
  failedCode: string | null;
}

/** 질문을 보낸 곳. 독의 입력창(`dock`) 또는 탐색 튜토리얼의 질문 종류 카드(`tutorial`)예요. 사용 기록에 남겨요 */
export type SearchQuestionEntry = "dock" | "tutorial";
