import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { useEffect, useRef } from "react";

import type { LandingScenes } from "../constants/landing";

/**
 * 랜딩에서 본 장면을 사용 기록(`landing_scene_viewed`)으로 보내는 훅. 지금 장면 번호를 지켜보기만 하고 무대 동작은 바꾸지 않아요.
 *
 * 페이지를 열 때마다 장면마다 처음 볼 때 한 번만 보내요. 앞뒤로 오가며 다시 본 장면은 보내지 않아요.
 * 장면 이름은 받은 장면 묶음(`scenes.analyticsNames`)에서 읽어요. 공개 랜딩과 기술 소개 페이지는 같은 번호에 다른 장면이 있어요.
 * 첫 장면(소개)은 엔진이 시작하기를 기다리지 않고 처음 그려질 때 보내요.
 */
const useLandingSceneAnalytics = (scene: number, scenes: LandingScenes) => {
  const viewedScenesRef = useRef(new Set<number>());

  useEffect(() => {
    const viewedScenes = viewedScenesRef.current;
    if (viewedScenes.has(scene)) return;

    viewedScenes.add(scene);
    captureAnalyticsEvent({
      event: "landing_scene_viewed",
      properties: { scene: scenes.analyticsNames[scene] },
    });
  }, [scene, scenes]);
};

export default useLandingSceneAnalytics;
