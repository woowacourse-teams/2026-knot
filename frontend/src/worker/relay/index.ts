export interface Env {
  /** 정적 파일(빌드한 앱)을 돌려주는 Cloudflare Workers 정적 자산 바인딩 */
  ASSETS: { fetch: (request: Request) => Promise<Response> };
  /** 피드백 접수함에만 연결한 Notion 연동의 토큰. dev · prod Worker에 비밀값으로 넣어요(ADR 930) */
  NOTION_FEEDBACK_TOKEN?: string;
  /** 새 소식 DB에만 연결한 Notion 읽기 연동의 토큰. dev · prod Worker에 비밀값으로 넣어요(ADR 956) */
  NOTION_RELEASE_NOTE_TOKEN?: string;
  /** 새 소식 DB의 ID. Actions가 쓰는 저장소 변수 `NOTION_RELEASE_NOTE_DATABASE_ID`와 같은 값을 토큰과 함께 비밀값으로 넣어요 */
  NOTION_RELEASE_NOTE_DATABASE_ID?: string;
}

/** Worker가 받는 중계 경로. wrangler의 `run_worker_first`와 맞춰야 해요 */
export const RELAY_PATH = "/relay";
/**
 * PostHog로 넘기는 하위 경로. `src/index.tsx`의 `api_host`와 맞춰야 해요.
 * Sentry 같은 다른 중계는 `/relay` 아래 다른 하위 경로로 더해요
 */
const POSTHOG_RELAY_PATH = `${RELAY_PATH}/p`;

const POSTHOG_API_HOST = "us.i.posthog.com";
const POSTHOG_ASSET_HOST = "us-assets.i.posthog.com";
// PostHog가 나중에 불러오는 스크립트(녹화 · 설문)와 프로젝트 설정 파일 경로예요. 이 둘만 파일 주소로 가요
const POSTHOG_ASSET_PATH_PREFIXES = ["/static/", "/array/"];

const toPostHogUrl = (url: URL) => {
  const path = url.pathname.slice(POSTHOG_RELAY_PATH.length);
  const host = POSTHOG_ASSET_PATH_PREFIXES.some((prefix) =>
    path.startsWith(prefix),
  )
    ? POSTHOG_ASSET_HOST
    : POSTHOG_API_HOST;

  // 경로를 문자열로 이어 붙이면 `.evil.com/x` 같은 경로가 호스트를 바꿔서, 호스트를 먼저 정하고 경로를 넣어요
  const target = new URL(`https://${host}`);
  target.pathname = path;
  target.search = url.search;

  return target;
};

/**
 * @description 우리 주소의 `/relay/p` 아래로 온 PostHog 요청을 PostHog로 넘기고 응답을 돌려줘요
 * @param request - 브라우저가 보낸 요청. 예: `POST /relay/p/i/v0/e/?ver=…`
 * @returns PostHog 응답의 상태 · 본문 그대로, Set-Cookie만 지운 응답
 * @example
 * if (url.pathname.startsWith("/relay/p/")) return relayToPostHog(request);
 *
 * 우리 쿠키와 인증 헤더는 PostHog로 보내지 않아요. 같은 주소라 브라우저가 우리 쿠키를 함께 실어 보내기 때문이에요.
 * PostHog가 방문자 위치를 Cloudflare 데이터 센터가 아니라 실제 위치로 기록하도록 방문자 IP를 X-Forwarded-For로 넘겨요.
 */
export const relayToPostHog = async (request: Request) => {
  const headers = new Headers(request.headers);
  headers.delete("Cookie");
  headers.delete("Authorization");
  headers.delete("Host");

  const clientIp = request.headers.get("CF-Connecting-IP");
  if (clientIp) headers.set("X-Forwarded-For", clientIp);

  const hasBody = request.method !== "GET" && request.method !== "HEAD";
  const upstreamResponse = await fetch(
    new Request(toPostHogUrl(new URL(request.url)), {
      method: request.method,
      headers,
      body: hasBody ? await request.arrayBuffer() : undefined,
      redirect: "follow",
    }),
  );

  const responseHeaders = new Headers(upstreamResponse.headers);
  responseHeaders.delete("Set-Cookie");

  return new Response(upstreamResponse.body, {
    status: upstreamResponse.status,
    statusText: upstreamResponse.statusText,
    headers: responseHeaders,
  });
};

/** Sentry로 넘기는 경로. `src/shared/provider/sentry`의 `tunnel`과 맞춰야 해요 */
const SENTRY_RELAY_PATH = `${RELAY_PATH}/s`;

// 우리 Sentry 프로젝트의 수집 주소와 ID예요. 개발 · 운영이 같은 프로젝트를 써요.
// 배포 워크플로가 넣는 저장소 변수 `SENTRY_DSN`과 같은 프로젝트여야 해요. 다르면 모든 envelope가 403이에요
const SENTRY_INGEST_HOST = "o4512278291283968.ingest.us.sentry.io";
const SENTRY_PROJECT_ID = "4512278350069760";
const SENTRY_ENVELOPE_URL = `https://${SENTRY_INGEST_HOST}/api/${SENTRY_PROJECT_ID}/envelope/`;

/** envelope 머리를 찾는 앞부분의 크기. SDK가 만드는 머리는 1KB 안팎이에요 */
const MAX_ENVELOPE_HEADER_BYTES = 64 * 1024;

/** envelope 첫 줄(머리)의 DSN을 꺼내요. 첫 줄이 JSON이 아니거나 DSN이 주소가 아니면 null이에요 */
const readEnvelopeDsn = (body: ArrayBuffer) => {
  // 뒤쪽 항목(리플레이 등)은 압축된 바이트라 첫 줄만 글자로 읽어요. 큰 본문을 끝까지 뒤지지 않게 앞부분에서만 찾아요
  const bytes = new Uint8Array(
    body,
    0,
    Math.min(body.byteLength, MAX_ENVELOPE_HEADER_BYTES),
  );
  const headerEnd = bytes.indexOf("\n".charCodeAt(0));
  const headerLine = new TextDecoder().decode(
    headerEnd === -1 ? bytes : bytes.subarray(0, headerEnd),
  );

  try {
    return new URL(JSON.parse(headerLine).dsn);
  } catch {
    return null;
  }
};

/**
 * @description 우리 주소의 `/relay/s`로 온 Sentry envelope를 우리 Sentry 프로젝트로 넘기고 응답을 돌려줘요
 * @param request - Sentry SDK가 `tunnel`로 보낸 요청. 예: `POST /relay/s`
 * @returns Sentry 응답의 상태 · 본문 그대로, Set-Cookie만 지운 응답
 * @example
 * if (pathname === "/relay/s") return relayToSentry(request);
 *
 * 누구나 이 경로로 보낼 수 있어서, envelope 머리의 DSN이 우리 프로젝트일 때만 넘겨요. 목적지는 DSN에서 만들지 않고 고정해요.
 * 다른 프로젝트면 403, envelope 머리를 앞 64KB 안에서 읽을 수 없으면 400, POST가 아니면 405예요.
 * 요청 헤더는 `Content-Type`만 넘겨요. 같은 주소라 브라우저가 우리 쿠키를 함께 실어 보내고, 방문자 IP를 담은 헤더도 붙어 오기 때문이에요.
 */
export const relayToSentry = async (request: Request) => {
  if (request.method !== "POST") {
    return new Response("Method Not Allowed", { status: 405 });
  }

  const body = await request.arrayBuffer();
  const dsn = readEnvelopeDsn(body);
  if (!dsn) return new Response("Bad Request", { status: 400 });

  if (
    dsn.host !== SENTRY_INGEST_HOST ||
    dsn.pathname !== `/${SENTRY_PROJECT_ID}`
  ) {
    return new Response("Forbidden", { status: 403 });
  }

  const headers = new Headers();
  const contentType = request.headers.get("Content-Type");
  if (contentType) headers.set("Content-Type", contentType);

  const upstreamResponse = await fetch(
    new Request(SENTRY_ENVELOPE_URL, { method: "POST", headers, body }),
  );

  const responseHeaders = new Headers(upstreamResponse.headers);
  responseHeaders.delete("Set-Cookie");

  return new Response(upstreamResponse.body, {
    status: upstreamResponse.status,
    statusText: upstreamResponse.statusText,
    headers: responseHeaders,
  });
};

/** 피드백을 Notion에 넣는 경로. `src/shared/api/fetch/relay/f`의 `FEEDBACK_RELAY_PATH`와 맞춰야 해요 */
const FEEDBACK_RELAY_PATH = `${RELAY_PATH}/f`;

// Notion 「사용자 피드백 접수함」 데이터베이스예요. 2022-06-28 판은 데이터베이스 ID를 부모로 받아요
const NOTION_PAGES_URL = "https://api.notion.com/v1/pages";
const NOTION_VERSION = "2022-06-28";
const NOTION_FEEDBACK_DATABASE_ID = "98224ba95fff4abe81a4a9ee67591523";

/** 앱의 피드백 유형을 접수함 「종류」 선택지로 바꿔요. 키는 `src/shared/types/feedback`의 `FeedbackType`과 맞춰야 해요 */
const FEEDBACK_KIND = {
  bug: "버그",
  inconvenience: "불편",
  idea: "요청",
  praise: "칭찬",
} as const;

/** 적은 내용의 최대 길이. Notion 글 칸 한 덩어리의 한도이고, 패널 입력창도 같은 길이로 막아요 */
const MAX_FEEDBACK_CONTENT_LENGTH = 2000;
const MAX_FEEDBACK_URL_LENGTH = 2000;
/** 요청 본문의 최대 길이. 내용과 주소 둘을 다 채워도 넘지 않아요 */
const MAX_FEEDBACK_BODY_LENGTH = 8 * 1024;
const MAX_FEEDBACK_TITLE_LENGTH = 60;
/** 원문 링크로 받는 PostHog 리플레이 주소. `src/index.tsx`의 `ui_host`와 맞춰요 */
const POSTHOG_REPLAY_URL_PREFIX = "https://us.posthog.com/";
/** 한국 시간 오프셋. 접수일을 한국 날짜로 남겨요 */
const KST_OFFSET_MS = 9 * 60 * 60 * 1000;

interface Feedback {
  type: keyof typeof FEEDBACK_KIND;
  content: string;
  pageUrl: string;
  replayUrl?: string;
}

const isShortString = (value: unknown, maxLength: number): value is string =>
  typeof value === "string" && value.length <= maxLength;

const isFeedbackType = (value: unknown): value is Feedback["type"] =>
  typeof value === "string" && Object.keys(FEEDBACK_KIND).includes(value);

const hasOrigin = (value: string, origin: string) => {
  try {
    return new URL(value).origin === origin;
  } catch {
    return false;
  }
};

/** 본문을 피드백으로 읽어요. 모양이 다르거나, 너무 길거나, 화면 주소가 우리 주소가 아니거나, 원문 링크가 PostHog가 아니면 null이에요 */
const readFeedback = (text: string, origin: string): Feedback | null => {
  let body: unknown;
  try {
    body = JSON.parse(text);
  } catch {
    return null;
  }
  if (typeof body !== "object" || body === null) return null;

  const { type, content, pageUrl, replayUrl } = body as Record<string, unknown>;
  if (
    !isFeedbackType(type) ||
    !isShortString(content, MAX_FEEDBACK_CONTENT_LENGTH) ||
    !isShortString(pageUrl, MAX_FEEDBACK_URL_LENGTH) ||
    !hasOrigin(pageUrl, origin)
  ) {
    return null;
  }
  if (
    replayUrl !== undefined &&
    !(
      isShortString(replayUrl, MAX_FEEDBACK_URL_LENGTH) &&
      replayUrl.startsWith(POSTHOG_REPLAY_URL_PREFIX)
    )
  ) {
    return null;
  }

  return { type, content, pageUrl, replayUrl };
};

const toRichText = (content: string) =>
  content ? [{ text: { content } }] : [];

/** 접수함에 한 줄로 넣을 Notion 페이지. 제목은 적은 내용의 첫 줄 앞부분이고, 닉네임은 적지 않아요 */
const toNotionPage = ({ type, content, pageUrl, replayUrl }: Feedback) => {
  const title =
    content.trim().split("\n")[0].slice(0, MAX_FEEDBACK_TITLE_LENGTH) ||
    "(내용 없음)";
  const receivedOn = new Date(Date.now() + KST_OFFSET_MS)
    .toISOString()
    .slice(0, 10);

  return {
    parent: { database_id: NOTION_FEEDBACK_DATABASE_ID },
    properties: {
      피드백: { title: toRichText(title) },
      종류: { select: { name: FEEDBACK_KIND[type] } },
      내용: { rich_text: toRichText(content) },
      "사용 상황": { rich_text: toRichText(pageUrl) },
      "원문 링크": { url: replayUrl ?? null },
      "접수 경로": { select: { name: "앱 내" } },
      "출처 유형": { select: { name: "사용자 제보" } },
      "접수 상태": { select: { name: "신규" } },
      접수일: { date: { start: receivedOn } },
    },
  };
};

/**
 * @description 우리 화면에서 `/relay/f`로 보낸 피드백을 Notion 「사용자 피드백 접수함」에 한 줄로 넣어요(ADR 930)
 * @param request - 피드백 패널이 보낸 요청. 예: `POST /relay/f`, 본문 `{ type, content, pageUrl, replayUrl? }`
 * @param env - Worker 바인딩. Notion 토큰은 `env.NOTION_FEEDBACK_TOKEN`이에요
 * @returns 넣었으면 201, Notion이 거절하면 Notion의 응답 코드 그대로
 * @example
 * if (pathname === "/relay/f") return relayToNotion(request, env);
 *
 * 누구나 이 경로로 보낼 수 있어서 우리 화면(Origin)에서 온 요청인지, 유형이 넷 중 하나인지, 길이만 봐요. 로그인 확인과 횟수 제한은 하지 않아요.
 * 다른 주소에서 오면 403, 본문이 맞지 않으면 400, 본문이 너무 길면 413, POST가 아니면 405, 토큰이 없으면 503이에요.
 * Notion이 거절한 코드(401 토큰 오류, 404 연결 안 됨, 400 칸 이름 불일치 등)는 그대로 돌려줘, 화면이 그 코드를 Sentry에 남겨요.
 */
export const relayToNotion = async (request: Request, env: Env) => {
  if (request.method !== "POST") {
    return new Response("Method Not Allowed", { status: 405 });
  }

  const { origin } = new URL(request.url);
  if (request.headers.get("Origin") !== origin) {
    return new Response("Forbidden", { status: 403 });
  }

  const text = await request.text();
  if (text.length > MAX_FEEDBACK_BODY_LENGTH) {
    return new Response("Payload Too Large", { status: 413 });
  }

  const feedback = readFeedback(text, origin);
  if (!feedback) return new Response("Bad Request", { status: 400 });

  if (!env.NOTION_FEEDBACK_TOKEN) {
    return new Response("Service Unavailable", { status: 503 });
  }

  const notionResponse = await fetch(NOTION_PAGES_URL, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${env.NOTION_FEEDBACK_TOKEN}`,
      "Notion-Version": NOTION_VERSION,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(toNotionPage(feedback)),
  });

  return new Response(null, {
    status: notionResponse.ok ? 201 : notionResponse.status,
  });
};

/** 새 소식 노트를 읽는 경로. `src/shared/api/fetch/relay/n`의 `RELEASE_NOTES_RELAY_PATH`와 맞춰야 해요 */
const RELEASE_NOTES_RELAY_PATH = `${RELAY_PATH}/n`;

/** 읽은 노트를 Worker 캐시에 두는 시간(초). Notion에서 고친 노트는 길어야 이만큼 뒤에 보여요 */
const RELEASE_NOTES_CACHE_SECONDS = 300;

interface NotionProperty {
  title?: { plain_text: string }[];
  rich_text?: { plain_text: string }[];
  date?: { start: string } | null;
  checkbox?: boolean;
  number?: number | null;
}

/** 새 소식 DB 한 줄. 칸 이름은 `scripts/release-note.mjs`가 쓰는 이름과 같아요 */
interface ReleaseNoteRow {
  noteId: string;
  releasedAt: string;
  order: number;
  item: {
    title: string;
    description: string;
    link?: string;
    isFromFeedback: boolean;
    isHighlight: boolean;
  };
}

const readText = (property?: NotionProperty) =>
  (property?.title ?? property?.rich_text ?? [])
    .map(({ plain_text }) => plain_text)
    .join("")
    .trim();

/**
 * 새 소식 DB 한 줄을 노트 항목으로 읽어요. 노트 id, 배포 날짜, 제목, 설명이 없으면 null이에요.
 * 바로 가보기는 화면 이름을 확인하지 않고 그대로 넘겨요. 앱에 없는 화면이면 앱이 버튼만 숨겨요
 */
const readReleaseNoteRow = (
  properties: Record<string, NotionProperty | undefined>,
): ReleaseNoteRow | null => {
  const noteId = readText(properties["노트 id"]);
  const releasedAt = properties["배포 날짜"]?.date?.start.slice(0, 10) ?? "";
  const title = readText(properties.제목);
  const description = readText(properties.설명);
  const link = readText(properties["바로 가보기"]);

  if (
    !noteId ||
    !/^\d{4}-\d{2}-\d{2}$/.test(releasedAt) ||
    !title ||
    !description
  ) {
    return null;
  }

  return {
    noteId,
    releasedAt,
    order: properties.순서?.number ?? 0,
    item: {
      title,
      description,
      link: link || undefined,
      isFromFeedback: properties["피드백 덕분"]?.checkbox === true,
      isHighlight: properties["큰 변화"]?.checkbox === true,
    },
  };
};

/** DB 줄을 노트로 묶어요. 최신 배포 날짜부터, 같은 날이면 나중에 머지한 PR(`pr-1002`가 `pr-998`보다 앞)부터, 항목은 순서대로예요 */
const toReleaseNotes = (
  pages: { properties?: Record<string, NotionProperty> }[],
) => {
  const rows = pages
    .map(({ properties = {} }) => readReleaseNoteRow(properties))
    .filter((row) => row !== null)
    .sort(
      (a, b) =>
        b.releasedAt.localeCompare(a.releasedAt) ||
        b.noteId.localeCompare(a.noteId, undefined, { numeric: true }) ||
        a.order - b.order,
    );

  return rows.reduce<
    { id: string; releasedAt: string; items: ReleaseNoteRow["item"][] }[]
  >((notes, { noteId, releasedAt, item }) => {
    const lastNote = notes[notes.length - 1];

    if (lastNote?.id === noteId) {
      lastNote.items.push(item);
    } else {
      notes.push({ id: noteId, releasedAt, items: [item] });
    }

    return notes;
  }, []);
};

/**
 * @description 우리 화면이 `/relay/n`으로 부르면 Notion 새 소식 DB를 읽어 새 소식 노트로 바꿔 돌려줘요(ADR 956)
 * @param request - 새 소식 쿼리가 보낸 요청. 예: `GET /relay/n`
 * @param env - Worker 바인딩. Notion 토큰과 DB ID는 `env.NOTION_RELEASE_NOTE_TOKEN`, `env.NOTION_RELEASE_NOTE_DATABASE_ID`예요
 * @returns 노트 배열(`ReleaseNote[]`, 최신 노트가 맨 앞). Notion이 실패하면 Notion의 응답 코드 그대로
 * @example
 * if (pathname === "/relay/n") return relayReleaseNotes(request, env);
 *
 * 노트는 GitHub Actions의 Claude가 main 머지마다 DB에 쓰고, 이 경로는 읽기만 해요. 형식이 맞지 않는 줄은 빼고 돌려줘요.
 * 읽은 결과는 5분 동안 Worker 캐시에서 돌려줘 Notion을 사용자마다 부르지 않아요. 쿼리 문자열은 캐시 키에서 빼요.
 * GET이 아니면 405, 토큰이나 DB ID가 없으면 503이에요. 화면은 실패하면 빈 패널을 보여 주고 오류는 Sentry에 남겨요.
 */
export const relayReleaseNotes = async (request: Request, env: Env) => {
  if (request.method !== "GET") {
    return new Response("Method Not Allowed", { status: 405 });
  }

  if (!env.NOTION_RELEASE_NOTE_TOKEN || !env.NOTION_RELEASE_NOTE_DATABASE_ID) {
    return new Response("Service Unavailable", { status: 503 });
  }

  const cache = await caches.open("release-notes");
  const cacheKey = new Request(new URL(RELEASE_NOTES_RELAY_PATH, request.url));
  const cachedResponse = await cache.match(cacheKey);
  if (cachedResponse) return cachedResponse;

  // shortcut: 최근 100줄만 읽어요. 오래된 노트까지 보여 줘야 하면 next_cursor로 이어 읽어요
  const notionResponse = await fetch(
    `https://api.notion.com/v1/databases/${env.NOTION_RELEASE_NOTE_DATABASE_ID}/query`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${env.NOTION_RELEASE_NOTE_TOKEN}`,
        "Notion-Version": NOTION_VERSION,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        page_size: 100,
        sorts: [{ property: "배포 날짜", direction: "descending" }],
      }),
    },
  );
  if (!notionResponse.ok) {
    return new Response(null, { status: notionResponse.status });
  }

  const { results = [] } = (await notionResponse.json()) as {
    results?: { properties?: Record<string, NotionProperty> }[];
  };
  const response = new Response(JSON.stringify(toReleaseNotes(results)), {
    headers: {
      "Content-Type": "application/json",
      "Cache-Control": `public, max-age=${RELEASE_NOTES_CACHE_SECONDS}`,
    },
  });
  await cache.put(cacheKey, response.clone());

  return response;
};

/**
 * @description Worker가 받은 요청을 경로에 따라 PostHog · Sentry · Notion 중계 · 404 · 정적 파일로 나눠요
 * @param request - Worker가 받은 요청
 * @param env - Worker 바인딩. 정적 파일은 `env.ASSETS`로 돌려줘요
 * @returns 경로에 맞는 응답
 * @example
 * export default { fetch: handleRequest };
 *
 * wrangler 설정이 `/relay` 아래 요청만 Worker를 먼저 거치게 하고, 나머지 화면 · 파일 주소는 정적 파일로 바로 나가요.
 * `/relay` 아래는 목적지마다 하위 경로로 나눠요. PostHog(`/relay/p` 아래), Sentry(`/relay/s` 하나), Notion 피드백(`/relay/f` 하나), Notion 새 소식(`/relay/n` 하나)이 있고, 목적지가 없는 하위 경로는 404예요.
 */
export const handleRequest = (request: Request, env: Env) => {
  const { pathname } = new URL(request.url);

  if (pathname.startsWith(`${POSTHOG_RELAY_PATH}/`)) {
    return relayToPostHog(request);
  }

  if (pathname === SENTRY_RELAY_PATH) return relayToSentry(request);

  if (pathname === FEEDBACK_RELAY_PATH) return relayToNotion(request, env);

  if (pathname === RELEASE_NOTES_RELAY_PATH) {
    return relayReleaseNotes(request, env);
  }

  if (pathname === RELAY_PATH || pathname.startsWith(`${RELAY_PATH}/`)) {
    return new Response("Not Found", { status: 404 });
  }

  return env.ASSETS.fetch(request);
};
