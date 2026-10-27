import { isRetryRejectedError } from "@utils/isRetryRejectedError";
import { isUnauthorizedError } from "@utils/isUnauthorizedError";

/**
 * @description 다시 시도 요청마다의 오류를 사용 기록에 남길 결과 하나로 묶어요
 * @param errors - 다시 시도 요청마다의 오류. 접수된 요청은 `null`이에요. 문서 만들기는 작업마다 요청해서 여러 개일 수 있어요
 * @example
 * getRetryResult([null, conflictError]); // "accepted"
 *
 * 하나라도 접수되면 접수(`accepted`), 모두 더 시도할 수 없다는 응답(409 · 404)이면 거절(`rejected`), 그 밖은 오류(`error`)예요.
 * 로그인이 풀린(401) 요청이 있으면 다시 시도의 결과가 아니라 로그인 화면으로 가는 것이고, 보낸 요청이 없으면 서버 응답도 없어서 `null`이에요.
 * `null`이면 사용 기록을 보내지 않아요.
 */
export const getRetryResult = (errors: unknown[]) => {
  // 빈 목록은 `every`가 true라 그대로 두면 거절로 세어져요
  if (errors.length === 0 || errors.some(isUnauthorizedError)) return null;
  if (errors.some((error) => error === null)) return "accepted" as const;

  return errors.every(isRetryRejectedError)
    ? ("rejected" as const)
    : ("error" as const);
};
