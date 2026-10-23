import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";

import { mockServer } from "@api/mock/server";

import worker from ".";

const SITE = "https://dev.knoted.kr";

describe("worker", () => {
  const createEnv = () => ({
    ASSETS: { fetch: vi.fn(async () => new Response("asset")) },
  });

  it("PostHog 중계 경로는 정적 파일로 넘기지 않고 PostHog로 넘긴다", async () => {
    const env = createEnv();
    mockServer.use(
      http.post("https://us.i.posthog.com/i/v0/e/", () =>
        HttpResponse.text("posthog"),
      ),
    );

    const response = await worker.fetch(
      new Request(`${SITE}/relay/p/i/v0/e/`, { method: "POST", body: "x" }),
      env,
    );

    expect(await response.text()).toBe("posthog");
    expect(env.ASSETS.fetch).not.toHaveBeenCalled();
  });

  it("Sentry 중계 경로는 정적 파일로 넘기지 않고 Sentry로 넘긴다", async () => {
    const env = createEnv();
    mockServer.use(
      http.post(
        "https://o4512278291283968.ingest.us.sentry.io/api/4512278350069760/envelope/",
        () => HttpResponse.text("sentry"),
      ),
    );
    const envelope = `${JSON.stringify({
      dsn: "https://publickey@o4512278291283968.ingest.us.sentry.io/4512278350069760",
    })}\n{}`;

    const response = await worker.fetch(
      new Request(`${SITE}/relay/s`, { method: "POST", body: envelope }),
      env,
    );

    expect(await response.text()).toBe("sentry");
    expect(env.ASSETS.fetch).not.toHaveBeenCalled();
  });

  it("피드백 경로는 정적 파일로 넘기지 않고 Notion 접수함에 넣는다", async () => {
    const env = { ...createEnv(), NOTION_FEEDBACK_TOKEN: "secret_test" };
    mockServer.use(
      http.post("https://api.notion.com/v1/pages", () =>
        HttpResponse.json({ id: "page" }),
      ),
    );

    const response = await worker.fetch(
      new Request(`${SITE}/relay/f`, {
        method: "POST",
        headers: { Origin: SITE },
        body: JSON.stringify({
          type: "praise",
          content: "",
          pageUrl: `${SITE}/workspace/1`,
        }),
      }),
      env,
    );

    expect(response.status).toBe(201);
    expect(env.ASSETS.fetch).not.toHaveBeenCalled();
  });

  it("중계 경로가 아닌 요청은 정적 파일로 돌려준다", async () => {
    const env = createEnv();
    const request = new Request(`${SITE}/workspace/1`);

    const response = await worker.fetch(request, env);

    expect(env.ASSETS.fetch).toHaveBeenCalledWith(request);
    expect(await response.text()).toBe("asset");
  });

  it.each(["/relay", "/relay/p", "/relay/s/x", "/relay/x/y"])(
    "목적지가 정해지지 않은 중계 경로(%s)는 404를 돌려주고 정적 파일로도 넘기지 않는다",
    async (path) => {
      const env = createEnv();

      const response = await worker.fetch(new Request(`${SITE}${path}`), env);

      expect(response.status).toBe(404);
      expect(env.ASSETS.fetch).not.toHaveBeenCalled();
    },
  );
});
