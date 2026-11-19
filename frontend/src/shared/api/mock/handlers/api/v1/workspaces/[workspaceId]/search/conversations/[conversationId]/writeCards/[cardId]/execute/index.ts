import { http, HttpResponse } from "msw";

import {
  searchWriteCardCreatedPageUrl,
  searchWriteCardResponse,
} from "@api/mock/responses/search";

// 경로 파라미터가 있어 fetch 상수 대신 패턴을 직접 적어요
export const workspaceSearchWriteCardExecuteHandlers = [
  http.post(
    "*/api/v1/workspaces/:workspaceId/search/conversations/:conversationId/write-cards/:cardId/execute",
    // 실제 서버처럼 요청한 카드를 실행됨으로 바꿔 답해요
    ({ params }) =>
      HttpResponse.json({
        ...searchWriteCardResponse,
        id: Number(params.cardId),
        status: "DONE",
        createdPageUrl: searchWriteCardCreatedPageUrl,
      }),
  ),
];
