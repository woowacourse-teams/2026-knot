import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { mockServer } from "@api/mock/server";

import {
  relayReleaseNotes,
  relayToNotion,
  relayToPostHog,
  relayToSentry,
} from ".";

const SITE = "https://dev.knoted.kr";

/** PostHog 두 주소로 넘어간 요청을 꺼내 볼 수 있게 남기고, 정해 둔 응답을 돌려줘요 */
const capturePostHogRequests = (
  createResponse = () => new HttpResponse("ok"),
) => {
  const requests: Request[] = [];
  const capture = ({ request }: { request: Request }) => {
    requests.push(request.clone());

    return createResponse();
  };

  mockServer.use(
    http.all("https://us.i.posthog.com/*", capture),
    http.all("https://us-assets.i.posthog.com/*", capture),
  );

  return requests;
};

describe("relayToPostHog", () => {
  it("이벤트 전송은 경로와 쿼리를 그대로 두고 PostHog 수집 주소로 넘긴다", async () => {
    const requests = capturePostHogRequests();
    const body = JSON.stringify({ event: "$pageview" });

    await relayToPostHog(
      new Request(`${SITE}/relay/p/i/v0/e/?ver=1.438.10&compression=gzip-js`, {
        method: "POST",
        headers: { "Content-Type": "text/plain" },
        body,
      }),
    );

    const [upstream] = requests;
    expect(upstream.url).toBe(
      "https://us.i.posthog.com/i/v0/e/?ver=1.438.10&compression=gzip-js",
    );
    expect(upstream.method).toBe("POST");
    expect(upstream.headers.get("Content-Type")).toBe("text/plain");
    expect(await upstream.text()).toBe(body);
  });

  it.each([
    "/static/1.438.10/posthog-recorder.js",
    "/array/phc_test/config.js",
  ])("스크립트 · 설정 파일(%s)은 PostHog 파일 주소로 넘긴다", async (path) => {
    const requests = capturePostHogRequests();

    await relayToPostHog(new Request(`${SITE}/relay/p${path}`));

    expect(requests[0].url).toBe(`https://us-assets.i.posthog.com${path}`);
  });

  it.each(["/relay/p.evil.com/x", "/relay/p@evil.com/x"])(
    "경로(%s)가 무엇이든 목적지 호스트는 PostHog 주소에서 바뀌지 않는다",
    async (path) => {
      const requests = capturePostHogRequests();

      await relayToPostHog(new Request(`${SITE}${path}`));

      expect(new URL(requests[0].url).host).toBe("us.i.posthog.com");
    },
  );

  it("우리 쿠키와 인증 헤더는 지우고, 방문자 IP를 X-Forwarded-For로 넘긴다", async () => {
    const requests = capturePostHogRequests();

    // msw가 켜져 있으면 본문 있는 요청의 헤더를 복사할 때 Cookie가 빠져서, 지우는 코드가 없어도 통과해요. 그래서 본문 없는 요청으로 확인해요
    await relayToPostHog(
      new Request(`${SITE}/relay/p/array/phc_test/config.js`, {
        headers: {
          Cookie: "ph_session=abc",
          Authorization: "Bearer secret",
          "CF-Connecting-IP": "203.0.113.7",
        },
      }),
    );

    const { headers } = requests[0];
    expect(headers.get("Cookie")).toBeNull();
    expect(headers.get("Authorization")).toBeNull();
    expect(headers.get("X-Forwarded-For")).toBe("203.0.113.7");
  });

  it("PostHog 응답의 상태와 본문은 그대로 돌려주고 Set-Cookie는 지운다", async () => {
    capturePostHogRequests(
      () =>
        new HttpResponse("bad request", {
          status: 400,
          headers: { "Set-Cookie": "tracker=1", "Content-Type": "text/plain" },
        }),
    );

    const response = await relayToPostHog(
      new Request(`${SITE}/relay/p/i/v0/e/`, { method: "POST", body: "x" }),
    );

    expect(response.status).toBe(400);
    expect(await response.text()).toBe("bad request");
    expect(response.headers.get("Set-Cookie")).toBeNull();
    expect(response.headers.get("Content-Type")).toBe("text/plain");
  });
});

describe("relayToSentry", () => {
  const SENTRY_ENVELOPE_URL =
    "https://o4512278291283968.ingest.us.sentry.io/api/4512278350069760/envelope/";
  /** 우리 프로젝트의 DSN. 공개 키 자리는 테스트용 값이에요 */
  const OUR_DSN =
    "https://publickey@o4512278291283968.ingest.us.sentry.io/4512278350069760";

  /** SDK가 보내는 모양의 envelope. 첫 줄 머리에 DSN이 있어요 */
  const toEnvelope = (header: object) =>
    [
      JSON.stringify(header),
      JSON.stringify({ type: "event" }),
      JSON.stringify({ message: "오류" }),
    ].join("\n");

  const toRelayRequest = (body: string, init: RequestInit = {}) =>
    new Request(`${SITE}/relay/s`, { method: "POST", body, ...init });

  /** Sentry envelope 주소로 넘어간 요청을 남기고, 정해 둔 응답을 돌려줘요 */
  const captureSentryRequests = (
    createResponse = () => new HttpResponse("{}"),
  ) => {
    const requests: Request[] = [];
    mockServer.use(
      http.post(SENTRY_ENVELOPE_URL, ({ request }) => {
        requests.push(request.clone());

        return createResponse();
      }),
    );

    return requests;
  };

  it("우리 프로젝트의 envelope는 본문 그대로 그 프로젝트의 envelope 주소로 넘긴다", async () => {
    const requests = captureSentryRequests();
    const envelope = toEnvelope({ event_id: "a1", dsn: OUR_DSN });

    await relayToSentry(
      toRelayRequest(envelope, {
        headers: { "Content-Type": "application/x-sentry-envelope" },
      }),
    );

    const [upstream] = requests;
    expect(upstream.url).toBe(SENTRY_ENVELOPE_URL);
    expect(upstream.method).toBe("POST");
    expect(upstream.headers.get("Content-Type")).toBe(
      "application/x-sentry-envelope",
    );
    expect(await upstream.text()).toBe(envelope);
  });

  it.each([
    {
      reason: "다른 프로젝트",
      dsn: "https://publickey@o4512278291283968.ingest.us.sentry.io/1",
    },
    {
      reason: "다른 수집 주소",
      dsn: "https://publickey@evil.example.com/4512278350069760",
    },
  ])("$reason의 DSN이면 넘기지 않고 403을 돌려준다", async ({ dsn }) => {
    const requests = captureSentryRequests();

    const response = await relayToSentry(
      toRelayRequest(toEnvelope({ event_id: "a1", dsn })),
    );

    expect(response.status).toBe(403);
    expect(requests).toHaveLength(0);
  });

  it.each([
    { reason: "첫 줄이 JSON이 아니면", body: "not-json\n{}" },
    { reason: "머리에 DSN이 없으면", body: toEnvelope({ event_id: "a1" }) },
    {
      reason: "DSN이 주소가 아니면",
      body: toEnvelope({ event_id: "a1", dsn: "not a url" }),
    },
    {
      reason: "첫 줄이 앞 64KB 안에서 끝나지 않으면",
      body: toEnvelope({
        event_id: "a1",
        dsn: OUR_DSN,
        padding: "x".repeat(64 * 1024),
      }),
    },
  ])("$reason 넘기지 않고 400을 돌려준다", async ({ body }) => {
    const requests = captureSentryRequests();

    const response = await relayToSentry(toRelayRequest(body));

    expect(response.status).toBe(400);
    expect(requests).toHaveLength(0);
  });

  it("POST가 아니면 넘기지 않고 405를 돌려준다", async () => {
    const requests = captureSentryRequests();

    const response = await relayToSentry(
      new Request(`${SITE}/relay/s`, { method: "GET" }),
    );

    expect(response.status).toBe(405);
    expect(requests).toHaveLength(0);
  });

  it("Content-Type만 넘기고, 쿠키 · 인증 · 방문자 IP 헤더는 넘기지 않는다", async () => {
    // msw가 켜져 있으면 본문 있는 요청의 헤더를 복사할 때 헤더가 빠져, 지우는 코드가 없어도 통과해요.
    // 그래서 이 테스트만 msw를 끄고 fetch를 바꿔 넘긴 요청을 봐요.
    // vi.spyOn으로 바꾸면 되돌린 뒤 msw를 다시 켜도 가로채지 못해, 다음 테스트가 실제 주소로 요청해요
    mockServer.close();
    const originalFetch = globalThis.fetch;
    const fetchUpstream = vi.fn().mockResolvedValue(new Response("{}"));
    globalThis.fetch = fetchUpstream;

    try {
      await relayToSentry(
        toRelayRequest(toEnvelope({ event_id: "a1", dsn: OUR_DSN }), {
          headers: {
            "Content-Type": "text/plain;charset=UTF-8",
            Cookie: "KNOT_SESSION=abc",
            Authorization: "Bearer x",
            "CF-Connecting-IP": "203.0.113.7",
            "X-Forwarded-For": "203.0.113.7",
            "X-Real-IP": "203.0.113.7",
          },
        }),
      );

      const [upstream] = fetchUpstream.mock.calls[0] as [Request];
      expect([...upstream.headers]).toEqual([
        ["content-type", "text/plain;charset=UTF-8"],
      ]);
    } finally {
      globalThis.fetch = originalFetch;
      mockServer.listen({ onUnhandledRequest: "error" });
    }
  });

  it("Sentry 응답의 상태와 본문은 그대로 돌려주고 Set-Cookie는 지운다", async () => {
    captureSentryRequests(
      () =>
        new HttpResponse("rate limited", {
          status: 429,
          headers: { "Set-Cookie": "tracker=1", "Retry-After": "60" },
        }),
    );

    const response = await relayToSentry(
      toRelayRequest(toEnvelope({ event_id: "a1", dsn: OUR_DSN })),
    );

    expect(response.status).toBe(429);
    expect(await response.text()).toBe("rate limited");
    expect(response.headers.get("Retry-After")).toBe("60");
    expect(response.headers.get("Set-Cookie")).toBeNull();
  });
});

describe("relayToNotion", () => {
  const NOTION_PAGES_URL = "https://api.notion.com/v1/pages";
  const env = {
    ASSETS: { fetch: vi.fn() },
    NOTION_FEEDBACK_TOKEN: "secret_test",
  };
  const feedback = {
    type: "bug",
    content: "문서 복사 버튼을 눌렀는데\n표가 깨져요.",
    pageUrl: `${SITE}/workspace/1/documents/3`,
    replayUrl: "https://us.posthog.com/project/phc_test/replay/abc?t=10",
  };

  const toFeedbackRequest = (
    body: unknown,
    { origin = SITE, method = "POST" } = {},
  ) =>
    new Request(`${SITE}/relay/f`, {
      method,
      headers: { Origin: origin, "Content-Type": "application/json" },
      body: method === "POST" ? JSON.stringify(body) : undefined,
    });

  /** Notion 페이지 생성으로 넘어간 요청을 남기고, 정해 둔 응답을 돌려줘요 */
  const captureNotionRequests = (
    createResponse = () => HttpResponse.json({ id: "page" }),
  ) => {
    const requests: Request[] = [];
    mockServer.use(
      http.post(NOTION_PAGES_URL, ({ request }) => {
        requests.push(request.clone());

        return createResponse();
      }),
    );

    return requests;
  };

  it("피드백을 접수함에 새 줄로 넣고 201을 돌려준다", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    // 한국 시간으로는 이미 다음 날이에요
    vi.setSystemTime(new Date("2026-10-22T15:30:00Z"));
    const requests = captureNotionRequests();

    const response = await relayToNotion(toFeedbackRequest(feedback), env);
    vi.useRealTimers();

    expect(response.status).toBe(201);
    const [upstream] = requests;
    expect(upstream.headers.get("Authorization")).toBe("Bearer secret_test");
    expect(upstream.headers.get("Notion-Version")).toBe("2022-06-28");
    expect(await upstream.json()).toEqual({
      parent: { database_id: "98224ba95fff4abe81a4a9ee67591523" },
      properties: {
        피드백: { title: [{ text: { content: "문서 복사 버튼을 눌렀는데" } }] },
        종류: { select: { name: "버그" } },
        내용: { rich_text: [{ text: { content: feedback.content } }] },
        "사용 상황": { rich_text: [{ text: { content: feedback.pageUrl } }] },
        "원문 링크": { url: feedback.replayUrl },
        "접수 경로": { select: { name: "앱 내" } },
        "출처 유형": { select: { name: "사용자 제보" } },
        "접수 상태": { select: { name: "신규" } },
        접수일: { date: { start: "2026-10-23" } },
      },
    });
  });

  it.each([
    { type: "inconvenience", kind: "불편" },
    { type: "idea", kind: "요청" },
    { type: "praise", kind: "칭찬" },
  ])("유형 $type은 종류 「$kind」로 넣는다", async ({ type, kind }) => {
    const requests = captureNotionRequests();

    await relayToNotion(toFeedbackRequest({ ...feedback, type }), env);

    const { properties } = await requests[0].json();
    expect(properties.종류).toEqual({ select: { name: kind } });
  });

  it("내용 없이 보내면 제목은 「(내용 없음)」이고 내용 칸과 원문 링크는 비운다", async () => {
    const requests = captureNotionRequests();

    await relayToNotion(
      toFeedbackRequest({ ...feedback, content: "", replayUrl: undefined }),
      env,
    );

    const { properties } = await requests[0].json();
    expect(properties.피드백).toEqual({
      title: [{ text: { content: "(내용 없음)" } }],
    });
    expect(properties.내용).toEqual({ rich_text: [] });
    expect(properties["원문 링크"]).toEqual({ url: null });
  });

  it("제목은 첫 줄의 앞 60자까지만 쓴다", async () => {
    const requests = captureNotionRequests();

    await relayToNotion(
      toFeedbackRequest({ ...feedback, content: "가".repeat(80) }),
      env,
    );

    const { properties } = await requests[0].json();
    expect(properties.피드백.title[0].text.content).toBe("가".repeat(60));
  });

  it.each([
    { reason: "Origin이 다른 주소", origin: "https://evil.example.com" },
    { reason: "Origin이 없음", origin: "" },
  ])("$reason이면 넣지 않고 403을 돌려준다", async ({ origin }) => {
    const requests = captureNotionRequests();

    const response = await relayToNotion(
      toFeedbackRequest(feedback, { origin }),
      env,
    );

    expect(response.status).toBe(403);
    expect(requests).toHaveLength(0);
  });

  it.each([
    {
      reason: "유형이 넷 중 하나가 아니면",
      body: { ...feedback, type: "toString" },
    },
    {
      reason: "내용이 2000자를 넘으면",
      body: { ...feedback, content: "가".repeat(2001) },
    },
    {
      reason: "화면 주소가 우리 주소가 아니면",
      body: { ...feedback, pageUrl: "https://evil.example.com/x" },
    },
    {
      reason: "원문 링크가 PostHog 리플레이가 아니면",
      body: { ...feedback, replayUrl: "https://evil.example.com/x" },
    },
    { reason: "본문이 객체가 아니면", body: "피드백" },
  ])("$reason 넣지 않고 400을 돌려준다", async ({ body }) => {
    const requests = captureNotionRequests();

    const response = await relayToNotion(toFeedbackRequest(body), env);

    expect(response.status).toBe(400);
    expect(requests).toHaveLength(0);
  });

  it("본문이 너무 길면 넣지 않고 413을 돌려준다", async () => {
    const requests = captureNotionRequests();

    const response = await relayToNotion(
      toFeedbackRequest({ ...feedback, padding: "x".repeat(8 * 1024) }),
      env,
    );

    expect(response.status).toBe(413);
    expect(requests).toHaveLength(0);
  });

  it("POST가 아니면 넣지 않고 405를 돌려준다", async () => {
    const requests = captureNotionRequests();

    const response = await relayToNotion(
      toFeedbackRequest(undefined, { method: "GET" }),
      env,
    );

    expect(response.status).toBe(405);
    expect(requests).toHaveLength(0);
  });

  it("토큰이 없으면 넣지 않고 503을 돌려준다", async () => {
    const requests = captureNotionRequests();

    const response = await relayToNotion(toFeedbackRequest(feedback), {
      ASSETS: env.ASSETS,
    });

    expect(response.status).toBe(503);
    expect(requests).toHaveLength(0);
  });

  it("Notion이 거절하면 그 응답 코드를 그대로 돌려준다", async () => {
    captureNotionRequests(
      () => new HttpResponse('{"code":"unauthorized"}', { status: 401 }),
    );

    const response = await relayToNotion(toFeedbackRequest(feedback), env);

    expect(response.status).toBe(401);
  });
});

describe("relayReleaseNotes", () => {
  const DATABASE_ID = "release-note-db";
  const NOTION_QUERY_URL = `https://api.notion.com/v1/databases/${DATABASE_ID}/query`;
  const env = {
    ASSETS: { fetch: vi.fn() },
    NOTION_RELEASE_NOTE_TOKEN: "secret_read",
    NOTION_RELEASE_NOTE_DATABASE_ID: DATABASE_ID,
  };

  interface RowParams {
    noteId?: string;
    releasedAt?: string;
    title?: string;
    description?: string;
    link?: string;
    isFromFeedback?: boolean;
    isHighlight?: boolean;
    order?: number | null;
  }

  const toText = (value: string) => (value ? [{ plain_text: value }] : []);

  /** 새 소식 DB의 한 줄(항목 하나)을 Notion 조회 응답 모양으로 만들어요 */
  const toRow = ({
    noteId = "pr-947",
    releasedAt = "2026-10-25",
    title = "제목",
    description = "설명",
    link = "",
    isFromFeedback = false,
    isHighlight = false,
    order = 1,
  }: RowParams = {}) => ({
    object: "page",
    properties: {
      제목: { type: "title", title: toText(title) },
      "노트 id": { type: "rich_text", rich_text: toText(noteId) },
      "배포 날짜": {
        type: "date",
        date: releasedAt ? { start: releasedAt } : null,
      },
      설명: { type: "rich_text", rich_text: toText(description) },
      "바로 가보기": { type: "rich_text", rich_text: toText(link) },
      "피드백 덕분": { type: "checkbox", checkbox: isFromFeedback },
      "큰 변화": { type: "checkbox", checkbox: isHighlight },
      순서: { type: "number", number: order },
    },
  });

  /** Notion 조회로 넘어간 요청을 남기고, 정해 둔 응답을 돌려줘요 */
  const captureNotionQueries = (
    createResponse: () => Response = () =>
      HttpResponse.json({ results: [], has_more: false }),
  ) => {
    const requests: Request[] = [];
    mockServer.use(
      http.post(NOTION_QUERY_URL, ({ request }) => {
        requests.push(request.clone());

        return createResponse();
      }),
    );

    return requests;
  };

  const respondRows = (rows: ReturnType<typeof toRow>[]) =>
    captureNotionQueries(() =>
      HttpResponse.json({ results: rows, has_more: false }),
    );

  const requestNotes = (path = "/relay/n", { method = "GET" } = {}) =>
    relayReleaseNotes(new Request(`${SITE}${path}`, { method }), env);

  // Workers의 Cache API는 테스트 환경에 없어서, 주소별로 응답을 담아 두는 캐시로 바꿔 둬요
  beforeEach(() => {
    const store = new Map<string, Response>();
    vi.stubGlobal("caches", {
      open: async () => ({
        match: async (request: Request) => store.get(request.url)?.clone(),
        put: async (request: Request, response: Response) => {
          store.set(request.url, response);
        },
      }),
    });
  });
  afterEach(() => vi.unstubAllGlobals());

  it("새 소식 DB를 읽어 노트별로 묶고, 최신 노트를 맨 앞에, 항목은 순서대로 돌려준다", async () => {
    const requests = respondRows([
      toRow({ noteId: "v0.1.0", releasedAt: "2026-10-19", title: "옛 항목" }),
      toRow({ noteId: "pr-947", title: "둘째 항목", order: 2 }),
      toRow({
        noteId: "pr-947",
        title: "첫 항목",
        description: "첫 설명",
        link: "CHAT",
        isFromFeedback: true,
        isHighlight: true,
        order: 1,
      }),
    ]);

    const response = await requestNotes();

    expect(response.status).toBe(200);
    expect(response.headers.get("Content-Type")).toBe("application/json");
    expect(await response.json()).toEqual([
      {
        id: "pr-947",
        releasedAt: "2026-10-25",
        items: [
          {
            title: "첫 항목",
            description: "첫 설명",
            link: "CHAT",
            isFromFeedback: true,
            isHighlight: true,
          },
          {
            title: "둘째 항목",
            description: "설명",
            isFromFeedback: false,
            isHighlight: false,
          },
        ],
      },
      {
        id: "v0.1.0",
        releasedAt: "2026-10-19",
        items: [
          {
            title: "옛 항목",
            description: "설명",
            isFromFeedback: false,
            isHighlight: false,
          },
        ],
      },
    ]);
    const [upstream] = requests;
    expect(upstream.headers.get("Authorization")).toBe("Bearer secret_read");
    expect(upstream.headers.get("Notion-Version")).toBe("2022-06-28");
    expect(await upstream.json()).toEqual({
      page_size: 100,
      sorts: [{ property: "배포 날짜", direction: "descending" }],
    });
  });

  it("같은 날 낸 노트는 나중에 머지한 PR의 노트를 앞에 둔다", async () => {
    respondRows([toRow({ noteId: "pr-998" }), toRow({ noteId: "pr-1002" })]);

    const response = await requestNotes();
    const notes: { id: string }[] = await response.json();

    expect(notes.map(({ id }) => id)).toEqual(["pr-1002", "pr-998"]);
  });

  it.each<[string, RowParams]>([
    ["노트 id가 없는", { noteId: "" }],
    ["배포 날짜가 없는", { releasedAt: "" }],
    ["제목이 없는", { title: "" }],
    ["설명이 없는", { description: "" }],
    ["바로 가보기가 앱 화면 키가 아닌", { link: "https://evil.com" }],
    ["바로 가보기가 워크스페이스 밖 화면인", { link: "LOGIN" }],
  ])("%s 줄은 빼고 돌려준다", async (_, row) => {
    respondRows([toRow({ title: "맞는 항목" }), toRow(row)]);

    const response = await requestNotes();
    const [note] = await response.json();

    expect(note.items).toHaveLength(1);
    expect(note.items[0].title).toBe("맞는 항목");
  });

  it("새 소식 DB가 비어 있으면 빈 배열을 돌려준다", async () => {
    respondRows([]);

    const response = await requestNotes();

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual([]);
  });

  it("한 번 읽은 노트는 캐시에서 돌려주고, 쿼리 문자열을 바꿔도 Notion을 다시 부르지 않는다", async () => {
    const requests = respondRows([toRow()]);

    await requestNotes();
    const cached = await requestNotes("/relay/n?nocache=1");

    expect(requests).toHaveLength(1);
    expect(await cached.json()).toHaveLength(1);
  });

  it.each([
    ["토큰이", { NOTION_RELEASE_NOTE_TOKEN: undefined }],
    ["DB ID가", { NOTION_RELEASE_NOTE_DATABASE_ID: undefined }],
  ])("%s 없으면 읽지 않고 503을 돌려준다", async (_, missing) => {
    const requests = captureNotionQueries();

    const response = await relayReleaseNotes(new Request(`${SITE}/relay/n`), {
      ...env,
      ...missing,
    });

    expect(response.status).toBe(503);
    expect(requests).toHaveLength(0);
  });

  it("Notion이 실패하면 그 응답 코드를 그대로 돌려주고 캐시하지 않는다", async () => {
    const requests = captureNotionQueries(
      () => new HttpResponse('{"code":"object_not_found"}', { status: 404 }),
    );

    const response = await requestNotes();
    await requestNotes();

    expect(response.status).toBe(404);
    expect(requests).toHaveLength(2);
  });

  it("GET이 아니면 읽지 않고 405를 돌려준다", async () => {
    const requests = captureNotionQueries();

    const response = await requestNotes("/relay/n", { method: "POST" });

    expect(response.status).toBe(405);
    expect(requests).toHaveLength(0);
  });
});
