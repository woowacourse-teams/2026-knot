import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";

import {
  parseLinkScreens,
  readGuide,
  replaceNote,
  toKstDate,
  validateNote,
} from "./release-note.mjs";

const LINK_KEYS = ["WORKSPACE_HOME", "CHAT"];
const IMAGE = "https://github.com/user-attachments/assets/76f059d4";

const item = (overrides = {}) => ({
  title: "탐색 기록이 남아요",
  description: "다시 열면 보던 대화가 그대로예요.",
  ...overrides,
});

/** Notion 호출을 남기고, 경로와 메서드에 맞춰 정해 둔 응답을 돌려주는 가짜 클라이언트 */
const fakeNotion = (respond = () => ({})) => {
  const calls = [];
  const notion = async (path, { method = "GET", body } = {}) => {
    calls.push({ path, method, body });
    return respond({ path, method, body });
  };
  return { notion, calls };
};

describe("parseLinkScreens", () => {
  it("라우트 표에서 워크스페이스 ID만 채우면 갈 수 있는 화면만 고른다", () => {
    const source = `
      import { generatePath } from "react-router";
      export const PATH_ROUTE = {
        HOME: "/",
        WORKSPACE_HOME: "/workspace/:workspaceId",
        // 주석은 건너뛴다
        CHAT: "/workspace/:workspaceId/chat",
        CHAT_SESSION: "/workspace/:workspaceId/chat/:sessionId",
        INVITE: "/invite/:token",
        FILES: "/workspace/:workspaceId/*",
      } as const;
      const OTHER = { NOT_ROUTE: "/workspace/:workspaceId/x" };
    `;

    assert.deepEqual(parseLinkScreens(source), [
      { key: "WORKSPACE_HOME", path: "/workspace/:workspaceId" },
      { key: "CHAT", path: "/workspace/:workspaceId/chat" },
    ]);
  });

  it("실제 라우트 표를 읽을 수 있다", () => {
    const keys = parseLinkScreens(
      readFileSync(
        new URL("../src/shared/routes/PATH_ROUTE.ts", import.meta.url),
        "utf8",
      ),
    ).map(({ key }) => key);

    assert.ok(keys.includes("WORKSPACE_HOME"));
    assert.ok(keys.includes("CHAT"));
    assert.ok(!keys.includes("CHAT_SESSION"));
    assert.ok(!keys.includes("LOGIN"));
  });

  it("표 모양이 바뀌어 화면을 하나도 못 찾으면 멈춘다", () => {
    assert.throws(() => parseLinkScreens("export const ROUTES = {};"));
  });
});

describe("validateNote", () => {
  it("제목과 설명을 다듬고 사진 · 바로 가보기 · 피드백 덕분 · 큰 변화를 그대로 둔다", () => {
    const { items, errors } = validateNote({
      note: {
        items: [
          item({
            title: "  탐색 기록이 남아요 ",
            image: IMAGE,
            link: "CHAT",
            isFromFeedback: true,
            isHighlight: true,
          }),
        ],
      },
      linkKeys: LINK_KEYS,
    });

    assert.deepEqual(errors, []);
    assert.deepEqual(items, [
      {
        title: "탐색 기록이 남아요",
        description: "다시 열면 보던 대화가 그대로예요.",
        image: IMAGE,
        link: "CHAT",
        isFromFeedback: true,
        isHighlight: true,
      },
    ]);
  });

  it("항목이 없으면 사용자 변화가 없는 노트로 통과한다", () => {
    assert.deepEqual(
      validateNote({ note: { items: [] }, linkKeys: LINK_KEYS }),
      { items: [], errors: [] },
    );
  });

  it("JSON 객체가 아니거나 items가 배열이 아니면 고칠 것으로 알린다", () => {
    assert.equal(
      validateNote({ note: null, linkKeys: LINK_KEYS }).errors.length,
      1,
    );
    assert.equal(
      validateNote({ note: { items: "없음" }, linkKeys: LINK_KEYS }).errors
        .length,
      1,
    );
  });

  it("제목이나 설명이 비어 있으면 고칠 것으로 알린다", () => {
    const { errors } = validateNote({
      note: {
        items: [item({ title: " " }), item({ description: "" })],
      },
      linkKeys: LINK_KEYS,
    });

    assert.equal(errors.length, 2);
  });

  it("바로 가보기가 앱 화면 키가 아니면 고칠 것으로 알린다", () => {
    const { errors } = validateNote({
      note: {
        items: [item({ link: "LOGIN" }), item({ link: "https://knoted.kr" })],
      },
      linkKeys: LINK_KEYS,
    });

    assert.equal(errors.length, 2);
  });

  it("사진이 GitHub 첨부 사진 주소가 아니면 고칠 것으로 알린다", () => {
    const { errors } = validateNote({
      note: {
        items: [
          item({
            image:
              "https://github-production-user-asset-6210df.s3.amazonaws.com/1/a.png",
          }),
          item({ image: "https://knoted.kr/a.png" }),
          item({ image: "http://github.com/user-attachments/assets/a" }),
          item({ image: 1 }),
        ],
      },
      linkKeys: LINK_KEYS,
    });

    assert.equal(errors.length, 4);
  });

  it("큰 변화가 둘 이상이면 고칠 것으로 알린다", () => {
    const { errors } = validateNote({
      note: {
        items: [item({ isHighlight: true }), item({ isHighlight: true })],
      },
      linkKeys: LINK_KEYS,
    });

    assert.equal(errors.length, 1);
  });
});

describe("toKstDate", () => {
  it("머지 시각을 한국 날짜로 바꾼다", () => {
    assert.equal(toKstDate("2026-10-24T16:30:00Z"), "2026-10-25");
    assert.equal(toKstDate("2026-10-24T14:59:00Z"), "2026-10-24");
  });
});

describe("replaceNote", () => {
  const existingRows = {
    results: [{ id: "old-1" }, { id: "old-2" }],
    has_more: false,
  };

  it("같은 id 줄을 찾아 새 줄을 순서대로 넣은 뒤 옛 줄을 보관해, 다시 실행해도 겹치지 않는다", async () => {
    const { notion, calls } = fakeNotion(({ method }) =>
      method === "POST" ? existingRows : {},
    );
    const items = [
      item({ link: "CHAT", isHighlight: true }),
      item({ title: "둘째" }),
    ];

    await replaceNote({
      notion,
      databaseId: "db",
      noteId: "pr-947",
      releasedAt: "2026-10-25",
      items,
    });

    assert.deepEqual(
      calls.map(({ path, method }) => `${method} ${path}`),
      [
        "POST /databases/db/query",
        "POST /pages",
        "POST /pages",
        "PATCH /pages/old-1",
        "PATCH /pages/old-2",
      ],
    );
    assert.deepEqual(calls[0].body.filter, {
      property: "노트 id",
      rich_text: { equals: "pr-947" },
    });
    assert.deepEqual(calls[1].body, {
      parent: { database_id: "db" },
      properties: {
        제목: { title: [{ text: { content: "탐색 기록이 남아요" } }] },
        "노트 id": { rich_text: [{ text: { content: "pr-947" } }] },
        "배포 날짜": { date: { start: "2026-10-25" } },
        설명: {
          rich_text: [
            { text: { content: "다시 열면 보던 대화가 그대로예요." } },
          ],
        },
        "바로 가보기": { rich_text: [{ text: { content: "CHAT" } }] },
        "피드백 덕분": { checkbox: false },
        "큰 변화": { checkbox: true },
        순서: { number: 1 },
      },
    });
    assert.deepEqual(calls[2].body.properties["바로 가보기"], {
      rich_text: [],
    });
    assert.deepEqual(calls[2].body.properties.순서, { number: 2 });
    assert.deepEqual(calls[3].body, { archived: true });
  });

  it("사진이 있는 항목만 「사진」 칸을 넣는다", async () => {
    const { notion, calls } = fakeNotion(({ path }) =>
      path.endsWith("/query") ? { results: [], has_more: false } : {},
    );

    await replaceNote({
      notion,
      databaseId: "db",
      noteId: "pr-1051",
      releasedAt: "2026-10-27",
      items: [item({ image: IMAGE }), item()],
    });

    assert.deepEqual(calls[1].body.properties.사진, { url: IMAGE });
    assert.equal("사진" in calls[2].body.properties, false);
  });

  it("같은 id 줄이 없으면 새 줄만 넣는다", async () => {
    const { notion, calls } = fakeNotion(({ path }) =>
      path.endsWith("/query") ? { results: [], has_more: false } : {},
    );

    await replaceNote({
      notion,
      databaseId: "db",
      noteId: "pr-948",
      releasedAt: "2026-10-25",
      items: [item()],
    });

    assert.deepEqual(
      calls.map(({ method }) => method),
      ["POST", "POST"],
    );
  });

  it("새 줄을 넣다 실패하면 옛 줄을 지우지 않는다", async () => {
    const { notion, calls } = fakeNotion(({ path }) => {
      if (path === "/pages") throw new Error("Notion 400");
      return existingRows;
    });

    await assert.rejects(
      replaceNote({
        notion,
        databaseId: "db",
        noteId: "pr-947",
        releasedAt: "2026-10-25",
        items: [item()],
      }),
      /Notion 400/,
    );
    assert.equal(
      calls.some(({ method }) => method === "PATCH"),
      false,
    );
  });
});

describe("readGuide", () => {
  it("페이지 블록을 이어 읽어 제목 · 목록 · 하위 블록을 글로 바꾼다", async () => {
    const text = (content) => [{ plain_text: content }];
    const pages = {
      "/blocks/guide/children?page_size=100": {
        results: [
          {
            id: "h",
            type: "heading_2",
            has_children: false,
            heading_2: { rich_text: text("쓰는 기준") },
          },
          {
            id: "b",
            type: "bulleted_list_item",
            has_children: true,
            bulleted_list_item: { rich_text: text("해요체로 써요") },
          },
        ],
        has_more: true,
        next_cursor: "next",
      },
      "/blocks/guide/children?page_size=100&start_cursor=next": {
        results: [
          { id: "d", type: "divider", has_children: false, divider: {} },
        ],
        has_more: false,
      },
      "/blocks/b/children?page_size=100": {
        results: [
          {
            id: "p",
            type: "paragraph",
            has_children: false,
            paragraph: { rich_text: text("예: 볼 수 있어요") },
          },
        ],
        has_more: false,
      },
    };
    const { notion } = fakeNotion(({ path }) => pages[path]);

    assert.equal(
      await readGuide({ notion, pageId: "guide" }),
      "## 쓰는 기준\n- 해요체로 써요\n  예: 볼 수 있어요",
    );
  });
});
