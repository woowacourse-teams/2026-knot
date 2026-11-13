import { useEffect } from "react";

interface UseScreenWakeLockParams {
  isEnabled: boolean;
}

/**
 * 켜 둔 동안 기기 화면이 저절로 꺼지지 않게 해요(Screen Wake Lock).
 *
 * 브라우저는 탭이 가려지면 잠금을 풀어요. 그래서 탭이 다시 보이면 다시 잡아요.
 * 지원하지 않는 브라우저이거나 배터리 절약 모드처럼 거절당하면 조용히 넘어가요. 화면이 꺼질 수 있을 뿐이에요.
 */
const useScreenWakeLock = ({ isEnabled }: UseScreenWakeLockParams) => {
  useEffect(() => {
    if (!isEnabled || !("wakeLock" in navigator)) return;

    let sentinel: WakeLockSentinel | null = null;
    let isReleased = false;

    const acquire = async () => {
      if (document.visibilityState !== "visible") return;

      try {
        const acquired = await navigator.wakeLock.request("screen");
        // 기다리는 사이 꺼졌으면 받은 잠금을 바로 놓아요
        if (isReleased) void acquired.release();
        else sentinel = acquired;
      } catch {
        // 거절돼도 화면이 꺼질 수 있을 뿐 쓰는 쪽의 일은 이어 가요
      }
    };

    void acquire();
    document.addEventListener("visibilitychange", acquire);

    return () => {
      isReleased = true;
      document.removeEventListener("visibilitychange", acquire);
      void sentinel?.release();
    };
  }, [isEnabled]);
};

export default useScreenWakeLock;
