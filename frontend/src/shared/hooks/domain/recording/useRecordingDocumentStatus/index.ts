import useRecordingTranscriptQuery from "@api/queries/useRecordingTranscriptQuery";
import { HTTP_ERROR_TYPE, isHttpError } from "@api/httpClient/error";
import useRecordingDocumentQuery from "@api/queries/useRecordingDocumentQuery";
import { recordingDocumentKeys } from "@api/queryKey/recordingDocument";
import useApiSupportState from "@hooks/common/useApiSupportState";
import { isUnauthorizedError } from "@utils/isUnauthorizedError";

interface UseRecordingDocumentStatusParams {
  workspaceId: number;
  recordingId: number;
  /** `false`면 조회하지 않고 `loading`으로 둬요. 녹음 문서 화면이 아닐 때 꺼 둬요. 기본값은 `true` */
  isEnabled?: boolean;
  /** `true`면 처리나 요약이 끝나기 전까지 녹음 문서를 3초마다 다시 물어봐요. 같은 화면에서 녹음 문서 섹션 한 곳만 켜요. 기본값은 `false` */
  isPolling?: boolean;
}

/** 다시 물어도 결과가 같은 조회 실패(4xx). 앞서 받은 녹음 문서가 있어도 보여 주지 않아요 */
const isUnrecoverableLoadError = (error: unknown) =>
  isHttpError(error) && error.isClientError;

/** 전사 준비 여부·주제 분류·문서 처리 상태를 서버의 독립 상태로 판단해요. */
const useRecordingDocumentStatus = ({
  workspaceId,
  recordingId,
  isEnabled = true,
  isPolling = false,
}: UseRecordingDocumentStatusParams) => {
  const {
    data: recordingDocument,
    error,
    isError,
    refetch,
  } = useRecordingDocumentQuery({
    workspaceId,
    recordingId,
    isEnabled,
    isPolling,
  });
  const { isUnsupported, isUnavailable } = useApiSupportState({
    queryKey: recordingDocumentKeys.detail({ workspaceId, recordingId }),
    error,
  });
  // 새 상태 필드가 없는 이전 BE 응답에서만 예전 조회 판정을 유지해요.
  const isLegacyFailure =
    recordingDocument?.transcriptStatus === null &&
    recordingDocument.processingStatus === "DOCUMENT_FAILED";
  const { data: legacyTranscript, error: legacyTranscriptError } =
    useRecordingTranscriptQuery({
      workspaceId,
      recordingId,
      isEnabled: isEnabled && isLegacyFailure,
    });
  const isLoadFailed =
    !isUnauthorizedError(error) &&
    (isUnrecoverableLoadError(error) ||
      ((recordingDocument === undefined || isUnavailable) && isError));

  const getStatus = () => {
    if (isUnsupported) return "unsupported" as const;
    if (isLoadFailed) return "loadFailed" as const;
    if (isUnavailable) return "loading" as const;
    if (recordingDocument === undefined) return "loading" as const;

    switch (recordingDocument.processingStatus) {
      case "PROCESSING":
        return "organizing" as const;
      case "TRANSCRIPTION_FAILED":
        return "transcriptionFailed" as const;
      case "NO_CONTENT":
        return "noContent" as const;
      case "COMPLETED":
        return "completed" as const;
      case "DOCUMENT_FAILED":
        if (isLegacyFailure && recordingDocument.meetingSummary === null) {
          if (
            isHttpError(legacyTranscriptError, HTTP_ERROR_TYPE.notFound) &&
            legacyTranscriptError.code === "RECORDING_TRANSCRIPT_NOT_FOUND"
          )
            return "topicFailed" as const;
          if (legacyTranscript === undefined && legacyTranscriptError === null)
            return "loading" as const;
        }
        return recordingDocument.topicClassificationStatus === "FAILED"
          ? ("topicFailed" as const)
          : ("documentFailed" as const);
    }
  };

  const status = getStatus();

  // 화면을 새로고침하지 않고 녹음 문서만 다시 조회해요. 새로고침하면 브라우저 안에만 있는 진행 중인 녹음이 사라져요
  const retryLoad = () => {
    refetch();
  };

  return {
    status,
    recordingDocument,
    error,
    isTranscriptAvailable:
      (recordingDocument?.transcriptStatus === "READY" ||
        recordingDocument?.transcriptStatus === "EMPTY" ||
        (recordingDocument?.transcriptStatus === null &&
          (status === "completed" || status === "documentFailed"))) &&
      !isUnavailable &&
      !isLoadFailed &&
      !isUnsupported,
    retryLoad,
  };
};

export default useRecordingDocumentStatus;
