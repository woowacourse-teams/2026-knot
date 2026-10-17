import useDocumentsByRecordingQuery from "@api/queries/useDocumentsByRecordingQuery";
import useNavigateToDocument from "@hooks/domain/document/useNavigateToDocument";

interface UseDocumentStepperParams {
  workspaceId: number;
  /** 지금 보고 있는 문서의 ID */
  documentId: number;
  /** 지금 문서가 만들어진 녹음의 ID. 이 녹음에서 나온 문서들 사이를 넘겨요 */
  recordingSessionId: number;
}

/**
 * 같은 녹음에서 나온 문서들 가운데 지금 문서가 몇 번째인지와, 앞 · 다음 문서로 가는 동작을 돌려줘요.
 *
 * 문서 순서는 서버가 준 순서 그대로예요. 여기서 다시 정렬하지 않아요.
 * 이동은 방문 기록에 쌓여서, 뒤로 가면 앞에 보던 문서로 돌아가요.
 * 스테퍼는 정리 화면에서 넘어온 문서에만 있으므로, 넘어간 문서에도 같은 쿼리를 붙여 스테퍼가 남게 해요.
 * 같은 녹음의 문서를 아직 받지 못했거나 받지 못했으면 `position`이 `undefined`예요. 받은 문서에 지금 문서가 없을 때도 같아요.
 */
export const useDocumentStepper = ({
  workspaceId,
  documentId,
  recordingSessionId,
}: UseDocumentStepperParams) => {
  const { data: recordingDocumentList } = useDocumentsByRecordingQuery({
    workspaceId,
    recordingSessionId,
  });
  const { navigateToDocument } = useNavigateToDocument();

  const documentIds = recordingDocumentList?.items.map(({ id }) => id) ?? [];
  const currentIndex = documentIds.indexOf(documentId);

  /** 지금 문서에서 `offset`만큼 떨어진 문서로 가요. 그 자리에 문서가 없으면 아무것도 하지 않아요 */
  const goTo = (offset: number) => {
    const targetDocumentId = documentIds[currentIndex + offset];

    if (targetDocumentId === undefined) return;

    navigateToDocument({
      workspaceId,
      documentId: targetDocumentId,
      isFromRecording: true,
    });
  };

  return {
    position:
      currentIndex === -1
        ? undefined
        : { current: currentIndex + 1, total: documentIds.length },
    goToPrevious: () => goTo(-1),
    goToNext: () => goTo(1),
  };
};
