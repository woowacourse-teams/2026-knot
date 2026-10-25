#!/usr/bin/env node
/**
 * release-note — main 머지마다 Claude가 낸 새 소식 노트를 Notion 새 소식 DB에 넣는 워크플로 스크립트(ADR 956).
 * `.github/workflows/release-note.yml`이 부른다. 앱은 프론트 Worker(`/relay/n`)를 거쳐 이 DB를 읽는다.
 *
 * 모드:
 * - guide: Notion 「릴리즈 노트」 페이지(노트 문구 기준)를 글로 읽어 GITHUB_OUTPUT의 `guide`로 낸다. 저장소에는 옮기지 않는다.
 *   바로 가보기로 고를 수 있는 앱 화면도 앱 라우트 표에서 읽어 `link_keys`(JSON 배열)와 `links`(화면 키와 주소)로 낸다.
 * - publish: NOTE_JSON(Claude의 structured_output)을 검사하고, 항목이 있으면 노트 id `pr-{PR_NUMBER}`로 DB에 바꿔 넣는다.
 *   항목이 없으면 「사용자 변화 없음」으로 DB를 건드리지 않는다.
 *
 * 환경 변수: NOTION_TOKEN(새 소식 DB와 「릴리즈 노트」 페이지에 연결한 쓰기 연동), NOTION_DATABASE_ID,
 * publish만 NOTE_JSON, PR_NUMBER, MERGED_AT(PR 머지 시각, ISO 8601).
 * 출력: GITHUB_STEP_SUMMARY에 쓴 노트와 Notion 링크, 또는 「사용자 변화 없음」.
 * 종료 코드: 0 성공, 1 노트 형식이 틀렸거나 Notion 호출이 실패함(다시 실행하면 같은 id로 바꿔 넣는다).
 */
import { appendFileSync, readFileSync } from "node:fs";
import { randomUUID } from "node:crypto";
import { pathToFileURL } from "node:url";

/** 노트 문구 기준이 있는 Notion 「릴리즈 노트」 페이지 */
const GUIDE_PAGE_ID = "3feb435175228125a4bac00f35c39df5";
const NOTION_API_URL = "https://api.notion.com/v1";
// Worker(`src/worker/relay`)와 같은 판을 써요. 2022-06-28 판은 DB ID로 줄을 넣고 조회해요
const NOTION_VERSION = "2022-06-28";
const KST_OFFSET_MS = 9 * 60 * 60 * 1000;

/** 「바로 가보기」로 고를 화면을 읽는 앱 라우트 표. 화면 목록을 따로 적어 두지 않고 실행할 때마다 여기서 읽는다 */
const PATH_ROUTE_FILE = new URL(
  "../src/shared/routes/PATH_ROUTE.ts",
  import.meta.url,
);

/**
 * 앱 라우트 표의 글에서 워크스페이스 ID만 채우면 갈 수 있는 화면을 `{ key, path }`로 고른다.
 * 앱의 `isWorkspaceRouteKey`와 같은 기준이다. 표가 `KEY: "경로",` 모양이 아니게 바뀌어 하나도 못 찾으면 멈춘다.
 */
export const parseLinkScreens = (source) => {
  const table = source.match(/PATH_ROUTE = \{([\s\S]*?)\} as const/)?.[1] ?? "";
  const screens = [...table.matchAll(/^\s*([A-Z_]+):\s*"([^"]*)"/gm)]
    .map(([, key, path]) => ({ key, path }))
    .filter(({ path }) => path.match(/:\w+|\*/g)?.join() === ":workspaceId");

  if (screens.length === 0) {
    throw new Error(
      `${PATH_ROUTE_FILE.pathname}에서 바로 가보기 화면을 찾지 못했어요. 라우트 표가 \`KEY: "경로",\` 모양인지 확인해 주세요.`,
    );
  }

  return screens;
};

const readLinkScreens = () =>
  parseLinkScreens(readFileSync(PATH_ROUTE_FILE, "utf8"));

const isFilledString = (value) =>
  typeof value === "string" && value.trim().length > 0;

/**
 * Claude가 낸 노트 JSON을 검사한다. 제목과 설명은 꼭 있어야 하고, 바로 가보기는 앱 화면 키만, 큰 변화는 하나까지 받는다.
 * items가 비어 있으면 사용자 변화가 없는 노트다.
 */
export const validateNote = ({ note, linkKeys }) => {
  if (typeof note !== "object" || note === null || !Array.isArray(note.items)) {
    return {
      items: [],
      errors: ["노트 JSON은 `{ items: [...] }` 모양이어야 해요."],
    };
  }

  const errors = [];
  const items = note.items.map((rawItem, index) => {
    const { title, description, link, isFromFeedback, isHighlight } =
      rawItem ?? {};
    const label = `${index + 1}번째 항목`;

    if (!isFilledString(title)) errors.push(`${label}에 제목이 없어요.`);
    if (!isFilledString(description)) errors.push(`${label}에 설명이 없어요.`);
    if (link != null && !linkKeys.includes(link)) {
      errors.push(
        `${label}의 바로 가보기 「${link}」는 앱 화면 키(${linkKeys.join(", ")})가 아니에요.`,
      );
    }

    return {
      title: String(title ?? "").trim(),
      description: String(description ?? "").trim(),
      link: link ?? undefined,
      isFromFeedback: isFromFeedback === true,
      isHighlight: isHighlight === true,
    };
  });

  if (items.filter(({ isHighlight }) => isHighlight).length > 1) {
    errors.push("큰 변화는 노트마다 하나까지만 둬요.");
  }

  return { items: errors.length > 0 ? [] : items, errors };
};

/** 머지 시각을 한국 날짜(YYYY-MM-DD)로 바꾼다. 다시 실행해도 배포 날짜가 바뀌지 않게 실행 시각이 아니라 머지 시각을 쓴다 */
export const toKstDate = (iso) =>
  new Date(new Date(iso).getTime() + KST_OFFSET_MS).toISOString().slice(0, 10);

const toRichText = (content) => (content ? [{ text: { content } }] : []);

/** 항목 하나를 새 소식 DB 한 줄로 바꾼다. 칸 이름은 Worker가 읽는 이름과 같다 */
const toNotionPage = ({ databaseId, noteId, releasedAt, item, order }) => ({
  parent: { database_id: databaseId },
  properties: {
    제목: { title: toRichText(item.title) },
    "노트 id": { rich_text: toRichText(noteId) },
    "배포 날짜": { date: { start: releasedAt } },
    설명: { rich_text: toRichText(item.description) },
    "바로 가보기": { rich_text: toRichText(item.link ?? "") },
    "피드백 덕분": { checkbox: item.isFromFeedback === true },
    "큰 변화": { checkbox: item.isHighlight === true },
    순서: { number: order },
  },
});

/**
 * 같은 노트 id의 줄을 새 항목으로 바꿔 넣는다. 새 줄을 모두 넣은 뒤에 옛 줄을 보관해, 중간에 실패해도 옛 노트가 사라지지 않는다.
 * 실패한 뒤 다시 실행하면 옛 줄과 먼저 넣은 새 줄을 함께 찾아 보관하므로 겹치지 않는다.
 */
export const replaceNote = async ({
  notion,
  databaseId,
  noteId,
  releasedAt,
  items,
}) => {
  // shortcut: 같은 id 줄은 100개까지만 찾아요. 노트 하나가 그보다 커지면 next_cursor로 이어 찾아요
  const { results: oldRows } = await notion(`/databases/${databaseId}/query`, {
    method: "POST",
    body: {
      filter: { property: "노트 id", rich_text: { equals: noteId } },
      page_size: 100,
    },
  });

  for (const [index, item] of items.entries()) {
    await notion("/pages", {
      method: "POST",
      body: toNotionPage({
        databaseId,
        noteId,
        releasedAt,
        item,
        order: index + 1,
      }),
    });
  }

  for (const { id } of oldRows) {
    await notion(`/pages/${id}`, { method: "PATCH", body: { archived: true } });
  }
};

const BLOCK_PREFIX = {
  heading_1: "# ",
  heading_2: "## ",
  heading_3: "### ",
  bulleted_list_item: "- ",
  numbered_list_item: "1. ",
  to_do: "- [ ] ",
  quote: "> ",
};

/** Notion 페이지의 블록을 끝까지 읽어 글로 바꾼다. 하위 블록은 두 칸씩 들여 쓰고, 글이 없는 블록(구분선 등)은 뺀다 */
export const readGuide = async ({ notion, pageId, depth = 0 }) => {
  const lines = [];
  let cursor;

  do {
    const query = `page_size=100${cursor ? `&start_cursor=${cursor}` : ""}`;
    const { results, has_more, next_cursor } = await notion(
      `/blocks/${pageId}/children?${query}`,
    );

    for (const block of results) {
      const text = (block[block.type]?.rich_text ?? [])
        .map(({ plain_text }) => plain_text)
        .join("");
      if (text)
        lines.push(
          `${"  ".repeat(depth)}${BLOCK_PREFIX[block.type] ?? ""}${text}`,
        );
      if (
        block.has_children &&
        block.type !== "child_page" &&
        block.type !== "child_database"
      ) {
        const children = await readGuide({
          notion,
          pageId: block.id,
          depth: depth + 1,
        });
        if (children) lines.push(children);
      }
    }

    cursor = has_more ? next_cursor : undefined;
  } while (cursor);

  return lines.join("\n");
};

/** Notion API를 부르는 함수. 실패하면 응답 코드와 Notion 오류 문구를 담아 던진다 */
const createNotion =
  (token) =>
  async (path, { method = "GET", body } = {}) => {
    const response = await fetch(`${NOTION_API_URL}${path}`, {
      method,
      headers: {
        Authorization: `Bearer ${token}`,
        "Notion-Version": NOTION_VERSION,
        "Content-Type": "application/json",
      },
      body: body ? JSON.stringify(body) : undefined,
    });
    const json = await response.json().catch(() => ({}));
    if (!response.ok)
      throw new Error(
        `Notion ${method} ${path} 실패(${response.status}): ${json.message ?? "응답 없음"}`,
      );

    return json;
  };

const writeSummary = (markdown) => {
  if (process.env.GITHUB_STEP_SUMMARY)
    appendFileSync(process.env.GITHUB_STEP_SUMMARY, `${markdown}\n`);
  else console.log(markdown);
};

const renderItem = ({
  title,
  description,
  link,
  isFromFeedback,
  isHighlight,
}) => {
  const marks = [
    link && `바로 가보기: ${link}`,
    isFromFeedback && "피드백 덕분",
    isHighlight && "큰 변화",
  ].filter(Boolean);
  return `- **${title}** ${description}${marks.length > 0 ? ` (${marks.join(", ")})` : ""}`;
};

const runGuide = async (notion) => {
  const guide = await readGuide({ notion, pageId: GUIDE_PAGE_ID });
  const screens = readLinkScreens();
  const delimiter = `GUIDE_${randomUUID()}`;
  appendFileSync(
    process.env.GITHUB_OUTPUT,
    [
      `guide<<${delimiter}`,
      guide,
      delimiter,
      `link_keys=${JSON.stringify(screens.map(({ key }) => key))}`,
      `links=${screens.map(({ key, path }) => `${key}(${path})`).join(", ")}`,
      "",
    ].join("\n"),
  );
};

const runPublish = async (notion) => {
  const { NOTE_JSON, PR_NUMBER, MERGED_AT, NOTION_DATABASE_ID } = process.env;
  const noteId = `pr-${PR_NUMBER}`;

  let note;
  try {
    note = JSON.parse(NOTE_JSON);
  } catch {
    note = null;
  }
  const { items, errors } = validateNote({
    note,
    linkKeys: readLinkScreens().map(({ key }) => key),
  });

  if (errors.length > 0) {
    writeSummary(
      `### 새 소식 노트 \`${noteId}\`: 형식이 틀려 쓰지 않았어요\n\n${errors.map((error) => `- ${error}`).join("\n")}`,
    );
    process.exitCode = 1;
    return;
  }

  if (items.length === 0) {
    writeSummary(
      `### 새 소식 노트 \`${noteId}\`: 사용자 변화 없음\n\n이번 머지로 사용자에게 바뀐 점이 없어 노트를 쓰지 않았어요.`,
    );
    return;
  }

  const releasedAt = toKstDate(MERGED_AT);
  await replaceNote({
    notion,
    databaseId: NOTION_DATABASE_ID,
    noteId,
    releasedAt,
    items,
  });

  writeSummary(
    [
      `### 새 소식 노트 \`${noteId}\` (${releasedAt})`,
      "",
      ...items.map(renderItem),
      "",
      `[Notion 새 소식 DB](https://www.notion.so/${NOTION_DATABASE_ID.replaceAll("-", "")})에 넣었어요. 앱에는 Worker 캐시(5분)가 지나면 보여요.`,
    ].join("\n"),
  );
};

const main = async () => {
  const [mode] = process.argv.slice(2);
  const notion = createNotion(process.env.NOTION_TOKEN);

  if (mode === "guide") return runGuide(notion);
  if (mode === "publish") return runPublish(notion);

  console.error("사용법: node scripts/release-note.mjs guide|publish");
  process.exitCode = 1;
};

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
}
