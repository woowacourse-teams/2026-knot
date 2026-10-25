import { http, HttpResponse } from "msw";

import { RELEASE_NOTES_RELAY_PATH } from "@api/fetch/relay/n";
import { releaseNotesResponse } from "@api/mock/responses/releaseNote";

// 로컬 · 스토리북에는 프론트 Worker가 없어 Notion 새 소식 DB에서 읽은 것처럼 노트를 돌려줘요
export const releaseNotesRelayHandlers = [
  http.get(`*${RELEASE_NOTES_RELAY_PATH}`, () =>
    HttpResponse.json(releaseNotesResponse),
  ),
];
