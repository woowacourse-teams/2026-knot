import { AUTH_CSRF_API_PATH } from "@api/fetch/api/v1/auth/csrf";
import { AUTH_REFRESH_API_PATH } from "@api/fetch/api/v1/auth/refresh";
import { mockServer } from "@api/mock/server";
import { delay, http, HttpResponse } from "msw";
import { afterEach, describe, expect, it, vi } from "vitest";

import { getCsrfToken, httpClient } from ".";
import { HTTP_ERROR_TYPE, isHttpError } from "./error";

// 인스턴스 동작만 보는 임시 경로예요
const TEST_PATH = "/__http-client-test";

const CSRF_HEADER_NAME = "X-XSRF-TOKEN";

/** 받은 CSRF 헤더 값을 응답으로 돌려주는 핸들러 */
const echoCsrfHeader = (method: "get" | "post") =>
  http[method](`*${TEST_PATH}`, ({ request }) =>
    HttpResponse.json({ csrf: request.headers.get(CSRF_HEADER_NAME) }),
  );

/** 호출마다 다른 토큰을 내려주는 CSRF 발급 핸들러 */
const rotatingCsrfHandler = () => {
  let issued = 0;

  return http.get(`*${AUTH_CSRF_API_PATH}`, () => {
    issued += 1;
    return HttpResponse.json({ token: `token-${issued}` });
  });
};

// 토큰 캐시가 모듈에 남으므로, 매 테스트가 시작할 때 들고 있는 토큰을 기준으로 기대값을 잡아요
describe("httpClient의 CSRF 처리", () => {
  it("조회 요청에는 CSRF 토큰을 붙이지 않는다", async () => {
    mockServer.use(echoCsrfHeader("get"));

    const { data } = await httpClient.get(TEST_PATH);

    expect(data.csrf).toBeNull();
  });

  it("상태를 바꾸는 요청에는 CSRF 토큰을 헤더로 붙인다", async () => {
    mockServer.use(echoCsrfHeader("post"));

    const { data } = await httpClient.post(TEST_PATH);

    expect(data.csrf).toBe(await getCsrfToken());
  });

  it("403이 오면 토큰을 새로 받아 한 번 다시 보낸다", async () => {
    let attempts = 0;
    mockServer.use(
      rotatingCsrfHandler(),
      http.post(`*${TEST_PATH}`, ({ request }) => {
        attempts += 1;
        if (attempts === 1) return new HttpResponse(null, { status: 403 });

        return HttpResponse.json({
          csrf: request.headers.get(CSRF_HEADER_NAME),
        });
      }),
    );

    const { data } = await httpClient.post(TEST_PATH);

    expect(attempts).toBe(2);
    expect(data.csrf).toBe("token-1");
  });

  it("다시 보낸 요청도 403이면 토큰을 한 번 더 받아 보내고, 그래도 403이면 더 보내지 않고 실패한다", async () => {
    let attempts = 0;
    let issued = 0;
    mockServer.use(
      http.get(`*${AUTH_CSRF_API_PATH}`, () => {
        issued += 1;
        return HttpResponse.json({ token: `token-${issued}` });
      }),
      http.post(`*${TEST_PATH}`, () => {
        attempts += 1;
        return new HttpResponse(null, { status: 403 });
      }),
    );

    const error = await httpClient.post(TEST_PATH).catch((e: unknown) => e);

    expect(isHttpError(error, HTTP_ERROR_TYPE.forbidden)).toBe(true);
    expect(attempts).toBe(3);
    expect(issued).toBe(2);
  });
});

/** 토큰 캐시가 모듈에 남으므로, 캐시가 빈 상태에서 시작해야 하는 테스트는 모듈을 새로 불러와요 */
const loadFreshHttpClient = async () => {
  vi.resetModules();

  return import(".");
};

/**
 * 서버가 CSRF 쿠키를 지운 상태를 흉내 내는 핸들러예요.
 *
 * 실제 서버는 쿠키가 없을 때 받은 CSRF 조회와 거절한 변경 요청마다 새 토큰을 만들어 응답 쿠키로 내려줘요.
 * 쿠키는 응답이 도착할 때 브라우저에 저장되므로, 쿠키를 바꾸는 일은 응답을 늦춘 뒤에 해요.
 * 헤더의 토큰이 지금 쿠키와 같은 요청만 통과시켜요.
 */
interface ClearedCookieCsrfServerParams {
  /** 경로마다 거절 응답이 도착하기까지 걸리는 시간(ms) */
  rejectDelayMs: Record<string, number>;
  /** CSRF 조회 응답이 도착하기까지 걸리는 시간(ms) */
  csrfDelayMs: number;
}

const clearedCookieCsrfServer = ({
  rejectDelayMs,
  csrfDelayMs,
}: ClearedCookieCsrfServerParams) => {
  const state = {
    cookie: "token-before-clear" as string | undefined,
    issued: 0,
    csrfRequests: 0,
  };
  const newToken = () => {
    state.issued += 1;
    return `token-${state.issued}`;
  };

  const csrfHandler = http.get(`*${AUTH_CSRF_API_PATH}`, async () => {
    state.csrfRequests += 1;
    const issuedToken = state.cookie === undefined ? newToken() : undefined;
    const token = issuedToken ?? state.cookie;
    await delay(csrfDelayMs);
    if (issuedToken !== undefined) state.cookie = issuedToken;

    return HttpResponse.json({ token });
  });

  const mutationHandlers = Object.entries(rejectDelayMs).map(([path, ms]) =>
    http.post(`*${path}`, async ({ request }) => {
      if (request.headers.get(CSRF_HEADER_NAME) === state.cookie) {
        return HttpResponse.json({ ok: true });
      }

      const issuedToken = state.cookie === undefined ? newToken() : undefined;
      await delay(ms);
      if (issuedToken !== undefined) state.cookie = issuedToken;

      return new HttpResponse(null, { status: 403 });
    }),
  );

  /** 새 세션의 첫 인증 요청에서 서버가 CSRF 쿠키를 지운 순간을 만들어요 */
  const clearCookie = () => {
    state.cookie = undefined;
  };

  return { handlers: [csrfHandler, ...mutationHandlers], state, clearCookie };
};

describe("httpClient의 CSRF 토큰 조회 묶기", () => {
  it("토큰이 없을 때 변경 요청 두 개가 동시에 나가도 CSRF 토큰은 한 번만 받는다", async () => {
    const { httpClient: freshClient } = await loadFreshHttpClient();
    let csrfRequests = 0;
    mockServer.use(
      http.get(`*${AUTH_CSRF_API_PATH}`, () => {
        csrfRequests += 1;
        return HttpResponse.json({ token: `token-${csrfRequests}` });
      }),
      echoCsrfHeader("post"),
    );

    const [first, second] = await Promise.all([
      freshClient.post(TEST_PATH),
      freshClient.post(TEST_PATH),
    ]);

    expect(csrfRequests).toBe(1);
    expect(first.data.csrf).toBe("token-1");
    expect(second.data.csrf).toBe("token-1");
  });

  it("서버가 CSRF 쿠키를 지운 뒤 동시에 보낸 변경 요청이 403을 받아도 둘 다 성공한다", async () => {
    const { httpClient: freshClient, getCsrfToken: getFreshCsrfToken } =
      await loadFreshHttpClient();
    // 첫 거절이 쿠키를 심은 뒤 둘째 거절이 쿠키를 다시 덮어쓰고, 그 사이에 토큰 조회가 첫 쿠키를 받는 순서예요(2026-10-18 개발 서버 관찰)
    const server = clearedCookieCsrfServer({
      rejectDelayMs: { "/__csrf-first": 0, "/__csrf-second": 10 },
      csrfDelayMs: 20,
    });
    mockServer.use(...server.handlers);
    await getFreshCsrfToken();
    server.clearCookie();

    const results = await Promise.allSettled([
      freshClient.post("/__csrf-first"),
      freshClient.post("/__csrf-second"),
    ]);

    expect(results.map(({ status }) => status)).toEqual([
      "fulfilled",
      "fulfilled",
    ]);
  });

  it("CSRF 토큰 조회가 실패하면 기다리던 요청이 모두 실패하고, 다음 요청은 조회를 새로 한다", async () => {
    const { httpClient: freshClient } = await loadFreshHttpClient();
    let csrfRequests = 0;
    mockServer.use(
      http.get(`*${AUTH_CSRF_API_PATH}`, () => {
        csrfRequests += 1;
        if (csrfRequests === 1) return new HttpResponse(null, { status: 500 });

        return HttpResponse.json({ token: "token-after-failure" });
      }),
      echoCsrfHeader("post"),
    );

    const failed = await Promise.allSettled([
      freshClient.post(TEST_PATH),
      freshClient.post(TEST_PATH),
    ]);
    const { data } = await freshClient.post(TEST_PATH);

    expect(failed.map(({ status }) => status)).toEqual([
      "rejected",
      "rejected",
    ]);
    expect(csrfRequests).toBe(2);
    expect(data.csrf).toBe("token-after-failure");
  });
});

interface CountingRefreshHandlerParams {
  /** 재발급 응답 상태 코드 */
  status?: number;
  /** 응답을 늦추는 시간(ms) */
  delayMs?: number;
}

/** 받은 재발급 요청 수와 그때의 CSRF 헤더를 기록하는 재발급 핸들러. `delayMs`만큼 늦게 답해요 */
const countingRefreshHandler = ({
  status = 204,
  delayMs = 0,
}: CountingRefreshHandlerParams = {}) => {
  const calls: (string | null)[] = [];
  const handler = http.post(
    `*${AUTH_REFRESH_API_PATH}`,
    async ({ request }) => {
      calls.push(request.headers.get(CSRF_HEADER_NAME));
      await delay(delayMs);
      return new HttpResponse(null, { status });
    },
  );

  return { handler, calls };
};

/**
 * 처음 `failures`번은 `status`로 실패하고 그 뒤로는 성공하는 조회 핸들러. 받은 요청 수를 셉니다.
 * 실패 응답은 `failureDelayMs`만큼 늦게 줘요
 */
interface FailingThenOkHandlerParams {
  /** 실패 응답 상태 코드 */
  status: number;
  /** 처음 몇 번 실패할지 */
  failures: number;
  /** 핸들러를 걸 경로 */
  path?: string;
  /** 실패 응답을 늦추는 시간(ms) */
  failureDelayMs?: number;
}

const failingThenOkHandler = ({
  status,
  failures,
  path = TEST_PATH,
  failureDelayMs = 0,
}: FailingThenOkHandlerParams) => {
  const attempts = { count: 0 };
  const handler = http.get(`*${path}`, async () => {
    attempts.count += 1;
    if (attempts.count <= failures) {
      await delay(failureDelayMs);
      return new HttpResponse(null, { status });
    }

    return HttpResponse.json({ ok: true });
  });

  return { handler, attempts };
};

describe("httpClient의 토큰 재발급", () => {
  it("401이 오면 토큰을 재발급하고 원래 요청을 한 번 다시 보낸다", async () => {
    const refresh = countingRefreshHandler();
    const request = failingThenOkHandler({ status: 401, failures: 1 });
    mockServer.use(refresh.handler, request.handler);

    const { data } = await httpClient.get(TEST_PATH);

    expect(data).toEqual({ ok: true });
    expect(request.attempts.count).toBe(2);
    expect(refresh.calls).toHaveLength(1);
  });

  it("재발급 요청에는 CSRF 토큰을 붙인다", async () => {
    const refresh = countingRefreshHandler();
    mockServer.use(
      refresh.handler,
      failingThenOkHandler({ status: 401, failures: 1 }).handler,
    );

    await httpClient.get(TEST_PATH);

    expect(refresh.calls[0]).toBe(await getCsrfToken());
  });

  // 재발급이 401이 아닌 이유로 실패해도 원래 요청의 401을 받아야, 화면이 로그인 화면으로 보낼 수 있어요.
  // 403은 csrfInterceptor가 재발급을 한 번 더 보내 횟수가 달라지므로 500으로 확인해요
  it.each([401, 500])(
    "재발급 응답이 %i이면 원래 요청을 다시 보내지 않고 원래 요청의 401로 실패한다",
    async (refreshStatus) => {
      const refresh = countingRefreshHandler({ status: refreshStatus });
      const request = failingThenOkHandler({ status: 401, failures: 1 });
      mockServer.use(refresh.handler, request.handler);

      const error = await httpClient.get(TEST_PATH).catch((e: unknown) => e);

      expect(isHttpError(error, HTTP_ERROR_TYPE.unauthorized)).toBe(true);
      expect(request.attempts.count).toBe(1);
      expect(refresh.calls).toHaveLength(1);
    },
  );

  it("다시 보낸 요청도 401이면 더 재발급하지 않고 401로 실패한다", async () => {
    const refresh = countingRefreshHandler();
    const request = failingThenOkHandler({ status: 401, failures: 2 });
    mockServer.use(refresh.handler, request.handler);

    const error = await httpClient.get(TEST_PATH).catch((e: unknown) => e);

    expect(isHttpError(error, HTTP_ERROR_TYPE.unauthorized)).toBe(true);
    expect(request.attempts.count).toBe(2);
    expect(refresh.calls).toHaveLength(1);
  });

  it("401이 아닌 실패에는 재발급하지 않는다", async () => {
    const refresh = countingRefreshHandler();
    const request = failingThenOkHandler({ status: 500, failures: 1 });
    mockServer.use(refresh.handler, request.handler);

    const error = await httpClient.get(TEST_PATH).catch((e: unknown) => e);

    expect(isHttpError(error, HTTP_ERROR_TYPE.serverError)).toBe(true);
    expect(refresh.calls).toHaveLength(0);
  });

  it("재발급 요청 자체가 401이면 재발급을 다시 부르지 않는다", async () => {
    const refresh = countingRefreshHandler({ status: 401 });
    mockServer.use(refresh.handler);

    const error = await httpClient
      .post(AUTH_REFRESH_API_PATH)
      .catch((e: unknown) => e);

    expect(isHttpError(error, HTTP_ERROR_TYPE.unauthorized)).toBe(true);
    expect(refresh.calls).toHaveLength(1);
  });

  it("CSRF 토큰 조회가 401이면 재발급을 부르지 않는다", async () => {
    const refresh = countingRefreshHandler();
    mockServer.use(
      refresh.handler,
      http.get(
        `*${AUTH_CSRF_API_PATH}`,
        () => new HttpResponse(null, { status: 401 }),
      ),
    );

    const error = await httpClient
      .get(AUTH_CSRF_API_PATH)
      .catch((e: unknown) => e);

    expect(isHttpError(error, HTTP_ERROR_TYPE.unauthorized)).toBe(true);
    expect(refresh.calls).toHaveLength(0);
  });

  it("동시에 401을 받은 요청들은 재발급 한 번을 함께 기다렸다가 각자 다시 보낸다", async () => {
    const refresh = countingRefreshHandler({ delayMs: 50 });
    const requests = ["/__refresh-a", "/__refresh-b", "/__refresh-c"].map(
      (path) => ({
        path,
        ...failingThenOkHandler({ status: 401, failures: 1, path }),
      }),
    );
    mockServer.use(refresh.handler, ...requests.map(({ handler }) => handler));

    const responses = await Promise.all(
      requests.map(({ path }) => httpClient.get(path)),
    );

    expect(responses.map(({ data }) => data)).toEqual([
      { ok: true },
      { ok: true },
      { ok: true },
    ]);
    expect(refresh.calls).toHaveLength(1);
    requests.forEach(({ attempts }) => {
      expect(attempts.count).toBe(2);
    });
  });

  it("다른 요청이 재발급을 끝낸 뒤에 도착한 401은 재발급 없이 다시 보낸다", async () => {
    const refresh = countingRefreshHandler();
    const early = failingThenOkHandler({
      status: 401,
      failures: 1,
      path: "/__refresh-early",
    });
    // 만료된 토큰으로 함께 보냈지만 401이 늦게 도착하는 요청이에요
    const late = failingThenOkHandler({
      status: 401,
      failures: 1,
      path: "/__refresh-late",
      failureDelayMs: 100,
    });
    mockServer.use(refresh.handler, early.handler, late.handler);

    await Promise.all([
      httpClient.get("/__refresh-early"),
      httpClient.get("/__refresh-late"),
    ]);

    expect(refresh.calls).toHaveLength(1);
    expect(late.attempts.count).toBe(2);
  });
});

/** 잠금을 잡은 동안만 `isHeld`가 참인 가짜 Web Locks. 받은 잠금 이름을 기록해요 */
const stubWebLocks = () => {
  const lock = { isHeld: false, names: [] as string[] };
  const request = vi.fn(
    async (name: string, callback: () => Promise<unknown>) => {
      lock.names.push(name);
      lock.isHeld = true;
      try {
        return await callback();
      } finally {
        lock.isHeld = false;
      }
    },
  );
  Object.defineProperty(navigator, "locks", {
    configurable: true,
    value: { request },
  });

  return lock;
};

describe("httpClient의 탭 사이 재발급 잠금", () => {
  afterEach(() => {
    Reflect.deleteProperty(navigator, "locks");
  });

  it("Web Locks가 있으면 탭 사이 잠금을 잡은 채로 재발급을 보낸다", async () => {
    const lock = stubWebLocks();
    const isHeldAtRefresh: boolean[] = [];
    mockServer.use(
      http.post(`*${AUTH_REFRESH_API_PATH}`, () => {
        isHeldAtRefresh.push(lock.isHeld);
        return new HttpResponse(null, { status: 204 });
      }),
      failingThenOkHandler({ status: 401, failures: 1 }).handler,
    );

    const { data } = await httpClient.get(TEST_PATH);

    expect(data).toEqual({ ok: true });
    expect(lock.names).toEqual(["knot-auth-refresh"]);
    expect(isHeldAtRefresh).toEqual([true]);
  });

  it("다시 보내는 원래 요청은 잠금을 놓은 뒤에 보낸다", async () => {
    const lock = stubWebLocks();
    const isHeldAtRetry: boolean[] = [];
    let attempts = 0;
    mockServer.use(
      http.get(`*${TEST_PATH}`, () => {
        attempts += 1;
        if (attempts === 1) return new HttpResponse(null, { status: 401 });

        isHeldAtRetry.push(lock.isHeld);
        return HttpResponse.json({ ok: true });
      }),
    );

    await httpClient.get(TEST_PATH);

    expect(isHeldAtRetry).toEqual([false]);
  });
});
