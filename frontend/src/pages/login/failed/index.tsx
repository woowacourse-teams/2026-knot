import PageErrorNotice from "@primitives/ui/PageErrorNotice";
import { PATH_ROUTE } from "@routes/PATH_ROUTE";
import { useNavigate } from "react-router";

/**
 * 로그인 실패 화면 (`/login/failed`)
 *
 * GitHub, Google, 카카오 로그인 처리가 실패했거나 원인을 알 수 없을 때 보여 준다(ERR-15, AUTH-R7).
 * 백엔드가 `/login?error=oauth2`로 돌려보내면 로그인 화면이 이 화면으로 보낸다.
 * 승인 화면에서 취소한 경우(`?error=oauth2_cancelled`)는 오지 않고 로그인 화면에 머문다(AUTH-R16).
 *
 * `로그인 화면으로`는 로그인 화면(`/login`)으로 보내 로그인 수단을 다시 고르게 한다(AUTH-R8).
 * 자동으로 다시 로그인하지 않고, 뒤로 가기로 이 화면에 다시 오지 않도록 기록을 바꿔 보낸다.
 * 실패 사유는 사용자가 손쓸 수 있는 것이 아니라서 구분하지 않고, 내부 오류 코드도 보여 주지 않는다.
 *
 * 로고와 배경까지 `PageErrorNotice`가 그리므로 `CenteredLayout` 밖에 둔다.
 *
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=3595-13883
 */
export default function LoginFailedPage() {
  const navigate = useNavigate();

  return (
    <PageErrorNotice
      variant="loginFailed"
      onAction={() => navigate(PATH_ROUTE.LOGIN, { replace: true })}
    />
  );
}
