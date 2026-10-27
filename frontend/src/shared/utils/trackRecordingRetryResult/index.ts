import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { getRetryResult } from "@utils/getRetryResult";

interface TrackRecordingRetryResultParams {
  /** 다시 시도를 누른 곳. 녹음 문서 또는 홈 녹음 기록 카드예요 */
  surface: "recording_document" | "recording_history";
  /** 무엇을 다시 시도했는지. 전사 · 문서 만들기 · 회의 전체 요약이에요 */
  stage: "transcription" | "document_generation" | "meeting_summary";
  /** 다시 시도 요청마다의 오류. 접수된 요청은 `null`이에요. 문서 만들기는 작업마다 요청해서 여러 개일 수 있어요 */
  errors: unknown[];
  workspaceId: number;
  recordingId: number;
}

/**
 * @description 녹음 문서 · 홈 녹음 기록 카드의 다시 시도 응답을 사용 기록(`recording_retry_result`)으로 보내요
 * @param params - 다시 시도를 누른 곳과 단계, 요청마다의 오류(접수면 `null`), 워크스페이스 · 녹음 id
 * @example
 * trackRecordingRetryResult({ surface: "recording_document", stage: "transcription", errors: [null], workspaceId, recordingId });
 *
 * 결과는 정리 화면의 다시 시도 기록과 같은 규칙(`getRetryResult`)으로 묶고, 로그인이 풀렸거나 보낸 요청이 없으면 보내지 않아요.
 */
export const trackRecordingRetryResult = ({
  surface,
  stage,
  errors,
  workspaceId,
  recordingId,
}: TrackRecordingRetryResultParams) => {
  const result = getRetryResult(errors);
  if (result === null) return;

  captureAnalyticsEvent({
    event: "recording_retry_result",
    properties: {
      surface,
      stage,
      result,
      recording_id: recordingId,
      workspace_id: workspaceId,
    },
  });
};
