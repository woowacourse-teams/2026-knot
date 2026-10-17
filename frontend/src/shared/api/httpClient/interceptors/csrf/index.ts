import type { AxiosInstance } from "axios";
import axios from "axios";

import type { HttpInterceptor } from "../../types/interceptor";

/** CSRF 토큰을 발급받는 경로. 응답 본문으로 토큰을 주고 `XSRF-TOKEN` 쿠키도 함께 내려줘요. */
export const CSRF_API_PATH = "/api/v1/auth/csrf";

const CSRF_HEADER_NAME = "X-XSRF-TOKEN";

/** 서버 상태를 바꾸는 메서드. 이 요청들만 CSRF 토큰을 요구해요. */
const MUTATING_METHODS = ["post", "put", "patch", "delete"];

/** 403을 받았을 때 토큰을 다시 받아 다시 보내는 최대 횟수. SSE 요청도 같은 횟수를 써요. */
export const MAX_CSRF_RETRIES = 2;

interface CsrfTokenResponse {
  token: string;
}

/**
 * 서버가 준 CSRF 토큰. 요청마다 새로 받지 않도록 들고 있어요.
 *
 * 서버가 토큰을 새로 만들면 이 값이 낡을 수 있습니다. 로그인할 때, 그리고 서버 세션이 새로 생긴 뒤
 * 첫 인증 요청에서 서버가 CSRF 쿠키를 지울 때예요. 그때는 403이 오는데, `csrfInterceptor`가 다시 받아 다시 보냅니다.
 */
let csrfToken: string | undefined;

/** 진행 중인 CSRF 토큰 조회. 동시에 토큰이 필요한 요청들이 이 조회 하나를 함께 기다려요. */
let loadingCsrfToken: Promise<string> | undefined;

/**
 * CSRF 토큰을 서버에서 새로 받아 들고 있어요. 이미 받는 중이면 새로 보내지 않고 그 결과를 함께 씁니다.
 *
 * 쿠키가 없을 때 서버는 조회할 때마다 새 토큰을 만들어 쿠키를 덮어써요. 조회가 둘 겹치면 쿠키에는 한쪽 토큰만 남아
 * 다른 쪽 토큰을 쓴 요청이 403을 받으므로, 겹치는 조회를 하나로 묶어요. 조회가 끝나면 묶음을 풀어 다음 조회는 새로 보냅니다.
 */
const loadCsrfToken = (client: AxiosInstance) => {
  loadingCsrfToken ??= client
    .get<CsrfTokenResponse>(CSRF_API_PATH)
    .then(({ data }) => {
      csrfToken = data.token;

      return data.token;
    })
    .finally(() => {
      loadingCsrfToken = undefined;
    });

  return loadingCsrfToken;
};

/**
 * CSRF 토큰을 새로 받아 돌려줍니다. 다른 요청이 이미 받는 중이면 그 결과를 함께 써요.
 *
 * fetch로 직접 보내는 SSE 요청이 403을 받았을 때, 이 인터셉터와 같은 묶인 조회로 토큰을 다시 받기 위해 씁니다.
 */
export const reloadCsrfToken = (client: AxiosInstance) => loadCsrfToken(client);

/**
 * 지금 들고 있는 CSRF 토큰을 돌려주고, 없으면 `client`로 받아옵니다.
 *
 * SSE 응답은 axios(XHR 어댑터)로 읽을 수 없어 fetch로 직접 보내야 하는데,
 * 그 요청도 상태를 바꾸는 POST라 같은 토큰이 필요합니다. 요청마다 새로 받지 않도록
 * 인터셉터와 이 캐시를 함께 씁니다.
 */
export const getCsrfToken = async (client: AxiosInstance) =>
  csrfToken ?? (await loadCsrfToken(client));

const isMutatingRequest = (method?: string) =>
  MUTATING_METHODS.includes((method ?? "get").toLowerCase());

const isForbidden = (error: unknown) =>
  axios.isAxiosError(error) && error.response?.status === 403;

/**
 * 상태를 바꾸는 요청에 CSRF 토큰을 헤더로 붙이고, 토큰이 낡아 403이 오면 새로 받아 최대 두 번까지 다시 보냅니다.
 *
 * 서버는 이 헤더와 `XSRF-TOKEN` 쿠키를 비교해 우리 화면에서 온 요청인지 판별해요.
 * 쿠키는 브라우저가 알아서 보내지만, 프론트와 API의 도메인이 달라
 * 자바스크립트가 그 쿠키를 읽을 수 없습니다. 그래서 값을 `/api/v1/auth/csrf`로 받아 씁니다.
 * 발급 요청은 조회라 이 인터셉터를 그냥 지나가요.
 *
 * 두 번까지 다시 받는 이유: 동시에 보낸 요청들이 쿠키가 없는 서버에서 거절되면, 거절 응답마다 서버가 새 토큰을 쿠키에 덮어씁니다.
 * 먼저 도착한 403 뒤에 받은 토큰이, 늦게 도착한 403의 쿠키 때문에 다시 낡을 수 있어요.
 * 두 번째로 받을 때는 쿠키가 남아 있어 서버가 같은 값을 돌려주므로, 거기서 멈춥니다.
 * 그래도 403이면 그대로 던져, 계속 실패하는 상황에서 요청이 끝없이 반복되지 않게 해요.
 */
export const csrfInterceptor: HttpInterceptor =
  (next, client) => async (config) => {
    if (!isMutatingRequest(config.method)) return next(config);

    config.headers.set(CSRF_HEADER_NAME, await getCsrfToken(client));

    for (let retriesLeft = MAX_CSRF_RETRIES; ; retriesLeft -= 1) {
      try {
        return await next(config);
      } catch (error) {
        if (!isForbidden(error) || retriesLeft === 0) throw error;

        config.headers.set(CSRF_HEADER_NAME, await loadCsrfToken(client));
      }
    }
  };
