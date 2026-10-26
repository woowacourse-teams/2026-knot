import {
  GetReleaseNotesResponseDto,
  type GetReleaseNotesResponseRaw,
} from "@api/dto/releaseNote";
import axios from "axios";

/**
 * 새 소식 노트를 받는 우리 주소의 중계 경로. 프론트 Worker(`src/worker/relay`)가 Notion 새 소식 DB를 읽어요(ADR 956).
 * Worker의 `RELEASE_NOTES_RELAY_PATH`와 같아야 하고, wrangler.jsonc의 `run_worker_first`(`/relay/*`) 안에 있어야 해요
 */
export const RELEASE_NOTES_RELAY_PATH = "/relay/n";

const TIMEOUT_DURATION = 10_000;

/**
 * @description 새 소식 패널에 보여 줄 노트를 프론트 Worker에서 받습니다
 * @returns 노트 목록(최신 노트가 맨 앞)
 * @example
 * const { notes } = await getReleaseNotesApi();
 */
export const getReleaseNotesApi = async () => {
  // 우리 API가 아니라 같은 주소의 Worker라, httpClient의 API 주소 · 로그인 갱신을 거치지 않아요
  const response = await axios.get<GetReleaseNotesResponseRaw>(
    RELEASE_NOTES_RELAY_PATH,
    { timeout: TIMEOUT_DURATION },
  );

  return new GetReleaseNotesResponseDto(response.data);
};
