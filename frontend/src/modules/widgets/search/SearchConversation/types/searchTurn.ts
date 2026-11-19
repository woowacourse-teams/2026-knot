import type {
  SearchAnswerStage,
  SearchMessage,
} from "@/shared/types/searchAnswer";

/** 턴의 답변. 저장된 답변 메시지와 생성 중인 답변을 같은 모양으로 담아요 */
export interface SearchTurnAnswer {
  /** 답변 메시지 ID. 찾은 기록을 펼칠 때 주소에 실어요 */
  id: number;

  content: string;

  /** 생성 중인 답변은 `STREAMING`이에요 */
  status: SearchMessage["status"];

  /** 근거 문서. 최대 3개, 관련도 순이에요 */
  evidences: SearchMessage["evidences"];

  /** 답에 쓴 노션 페이지. 서버가 준 순서 그대로예요 */
  notionEvidences: SearchMessage["notionEvidences"];

  /** 노션을 읽지 못해 회의 문서만으로 답한 사유. 해당 없으면 `null`이에요 */
  notionNotice: SearchMessage["notionNotice"];

  /** 이 답변에 붙은 쓰기 확인 카드. 없으면 `null`이에요 */
  writeCard: SearchMessage["writeCard"];

  /** 생성 중인 답변의 진행 단계. 저장된 답변이거나 progress를 받기 전이면 `null`이에요 */
  stage: SearchAnswerStage | null;
}

/** 화면에 그려지는 대화 단위. 질문 하나와 그에 대한 답변 하나를 묶어요 */
export interface SearchTurn {
  /** 목록 key. 생성 중인 턴은 접수 전후로 같은 값이라 접수돼도 다시 그려지지 않아요 */
  key: string;

  /** 질문 메시지 ID. 실패한 답변을 다시 요청할 때 써요. 접수 전에 보낸 질문은 아직 없어 `null`이에요 */
  questionId: number | null;

  /** 질문 내용 */
  question: string;

  /** 아직 답변이 없으면(접수 대기 중이거나 답변 메시지가 없으면) `null`이에요 */
  answer: SearchTurnAnswer | null;
}
