export interface ReleaseNoteItem {
  title: string;
  description: string;
  image?: string;
  link?: string;
  isFromFeedback?: boolean;
  isHighlight?: boolean;
}

export interface ReleaseNote {
  /** 머지 PR 번호(`pr-947`) 또는 옮겨 온 릴리즈 버전(`v0.2.0`) */
  id: string;
  /** YYYY-MM-DD */
  releasedAt: string;
  /** 패널에 보일 항목. 패널에서 빠진 노트(3일 지남, 「숨기기」)는 빈 배열이에요 */
  items: ReleaseNoteItem[];
}
