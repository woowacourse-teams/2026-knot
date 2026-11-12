import { PATH_ROUTE } from "@routes/PATH_ROUTE";
import type { SearchTutorialSource } from "@store/searchTutorialStore";
import { useMatch } from "react-router";

/**
 * 지금 화면이 튜토리얼을 띄울 수 있는 화면인지와 그 이름을 알려줘요.
 *
 * 독과 GNB가 함께 있는 화면(홈, 탐색, 문서 목록, 문서, 녹음 문서)에서만 띄워요.
 * 녹음 화면과 문서를 정리하는 화면은 다른 일을 하는 중이라 띄우지 않아요.
 */
export const useSearchTutorialScreen = (): {
  screen: Exclude<SearchTutorialSource, "replay"> | null;
  isChatScreen: boolean;
} => {
  const isHome = useMatch({ path: PATH_ROUTE.WORKSPACE_HOME, end: true }) !== null;
  const isChat = useMatch(PATH_ROUTE.CHAT) !== null;
  const isChatSession = useMatch(PATH_ROUTE.CHAT_SESSION) !== null;
  const isDocumentsByRecording = useMatch(PATH_ROUTE.DOCUMENTS_BY_RECORDING) !== null;
  const isDocumentsByTopic = useMatch(PATH_ROUTE.DOCUMENTS_BY_TOPIC) !== null;
  const isDocumentsRoot = useMatch({ path: PATH_ROUTE.DOCUMENTS, end: true }) !== null;
  const isDocument = useMatch(PATH_ROUTE.DOCUMENT) !== null;
  const isRecordingDocument = useMatch(PATH_ROUTE.RECORDING_DOCUMENT) !== null;

  const isChatScreen = isChat || isChatSession;
  const isDocumentsList = isDocumentsRoot || isDocumentsByRecording || isDocumentsByTopic;

  // 문서 목록 탭 주소(`/documents/recordings`)도 문서 하나의 주소 모양과 맞으므로 목록을 먼저 가려요
  if (isHome) return { screen: "home", isChatScreen };
  if (isChatScreen) return { screen: "search", isChatScreen };
  if (isDocumentsList) return { screen: "documents", isChatScreen };
  if (isRecordingDocument) return { screen: "recording_document", isChatScreen };
  if (isDocument) return { screen: "document", isChatScreen };

  return { screen: null, isChatScreen };
};
