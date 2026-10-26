import useNavigateToChat from "@hooks/domain/chat/useNavigateToChat";
import useNavigateToChatSession from "@hooks/domain/chat/useNavigateToChatSession";
import useNavigateToDocuments from "@hooks/domain/document/useNavigateToDocuments";
import useNavigateToWorkspaceHome from "@hooks/domain/workspace/useNavigateToWorkspaceHome";
import { PATH_ROUTE } from "@routes/PATH_ROUTE";
import { readLastConversationId } from "@utils/lastConversation";
import { matchPath, useMatch, useParams } from "react-router";

/**
 * 워크스페이스 안의 세 화면(홈·탐색·문서) 중 지금 어디에 있는지 알려주고, 서로 오가게 하는 도메인 훅.
 *
 * GNB의 내비 필이 쓰지만 반환값이 화면 이름과 이동 함수뿐이라 다른 화면에서도 그대로 쓸 수 있어요.
 * 이미 있는 화면의 버튼은 눌러도 이동하지 않아 히스토리가 같은 화면으로 쌓이지 않아요.
 * 녹음 문서(`/recordings/:recordingId/document`)도 문서 탭 「녹음별」 탭에서 여는 화면이라 문서 탭으로 봐요(STT-R40).
 * 문서 하나나 녹음 문서를 보는 중, 「주제별」 탭을 보는 중에 문서를 누르면 「녹음별」 탭으로 가요. 문서 탭은 언제나 「녹음별」 탭으로 열려요(DOC-R9 · DOC-R20).
 * 지금 화면인지는 누른 순간의 주소로 봐요. 라우터는 주소를 먼저 바꾸고 화면은 transition으로 나중에 그려서,
 * 그 사이에는 그려진 화면(useMatch)이 이전 화면을 가리켜요. 예를 들어 목록에서 문서를 연 직후 「문서」를 누르면 목록으로 돌아와야 하는데 막혀요.
 * 탐색을 누르면 이 탭에서 이 워크스페이스의 탐색 화면을 마지막으로 봤을 때의 대화로 돌아가요.
 */
/** 누른 순간의 브라우저 주소가 이 경로인지. `end: false`면 하위 경로까지 같은 화면으로 봐요 */
const isAtPath = (path: string, { end = true }: { end?: boolean } = {}) =>
  matchPath({ path, end }, window.location.pathname) !== null;

const useWorkspaceNav = () => {
  const { workspaceId } = useParams();
  const { navigateToWorkspaceHome } = useNavigateToWorkspaceHome();
  const { navigateToChat } = useNavigateToChat();
  const { navigateToChatSession } = useNavigateToChatSession();
  const { navigateToDocuments: navigateToDocumentList } =
    useNavigateToDocuments();

  const isHomeActive = useMatch(PATH_ROUTE.WORKSPACE_HOME) !== null;
  // 세션이 붙은 `/chat/:sessionId`도 탐색 화면이므로 하위 경로까지 매칭해요
  const isChatActive = useMatch({ path: PATH_ROUTE.CHAT, end: false }) !== null;
  // 문서 하나를 보는 `/documents/:documentId`도 문서 화면이므로 하위 경로까지 매칭해요
  const isDocumentListActive =
    useMatch({ path: PATH_ROUTE.DOCUMENTS, end: false }) !== null;
  const isRecordingDocumentActive =
    useMatch(PATH_ROUTE.RECORDING_DOCUMENT) !== null;
  const isDocumentsActive = isDocumentListActive || isRecordingDocumentActive;
  // 탭 순서(홈, 탐색, 문서)에서 지금 탭의 자리. 녹음처럼 탭이 아닌 화면이면 -1이에요
  const activeTabIndex = [
    isHomeActive,
    isChatActive,
    isDocumentsActive,
  ].indexOf(true);

  const navigateToHome = () => {
    if (!workspaceId || isAtPath(PATH_ROUTE.WORKSPACE_HOME)) return;

    navigateToWorkspaceHome({ workspaceId });
  };

  const navigateToExplore = () => {
    if (!workspaceId || isAtPath(PATH_ROUTE.CHAT, { end: false })) return;

    // 본 적이 없거나 마지막에 빈 새 대화를 봤으면 새 대화로 가요
    const lastConversationId = readLastConversationId(workspaceId);
    if (lastConversationId === undefined) {
      navigateToChat({ workspaceId });
      return;
    }

    navigateToChatSession({
      workspaceId,
      sessionId: String(lastConversationId),
    });
  };

  const navigateToDocuments = () => {
    // 문서 하나나 「주제별」 탭을 보는 중에는 「녹음별」 탭으로 보내야 해서 「녹음별」 탭 그 자체인지만 봐요
    if (!workspaceId || isAtPath(PATH_ROUTE.DOCUMENTS_BY_RECORDING)) return;

    navigateToDocumentList({ workspaceId });
  };

  return {
    isHomeActive,
    isChatActive,
    isDocumentsActive,
    activeTabIndex,
    navigateToHome,
    navigateToExplore,
    navigateToDocuments,
  };
};

export default useWorkspaceNav;
