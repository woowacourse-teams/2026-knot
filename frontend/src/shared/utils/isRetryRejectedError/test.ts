import { createHttpError, HTTP_ERROR_TYPE } from "@api/httpClient/error";
import { describe, expect, it } from "vitest";

import { isRetryRejectedError } from ".";

describe("isRetryRejectedError", () => {
  it.each([HTTP_ERROR_TYPE.conflict, HTTP_ERROR_TYPE.notFound])(
    "더 시도할 수 없다는 응답(%s)이면 true를 돌려준다",
    (type) => {
      expect(isRetryRejectedError(createHttpError({ type }))).toBe(true);
    },
  );

  it.each([
    HTTP_ERROR_TYPE.unauthorized,
    HTTP_ERROR_TYPE.forbidden,
    HTTP_ERROR_TYPE.serverError,
    HTTP_ERROR_TYPE.network,
  ])(
    "다시 시도해 볼 수 있거나 다른 처리가 필요한 응답(%s)이면 false를 돌려준다",
    (type) => {
      expect(isRetryRejectedError(createHttpError({ type }))).toBe(false);
    },
  );

  it("HTTP 에러가 아니거나 에러가 없으면 false를 돌려준다", () => {
    expect(isRetryRejectedError(new Error("boom"))).toBe(false);
    expect(isRetryRejectedError(null)).toBe(false);
    expect(isRetryRejectedError(undefined)).toBe(false);
  });
});
