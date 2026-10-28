import type { ReleaseNote } from "@/shared/types/releaseNote";

interface GetUnreadNoteIdsParams {
  /** 패널에 보여 줄 노트. 최신 노트가 맨 앞이에요 */
  notes: ReleaseNote[];
  /**
   * 이 기기에서 새 소식 패널을 마지막으로 열었을 때 맨 위에 있던 노트 id.
   * 연 적이 없으면 `null`, localStorage를 쓸 수 없어 알 수 없으면 `undefined`예요
   */
  lastSeenId: string | null | undefined;
}

/**
 * 안 읽은 노트의 id를 최신부터 골라요(NEWS-R7). 노트 앞뒤는 날짜가 아니라 목록 위치로만 가려요.
 *
 * - 연 적이 없으면(처음 온 사람) 모두 안 읽음이에요.
 * - 마지막으로 연 노트가 목록에 있으면 그보다 위(최신)의 노트만 안 읽음이에요.
 * - 마지막으로 연 노트가 목록에 없거나 알 수 없으면 모두 읽은 것으로 봐요(NEWS-R6).
 */
export const getUnreadNoteIds = ({
  notes,
  lastSeenId,
}: GetUnreadNoteIdsParams) => {
  const noteIds = notes.map(({ id }) => id);
  if (lastSeenId === null) return noteIds;

  const lastSeenIndex =
    lastSeenId === undefined ? -1 : noteIds.indexOf(lastSeenId);
  return lastSeenIndex === -1 ? [] : noteIds.slice(0, lastSeenIndex);
};
