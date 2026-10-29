import styled from "@emotion/styled";
import DockColumn from "@primitives/layout/DockColumn";

import SearchConversation from "@widgets/search/SearchConversation";

/**
 * 탐색(채팅) 화면 (`/workspace/:workspaceId/chat`, `/workspace/:workspaceId/chat/:sessionId`)
 *
 * 워크스페이스에 쌓인 기록을 대화로 찾는 화면입니다.
 * `sessionId` 없이 들어오면 입력 전(빈 결과) 상태로 시작하고,
 * 대화가 시작되면 해당 세션(`/chat/:sessionId`)으로 이어집니다.
 *
 * 대화 열은 하단 독과 같은 `DockColumn`에 놓여 늘 같은 폭·같은 위치예요(SEARCH-R11).
 * 찾은 기록 패널은 이 화면이 아니라 레이아웃(`WorkspaceLayout`)의 오른쪽 레일에 놓여요.
 * 어느 답변의 찾은 기록을 펼쳐 뒀는지는 주소(`?messageId=`)에 있어 새로고침해도 그대로예요.
 *
 * 사이드바 오픈, 채팅 세션 목록도 별도 라우트가 아니라 이 화면 위의 상태 변형입니다.
 *
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2098-29235 탐색/시작 전
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=1283-7940 탐색 결과/문서 닫힘
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=1434-2024 탐색 결과/문서 열림
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=413-14915 전체 플로우
 */
export default function ChatPage() {
  return (
    <Root>
      <title>탐색 - knot</title>
      <DockColumn>
        <SearchConversation />
      </DockColumn>
    </Root>
  );
}

/**
 * 대화가 짧으면 본문 높이를 채워 빈 화면 안내가 가운데 놓이고,
 * 길어지면 함께 늘어나 마지막 턴 아래에도 독 자리가 남아요.
 * 그리드 칸으로 늘어난 높이는 확정된 높이로 쳐서 자식의 `height: 100%`가 통해요.
 */
const Root = styled.div`
  display: grid;
  min-height: 100%;
  padding-bottom: 7rem; /* 112px — 하단 독 자리 */
`;
