import { HTTP_ERROR_TYPE, isHttpError } from "@api/httpClient/error";

/**
 * 서버가 요청 값을 거절한 400 응답인지 판별해요.
 *
 * 같은 값으로 다시 보내도 결과가 같아서, 그대로 다시 시도하는 대신 값을 바꿔 요청해야 할 때 써요(예: 목록의 다음 페이지 커서).
 */
export const isBadRequestError = (error: unknown) =>
  isHttpError(error, HTTP_ERROR_TYPE.badRequest);
