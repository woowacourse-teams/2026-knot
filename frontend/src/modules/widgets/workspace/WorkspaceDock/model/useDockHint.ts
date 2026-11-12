import { useHasToast } from "@provider/context/toastContext";
import { PATH_ROUTE } from "@routes/PATH_ROUTE";
import { useIsSearchTutorialActive } from "@store/searchTutorialStore";
import { useEffect, useRef, useState } from "react";
import { useMatch } from "react-router";

import { DOCK_HINT_DELAY_MS, DOCK_HINT_MAX_SEEN_COUNT } from "../constants/dockHint";
import { readSeenCount, readVisitDecision, writeSeenCount, writeVisitDecision } from "../utils/dockHintStorage";

interface UseDockHintParams {
  /** 독이 이미 펼쳐져 있는지. 펼쳐져 있으면 안내할 게 없어요 */
  isDockExpanded: boolean;
  /** 녹음이 이어지고 있거나 시작을 기다리는 중인지. 독이 파형으로 접혀 있거나 구슬이 돌고 있어 안내를 띄울 자리가 아니에요 */
  isRecordingBusy: boolean;
}

/**
 * 독 첫 사용 안내를 띄울지 정하고, 보여준 방문 횟수를 브라우저에 남깁니다.
 *
 * 안내가 뜨는 자리는 **홈에서 독이 접혀 쉬고 있을 때**뿐이에요. 탐색은 독이 이미 펼쳐져 있고, 문서와 녹음 화면은
 * 다른 일을 하는 중이라 띄우지 않아요. 녹음이 이어지고 있거나 시작을 기다리는 동안도 띄우지 않고,
 * 토스트가 같은 자리에 떠 있으면 토스트를 우선해요. 홈이 그려지고 {@link DOCK_HINT_DELAY_MS} 뒤에 떠서
 * 탭 전환 모션과 겹치지 않아요.
 *
 * 처음 몇 번만 알려주면 되는 안내라 {@link DOCK_HINT_MAX_SEEN_COUNT}번의 방문까지만 띄웁니다.
 * 세는 단위는 화면을 그린 횟수가 아니라 **방문**이고, 실제로 띄운 방문만 세요. 한 방문 안에서 새로고침하거나
 * 홈과 탐색을 오가는 것은 같은 방문이라, 그때마다 세면 사용자가 안내를 읽기도 전에 세 번이 다 닳습니다.
 * 그래서 방문 단위 판단은 탭을 닫으면 지워지는 `sessionStorage`에 남기고, 방문 횟수만 `localStorage`에 쌓아요.
 *
 * 안내대로 독을 열거나 녹음을 시작하면 할 일을 다 한 안내라 이번 방문에는 다시 띄우지 않아요.
 * 저장소를 못 쓰는 브라우저(시크릿 모드 등)에서는 아예 띄우지 않습니다. 몇 번 봤는지 알 수 없어
 * 매번 띄우게 되는데, 그러면 안내가 아니라 방해가 되기 때문입니다.
 */
export const useDockHint = ({ isDockExpanded, isRecordingBusy }: UseDockHintParams) => {
  const isHome = useMatch({ path: PATH_ROUTE.WORKSPACE_HOME, end: true }) !== null;
  const hasToast = useHasToast();
  // 탐색 튜토리얼이 같은 독을 밝히는 동안에는 띄우지 않아요(SEARCH-R19)
  const isTutorialActive = useIsSearchTutorialActive();
  const [isDecidedToShow, setIsDecidedToShow] = useState(false);
  const [isSettled, setIsSettled] = useState(false);

  // StrictMode에서 효과가 두 번 실행돼도 판단은 한 번만 해요
  const hasDecidedRef = useRef(false);

  const isEligible = isHome && !isDockExpanded && !isRecordingBusy && !isTutorialActive;

  useEffect(() => {
    if (hasDecidedRef.current || !isEligible) return;

    hasDecidedRef.current = true;

    const visitDecision = readVisitDecision();

    // 이번 방문에서 이미 정해 뒀으면 그 판단을 그대로 따라요
    if (visitDecision !== null) {
      setIsDecidedToShow(visitDecision === "shown");
      return;
    }

    const seenCount = readSeenCount();

    if (seenCount >= DOCK_HINT_MAX_SEEN_COUNT) {
      writeVisitDecision("hidden");
      return;
    }

    writeSeenCount(seenCount + 1);
    writeVisitDecision("shown");
    setIsDecidedToShow(true);
  }, [isEligible]);

  // 홈이 그려지고 잠시 뒤에 떠요. 홈을 벗어나면 다음에 돌아올 때 다시 기다려요
  useEffect(() => {
    if (!isEligible) {
      setIsSettled(false);
      return;
    }

    const timer = window.setTimeout(() => setIsSettled(true), DOCK_HINT_DELAY_MS);

    return () => window.clearTimeout(timer);
  }, [isEligible]);

  // 안내대로 독을 열었거나 녹음을 시작했으면 할 일을 다 한 안내예요
  useEffect(() => {
    if (!isDecidedToShow || (!isDockExpanded && !isRecordingBusy)) return;

    writeVisitDecision("hidden");
    setIsDecidedToShow(false);
  }, [isDecidedToShow, isDockExpanded, isRecordingBusy]);

  return { isHintVisible: isDecidedToShow && isEligible && isSettled && !hasToast };
};
