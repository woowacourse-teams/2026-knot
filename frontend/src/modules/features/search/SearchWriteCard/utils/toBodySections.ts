import type { WriteCardBodySection } from "../types/writeCardBody";

const HEADING = /^#{1,6}\s+(.*)$/;
const BULLET = /^[-*]\s+(.*)$/;

/**
 * 카드에 보여 줄 초안 본문(Markdown)을 소제목 묶음으로 나눠요.
 *
 * `#` 소제목마다 묶음을 새로 만들고, `-`, `*` 글머리는 `• `로 바꾸며, 빈 줄은 버려요. 그 밖의 문법은 글자 그대로 둬요.
 *
 * @example
 * toBodySections("## 결정\n- 출시일은 12월 3일");
 * // [{ heading: "결정", lines: ["• 출시일은 12월 3일"] }]
 */
export const toBodySections = (body: string) => {
  const sections: WriteCardBodySection[] = [];

  for (const rawLine of body.split("\n")) {
    const line = rawLine.trim();
    if (line === "") continue;

    const heading = line.match(HEADING);
    if (heading) {
      sections.push({ heading: heading[1], lines: [] });
      continue;
    }

    const bullet = line.match(BULLET);
    if (sections.length === 0) sections.push({ heading: null, lines: [] });
    sections[sections.length - 1].lines.push(bullet ? `• ${bullet[1]}` : line);
  }

  return sections;
};
