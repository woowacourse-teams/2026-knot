import { HTTP_ERROR_TYPE, isHttpError } from "@api/httpClient/error";
import useRecordingDocumentQuery from "@api/queries/useRecordingDocumentQuery";
import useRecordingTranscriptQuery from "@api/queries/useRecordingTranscriptQuery";
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

/** 전사 조회가 주제 분류 전이나 실패라 전사가 없다고 답한 서버 오류 코드 */
const TRANSCRIPT_NOT_FOUND_CODE = "RECORDING_TRANSCRIPT_NOT_FOUND";

/** 다시 물어도 결과가 같은 조회 실패(4xx). 앞서 받은 녹음 문서가 있어도 보여 주지 않아요 */
const isUnrecoverableLoadError = (error: unknown) =>
  isHttpError(error) && error.isClientError;

/**
 * 녹음 문서 화면이 그릴 상태(`status`)를 정하는 도메인 훅(STT-R42).
 *
 * 녹음 문서의 처리 상태(`processingStatus`)를 화면 상태로 옮겨요. 서버는 주제 분류 실패와 문서 생성 실패를 모두 `DOCUMENT_FAILED`로 주므로,
 * 전사 조회가 404 `RECORDING_TRANSCRIPT_NOT_FOUND`이고 회의 전체 요약이 없으면(`meetingSummary` null) 주제 분류 실패로 봐요(API 명세의 처리 규칙).
 * 요약이 없는 `DOCUMENT_FAILED`는 전사 조회의 결과가 나올 때까지 `loading`이에요. 전사 조회가 네트워크 · 서버 문제로 실패하면 주제 분류 실패인지 알 수 없어, 문서 생성 실패로 두고 원문 패널이 불러오기 실패를 알려요.
 *
 * - `loading`: 녹음 문서를 아직 받지 못했어요. 로그인이 풀려(401) 로그인 화면으로 보내는 동안에도 이 상태예요
 * - `loadFailed`: 녹음 문서를 보여 주지 못해요. 400 · 403 · 404는 받은 값이 있어도, 네트워크 · 서버 문제는 받은 값이 없을 때만 이 상태예요
 * - `unsupported`: 신규 녹음 문서 경로가 없어요. 정상 캐시가 있어도 이 기능만 준비 안내로 바꿔요
 * - `organizing`: 문서 정리 중(`PROCESSING`)
 * - `transcriptionFailed`: 전사 실패
 * - `topicFailed`: 주제 분류 실패(전사는 성공, 주제를 나누지 못함)
 * - `documentFailed`: 주제 분류를 마친 뒤 문서 생성 실패(일부 · 전체)
 * - `noContent`: 정리할 내용 없음
 * - `completed`: 완료
 *
 * 전사는 완료와 문서 생성 실패에서만 보여 주므로(STT-R39), 그 두 상태일 때 `isTranscriptAvailable`이 `true`예요. 레이아웃은 이 값으로 원문 버튼과 패널을 둬요.
 * 같은 쿼리 키를 쓰므로 레이아웃 · 원문 패널 · 녹음 문서 섹션이 함께 불러도 요청은 한 번만 나가요.
 *
 * 로그인 화면으로 보내는 일은 하지 않아요. 레이아웃처럼 이동한 뒤에도 남는 곳에서도 부르기 때문이에요. 녹음 문서 섹션이 `error`로 직접 보내요.
 * 쓰는 쪽이 구조분해해서 받을 수 있게, 어느 상태든 같은 이름의 값을 모두 돌려줘요.
 */
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
    queryKey: recordingDocumentKeys.detail({ workspaceId, recordingId }), error,
  });
  const processingStatus = recordingDocument?.processingStatus;
  const hasTranscript =
    processingStatus === "COMPLETED" || processingStatus === "DOCUMENT_FAILED";
  const { data: transcript, error: transcriptError } =
    useRecordingTranscriptQuery({
      workspaceId,
      recordingId,
      isEnabled: isEnabled && hasTranscript,
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
      case "DOCUMENT_FAILED": {
        // 요약이 있으면 주제 분류를 마친 녹음이에요
        if (recordingDocument.meetingSummary !== null) {
          return "documentFailed" as const;
        }

        const isTopicFailed =
          isHttpError(transcriptError, HTTP_ERROR_TYPE.notFound) &&
          transcriptError.code === TRANSCRIPT_NOT_FOUND_CODE;
        if (isTopicFailed) return "topicFailed" as const;

        // 전사 조회의 결과가 아직 없으면 어느 실패인지 몰라 기다려요
        const isTranscriptPending =
          transcript === undefined && transcriptError === null;

        return isTranscriptPending
          ? ("loading" as const)
          : ("documentFailed" as const);
      }
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
      status === "completed" || status === "documentFailed",
    retryLoad,
  };
};

export default useRecordingDocumentStatus;
