import type { SearchAnswer, SearchMessage } from "@/shared/types/searchAnswer";

import type { SearchTurn } from "../types/searchTurn";
import { toSearchTurns } from "./toSearchTurns";

/**
 * 생성 중인 턴의 key. 질문 메시지 ID는 접수(accepted) 뒤에야 생겨서, 접수 전후로 같은 key를 쓰려고 고정해요.
 * 저장된 턴의 key는 질문 메시지 ID라 겹치지 않아요.
 */
const ANSWERING_TURN_KEY = "answering";

interface BuildSearchTurnsParams {
  /** 저장된 메시지. `sequence` 오름차순이에요 */
  messages: SearchMessage[];

  /** 전역 저장소의 도착 중인 답변 */
  answer: Pick<
    SearchAnswer,
    | "status"
    | "question"
    | "conversationId"
    | "questionMessageId"
    | "answerMessageId"
    | "text"
    | "evidences"
    | "notionEvidences"
    | "notionNotice"
    | "writeCard"
    | "stage"
  >;

  /** 지금 화면의 대화 ID. 질문 전의 새 대화면 없어요 */
  conversationId?: number;
}

/** 저장소의 답변으로 생성 중인 턴을 만들어요. 접수 전에는 질문만 있어요 */
const toAnsweringTurn = ({
  status,
  question,
  questionMessageId,
  answerMessageId,
  text,
  evidences,
  notionEvidences,
  notionNotice,
  writeCard,
  stage,
}: BuildSearchTurnsParams["answer"]): SearchTurn => {
  const turn = {
    key: ANSWERING_TURN_KEY,
    questionId: questionMessageId,
    question,
  };

  if (status === "IDLE" || status === "SENDING" || answerMessageId === null) {
    return { ...turn, answer: null };
  }

  return {
    ...turn,
    answer: {
      id: answerMessageId,
      content: text,
      status,
      evidences,
      notionEvidences,
      notionNotice,
      writeCard,
      stage,
    },
  };
};

/**
 * 저장된 메시지와 전역 저장소의 생성 중인 턴을 합쳐 화면에 그릴 턴 목록을 만듭니다.
 *
 * 저장소가 지금 화면의 대화에서 보낸 질문을 들고 있으면(첫 질문 접수 전에는 저장소와 화면 모두 대화가 없음)
 * 그 질문과 답변을 맨 뒤 턴으로 붙입니다.
 * 같은 질문이 조회 결과에도 있으면 그 턴 자리를 저장소의 턴으로 바꿔 두 번 보이지 않게 합니다.
 * 실패한 답변을 다시 요청하면 저장소가 접수 전부터 그 질문 ID를 들고 있어, 가운데 턴이어도 제자리에서 다시 그려집니다.
 * 저장소는 다음 질문까지 끝난 답변을 들고 있어, 답변이 끝나 다시 조회하는 동안에도 화면이 바뀌지 않습니다.
 * 다만 쓰기 확인 카드는 실행과 취소로 서버에서 바뀌므로, 바꿔 그린 턴의 조회 결과에 카드가 있으면 그 카드를 씁니다.
 *
 * @param params - 저장된 메시지, 저장소의 답변, 지금 화면의 대화 ID
 * @returns 저장된 턴 뒤에 생성 중인 턴을 붙인 목록
 *
 * @example
 * buildSearchTurns({ messages, answer, conversationId: 10 });
 * // [...저장된 턴, { key: "answering", questionId, question, answer }]
 */
export const buildSearchTurns = ({
  messages,
  answer,
  conversationId,
}: BuildSearchTurnsParams) => {
  const isAnsweringHere =
    answer.status !== "IDLE" &&
    answer.conversationId === (conversationId ?? null);

  if (!isAnsweringHere) return toSearchTurns(messages);

  const savedTurns = toSearchTurns(messages);
  const answeringTurn = toAnsweringTurn(answer);
  const replacedIndex = savedTurns.findIndex(
    ({ questionId }) =>
      questionId !== null && questionId === answer.questionMessageId,
  );

  if (replacedIndex === -1) return [...savedTurns, answeringTurn];

  const savedWriteCard = savedTurns[replacedIndex].answer?.writeCard;
  const replacingTurn =
    answeringTurn.answer && savedWriteCard
      ? {
          ...answeringTurn,
          answer: { ...answeringTurn.answer, writeCard: savedWriteCard },
        }
      : answeringTurn;

  return savedTurns.map((turn, index) =>
    index === replacedIndex ? replacingTurn : turn,
  );
};
