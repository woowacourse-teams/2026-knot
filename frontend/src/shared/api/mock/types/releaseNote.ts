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
  items: ReleaseNoteItem[];
}
