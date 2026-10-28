import useRecordingState from "@hooks/domain/recording/useRecordingState";
import { useState } from "react";

import type { ReleaseNote } from "@/shared/types/releaseNote";

import { LAST_SEEN_NOTE_ID_KEY } from "../constants/lastSeenNote";
import { getUnreadNoteIds } from "../utils/getUnreadNoteIds";

interface UseLastSeenNoteParams {
  /** 패널에 보여 줄 노트. 최신 노트가 맨 앞이고, 받는 중이거나 받지 못했으면 빈 배열이에요 */
  notes: ReleaseNote[];
}

/** 이 기기에 남긴 마지막으로 연 노트 id. 연 적이 없으면 `null`, localStorage를 쓸 수 없으면 `undefined`예요 */
const readLastSeenId = () => {
  try {
    return localStorage.getItem(LAST_SEEN_NOTE_ID_KEY);
  } catch {
    return undefined;
  }
};

/**
 * 새 소식을 처음 확인하는 사용자에게 패널을 저절로 열지와 안 읽은 노트를 정하고, 패널을 연 기록을 이 기기에 남겨요(NEWS-R6, NEWS-R7).
 *
 * - `isAutoOpen`: 패널을 저절로 열지. 이 기기에서 새 소식을 한 번도 열지 않았고 노트가 있을 때만 열어요.
 *   녹음 중에는 방해가 되므로 열지 않아요. 이때는 읽음도 남기지 않아 녹음 뒤에 들어오면 열려요.
 *   localStorage를 쓸 수 없으면 들어올 때마다 열리게 되므로 열지 않아요.
 * - `unreadNoteIds`: 안 읽은 노트의 id(최신부터). localStorage를 쓸 수 없거나 노트를 받지 못했으면 비어 있어요.
 * - `markAsSeen`: 패널이 열리면(저절로든 눌러서든) 맨 위 노트 id를 남겨 다음부터는 저절로 열지 않고, 안 읽은 노트도 비워요.
 *   부르면 `isAutoOpen`도 꺼져, 저절로 연 패널을 닫은 뒤 버튼으로 다시 열면 메뉴부터 보여요.
 *
 * 읽음 기록과 녹음 여부는 처음 그릴 때 한 번만 보고, 노트는 받은 뒤에 봐요. 열린 채로 녹음을 시작해도 닫지 않아요.
 */
export const useLastSeenNote = ({ notes }: UseLastSeenNoteParams) => {
  const { isRecordingActive } = useRecordingState();
  const [lastSeenId, setLastSeenId] = useState(readLastSeenId);
  const [canAutoOpen, setCanAutoOpen] = useState(
    () => !isRecordingActive && lastSeenId === null,
  );
  const isAutoOpen = canAutoOpen && notes.length > 0;
  const unreadNoteIds = getUnreadNoteIds({ notes, lastSeenId });

  const markAsSeen = () => {
    setCanAutoOpen(false);
    if (notes.length === 0) return;

    setLastSeenId(notes[0].id);
    try {
      localStorage.setItem(LAST_SEEN_NOTE_ID_KEY, notes[0].id);
    } catch {
      // 남길 수 없으면 이번에 연 것만으로 넘어가요
    }
  };

  return { isAutoOpen, unreadNoteIds, markAsSeen };
};
