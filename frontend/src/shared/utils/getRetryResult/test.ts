import { createHttpError, HTTP_ERROR_TYPE } from "@api/httpClient/error";
import { describe, expect, it } from "vitest";

import { getRetryResult } from ".";

const conflict = createHttpError({ type: HTTP_ERROR_TYPE.conflict });
const notFound = createHttpError({ type: HTTP_ERROR_TYPE.notFound });
const serverError = createHttpError({ type: HTTP_ERROR_TYPE.serverError });
const network = createHttpError({ type: HTTP_ERROR_TYPE.network });
const unauthorized = createHttpError({ type: HTTP_ERROR_TYPE.unauthorized });

describe("getRetryResult", () => {
  it.each([
    { reason: "접수되면", errors: [null], result: "accepted" },
    { reason: "409로 거절되면", errors: [conflict], result: "rejected" },
    { reason: "404로 거절되면", errors: [notFound], result: "rejected" },
    { reason: "서버 오류면", errors: [serverError], result: "error" },
    { reason: "네트워크 오류면", errors: [network], result: "error" },
    {
      reason: "작업 가운데 하나라도 접수되면",
      errors: [null, conflict],
      result: "accepted",
    },
    {
      reason: "작업이 모두 거절되면",
      errors: [conflict, notFound],
      result: "rejected",
    },
    {
      reason: "접수 없이 거절과 서버 오류가 섞이면",
      errors: [conflict, serverError],
      result: "error",
    },
  ])("다시 시도 응답이 $reason $result예요", ({ errors, result }) => {
    expect(getRetryResult(errors)).toBe(result);
  });

  it.each([
    { reason: "로그인이 풀렸으면(401)", errors: [unauthorized] },
    { reason: "작업 하나라도 로그인이 풀렸으면", errors: [null, unauthorized] },
    { reason: "보낸 요청이 없으면", errors: [] },
  ])("$reason 결과가 없어요", ({ errors }) => {
    expect(getRetryResult(errors)).toBeNull();
  });
});
