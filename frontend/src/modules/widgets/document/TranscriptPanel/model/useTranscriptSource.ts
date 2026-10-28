import useDocumentQuery from "@api/queries/useDocumentQuery";
import useDocumentTranscriptQuery from "@api/queries/useDocumentTranscriptQuery";
import useRecordingDocumentQuery from "@api/queries/useRecordingDocumentQuery";
import useRecordingTranscriptQuery from "@api/queries/useRecordingTranscriptQuery";
import { recordingDocumentKeys } from "@api/queryKey/recordingDocument";
import useApiSupportState from "@hooks/common/useApiSupportState";
import { PATH_ROUTE } from "@routes/PATH_ROUTE";
import { useMatch, useParams } from "react-router";

/**
 * 원문 패널이 그릴 전사와 머리의 날짜 · 녹음 길이를 주소에 맞춰 골라요.
 *
 * - 문서 보기(`/documents/:documentId`): 그 문서를 만든 원문이에요. 날짜는 문서를 만든 날, 녹음 길이는 원문 응답의 값이에요
 * - 녹음 문서(`/recordings/:recordingId/document`): 그 녹음의 전사 전체예요(STT-R39). 날짜는 녹음을 시작한 날, 녹음 길이는 녹음 문서의 값이에요
 *
 * 두 주소의 조회를 모두 부르되 지금 주소의 것만 켜요. 날짜와 녹음 길이는 본문 섹션과 같은 쿼리 키라 요청이 더 나가지 않아요.
 * 주소의 id는 숫자인지 확인하지 않고 그대로 요청해요. 잘못된 주소인지는 서버가 판단해요.
 * 쓰는 쪽이 구조분해해서 받을 수 있게, 어느 주소든 같은 이름의 값을 모두 돌려줘요.
 */
export const useTranscriptSource = () => {
  const params = useParams();
  const isRecordingDocument = useMatch(PATH_ROUTE.RECORDING_DOCUMENT) !== null;
  const workspaceId = Number(params.workspaceId);
  const documentId = Number(params.documentId);
  const recordingId = Number(params.recordingId);

  const {
    data: documentTranscript,
    error: documentTranscriptError,
    isError: isDocumentTranscriptError,
    refetch: refetchDocumentTranscript,
  } = useDocumentTranscriptQuery({
    workspaceId,
    documentId,
    isEnabled: !isRecordingDocument,
  });
  const { data: documentDetail } = useDocumentQuery({
    workspaceId,
    documentId,
    isEnabled: !isRecordingDocument,
  });
  const {
    data: recordingTranscript,
    error: recordingTranscriptError,
    isError: isRecordingTranscriptError,
    refetch: refetchRecordingTranscript,
  } = useRecordingTranscriptQuery({
    workspaceId,
    recordingId,
    isEnabled: isRecordingDocument,
  });
  const { data: recordingDocument } = useRecordingDocumentQuery({
    workspaceId,
    recordingId,
    isEnabled: isRecordingDocument,
  });

  const { isUnsupported, isUnavailable } = useApiSupportState({
    queryKey: recordingDocumentKeys.transcript({ workspaceId, recordingId }),
    error: recordingTranscriptError,
  });

  if (isRecordingDocument) {
    return {
      segments: isUnavailable ? undefined : recordingTranscript?.segments,
      date: recordingDocument?.startedAt,
      durationSeconds: recordingDocument?.recordingDurationSeconds,
      footerNotice: "이 녹음의 전사 전체예요.",
      error: recordingTranscriptError,
      isError: isRecordingTranscriptError,
      isUnsupported,
      refetch: refetchRecordingTranscript,
    };
  }

  return {
    segments: documentTranscript?.segments,
    date: documentDetail?.createdAt,
    durationSeconds: documentTranscript?.recordingDurationSeconds,
    footerNotice: "이 문서를 만든 원본이에요. 전사 전체를 그대로 볼 수 있어요",
    error: documentTranscriptError,
    isError: isDocumentTranscriptError,
    // 기존 문서 원문은 신규 기능이 아니므로 같은 경로 오류도 기존 실패 안내로 처리해요.
    isUnsupported: false,
    refetch: refetchDocumentTranscript,
  };
};
