import { http, HttpResponse } from "msw";

import { RELEASE_NOTES_RELAY_PATH } from "@api/fetch/relay/n";
import { releaseNotesResponse } from "@api/mock/responses/releaseNote";

/**
 * 노트 항목의 github.com 사진을 주소 끝에 적힌 크기(`news-1596x1386`)의 빈 사진으로 돌려줘요.
 * 크기가 적히지 않은 주소(`news-broken`)는 404라, 열리지 않는 사진이 돼요
 */
const toFakeImageResponse = (assetId: string) => {
  const size = assetId.match(/^news-(\d+)x(\d+)$/);
  if (!size) return new HttpResponse(null, { status: 404 });

  const [, width, height] = size;
  return new HttpResponse(
    `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}"><rect width="100%" height="100%" fill="#d8d4cd"/><text x="50%" y="50%" font-size="${Math.round(Number(width) / 12)}" text-anchor="middle" dominant-baseline="middle" fill="#595e66">${width}×${height}</text></svg>`,
    { headers: { "Content-Type": "image/svg+xml" } },
  );
};

// 로컬 · 스토리북에는 프론트 Worker가 없어 Notion 새 소식 DB에서 읽은 것처럼 노트를 돌려줘요
export const releaseNotesRelayHandlers = [
  http.get(`*${RELEASE_NOTES_RELAY_PATH}`, () =>
    HttpResponse.json(releaseNotesResponse),
  ),
  http.get(
    "https://github.com/user-attachments/assets/:assetId",
    ({ params }) => toFakeImageResponse(String(params.assetId)),
  ),
];
