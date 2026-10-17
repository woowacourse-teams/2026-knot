import {
  getCsrfToken,
  MAX_CSRF_RETRIES,
  reloadCsrfToken,
} from "@api/httpClient";
import { readSseStream } from "@api/sse/readSseStream";

interface RequestSseStreamParams {
  method: "GET" | "POST";

  /** API 경로. 예: `/api/v1/workspaces/1/search/conversations` */
  path: string;

  /** JSON으로 보낼 요청 본문. 없으면 본문과 Content-Type 없이 보내요 */
  body?: object;

  /** 요청과 스트림 읽기를 함께 취소하는 신호 */
  signal?: AbortSignal;
}

interface SseRequestErrorParams {
  status: number;
  code: string;
  message: string;
}

/**
 * SSE 요청이 스트림을 받기 전에 실패한 오류예요.
 *
 * 이 함수는 탐색 같은 도메인을 모르므로 실패를 해석하지 않고, 받는 쪽이 같은 상태 코드라도
 * 서버 오류 코드로 이유를 나눌 수 있도록 `status`와 `code`를 그대로 담아요.
 */
export class SseRequestError extends Error {
  /** 응답 상태 코드 */
  status: number;
  /** 응답 본문의 서버 오류 코드. 본문을 읽지 못하면 `UNKNOWN`, 성공인데 스트림이 없으면 `EMPTY_BODY`예요 */
  code: string;

  constructor({ status, code, message }: SseRequestErrorParams) {
    super(message);
    this.name = "SseRequestError";
    this.status = status;
    this.code = code;
  }
}

/**
 * fetch는 axios와 달리 baseURL을 모르고 상대 경로도 못 받아서 직접 절대 주소로 만들어요.
 * 배포 환경에서는 API 도메인이 따로 있고, 없으면 지금 보고 있는 오리진을 써요.
 *
 * `??`가 아니라 `||`인 이유는 mock 모드의 `API_BASE_URL`이 없는 값이 아니라 빈 문자열이기 때문이에요.
 */
const toAbsoluteUrl = (path: string) =>
  new URL(path, process.env.API_BASE_URL || window.location.origin).toString();

interface SendSseRequestParams extends RequestSseStreamParams {
  /** 헤더에 붙일 CSRF 토큰. GET에는 없어요 */
  csrfToken?: string;
}

const sendSseRequest = async ({
  method,
  path,
  body,
  signal,
  csrfToken,
}: SendSseRequestParams) => {
  const headers = new Headers({ Accept: "text/event-stream" });

  if (csrfToken) headers.set("X-XSRF-TOKEN", csrfToken);
  if (body) headers.set("Content-Type", "application/json");

  return fetch(toAbsoluteUrl(path), {
    method,
    headers,
    credentials: "include",
    body: body && JSON.stringify(body),
    signal,
  });
};

/**
 * SSE 요청을 보내고, GET이 아닌 요청이 403을 받으면 CSRF 토큰을 다시 받아 최대 두 번까지 다시 보내요.
 *
 * httpClient의 `csrfInterceptor`와 같은 규칙이에요. 서버가 CSRF 쿠키를 지운 뒤에는 들고 있던 토큰이 낡아
 * 403이 오는데, 다시 받지 않으면 같은 토큰으로 보내는 질문이 계속 거절돼요.
 * 서버는 CSRF 검사를 요청 처리보다 먼저 하므로, 403을 받은 질문은 만들어지지 않아 다시 보내도 겹치지 않아요.
 */
const sendSseRequestWithCsrfRetry = async (params: RequestSseStreamParams) => {
  if (params.method === "GET") return sendSseRequest(params);

  let response = await sendSseRequest({
    ...params,
    csrfToken: await getCsrfToken(),
  });

  for (
    let retriesLeft = MAX_CSRF_RETRIES;
    response.status === 403 && retriesLeft > 0;
    retriesLeft -= 1
  ) {
    response = await sendSseRequest({
      ...params,
      csrfToken: await reloadCsrfToken(),
    });
  }

  return response;
};

interface ErrorBody {
  code: string;
  message?: unknown;
}

const isErrorBody = (data: unknown): data is ErrorBody =>
  typeof data === "object" &&
  data !== null &&
  "code" in data &&
  typeof data.code === "string";

const toSseRequestError = async (response: Response) => {
  // 본문이 비었거나 JSON이 아닐 수 있어 읽기 실패는 값 없음으로 다뤄요
  const data: unknown = await response.json().catch(() => undefined);
  const fallbackMessage = `SSE 요청 실패: ${response.status}`;

  if (!isErrorBody(data)) {
    return new SseRequestError({
      status: response.status,
      code: "UNKNOWN",
      message: fallbackMessage,
    });
  }

  return new SseRequestError({
    status: response.status,
    code: data.code,
    message: typeof data.message === "string" ? data.message : fallbackMessage,
  });
};

/**
 * @description SSE 응답을 받는 요청을 보내고 도착하는 이벤트를 순서대로 흘려보내요. GET이 아닌 요청이 403을 받으면 CSRF 토큰을 다시 받아 최대 두 번까지 다시 보내요
 * @param params - 메서드, API 경로, JSON 요청 본문, 취소용 AbortSignal
 * @returns 도착 순서대로 SseEvent를 내는 async generator. 받는 쪽이 중간에 멈추면 스트림 읽기를 정리해요
 * @throws {SseRequestError} 응답은 받았지만 스트림을 받기 전에 실패한 경우. `status`·`code`로 분기할 수 있어요
 * @example
 * for await (const { event, data } of requestSseStream({ method: "POST", path, body, signal })) {
 *   if (event === "delta") answer += JSON.parse(data).text;
 * }
 */
export async function* requestSseStream(params: RequestSseStreamParams) {
  const response = await sendSseRequestWithCsrfRetry(params);

  if (!response.ok) throw await toSseRequestError(response);
  // 서버 오류 코드가 아니라 이 함수가 붙인 코드예요. 성공 상태라 본문에 오류 코드가 없어요
  if (!response.body) {
    throw new SseRequestError({
      status: response.status,
      code: "EMPTY_BODY",
      message: "SSE 응답에 스트림 본문이 없어요",
    });
  }

  for await (const sseEvent of readSseStream({
    stream: response.body,
    signal: params.signal,
  })) {
    yield sseEvent;
  }
}
