import { http, HttpResponse } from "msw";

import { recordingHeartbeatResponse } from "@api/mock/responses/recording";

// 이어 받기 응답은 생존 신호 응답과 필드가 같아요. 끝난 녹음, 403은 테스트에서 덮어요
export const recordingReconnectHandlers = [
  http.post(
    "*/api/v1/workspaces/:workspaceId/recordings/:recordingId/reconnect",
    () => HttpResponse.json(recordingHeartbeatResponse),
  ),
];
