import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";

import { mockServer } from "@api/mock/server";

import { relayToNotion, relayToPostHog, relayToSentry } from ".";

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
