import { createHttpError, HTTP_ERROR_TYPE } from "@api/httpClient/error";
import { describe, expect, it } from "vitest";

import { getSubmitErrorMessage } from "./getSubmitErrorMessage";

const FORMAT_ERROR_MESSAGE = "한글, 영어, 숫자와 공백만 사용할 수 있어요.";
const FORBIDDEN_ERROR_MESSAGE =
  "보안 확인에 실패했어요. 새로고침 후 다시 시도해 주세요.";
const UNKNOWN_ERROR_MESSAGE = "잠시 후 다시 시도해 주세요.";


describe("getSubmitErrorMessage", () => {
  it("400이면 이름 형식 문구를 돌려준다", () => {
    expect(getSubmitErrorMessage(createHttpError({ type: HTTP_ERROR_TYPE.badRequest }))).toBe(
      FORMAT_ERROR_MESSAGE,
    );
  });

  it("403이면 보안 확인 실패 문구를 돌려준다", () => {
    expect(getSubmitErrorMessage(createHttpError({ type: HTTP_ERROR_TYPE.forbidden }))).toBe(
      FORBIDDEN_ERROR_MESSAGE,
    );
  });

  it("그 외 상태 코드면 잠시 후 다시 시도 문구를 돌려준다", () => {
    expect(getSubmitErrorMessage(createHttpError({ type: HTTP_ERROR_TYPE.serverError }))).toBe(
      UNKNOWN_ERROR_MESSAGE,
    );
  });

  it("응답이 없거나 HTTP 에러가 아니어도 잠시 후 다시 시도 문구를 돌려준다", () => {
    expect(getSubmitErrorMessage(createHttpError({ type: HTTP_ERROR_TYPE.network }))).toBe(
      UNKNOWN_ERROR_MESSAGE,
    );
    expect(getSubmitErrorMessage(new Error("boom"))).toBe(
      UNKNOWN_ERROR_MESSAGE,
    );
  });
});
