import { getReleaseNotesApi } from "@api/fetch/relay/n";
import { releaseNoteKeys } from "@api/queryKey/releaseNote";
import { useQuery } from "@tanstack/react-query";

/**
 * 새 소식 패널에 보여 줄 노트를 받는 쿼리 훅. 프론트 Worker가 Notion 새 소식 DB에서 읽어 와요(ADR 956).
 *
 * 받지 못하면 다른 쿼리처럼 QueryCache가 Sentry에 남기고, 패널은 빈 상태를 보여 줘요.
 */
const useReleaseNotesQuery = () => {
  return useQuery({
    queryKey: releaseNoteKeys.list(),
    queryFn: getReleaseNotesApi,
  });
};

export default useReleaseNotesQuery;
