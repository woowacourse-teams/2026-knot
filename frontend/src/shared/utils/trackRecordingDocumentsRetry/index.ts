import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { getRetryResult } from "@utils/getRetryResult";

interface TrackRecordingDocumentsRetryParams {
  /** 무엇을 다시 시도했는지. 전사 또는 문서 만들기예요 */
  stage: "transcription" | "document_generation";
  /** 다시 시도 요청마다의 오류. 접수된 요청은 `null`이에요. 문서 만들기는 작업마다 요청해서 여러 개일 수 있어요 */
  errors: unknown[];
  workspaceId: number;
  recordingId: number;
}

/**
 * @description 정리 화면의 다시 시도 응답을 사용 기록(`recording_documents_retry`)으로 보내요
 * @param params - 다시 시도한 단계, 요청마다의 오류(접수면 `null`), 워크스페이스 · 녹음 id
 * @example
 * trackRecordingDocumentsRetry({ stage: "transcription", errors: [null], workspaceId, recordingId });
 *
 * 요청마다의 결과는 `getRetryResult`로 묶어요. 로그인이 풀린(401) 요청이 있거나 보낸 요청이 없으면 보내지 않아요.
 */
export const trackRecordingDocumentsRetry = ({
  stage,
  errors,
  workspaceId,
  recordingId,
}: TrackRecordingDocumentsRetryParams) => {
  const result = getRetryResult(errors);
  if (result === null) return;

  captureAnalyticsEvent({
    event: "recording_documents_retry",
    properties: {
      stage,
      result,
      recording_id: recordingId,
      workspace_id: workspaceId,
    },
  });
};
