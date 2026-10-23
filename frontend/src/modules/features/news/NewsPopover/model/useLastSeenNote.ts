import useRecordingState from "@hooks/domain/recording/useRecordingState";
import { useState } from "react";

import type { ReleaseNote } from "@/shared/types/releaseNote";

import { LAST_SEEN_NOTE_ID_KEY } from "../constants/lastSeenNote";

interface UseLastSeenNoteParams {
  /** 패널에 보여 줄 노트. 최신 노트가 맨 앞이에요 */
  notes: ReleaseNote[];
}

const hasSeenNotes = () => {
  try {
    return localStorage.getItem(LAST_SEEN_NOTE_ID_KEY) !== null;
  } catch {
    // 남길 수 없으면 들어올 때마다 열리게 되므로 이미 본 것으로 쳐요
    return true;
  }
};

/**
 * 새 소식을 처음 확인하는 사용자에게 패널을 저절로 열지 정하고, 패널을 연 기록을 이 기기에 남겨요(NEWS-R6, NEWS-R7).
 *
 * - `isAutoOpen`: 처음 그릴 때 패널을 저절로 열지. 이 기기에서 새 소식을 한 번도 열지 않았고 노트가 있을 때만 열어요.
 *   녹음 중에는 방해가 되므로 열지 않아요. 이때는 읽음도 남기지 않아 녹음 뒤에 들어오면 열려요.
 * - `markAsSeen`: 패널이 열리면(저절로든 눌러서든) 맨 위 노트 id를 남겨 다음부터는 저절로 열지 않아요.
 *
 * 판단은 처음 그릴 때 한 번만 해요. 열린 채로 녹음을 시작해도 닫지 않아요.
 */
export const useLastSeenNote = ({ notes }: UseLastSeenNoteParams) => {
  const { isRecordingActive } = useRecordingState();
  const [isAutoOpen] = useState(
    () => notes.length > 0 && !isRecordingActive && !hasSeenNotes(),
  );

  const markAsSeen = () => {
    if (notes.length === 0) return;

    try {
      localStorage.setItem(LAST_SEEN_NOTE_ID_KEY, notes[0].id);
    } catch {
      // 남길 수 없으면 이번에 연 것만으로 넘어가요
    }
  };

  return { isAutoOpen, markAsSeen };
};
