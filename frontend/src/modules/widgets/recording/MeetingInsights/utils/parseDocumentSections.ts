export interface DocumentSections {
  decisions: string[];
  holds: string[];
  undecided: string[];
  todos: string[];
}

const SECTION_KEY_BY_HEADING: Record<string, keyof DocumentSections> = {
  결정: "decisions",
  보류: "holds",
  미결정: "undecided",
  "할 일": "todos",
};

const HEADING_PATTERN = /^##\s+(.+?)\s*$/;
const LIST_ITEM_PATTERN = /^\s*(?:[-*]|\d+\.)\s+(.+?)\s*$/;

/** 구역의 줄에서 항목을 꺼내요. 목록이 하나라도 있으면 목록만, 없으면 문장 줄을 항목으로 봐요 */
const readItems = (lines: string[]) => {
  const listItems = lines.flatMap((line) => {
    const match = LIST_ITEM_PATTERN.exec(line);

    return match === null ? [] : [match[1]];
  });

  if (listItems.length > 0) return listItems;

  return lines.map((line) => line.trim()).filter((line) => line !== "");
};

/**
 * 주제별 문서 본문에서 결정 · 보류 · 미결정 · 할 일 구역의 항목을 꺼내요.
 * 문서 생성 규칙이 구역 이름을 고정해서 구역 이름으로 찾아요.
 */
export const parseDocumentSections = (content: string): DocumentSections => {
  const sections: DocumentSections = {
    decisions: [],
    holds: [],
    undecided: [],
    todos: [],
  };
  let currentKey: keyof DocumentSections | null = null;
  let currentLines: string[] = [];

  const closeSection = () => {
    if (currentKey !== null)
      sections[currentKey].push(...readItems(currentLines));
    currentLines = [];
  };

  content.split("\n").forEach((line) => {
    const heading = HEADING_PATTERN.exec(line);

    if (heading === null) {
      currentLines.push(line);

      return;
    }

    closeSection();
    currentKey = SECTION_KEY_BY_HEADING[heading[1]] ?? null;
  });
  closeSection();

  return sections;
};
