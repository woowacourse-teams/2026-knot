import PageErrorNotice from "@primitives/ui/PageErrorNotice";
import { PATH_ROUTE } from "@routes/PATH_ROUTE";
import { useNavigate } from "react-router";

/**
 * 정의되지 않은 주소 화면 (`*`)
 *
 * 어느 라우트에도 맞지 않는 주소로 들어왔을 때 빈 화면 대신 ERR-01(잘못된 요청)을 보여 준다.
 * 로그인 여부와 관계없이 같은 화면이고, `홈으로 이동`은 홈 경로(`/`)로 보낸다.
 * 홈 경로의 진입 분기가 로그인 여부에 따라 랜딩이나 워크스페이스로 다시 보낸다.
 *
 * 로고와 배경까지 `PageErrorNotice`가 그리므로 `CenteredLayout` 밖에 둔다.
 *
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=1788-4827
 */
export default function NotFoundPage() {
  const navigate = useNavigate();

  return (
    <>
      <title>요청을 처리할 수 없어요 - knot</title>
      <PageErrorNotice
        variant="badRequest"
        onAction={() => navigate(PATH_ROUTE.HOME)}
      />
    </>
  );
}
