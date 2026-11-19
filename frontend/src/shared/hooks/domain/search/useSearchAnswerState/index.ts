import { useSearchAnswerStore } from "@store/searchAnswerStore";
import { useShallow } from "zustand/react/shallow";

/**
 * 도착 중인 탐색 답변 내용을 읽는 도메인 훅. 대화 화면이 진행 중 턴을 그릴 때 써요.
 *
 * delta마다 값이 바뀌므로 답변을 그리는 곳에서만 불러요.
 * 전송 버튼처럼 진행 여부만 필요하면 `useSearchAnswerStatus`를 써서 delta에 다시 그려지지 않게 해요.
 */
const useSearchAnswerState = () =>
  useSearchAnswerStore(
    useShallow((state) => ({
      status: state.status,
      question: state.question,
      conversationId: state.conversationId,
      questionMessageId: state.questionMessageId,
      answerMessageId: state.answerMessageId,
      text: state.text,
      evidences: state.evidences,
      stage: state.stage,
      excludedDocumentCount: state.excludedDocumentCount,
      failedCode: state.failedCode,
      notionEvidences: state.notionEvidences,
      notionNotice: state.notionNotice,
      writeCard: state.writeCard,
    })),
  );

export default useSearchAnswerState;
