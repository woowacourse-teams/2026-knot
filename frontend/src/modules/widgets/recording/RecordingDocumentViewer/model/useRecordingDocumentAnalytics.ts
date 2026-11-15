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

/** 정리 결과인 화면 상태와 사용 기록(`recording_documents_result`)의 결과 · 실패 단계 값 */
const RESULT_BY_STATUS = {
  completed: { result: "completed" },
  noContent: { result: "no_content" },
  transcriptionFailed: { result: "failed", failure_stage: "transcription" },
  topicFailed: { result: "failed", failure_stage: "topic_classification" },
  documentFailed: { result: "failed", failure_stage: "document_generation" },
} as const;

type ResultStatus = keyof typeof RESULT_BY_STATUS;

const isResultStatus = (
  status: RecordingDocumentStatus["status"],
): status is ResultStatus => status in RESULT_BY_STATUS;

/** 회의 전체 요약의 상태와 사용 기록의 요약 상태 값. 기다리는 중과 만드는 중은 화면처럼 하나로 봐요 */
const MEETING_SUMMARY_STATUS_BY_STATUS = {
  QUEUED: "drafting",
  RUNNING: "drafting",
  SUCCEEDED: "completed",
  FAILED: "failed",
} as const;

/** 이 화면에서 본 「정리 중」 하나. 결과가 보이면 그 결과를 보낼 때까지 들고 있어요 */
interface OrganizingWait {
  /** 정리 중을 본 화면 방문. 다른 녹음으로 옮겨 가면 이어지지 않아요 */
  visitKey: string;
  /** 정리 중을 처음 본 시각 */
  startedAt: number;
  /** 결과가 보인 시각과 그 결과. 아직 정리 중이면 `null` */
  result: { status: ResultStatus; seenAt: number } | null;
}

interface UseRecordingDocumentAnalyticsParams {
  workspaceId: number;
  recordingId: number;
  status: RecordingDocumentStatus["status"];
  recordingDocument: RecordingDocumentStatus["recordingDocument"];
  /** 원문(전사)을 지금 볼 수 있는지 */
  isTranscriptAvailable: boolean;
}

/**
 * 녹음 문서 섹션에서 일어난 일을 사용 기록으로 보내는 훅. 화면의 상태를 지켜보기만 하고 섹션의 동작은 바꾸지 않아요.
 *
 * - 녹음 문서 보기(`recording_document_viewed`): 처리 상태가 정해지면 화면에 들어올 때마다 한 번 보내요.
 *   같은 화면에서 정리가 끝나 상태가 바뀌어도 다시 보내지 않아요. 화면에 들어온 것은 방문 기록의 키로 알아요.
 *   완료 · 문서 생성 실패면 「이 회의에서 나온 문서」의 문서 수를 함께 보내려고 목록을 기다리고, 목록을 받지 못하면 문서 수 없이 보내요.
 *   그때 원문을 볼 수 있었는지도 함께 보내요. 정리 중에 원문을 볼 수 있었으면 전사가 끝나 정리 화면에서 넘어온 녹음이에요.
 * - 정리 결과(`recording_documents_result`): 이 화면에서 「정리 중」을 본 뒤 결과로 바뀌면 녹음 문서(`recording_document`)에서 본 것으로 보내요.
 *   정리 중에 들어와 보는 동안 결과가 난 경우와, 다시 시도로 정리 중이 된 뒤 결과가 난 경우예요.
 *   들어왔을 때 이미 결과였으면 보내지 않아요. 정리 화면에서 결과를 본 녹음은 결과 상태로 들어와서, 한 녹음의 결과가 두 화면에서 겹쳐 보내지지 않아요.
 *   완료면 결과가 보인 뒤에 다시 받은 「이 회의에서 나온 문서」로 문서 수를 세요. 정리 중에 받아 둔 목록은 만드는 중인 문서가 빠져 있을 수 있어서예요.
 * - 요약 복사(`content_copied`): 제목 줄의 복사가 성공하면 `trackMeetingSummaryCopied`로 보내요.
 */
export const useRecordingDocumentAnalytics = ({
  workspaceId,
  recordingId,
  status,
  recordingDocument,
  isTranscriptAvailable,
}: UseRecordingDocumentAnalyticsParams) => {
  const { key: visitKey } = useLocation();
  const trackedVisitKeyRef = useRef<string | null>(null);
  const organizingWaitRef = useRef<OrganizingWait | null>(null);
  const hasDocuments = status === "completed" || status === "documentFailed";
  // 「이 회의에서 나온 문서」와 같은 쿼리 키라 요청이 더 나가지 않아요
  const {
    data: documentList,
    isError: isDocumentListError,
    dataUpdatedAt: documentListUpdatedAt,
    errorUpdatedAt: documentListErrorUpdatedAt,
  } = useDocumentsByRecordingQuery({
    workspaceId,
    recordingSessionId: recordingId,
    isEnabled: hasDocuments,
  });

  useEffect(() => {
    if (
      trackedVisitKeyRef.current === visitKey ||
      status === "loading" ||
      status === "loadFailed" ||
      status === "unsupported" ||
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
        is_transcript_ready: isTranscriptAvailable,
        recording_id: recordingId,
        workspace_id: workspaceId,
      },
    });
  }, [
    documentList,
    hasDocuments,
    isDocumentListError,
    isTranscriptAvailable,
    recordingDocument,
    recordingId,
    status,
    visitKey,
    workspaceId,
  ]);

  // 정리 중을 본 뒤 결과가 보인 때를 기록해요. 정리 중도 결과도 아닌 상태가 되면 그 정리 중은 끝나요
  useEffect(() => {
    const organizingWait = organizingWaitRef.current;
    const isSameVisit = organizingWait?.visitKey === visitKey;

    if (status === "organizing") {
      if (!isSameVisit || organizingWait.result !== null) {
        organizingWaitRef.current = {
          visitKey,
          startedAt: Date.now(),
          result: null,
        };
      }
      return;
    }

    if (!isSameVisit || !isResultStatus(status)) {
      organizingWaitRef.current = null;
      return;
    }

    if (organizingWait.result === null) {
      organizingWait.result = { status, seenAt: Date.now() };
    }
  }, [status, visitKey]);

  // 결과가 보이면 보내요. 완료는 결과가 보인 뒤 다시 받은 문서 목록을 기다려요
  useEffect(() => {
    const organizingWait = organizingWaitRef.current;
    const result = organizingWait?.result;
    if (
      organizingWait === null ||
      result === null ||
      result === undefined ||
      organizingWait.visitKey !== visitKey
    ) {
      return;
    }

    const isDocumentListFresh = documentListUpdatedAt >= result.seenAt;
    const isDocumentListSettled =
      isDocumentListFresh || documentListErrorUpdatedAt >= result.seenAt;
    if (result.status === "completed" && !isDocumentListSettled) return;

    organizingWaitRef.current = null;

    captureAnalyticsEvent({
      event: "recording_documents_result",
      properties: {
        ...RESULT_BY_STATUS[result.status],
        ...(result.status === "completed" &&
          isDocumentListFresh &&
          documentList !== undefined && {
            document_count: documentList.items.length,
          }),
        surface: "recording_document",
        waited_seconds:
          Math.round((result.seenAt - organizingWait.startedAt) / 100) / 10,
        recording_id: recordingId,
        workspace_id: workspaceId,
      },
    });
  }, [
    documentList,
    documentListErrorUpdatedAt,
    documentListUpdatedAt,
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
