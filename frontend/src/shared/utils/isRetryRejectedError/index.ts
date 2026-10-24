import { HTTP_ERROR_TYPE, isHttpError } from "@api/httpClient/error";

/**
 * 다시 시도 API(전사 다시 시도 · 문서 생성 작업 다시 시도)가 더 시도할 수 없다고 답했는지 판별해요.
 *
 * 409는 보관 기간이 지났거나 다시 시도할 수 있는 횟수를 다 썼거나 이미 진행 중이라는 뜻이고,
 * 404는 없는 녹음 · 음성 · 작업이라는 뜻이에요. 둘 다 같은 요청을 다시 보내도 결과가 같아요.
 * 네트워크 · 서버 오류는 잠깐의 문제일 수 있어 거절로 보지 않아요.
 *
 * @example
 * isRetryRejectedError(createHttpError({ type: HTTP_ERROR_TYPE.conflict })); // true
 * isRetryRejectedError(createHttpError({ type: HTTP_ERROR_TYPE.serverError })); // false
 */
export const isRetryRejectedError = (error: unknown) =>
  isHttpError(error, HTTP_ERROR_TYPE.conflict) ||
  isHttpError(error, HTTP_ERROR_TYPE.notFound);
