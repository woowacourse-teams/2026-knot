import { MEDIA_QUERY } from "@constants/breakpoint";
import styled from "@emotion/styled";
import RecordingDocumentViewer from "@widgets/recording/RecordingDocumentViewer";

/**
 * 녹음 문서 화면 (`/workspace/:workspaceId/recordings/:recordingId/document`)
 *
 * 녹음 하나의 제목 · 녹음 음성 · 회의 전체 요약 · 이 회의에서 나온 문서를 보여준다(STT-R36). 전사는 오른쪽 원문 패널에 있다.
 * 문서 탭 「녹음별」 탭의 녹음 행에서 들어온다.
 * GNB · 원문 패널 · 하단 독은 `WorkspaceLayout`이 담당하고, 이 페이지는 녹음 문서 섹션을 가운데에 놓기만 한다.
 *
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5368-2224 녹음 문서 화면 11개
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5455-64380 녹음 문서 정책
 */
export default function RecordingDocumentPage() {
  return (
    <Root>
      <RecordingDocumentViewer />
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
