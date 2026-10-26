import { Navigate, useParams } from "react-router";

import { getRouterPath } from "../PATH_ROUTE";

/**
 * 탭이 없는 문서 탭 주소(`/workspace/:workspaceId/documents`)에 도착한 사용자를 「녹음별」 탭으로 보냅니다.
 *
 * 문서 탭은 늘 「녹음별」 탭으로 열려요(DOC-R9 · DOC-R20). 탭마다 주소를 두면서 예전 주소로 남은
 * 링크(북마크, 새 소식의 화면 이름)가 끊기지 않게 여기서 대신 보내요. 히스토리에는 남기지 않아 뒤로 가기가 되돌아오지 않아요.
 */
export default function DocumentsRedirect() {
  const { workspaceId = "" } = useParams();

  return (
    <Navigate
      to={getRouterPath({
        routeKey: "DOCUMENTS_BY_RECORDING",
        params: { workspaceId },
      })}
      replace
    />
  );
}
