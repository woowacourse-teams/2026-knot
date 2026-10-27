import useAnalyticsScreenTarget from "@hooks/domain/document/useAnalyticsScreenTarget";
import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { useEffect, useRef } from "react";
import { useLocation } from "react-router";

/**
 * 원문 패널을 스크롤한 것을 사용 기록(`transcript_panel_scrolled`)으로 보내는 훅. 화면에 들어올 때마다 처음 한 번만 보내요.
 *
 * 원문 패널은 닫으면 사라졌다가 다시 열면 새로 그려져서, 보낸 것을 패널이 아니라 화면을 옮겨도 남아 있는 레이아웃에서 기억해요.
 * 주소가 바뀌면(뒤로 가기로 같은 화면에 돌아와도) 새로 들어온 것으로 보고 다시 세요. PostHog의 페이지 보기와 같은 기준이에요.
 * 스크롤은 원문 패널 자리에 `onScrollCapture`로 달아요. 스크롤 이벤트는 올라오지 않지만 내려가는 단계에서는 바깥에서도 받을 수 있어요.
 */
export const useTranscriptScrollAnalytics = () => {
  const { key: visitKey } = useLocation();
  const { analyticsScreenTarget } = useAnalyticsScreenTarget();
  const isTrackedRef = useRef(false);

  useEffect(() => {
    isTrackedRef.current = false;
  }, [visitKey]);

  const handleTranscriptScroll = () => {
    if (isTrackedRef.current) return;

    isTrackedRef.current = true;
    captureAnalyticsEvent({
      event: "transcript_panel_scrolled",
      properties: analyticsScreenTarget,
    });
  };

  return { handleTranscriptScroll };
};
