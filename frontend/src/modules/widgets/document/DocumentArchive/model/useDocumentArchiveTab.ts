import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import { useMatch, useNavigate, useParams } from "react-router";

import type { DocumentArchiveTab } from "../constants/documentArchiveTabs";

/** 탭마다 가는 주소. 탭은 주소로 나뉘어 뒤로 가기와 공유 링크가 보던 탭을 그대로 가리켜요 */
const ROUTE_KEY_BY_TAB = {
  recording: "DOCUMENTS_BY_RECORDING",
  topic: "DOCUMENTS_BY_TOPIC",
} as const;

/**
 * 지금 주소가 가리키는 탭과, 다른 탭의 주소로 옮기는 함수를 돌려줘요.
 *
 * 「주제별」 주소가 아니면 모두 「녹음별」로 봐요. 탭을 바꾸면 히스토리에 push해, 뒤로 가기로 보던 탭에 돌아올 수 있어요.
 */
export const useDocumentArchiveTab = () => {
  const { workspaceId = "" } = useParams();
  const navigate = useNavigate();
  const isTopicTab = useMatch(PATH_ROUTE.DOCUMENTS_BY_TOPIC) !== null;
  const tab: DocumentArchiveTab = isTopicTab ? "topic" : "recording";

  const selectTab = (nextTab: DocumentArchiveTab) => {
    navigate(
      getRouterPath({
        routeKey: ROUTE_KEY_BY_TAB[nextTab],
        params: { workspaceId },
      }),
    );
  };

  return { tab, selectTab };
};
