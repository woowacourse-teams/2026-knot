import { getSpeakerCount } from "@api/mock/state/recordingSpeaker";
import { http, HttpResponse } from "msw";
import { recordingDetailsResponse } from "@api/mock/responses/recording";
import {
  recordingDocumentResponse,
  recordingTranscriptResponse,
} from "@api/mock/responses/recordingDocument";
/** API_MOCKING 개발 브라우저에서만 읽는 녹음 결과 상황. 운영 번들에는 포함되지 않아요. */
const scenario = () => localStorage.getItem("knot.mock.recordingDemo");
export const recordingDemoHandlers = [
  http.get(
    "*/api/v1/workspaces/:workspaceId/recordings/:recordingId",
    ({ params }) => {
      if (params.recordingId === "current") return;
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
      if (selected === "processing")
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
