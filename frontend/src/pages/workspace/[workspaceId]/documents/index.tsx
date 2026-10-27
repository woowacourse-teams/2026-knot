import { MEDIA_QUERY } from "@constants/breakpoint";
import styled from "@emotion/styled";
import DocumentArchive from "@widgets/document/DocumentArchive";

/**
 * 문서 탭 화면 (`/workspace/:workspaceId/documents/recordings`, `/workspace/:workspaceId/documents/topics`)
 *
 * 워크스페이스의 문서를 「녹음별」 · 「주제별」 두 탭으로 보여준다. 어느 탭인지는 주소가 정하고, 두 주소 모두 이 화면이다.
 * GNB·사이드바·하단 독은 `WorkspaceLayout`이 담당하고, 이 페이지는 문서 탭 섹션을 가운데에 놓기만 한다.
 *
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5327-2402 문서 탭/녹음별 · 기본
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5327-2221 문서 탭/주제별
 */
export default function DocumentsPage() {
  return (
    <Root>
      <DocumentArchive />
    </Root>
  );
}

/** 피그마: 목록의 위 끝이 화면 위에서 112px. GNB(68px)와 본문 위 여백(20px) 아래로 24px을 띄운다 */
const Root = styled.div`
  display: flex;
  justify-content: center;
  min-height: 100%;
  padding: 1.5rem 1.5rem 7rem; /* 24px 24px 112px — 아래는 하단 독 자리 */

  ${MEDIA_QUERY.mobile} {
    padding-right: 1rem; /* 16px */
    padding-left: 1rem;
  }
`;
