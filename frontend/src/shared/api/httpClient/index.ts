import { createHttpClientBuilder } from "./builder";
import {
  csrfInterceptor,
  getCsrfToken as getCsrfTokenWith,
  reloadCsrfToken as reloadCsrfTokenWith,
} from "./interceptors/csrf";
import { httpErrorInterceptor } from "./interceptors/httpError";
import { refreshInterceptor } from "./interceptors/refresh";

const TIMEOUT_DURATION = 10_000;

export const httpClient = createHttpClientBuilder({
  baseURL: process.env.API_BASE_URL,
  timeout: TIMEOUT_DURATION,
  withCredentials: true,
})
  .use(httpErrorInterceptor)
  .use(refreshInterceptor)
  .use(csrfInterceptor)
  .build();

/** httpClient가 쓰는 CSRF 토큰을 돌려줍니다. 없으면 받아와요. */
export const getCsrfToken = () => getCsrfTokenWith(httpClient);

/** httpClient가 쓰는 CSRF 토큰을 새로 받아 돌려줍니다. 이미 받는 중이면 그 결과를 함께 써요. */
export const reloadCsrfToken = () => reloadCsrfTokenWith(httpClient);

export { MAX_CSRF_RETRIES } from "./interceptors/csrf";
