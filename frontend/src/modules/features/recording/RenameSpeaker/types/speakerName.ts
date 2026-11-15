/** 이름 바꾸기 창에서 저장하려는 이름과 그 이름을 정한 방법 */
export interface SpeakerNameDraft {
  /** 앞뒤 공백을 뺀 새 이름 */
  name: string;
  /** 워크스페이스 멤버를 골랐으면 `member`, 직접 적었거나 고른 뒤 고쳐 적었으면 `typed`예요 */
  nameSource: "member" | "typed";
}
