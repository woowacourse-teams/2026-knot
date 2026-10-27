import { http, HttpResponse } from "msw";

import { meetingSummaryRetryResponse } from "@api/mock/responses/recordingDocument";

// 경로 파라미터가 있어 fetch 상수 대신 패턴을 직접 적어요
export const meetingSummaryRetryHandlers = [
  http.post(
    "*/api/v1/workspaces/:workspaceId/recordings/:recordingId/meeting-summary/retry",
    ({ params }) =>
      HttpResponse.json(
        {
          ...meetingSummaryRetryResponse,
          recordingId: Number(params.recordingId),
        },
        { status: 202 },
      ),
  ),
];
