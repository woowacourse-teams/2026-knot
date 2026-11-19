/** 카드 본문의 소제목 묶음 하나 */
export interface WriteCardBodySection {
  /** 소제목. 첫 소제목보다 앞에 온 줄의 묶음은 `null`이에요 */
  heading: string | null;

  /** 소제목 아래 줄. 글머리는 `• `로 시작해요 */
  lines: string[];
}
