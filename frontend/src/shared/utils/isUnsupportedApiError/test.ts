import { createHttpError, HTTP_ERROR_TYPE } from "@api/httpClient/error";
import { describe, expect, it } from "vitest";

import { isUnsupportedApiError } from ".";

describe("isUnsupportedApiError", () => {
  it("404의 경로 미지원 코드만 구분한다", () => {
    expect(isUnsupportedApiError(createHttpError({
      type: HTTP_ERROR_TYPE.notFound,
      code: "REQUEST_PATH_NOT_FOUND",
    }))).toBe(true);
  });

  it.each([undefined, "WORKSPACE_NOT_FOUND", "RECORDING_NOT_FOUND", "DOCUMENT_NOT_FOUND"])(
    "404라도 %s는 미지원으로 바꾸지 않는다", (code) => {
      expect(isUnsupportedApiError(createHttpError({
        type: HTTP_ERROR_TYPE.notFound, code,
      }))).toBe(false);
    },
  );

  it.each(Object.values(HTTP_ERROR_TYPE).filter((type) => type !== HTTP_ERROR_TYPE.notFound))(
    "%s는 같은 코드가 있어도 미지원이 아니다", (type) => {
      expect(isUnsupportedApiError(createHttpError({
        type, code: "REQUEST_PATH_NOT_FOUND",
      }))).toBe(false);
    },
  );

  it.each([null, undefined, new Error("REQUEST_PATH_NOT_FOUND"), { code: "REQUEST_PATH_NOT_FOUND" }])(
    "HTTP 오류가 아닌 값은 미지원이 아니다", (error) => {
      expect(isUnsupportedApiError(error)).toBe(false);
    },
  );
});
