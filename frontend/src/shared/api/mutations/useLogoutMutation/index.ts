import { logoutApi } from "@api/fetch/api/v1/auth/logout";
import { useMutation } from "@tanstack/react-query";

/**
 * 이 브라우저의 로그인을 끝내는 뮤테이션 훅.
 *
 * 오프라인이어도 멈춰 두지 않고 바로 보내 실패로 끝내요(`networkMode: "always"`). 멈춰 두면 실패 안내 없이 처리 중에 머물러요(AUTH-R19).
 */
const useLogoutMutation = () => {
  return useMutation({
    mutationFn: logoutApi,
    networkMode: "always",
  });
};

export default useLogoutMutation;
