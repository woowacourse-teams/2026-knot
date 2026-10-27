import { http, HttpResponse } from "msw";

import { recordingStartResponse } from "@api/mock/responses/recording";
import { findRecordingHistoryItems } from "@api/mock/state/recording";
import type { RecordingsResponse } from "@api/mock/types/recording";

/** 서버의 기본 페이지 크기 */
const PAGE_SIZE = 20;

/**
 * 녹음 기록 목록의 한 페이지. 커서는 앞 페이지의 마지막 녹음 ID예요.
 * 빈 문자열이거나 그 ID의 녹음이 없으면 서버처럼 400이에요
 */
const findRecordingsPage = (cursor: string | null) => {
  const items = findRecordingHistoryItems();
  const cursorIndex = items.findIndex(
    ({ recordingId }) => String(recordingId) === cursor,
  );

  if (cursor !== null && cursorIndex === -1) return undefined;

  // 첫 페이지면 cursorIndex가 -1이라 0번째부터 잘라요
  const start = cursorIndex + 1;
  const end = start + PAGE_SIZE;

  return {
    items: items.slice(start, end),
    nextCursor: end < items.length ? String(items[end - 1].recordingId) : null,
  } satisfies RecordingsResponse;
};

export const workspaceRecordingsHandlers = [
  http.get("*/api/v1/workspaces/:workspaceId/recordings", ({ request }) => {
    const page = findRecordingsPage(
      new URL(request.url).searchParams.get("cursor"),
    );

    if (page === undefined) {
      return HttpResponse.json(
        { code: "INVALID_PARAMETER", message: "요청 값이 올바르지 않습니다." },
        { status: 400 },
      );
    }

    return HttpResponse.json(page);
  }),
  // 새 세션은 201이에요. 같은 requestId 재시도는 실제 서버가 200으로 기존 세션을 돌려줘요
  http.post("*/api/v1/workspaces/:workspaceId/recordings", () =>
    HttpResponse.json(recordingStartResponse, { status: 201 }),
  ),
];
