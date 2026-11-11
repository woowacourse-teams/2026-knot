import useMeQuery from "@api/queries/useMeQuery";
import useRecentDocumentsQuery from "@api/queries/useRecentDocumentsQuery";
import { useHasToast } from "@provider/context/toastContext";
import { useRecordingStore } from "@store/recordingStore";
import { useSearchTutorialStore } from "@store/searchTutorialStore";
import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { useEffect, useRef, useState } from "react";
import { useLocation, useParams } from "react-router";

import {
  SEARCH_TUTORIAL_DOCUMENT_DWELL_MS,
  SEARCH_TUTORIAL_SETTLE_MS,
} from "../constants/searchTutorial";
import {
  getSearchTutorialEntry,
  readSearchTutorialRecord,
  toLocalDate,
} from "../utils/searchTutorialRecord";
import { useSearchTutorialScreen } from "./useSearchTutorialScreen";

/** 다른 일을 하는 중인지. 입력창에 글을 적고 있거나 대화상자가 열려 있으면 기다려요 */
const isUserBusy = () => {
  const active = document.activeElement;
  const isTyping =
    (active instanceof HTMLTextAreaElement || active instanceof HTMLInputElement) &&
    active.value.trim().length > 0;
  const isDialogOpen = document.querySelector('[role="dialog"], [aria-modal="true"]') !== null;

  return isTyping || isDialogOpen;
};

/**
 * 탐색 튜토리얼을 언제 띄울지 정해요. 조건을 모두 만족하면 1단계로 시작해요.
 *
 * - 지금 워크스페이스에 완료된 회의 문서가 있어요. 없으면 첫 문서가 생길 때까지 기다려요.
 * - 멤버가 아직 튜토리얼을 끝내거나 닫지 않았어요. 「다음에 볼게요」로 미뤘다면 다음 날부터 한 번 더 띄워요.
 * - 독과 GNB가 있는 화면이에요(`useSearchTutorialScreen`). 녹음 화면과 정리 화면에서는 띄우지 않아요.
 * - 녹음 중, 토스트가 떠 있을 때, 입력창에 적고 있거나 대화상자가 열려 있을 때는 끝날 때까지 기다려요.
 *
 * 화면이 바뀐 뒤 탭 전환 모션이 끝나도록 잠깐 기다렸다가 띄워요.
 * 녹음 문서 화면에서는 문서를 살짝 확인하도록 5초가 지나거나 한 번 스크롤한 뒤에 띄워요.
 * 한 탭에서는 한 번만 판단해 띄워요. 미루거나 닫은 뒤 같은 탭에서 다시 뜨지 않아요.
 */
export const useSearchTutorialTrigger = () => {
  const { workspaceId } = useParams();
  const { pathname } = useLocation();
  const { screen } = useSearchTutorialScreen();
  const { data: me } = useMeQuery();
  const { data: recentDocuments } = useRecentDocumentsQuery({
    workspaceId: Number(workspaceId),
  });
  const hasToast = useHasToast();
  const isRecording = useRecordingStore((state) => state.status !== "idle");
  const step = useSearchTutorialStore((state) => state.step);
  const start = useSearchTutorialStore((state) => state.start);

  const hasStartedRef = useRef(false);
  const [isDocumentChecked, setIsDocumentChecked] = useState(false);

  const memberId = me?.memberId;
  const hasDocuments = (recentDocuments?.items.length ?? 0) > 0;
  const entry =
    memberId === undefined
      ? null
      : getSearchTutorialEntry(readSearchTutorialRecord(memberId), toLocalDate(new Date()));

  const isRecordingDocument = screen === "recording_document";

  // 녹음 문서는 5초가 지나거나 한 번 스크롤하면 살짝 확인한 것으로 봐요. 화면이 바뀌면 다시 재요
  useEffect(() => {
    setIsDocumentChecked(false);
    if (!isRecordingDocument) return;

    const scrollRoot = document.querySelector("[data-analytics-scroll-root]");
    const check = () => setIsDocumentChecked(true);
    const timer = window.setTimeout(check, SEARCH_TUTORIAL_DOCUMENT_DWELL_MS);
    scrollRoot?.addEventListener("scroll", check, { once: true });

    return () => {
      window.clearTimeout(timer);
      scrollRoot?.removeEventListener("scroll", check);
    };
  }, [isRecordingDocument, pathname]);

  const isEligible =
    !hasStartedRef.current &&
    step === null &&
    screen !== null &&
    entry !== null &&
    hasDocuments &&
    !hasToast &&
    !isRecording &&
    (!isRecordingDocument || isDocumentChecked);

  useEffect(() => {
    if (!isEligible || screen === null || entry === null) return;

    // 바쁜 동안은 잠깐씩 기다렸다 다시 봐요
    const timer = window.setInterval(() => {
      if (isUserBusy()) return;

      window.clearInterval(timer);
      hasStartedRef.current = true;
      const variant = entry === "again" ? "again" : isRecordingDocument ? "document" : "default";
      start({ source: screen, variant });
      captureAnalyticsEvent({
        event: "search_tutorial_shown",
        properties: { source: screen, is_again: entry === "again", workspace_id: Number(workspaceId) },
      });
    }, SEARCH_TUTORIAL_SETTLE_MS);

    return () => window.clearInterval(timer);
  }, [isEligible, screen, entry, isRecordingDocument, start, workspaceId, pathname]);
};
