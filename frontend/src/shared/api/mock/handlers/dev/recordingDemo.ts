import { getSpeakerCount } from "@api/mock/state/recordingSpeaker";
import { delay, http, HttpResponse } from "msw";
import { recordingDetailsResponse, recordingEndResponse, recordingStartResponse } from "@api/mock/responses/recording";
import {
  recordingDocumentResponse,
  recordingTranscriptResponse,
} from "@api/mock/responses/recordingDocument";
/** API_MOCKING 개발 브라우저에서만 읽는 녹음 결과 상황. 운영 번들에는 포함되지 않아요. */
const scenario = () => localStorage.getItem("knot.mock.recordingDemo");
const endedRecordings = new Map<string, number>();
export const recordingDemoHandlers = [
  http.post(
    "*/api/v1/workspaces/:workspaceId/recordings",
    () => {
      if (scenario() !== "participants-ending") return;
      endedRecordings.delete(String(recordingStartResponse.recordingId));
      return HttpResponse.json(recordingStartResponse, { status: 201 });
    },
  ),
  http.post(
    "*/api/v1/workspaces/:workspaceId/recordings/:recordingId/end",
    ({ params }) => {
      if (scenario() !== "participants-ending") return;
      endedRecordings.set(String(params.recordingId), Date.now());
      return HttpResponse.json(recordingEndResponse);
    },
  ),
  http.get(
    "*/api/v1/workspaces/:workspaceId/recordings/:recordingId",
    async ({ params }) => {
      if (params.recordingId === "current") return;
      if (scenario() === "participants-ending") {
        const endedAt = endedRecordings.get(String(params.recordingId));
        const isReady = endedAt !== undefined && Date.now() - endedAt >= 1200;
        if (endedAt !== undefined) await delay(250);
        return HttpResponse.json({
          ...recordingDetailsResponse[0].detail,
          status: endedAt === undefined ? "RECORDING" : "ENDED",
          endedAt: endedAt === undefined ? null : new Date(endedAt).toISOString(),
          transcriptionStatus: isReady ? "SUCCEEDED" : null,
          documentGeneration: null,
          speakerCount: getSpeakerCount(Number(params.workspaceId), Number(params.recordingId)),
        });
      }
      if (scenario() === "participants")
        return HttpResponse.json({
          ...recordingDetailsResponse[0].detail,
          status: "RECORDING",
          speakerCount: getSpeakerCount(
            Number(params.workspaceId),
            Number(params.recordingId),
          ),
        });
      if (scenario() !== "processing") return;
      return HttpResponse.json({
        ...recordingDetailsResponse[0].detail,
        status: "ENDED",
        transcriptionStatus: "SUCCEEDED",
        documentGeneration: {
          status: "RUNNING",
          succeededCount: 0,
          failedJobIds: [],
        },
      });
    },
  ),
  http.get(
    "*/api/v1/workspaces/:workspaceId/recordings/:recordingId/document",
    () => {
      const selected = scenario();
      if (!selected) return;
      if (selected === "processing" || selected === "participants-ending")
        return HttpResponse.json({
          ...recordingDocumentResponse,
          processingStatus: "PROCESSING",
          transcriptStatus: "READY",
          topicClassificationStatus: "SUCCEEDED",
          meetingSummary: { status: "QUEUED", content: null, retryable: false },
        });
      if (selected === "greeting")
        return HttpResponse.json({
          ...recordingDocumentResponse,
          processingStatus: "NO_CONTENT",
          transcriptStatus: "READY",
          topicClassificationStatus: "NO_CONTENT",
          meetingSummary: {
            status: "SUCCEEDED",
            content: "## 녹음 내용\n참석자가 인사를 나눴습니다.",
            retryable: false,
          },
        });
      if (selected === "empty")
        return HttpResponse.json({
          ...recordingDocumentResponse,
          processingStatus: "NO_CONTENT",
          transcriptStatus: "EMPTY",
          topicClassificationStatus: "NOT_STARTED",
          meetingSummary: null,
        });
    },
  ),
  http.get(
    "*/api/v1/workspaces/:workspaceId/recordings/:recordingId/transcript",
    () => {
      if (scenario() === "empty")
        return HttpResponse.json({
          ...recordingTranscriptResponse,
          transcriptText: "",
          segments: [],
        });
    },
  ),
];
