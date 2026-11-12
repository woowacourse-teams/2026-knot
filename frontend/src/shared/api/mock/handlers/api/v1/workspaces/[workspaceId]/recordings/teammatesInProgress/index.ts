import { http, HttpResponse } from "msw";

import { teammateRecordingsResponse } from "@api/mock/responses/recording";

// 경로 파라미터가 있어 fetch 상수 대신 패턴을 직접 적어요. 팀원 녹음 없음(빈 배열)·에러는 테스트에서 덮어요
export const teammateRecordingsHandlers = [
  http.get(
    "*/api/v1/workspaces/:workspaceId/recordings/teammates-in-progress",
    () => HttpResponse.json(teammateRecordingsResponse),
  ),
];
