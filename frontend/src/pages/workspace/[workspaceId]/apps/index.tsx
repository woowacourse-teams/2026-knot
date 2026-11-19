import { MEDIA_QUERY } from "@constants/breakpoint";
import styled from "@emotion/styled";
import ConnectedApps from "@widgets/connectedApp/ConnectedApps";

/**
 * 연결된 앱 화면 (`/workspace/:workspaceId/apps`)
 *
 * 탐색이 읽고 쓰는 앱(지금은 노션)의 연결을 보고, 연결하거나 끊는 화면이다(SEARCH-R29~R34).
 * 사이드바 아래 「연결된 앱」으로 들어오고, 노션 동의 화면에서 돌아오는 곳이기도 하다(`?notionConnection=`).
 * GNB, 사이드바, 하단 독은 `WorkspaceLayout`이 담당하고, 이 페이지는 연결된 앱 섹션을 가운데에 놓기만 한다.
 *
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=6513-4347 연결된 앱 화면, 노션 연결됨
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=6513-4649 노션 연결 흐름 4단계와 동의 안내 창
 */
export default function AppsPage() {
  return (
    <Root>
      <ConnectedApps />
    </Root>
  );
}

const Root = styled.div`
  display: flex;
  justify-content: center;
  min-height: 100%;
  padding: 1.375rem 1.5rem 7rem; /* 22px 24px 112px. 위는 피그마에서 머리가 화면 위 110px에 오도록, 아래는 하단 독 높이만큼 띄워요 */

  ${MEDIA_QUERY.mobile} {
    padding-right: 1rem; /* 16px */
    padding-left: 1rem;
  }
`;
