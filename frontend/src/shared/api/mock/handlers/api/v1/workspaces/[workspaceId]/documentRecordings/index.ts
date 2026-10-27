import { http, HttpResponse } from "msw";

import { documentRecordingsResponse } from "@api/mock/responses/document";
import type { DocumentRecordingsResponse } from "@api/mock/types/document";

/** 서버가 한 페이지에 주는 녹음 수. size를 받지 않고 고정이에요 */
const PAGE_SIZE = 20;

const invalidParameterResponse = () =>
  HttpResponse.json(
    { code: "INVALID_PARAMETER", message: "요청 값이 올바르지 않습니다." },
    { status: 400 },
  );

/**
 * 녹음별 문서 목록의 한 페이지. 커서는 앞 페이지의 마지막 녹음 ID예요.
 * 빈 문자열이거나 그 ID의 녹음이 없으면 서버처럼 400이에요. 첫 페이지를 다시 주면 이어 받는 쪽이 같은 녹음을 계속 받아요
 */
const findDocumentRecordings = (cursor: string | null) => {
  const { items } = documentRecordingsResponse;
  const cursorIndex = items.findIndex(
    ({ recordingId }) => String(recordingId) === cursor,
  );

  if (cursor !== null && cursorIndex === -1) return undefined;

  // 첫 페이지면 cursorIndex가 -1이라 0번째부터 잘라요
  const start = cursorIndex + 1;
  const end = start + PAGE_SIZE;
  const hasNextPage = end < items.length;

  return {
    items: items.slice(start, end),
    nextCursor: hasNextPage ? String(items[end - 1].recordingId) : null,
  } satisfies DocumentRecordingsResponse;
};

// 경로 파라미터가 있어 fetch 상수 대신 패턴을 직접 적어요
export const documentRecordingsHandlers = [
  http.get(
    "*/api/v1/workspaces/:workspaceId/document-recordings",
    ({ request }) => {
      const { searchParams } = new URL(request.url);
      const page = findDocumentRecordings(searchParams.get("cursor"));

      if (page === undefined) return invalidParameterResponse();

      return HttpResponse.json(page);
    },
  ),
];
