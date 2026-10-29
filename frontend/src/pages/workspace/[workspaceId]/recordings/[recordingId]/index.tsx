import { MEDIA_QUERY } from "@constants/breakpoint";
import styled from "@emotion/styled";
import RecordingDocuments from "@widgets/document/RecordingDocuments";

/**
 * 녹음 뒤 문서 정리 화면 (`/workspace/:workspaceId/recordings/:recordingId`)
 *
 * 녹음을 끝낸 뒤 문서가 만들어질 때까지 머무는 화면이다. 정리 중 · 문서로 만들 내용 없음 · 문서를 만들지 못함 가운데 하나를 보여준다.
 * GNB·사이드바·하단 독은 `WorkspaceLayout`이 담당하고, 이 페이지는 상태 화면을 가운데에 놓기만 한다.
 *
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2017-35460 문서/정리 중
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2017-35662 문서/정리 실패
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2262-33302 문서/정리할 내용 없음
 */
export default function RecordingDocumentsPage() {
  return (
    <Root>
      <title>문서 정리 - knot</title>
      <RecordingDocuments />
    </Root>
  );
}

const Root = styled.div`
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100%;
  padding: 1.25rem 1.5rem 7rem; /* 20px 24px 112px — 아래는 하단 독 자리 */

  ${MEDIA_QUERY.mobile} {
    padding-right: 1rem; /* 16px */
    padding-left: 1rem;
  }
`;
