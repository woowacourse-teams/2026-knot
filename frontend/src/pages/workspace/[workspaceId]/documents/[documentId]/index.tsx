import { MEDIA_QUERY } from "@constants/breakpoint";
import styled from "@emotion/styled";
import DocumentViewer from "@widgets/document/DocumentViewer";

/**
 * 문서 보기 화면 (`/workspace/:workspaceId/documents/:documentId`)
 *
 * 문서 하나의 제목과 본문을 보여준다. 탐색의 찾은 기록 카드, 문서 목록, 사이드바, 홈 카드에서 들어온다.
 * GNB·사이드바·하단 독은 `WorkspaceLayout`이 담당하고, 이 페이지는 문서 보기 섹션을 가운데에 놓기만 한다.
 *
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2017-36058 문서/확인한 사람 보기
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2259-32252 문서/녹음 직후 · 결정 없음
 */
export default function DocumentPage() {
  return (
    <Root>
      <title>문서 보기 - knot</title>
      <DocumentViewer />
    </Root>
  );
}

const Root = styled.div`
  display: flex;
  justify-content: center;
  min-height: 100%;
  padding: 1.25rem 1.5rem 7rem; /* 20px 24px 112px — 아래는 하단 독 자리 */

  ${MEDIA_QUERY.mobile} {
    padding-right: 1rem; /* 16px */
    padding-left: 1rem;
  }
`;
