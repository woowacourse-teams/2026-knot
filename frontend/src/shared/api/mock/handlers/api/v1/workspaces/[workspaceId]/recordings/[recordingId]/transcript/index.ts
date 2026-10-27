import { http, HttpResponse } from "msw";

import { recordingTranscriptResponse } from "@api/mock/responses/recordingDocument";

// 경로 파라미터가 있어 fetch 상수 대신 패턴을 직접 적어요
export const recordingTranscriptHandlers = [
  http.get(
    "*/api/v1/workspaces/:workspaceId/recordings/:recordingId/transcript",
    ({ params }) =>
      HttpResponse.json({
        ...recordingTranscriptResponse,
        recordingId: Number(params.recordingId),
      }),
  ),
];
