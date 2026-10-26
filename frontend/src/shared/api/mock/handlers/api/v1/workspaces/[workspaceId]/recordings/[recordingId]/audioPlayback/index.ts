import { http, HttpResponse } from "msw";

import { recordingAudioPlaybackResponse } from "@api/mock/responses/recordingDocument";

// 경로 파라미터가 있어 fetch 상수 대신 패턴을 직접 적어요
export const recordingAudioPlaybackHandlers = [
  http.get(
    "*/api/v1/workspaces/:workspaceId/recordings/:recordingId/audio-playback",
    ({ params }) =>
      HttpResponse.json({
        ...recordingAudioPlaybackResponse,
        recordingId: Number(params.recordingId),
      }),
  ),
];
