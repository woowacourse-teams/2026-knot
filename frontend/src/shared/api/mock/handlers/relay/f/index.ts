import { http, HttpResponse } from "msw";

import { FEEDBACK_RELAY_PATH } from "@api/fetch/relay/f";

// 로컬 · 스토리북에는 프론트 Worker가 없어 Notion에 넣은 것처럼 201만 돌려줘요
export const feedbackRelayHandlers = [
  http.post(
    `*${FEEDBACK_RELAY_PATH}`,
    () => new HttpResponse(null, { status: 201 }),
  ),
];
