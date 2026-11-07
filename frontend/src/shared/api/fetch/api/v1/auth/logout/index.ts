import { httpClient } from "@api/httpClient";

export const AUTH_LOGOUT_API_PATH = "/api/v1/auth/logout";

/**
 * @description 이 브라우저의 로그인을 끝냅니다. 서버가 지금 로그인만 끝내고 인증 쿠키를 만료해요. 성공 시 응답 본문은 없어요(204)
 * @example
 * await logoutApi();
 */
export const logoutApi = async () => {
  await httpClient({
    method: "post",
    url: AUTH_LOGOUT_API_PATH,
  });
};
