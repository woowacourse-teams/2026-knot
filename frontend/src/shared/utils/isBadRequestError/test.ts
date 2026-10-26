import { createHttpError, HTTP_ERROR_TYPE } from "@api/httpClient/error";
import { describe, expect, it } from "vitest";

import { isBadRequestError } from ".";

describe("isBadRequestError", () => {
  it("400 응답의 HTTP 에러면 true를 돌려준다", () => {
    expect(
      isBadRequestError(createHttpError({ type: HTTP_ERROR_TYPE.badRequest })),
    ).toBe(true);
  });

  it("400이 아닌 상태 코드면 false를 돌려준다", () => {
    expect(
      isBadRequestError(createHttpError({ type: HTTP_ERROR_TYPE.serverError })),
    ).toBe(false);
    expect(
      isBadRequestError(createHttpError({ type: HTTP_ERROR_TYPE.notFound })),
    ).toBe(false);
  });

  it("응답이 없는 네트워크 에러면 false를 돌려준다", () => {
    expect(
      isBadRequestError(createHttpError({ type: HTTP_ERROR_TYPE.network })),
    ).toBe(false);
  });

  it("HTTP 에러가 아니면 false를 돌려준다", () => {
    expect(isBadRequestError(new Error("boom"))).toBe(false);
    expect(isBadRequestError(undefined)).toBe(false);
  });
});
