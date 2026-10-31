/**
 * 새 소식 노트 DTO. 우리 API가 아니라 프론트 Worker가 Notion 새 소식 DB를 읽어 돌려줘요(ADR 956)
 *
 * - GET /relay/n
 */

/** 노트 항목 하나의 Worker 응답 모양 */
export interface ReleaseNoteItemRaw {
  title: string;
  description: string;
  image?: string;
  link?: string;
  isFromFeedback?: boolean;
  isHighlight?: boolean;
}

/** 새 소식 노트의 항목 하나. 사용자가 알아챌 만한 변화 하나를 적어요 */
export class ReleaseNoteItemDto {
  /** 무엇을 할 수 있게 됐는지 한 줄. 해요체로 써요 */
  title: string;
  /** 짧은 설명 */
  description: string;
  /** 변화를 보여 주는 사진 주소. Actions의 Claude가 PR 본문에서 고른 GitHub 첨부 사진이에요. 새 소식 DB 「사진」 칸 그대로라, 앱이 주소를 확인해 그릴지 정해요 */
  image?: string;
  /** 「바로 가보기」로 옮길 화면 키(`CHAT` 등). 지금 보고 있는 워크스페이스의 화면으로 가요. Notion에 적힌 글자 그대로라 앱에 없는 화면일 수 있어요 */
  link?: string;
  /** 사용자가 알려 준 문제를 고친 항목이면 true. 「피드백 덕분에 바뀌었어요」 칩을 붙여요 */
  isFromFeedback?: boolean;
  /** 큰 변화면 true. 노트마다 하나까지만 둬요 */
  isHighlight?: boolean;

  constructor(raw: ReleaseNoteItemRaw) {
    this.title = raw.title;
    this.description = raw.description;
    this.image = raw.image;
    this.link = raw.link;
    this.isFromFeedback = raw.isFromFeedback;
    this.isHighlight = raw.isHighlight;
  }
}

/** 노트 하나의 Worker 응답 모양 */
export interface ReleaseNoteRaw {
  id: string;
  releasedAt: string;
  /** 패널에 보일 항목만 와요. 3일이 지났거나 「숨기기」라 보이지 않는 노트는 글 없이 빈 배열이에요(NEWS-R15, R16) */
  items: ReleaseNoteItemRaw[];
}

/** 머지 하나(또는 옮겨 온 릴리즈 하나)에 낸 새 소식 노트 */
export class ReleaseNoteDto {
  /** 노트 id. Actions가 쓴 노트는 머지 PR 번호(`pr-947`), 상수 파일에서 옮긴 노트는 릴리즈 버전(`v0.2.0`)이에요. 화면에는 보이지 않고 읽음 기억에만 써요 */
  id: string;
  /** 배포 날짜(YYYY-MM-DD). 예: `2026-10-21` */
  releasedAt: string;
  /** 패널에 보일 항목 목록. 하나 이상이에요 */
  items: ReleaseNoteItemDto[];

  constructor(raw: ReleaseNoteRaw) {
    this.id = raw.id;
    this.releasedAt = raw.releasedAt;
    this.items = raw.items.map((item) => new ReleaseNoteItemDto(item));
  }
}

// GET /relay/n

/** 새 소식 노트 목록의 Worker 응답 모양. 객체로 감싸지 않은 노트 배열이고, 패널에 보이지 않는 노트도 순서대로 들어 있어요 */
export type GetReleaseNotesResponseRaw = ReleaseNoteRaw[];

/** 새 소식 노트 목록 응답 */
export class GetReleaseNotesResponseDto {
  /** 패널에 보여 줄 노트. 최신 노트가 맨 앞이고, 보이는 노트가 없으면 빈 배열이에요. Worker 응답에서 항목이 빈 노트를 뺐어요(NEWS-R15) */
  notes: ReleaseNoteDto[];
  /** 안 읽은 노트를 가릴 노트 id 순서. 최신이 맨 앞이고, 패널에 보이지 않는 노트도 들어 있어요(NEWS-R16). Worker 응답의 노트 id 그대로예요 */
  noteIds: string[];

  constructor(raw: GetReleaseNotesResponseRaw) {
    this.notes = raw
      .filter(({ items }) => items.length > 0)
      .map((note) => new ReleaseNoteDto(note));
    this.noteIds = raw.map(({ id }) => id);
  }
}
