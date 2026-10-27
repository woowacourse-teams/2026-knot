import useDocumentsByRecordingQuery from "@api/queries/useDocumentsByRecordingQuery";
import type useRecordingDocumentStatus from "@hooks/domain/recording/useRecordingDocumentStatus";
import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { useEffect, useRef } from "react";
import { useLocation } from "react-router";

type RecordingDocumentStatus = ReturnType<typeof useRecordingDocumentStatus>;

/** 처리 상태가 정해진 화면 상태와 사용 기록(`recording_document_viewed`)의 처리 상태 값 */
const PROCESSING_STATUS_BY_STATUS = {
  organizing: "organizing",
  transcriptionFailed: "transcription_failed",
  topicFailed: "topic_failed",
  documentFailed: "document_failed",
  noContent: "no_content",
  completed: "completed",
} as const;

/** 회의 전체 요약의 상태와 사용 기록의 요약 상태 값. 기다리는 중과 만드는 중은 화면처럼 하나로 봐요 */
const MEETING_SUMMARY_STATUS_BY_STATUS = {
  QUEUED: "drafting",
  RUNNING: "drafting",
  SUCCEEDED: "completed",
  FAILED: "failed",
} as const;

interface UseRecordingDocumentAnalyticsParams {
  workspaceId: number;
  recordingId: number;
  status: RecordingDocumentStatus["status"];
  recordingDocument: RecordingDocumentStatus["recordingDocument"];
}

/**
 * 녹음 문서 섹션에서 일어난 일을 사용 기록으로 보내는 훅. 화면의 상태를 지켜보기만 하고 섹션의 동작은 바꾸지 않아요.
 *
 * - 녹음 문서 보기(`recording_document_viewed`): 처리 상태가 정해지면 화면에 들어올 때마다 한 번 보내요.
 *   같은 화면에서 정리가 끝나 상태가 바뀌어도 다시 보내지 않아요. 화면에 들어온 것은 방문 기록의 키로 알아요.
 *   완료 · 문서 생성 실패면 「이 회의에서 나온 문서」의 문서 수를 함께 보내려고 목록을 기다리고, 목록을 받지 못하면 문서 수 없이 보내요.
 * - 요약 복사(`content_copied`): 제목 줄의 복사가 성공하면 `trackMeetingSummaryCopied`로 보내요.
 */
export const useRecordingDocumentAnalytics = ({
  workspaceId,
  recordingId,
  status,
  recordingDocument,
}: UseRecordingDocumentAnalyticsParams) => {
  const { key: visitKey } = useLocation();
  const trackedVisitKeyRef = useRef<string | null>(null);
  const hasDocuments = status === "completed" || status === "documentFailed";
  // 「이 회의에서 나온 문서」와 같은 쿼리 키라 요청이 더 나가지 않아요
  const { data: documentList, isError: isDocumentListError } =
    useDocumentsByRecordingQuery({
      workspaceId,
      recordingSessionId: recordingId,
      isEnabled: hasDocuments,
    });

  useEffect(() => {
    if (
      trackedVisitKeyRef.current === visitKey ||
      status === "loading" ||
      status === "loadFailed" ||
      recordingDocument === undefined
    ) {
      return;
    }
    if (hasDocuments && documentList === undefined && !isDocumentListError) {
      return;
    }

    trackedVisitKeyRef.current = visitKey;
    const { meetingSummary } = recordingDocument;

    captureAnalyticsEvent({
      event: "recording_document_viewed",
      properties: {
        processing_status: PROCESSING_STATUS_BY_STATUS[status],
        meeting_summary_status:
          meetingSummary === null
            ? "none"
            : MEETING_SUMMARY_STATUS_BY_STATUS[meetingSummary.status],
        ...(hasDocuments &&
          documentList !== undefined && {
            document_count: documentList.items.length,
          }),
        recording_id: recordingId,
        workspace_id: workspaceId,
      },
    });
  }, [
    documentList,
    hasDocuments,
    isDocumentListError,
    recordingDocument,
    recordingId,
    status,
    visitKey,
    workspaceId,
  ]);

  const trackMeetingSummaryCopied = () => {
    captureAnalyticsEvent({
      event: "content_copied",
      properties: {
        content: "meeting_summary",
        screen: "recording_document",
        recording_id: recordingId,
        workspace_id: workspaceId,
      },
    });
  };

  return { trackMeetingSummaryCopied };
};
